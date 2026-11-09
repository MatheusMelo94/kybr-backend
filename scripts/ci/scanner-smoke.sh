#!/usr/bin/env bash
# CI smoke test of the isolated scanner image (deploy/scanner): builds nothing itself; give it the image.
#   scripts/ci/scanner-smoke.sh <image> <work-dir outside the repository>
# Proves, with throwaway PKI: the entrypoint refuses foreign secrets; stunnel requires a client
# certificate from the link CA with an API identity (no cert, another CA, another name or TLS 1.2 are
# refused); the custom signatures make every refused PDF form FOUND (marker, /JavaScript, /Launch,
# encrypted, /OpenAction, /AA, /JS only inside a compressed object stream, letter-escaped names) while a
# plain PDF and a #20 font name are OK; the sanitizer answers a photo through its own mTLS port; clamd has
# no TCP listener; clamd, stunnel and the sanitizer run as separate users and the sanitizer's user can't
# reach the clamd socket, the TLS key, the signatures or any configuration; a stalled peer is dropped;
# a changed signature file stops the machine.
set -euo pipefail

image=${1:?usage: scanner-smoke.sh <image> <work-dir>}
work=${2:?usage: scanner-smoke.sh <image> <work-dir>}
repo=$(cd "$(dirname "$0")/../.." && pwd)
host=kybr-clamav.fly.dev
mkdir -p "$work"
fail=0
check() { # $1=description $2=expected $3=got
  if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1 (expected '$2', got '$3')"; fail=1; fi
}

# Throwaway PKI: the link (as in production) plus a foreign CA and a link-CA cert with a wrong name.
pki="$work/pki"
"$repo/deploy/scanner/pki/make-link-pki.sh" "$pki" >/dev/null
(
  cd "$pki"
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out rogue-ca.key 2>/dev/null
  openssl req -x509 -new -key rogue-ca.key -subj "/CN=rogue" -days 2 -out rogue-ca.crt
  for who in rogue wrongname; do
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$who.key" 2>/dev/null
    openssl req -new -key "$who.key" -subj "/CN=$who" -out "$who.csr"
    printf 'extendedKeyUsage=clientAuth\nsubjectAltName=DNS:%s\n' \
      "$([ "$who" = rogue ] && echo kybr-api.scan-link.internal || echo other.scan-link.internal)" > "$who.ext"
  done
  openssl x509 -req -in rogue.csr -CA rogue-ca.crt -CAkey rogue-ca.key -CAcreateserial -days 2 -extfile rogue.ext -out rogue.crt 2>/dev/null
  openssl x509 -req -in wrongname.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 2 -extfile wrongname.ext -out wrongname.crt 2>/dev/null
)

tls_env=(-e "SCAN_TLS_CERT=$(cat "$pki/server.crt")" -e "SCAN_TLS_KEY=$(cat "$pki/server.key")" -e "SCAN_TLS_CA=$(cat "$pki/ca.crt")")

# 1. The entrypoint refuses any setting from the API's world.
guard=$(docker run --rm "${tls_env[@]}" -e KYBR_RESEND_API_KEY=synthetic "$image" 2>&1 || true)
check "entrypoint refuses a foreign secret" yes "$(grep -q 'refusing to start' <<<"$guard" && echo yes || echo no)"

# 2. Start the scanner (first boot downloads the signature databases).
docker rm -f kybr-scanner-smoke >/dev/null 2>&1 || true
docker run -d --name kybr-scanner-smoke -p 127.0.0.1:13310:13310 -p 127.0.0.1:13311:13311 "${tls_env[@]}" "$image" >/dev/null
trap 'docker logs --tail 80 kybr-scanner-smoke 2>&1 | sed "s/^/  scanner| /"; docker rm -f kybr-scanner-smoke >/dev/null 2>&1 || true' EXIT

