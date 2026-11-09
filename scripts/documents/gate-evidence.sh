#!/usr/bin/env bash
# Documents gate evidence on STAGING against the REAL scanner org (staging must run
# KYBR_SCANNER_MODE=clamd and KYBR_IMAGE_MODE=remote). Prints one line per check with a UTC timestamp,
# for the private Deploy Checklist. Exit 1 on any failed check.
#
#   API_BASE=https://api-staging.kybr.com.br SLUG=<test link> FLY_APP=kybr-api-staging \
#   BFF_KEY=... [PKI_DIR=<dir with ca.crt, client-kybr-api-staging.{crt,key}>] scripts/documents/gate-evidence.sh
#
# Checks: staging is in remote mode; through the upload route, every refused PDF form (scanner marker,
# /JavaScript, /Launch, encrypted, /OpenAction, /AA, /JS inside an object stream, letter-escaped names)
# is refused (415); a text file is refused before any scan (415); a clean PDF, a legitimate #20 font name
# and a photo are stored (201); the scanner's TLS port refuses a client without a certificate and (with
# PKI_DIR) answers PONG to the API's certificate.
set -euo pipefail

API_BASE=${API_BASE:?set API_BASE}; SLUG=${SLUG:?set SLUG}; BFF_KEY=${BFF_KEY:?set BFF_KEY}
FLY_APP=${FLY_APP:-kybr-api-staging}
SCANNER_HOST=${SCANNER_HOST:-kybr-clamav.fly.dev}
[ "$FLY_APP" != "kybr-api" ] || { echo "staging only" >&2; exit 1; }

here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
umask 077
printf 'X-Kybr-BFF-Key: %s\n' "$BFF_KEY" > "$tmp/bff.h"
python3 -I "$here/make-test-files.py" "$tmp/files" >/dev/null

fail=0
report() { # $1=check $2=expected $3=got
  local verdict=PASS; [ "$2" = "$3" ] || { verdict=FAIL; fail=1; }
  printf '%s  %-4s  %-58s expected %-6s got %s\n' "$(date -u +%FT%TZ)" "$verdict" "$1" "$2" "$3"
}

# 0. Staging runs against the real scanner org.
for pair in KYBR_SCANNER_MODE=clamd KYBR_IMAGE_MODE=remote; do
  name=${pair%%=*}; want=${pair#*=}
  got=$(fly ssh console --app "$FLY_APP" -C "printenv $name" 2>/dev/null | tr -d '\r' || true)
  report "staging $name" "$want" "${got:-unset}"
done

# 1. A case that asks for documents (stub provider "pending" fixture).
verify_out=$(curl -sS --proto '=https' -m 20 -w '\n%{http_code}' -H @"$tmp/bff.h" -H 'X-Kybr-Client-IP: 203.0.113.60' \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)" -H 'Content-Type: application/json' \
  -d '{"cnpj":"52000001000130","responsavel":"Maria Teste Sintetica"}' "$API_BASE/public/v/$SLUG/verify")
token=$(printf '%s' "${verify_out%$'\n'*}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$token" ] || { echo "could not create a test case (status ${verify_out##*$'\n'})" >&2; exit 1; }
tipo=${TIPO:-}
for _ in $(seq 1 15); do
  [ -n "$tipo" ] && break
  tipo=$(curl -sS --proto '=https' -m 20 -H @"$tmp/bff.h" -H 'X-Kybr-Client-IP: 203.0.113.61' \
    "$API_BASE/public/v/$SLUG/cases/$token" | sed -n 's/.*"documento_tipo":"\([A-Z_]*\)".*/\1/p' | head -n1)
  [ -n "$tipo" ] || sleep 2
done
[ -n "$tipo" ] || { echo "the test case asks for no document (set TIPO)" >&2; exit 1; }

upload() { # $1=token $2=file
  curl -sS -o /dev/null -w '%{http_code}' --proto '=https' -m 60 -F "tipo=$tipo" \
    -F "arquivos=@$2;type=application/octet-stream" "$API_BASE/public/v/$SLUG/cases/$1/documents"
}
new_case() { # a fresh case per stored file: a type that holds a PDF takes nothing more
  curl -sS --proto '=https' -m 20 -H @"$tmp/bff.h" -H 'X-Kybr-Client-IP: 203.0.113.62' \
    -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)" -H 'Content-Type: application/json' \
    -d '{"cnpj":"52000001000130","responsavel":"Maria Teste Sintetica"}' "$API_BASE/public/v/$SLUG/verify" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

# 2. Scanner verdicts through the real route.
report "marker PDF (Kybr.Test.ScannerMarker) refused" 415 "$(upload "$token" "$tmp/files/marker.pdf")"
report "/JavaScript PDF refused" 415 "$(upload "$token" "$tmp/files/javascript.pdf")"
report "/Launch PDF refused" 415 "$(upload "$token" "$tmp/files/launch.pdf")"
report "encrypted PDF refused (AlertEncryptedDoc)" 415 "$(upload "$token" "$tmp/files/encrypted.pdf")"
report "/OpenAction PDF refused" 415 "$(upload "$token" "$tmp/files/openaction.pdf")"
report "/AA PDF refused" 415 "$(upload "$token" "$tmp/files/aa.pdf")"
report "/JS only inside a compressed object stream refused" 415 "$(upload "$token" "$tmp/files/objstm-js.pdf")"
report "hex-escaped /J#61vaScript refused" 415 "$(upload "$token" "$tmp/files/escaped-javascript.pdf")"
report "hex-escaped /#4A#53 refused" 415 "$(upload "$token" "$tmp/files/escaped-js.pdf")"
report "legitimate #20 font name stored" 201 "$(upload "$(new_case)" "$tmp/files/font-space-name.pdf")"
report "text file refused before any scan" 415 "$(upload "$token" "$tmp/files/not-a-document.txt")"
report "clean PDF stored (bucket, SSE-KMS)" 201 "$(upload "$(new_case)" "$tmp/files/plain.pdf")"
report "photo re-encoded by the sanitizer and stored" 201 "$(upload "$(new_case)" "$tmp/files/photo.png")"
echo "case token (staging, synthetic) for the bucket check: ${token:0:6}… slug $SLUG"

# 3. The scanner link refuses a client without a certificate.
noclient=$(openssl s_client -connect "$SCANNER_HOST:3310" -servername "$SCANNER_HOST" -tls1_3 -quiet \
  </dev/null 2>&1 || true)
if printf '%s' "$noclient" | grep -Eqi 'certificate required|alert|handshake failure|errno|unexpected eof'; then
  report "scanner refuses a client without a certificate" refused refused
else
  report "scanner refuses a client without a certificate" refused "accepted?"
fi
if [ -n "${PKI_DIR:-}" ]; then
  pong=$(printf 'zPING\0' | openssl s_client -connect "$SCANNER_HOST:3310" -servername "$SCANNER_HOST" -tls1_3 \
    -cert "$PKI_DIR/client-kybr-api-staging.crt" -key "$PKI_DIR/client-kybr-api-staging.key" \
    -CAfile "$PKI_DIR/ca.crt" -verify_return_error -quiet 2>/dev/null | tr -d '\0' || true)
  report "scanner answers the API certificate (clamd PING)" PONG "${pong:-none}"
fi

if [ "$fail" -ne 0 ]; then echo "GATE EVIDENCE: FAILED"; exit 1; fi
echo "GATE EVIDENCE: all checks passed"