s_client() { # $1=port $2=cert-prefix (or "none") ; stdin = payload
  local args=(-connect "127.0.0.1:$1" -servername "$host" -verify_hostname "$host" -CAfile "$pki/ca.crt"
    -verify_return_error -tls1_3 -quiet)
  case "$2" in
    none) ;;
    rogue) args+=(-cert "$pki/rogue.crt" -key "$pki/rogue.key") ;;
    *) args+=(-cert "$pki/$2.crt" -key "$pki/$2.key") ;;
  esac
  timeout 30 openssl s_client "${args[@]}" 2>/dev/null | tr -d '\0' || true
}
ping() { printf 'zPING\0' | s_client 13310 "$1"; }

for _ in $(seq 1 120); do
  [ "$(ping client-kybr-api-staging)" = "PONG" ] && break
  docker inspect -f '{{.State.Running}}' kybr-scanner-smoke | grep -q true || { echo "scanner container exited"; exit 1; }
  sleep 5
done

# 3. Mutual TLS and client identity.
check "API staging certificate gets PONG" PONG "$(ping client-kybr-api-staging)"
check "API prod certificate gets PONG" PONG "$(ping client-kybr-api)"
check "no client certificate is refused" "" "$(ping none)"
check "a certificate from another CA is refused" "" "$(ping rogue)"
check "a link-CA certificate with another name is refused" "" "$(ping wrongname)"
check "TLS 1.2 is refused" refused "$(printf 'zPING\0' | timeout 30 openssl s_client -connect 127.0.0.1:13310 \
  -servername "$host" -CAfile "$pki/ca.crt" -cert "$pki/client-kybr-api.crt" -key "$pki/client-kybr-api.key" \
  -tls1_2 -quiet 2>/dev/null | tr -d '\0' | grep -q PONG && echo accepted || echo refused)"

# 4. Verdicts through the link (INSTREAM framing: 4-byte big-endian length, data, zero terminator).
files="$work/files"
python3 -I "$repo/scripts/documents/make-test-files.py" "$files" >/dev/null
scan() { # $1=file ; prints clamd's reply
  python3 -I -c 'import struct,sys; d=open(sys.argv[1],"rb").read(); sys.stdout.buffer.write(b"zINSTREAM\0"+struct.pack(">I",len(d))+d+struct.pack(">I",0))' "$1" \
    | s_client 13310 client-kybr-api-staging
}
check "plain PDF is OK" "stream: OK" "$(scan "$files/plain.pdf")"
check "legitimate #20 font name is OK" "stream: OK" "$(scan "$files/font-space-name.pdf")"
for f in marker javascript launch encrypted openaction aa objstm-js escaped-javascript escaped-js; do
  reply=$(scan "$files/$f.pdf")
  check "$f PDF is FOUND ($reply)" yes "$(grep -q 'FOUND$' <<<"$reply" && echo yes || echo no)"
done
named() { # $1=file $2=signature name expected in the reply
  check "$1 is found by $2" yes "$(scan "$files/$1" | grep -qF "$2" && echo yes || echo no)"
}
named marker.pdf Kybr.Test.ScannerMarker
named openaction.pdf Kybr.PDF.ActiveContent.OpenAction
named aa.pdf Kybr.PDF.ActiveContent.AA
named objstm-js.pdf Kybr.PDF.ActiveContent.JS.InObject
named escaped-javascript.pdf Kybr.PDF.EscapedNameLetter
named escaped-js.pdf Kybr.PDF.EscapedNameLetter

# 5. The sanitizer through its own mTLS port.
code=$(curl -sS -o "$work/out.png" -w '%{http_code}' --resolve "$host:13311:127.0.0.1" --cacert "$pki/ca.crt" \
  --cert "$pki/client-kybr-api-staging.crt" --key "$pki/client-kybr-api-staging.key" --tlsv1.3 \
  -H 'Content-Type: image/png' --data-binary @"$files/photo.png" "https://$host:13311/sanitize" || true)
check "sanitizer re-encodes a photo through mTLS" 200 "$code"
check "sanitizer refuses a client without a certificate" refused "$(curl -sS -o /dev/null --resolve "$host:13311:127.0.0.1" \
  --cacert "$pki/ca.crt" -H 'Content-Type: image/png' --data-binary @"$files/photo.png" "https://$host:13311/sanitize" \
  >/dev/null 2>&1 && echo accepted || echo refused)"

# 6. Listeners: stunnel outside, the sanitizer on loopback, clamd on no TCP port at all.
# IPv4-mapped IPv6 (::ffff:127.0.0.1) is the same loopback address, so it is normalised first.
listeners=$(docker exec kybr-scanner-smoke sh -c 'netstat -tln 2>/dev/null' | awk 'NR>2 {sub(/^::ffff:/, "", $4); print $4}' | sort | tr '\n' ' ')
check "only stunnel listens outside loopback; clamd has no TCP listener" \
  "0.0.0.0:13310 0.0.0.0:13311 127.0.0.1:8090 " "$listeners"

# 7. Process separation: each service under its own user; the sanitizer's user can't reach clamd, the
# signatures, the configuration or the TLS key.
# ps truncates long user names (sanitizer -> sanitize), so resolve the owner from the process itself.
owner() {
  local pid
  pid=$(docker exec kybr-scanner-smoke ps -o pid,args | awk -v p="$1" '$0 ~ p && $0 !~ /awk/ {print $1; exit}')
  [ -n "$pid" ] && docker exec kybr-scanner-smoke stat -c %U "/proc/$pid"
}
check "clamd runs as clamav" clamav "$(owner 'clamd --foreground')"
check "stunnel runs as scanlink" scanlink "$(owner 'stunnel /etc/stunnel')"
check "the sanitizer runs as sanitizer" sanitizer "$(owner 'SanitizerApplication')"
as_sanitizer() { docker exec -u sanitizer kybr-scanner-smoke sh -c "$1" >/dev/null 2>&1 && echo allowed || echo denied; }
check "sanitizer user can't open the clamd socket" denied \
  "$(as_sanitizer "printf 'zPING\0' | timeout 5 socat - UNIX-CONNECT:/run/clamd/clamd.sock | grep -q PONG")"
check "sanitizer user can't read the TLS key" denied "$(as_sanitizer 'cat /run/kybr/tls/server.key')"
check "sanitizer user can't change a signature file" denied "$(as_sanitizer 'echo x >> /var/lib/clamav/kybr.ndb')"
check "sanitizer user can't add a signature file" denied "$(as_sanitizer 'touch /var/lib/clamav/extra.ndb')"
check "sanitizer user can't change clamd's config" denied "$(as_sanitizer 'echo x >> /run/kybr/clamd.conf')"
check "sanitizer user can't change the stunnel config" denied "$(as_sanitizer 'echo x >> /etc/stunnel/kybr.conf')"

# 8. A peer that connects and never completes the handshake is dropped (TIMEOUTbusy 10 s).
held=$(python3 -I -c '
import socket, time
s = socket.create_connection(("127.0.0.1", 13310), timeout=30)
t = time.time()
try:
    s.recv(1)
except Exception:
    pass
print(int(time.time() - t))')
check "a stalled peer is dropped within 15 s (held ${held}s)" yes "$([ "$held" -le 15 ] && echo yes || echo no)"

# 9. Tampering with a signature file restarts the machine (the supervisor exits).
docker exec kybr-scanner-smoke sh -c 'echo "Tamper:0:*:41414141" >> /var/lib/clamav/kybr.ndb'
sleep 15
check "a changed signature file stops the scanner" false "$(docker inspect -f '{{.State.Running}}' kybr-scanner-smoke)"

[ "$fail" -eq 0 ] || { echo "SCANNER SMOKE: FAILED"; exit 1; }
echo "SCANNER SMOKE: all checks passed"
