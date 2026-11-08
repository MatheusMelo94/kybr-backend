#!/bin/sh
# Scanner machine supervisor (PID 1 is tini; this script runs as root only to set up per-user runtime
# directories and drop every process to its own user with su-exec; it handles no input).
#
#   clamd + freshclam  user clamav     clamd on a Unix socket only: /run/clamd/clamd.sock
#                                      (dir clamav:scanlink 0750, socket 0660), no TCP listener
#   stunnel            user scanlink   the only outside listener; the only reader of the TLS key
#   photo sanitizer    user sanitizer  127.0.0.1:8090; cannot open the clamd socket, write the signature
#                                      directory or any configuration, or read the TLS key
#
# Exits (Fly restarts the machine) if clamd, freshclam or stunnel dies, or if a Kybr signature file no
# longer matches its root-owned original. Restarts the sanitizer alone when its health check fails.
set -eu

die() { echo "scanner: $*" >&2; exit 1; }
log() { echo "scanner: $*"; }
as() { user=$1; shift; exec su-exec "$user" "$@"; }

[ "$(id -u)" = 0 ] || die "the supervisor must start as root (it drops each process to its own user)"

# 1. This org holds the link's TLS material and nothing else.
for name in $(env | sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p'); do
  case "$name" in
    KYBR_*|MONGODB*|AWS_*|RESEND*|FLY_API_TOKEN|SPRING_*|*_PASSWORD|*_API_KEY)
      die "refusing to start: setting $name must not exist in the scanner org" ;;
  esac
done
: "${SCAN_TLS_CERT:?SCAN_TLS_CERT is required (server certificate, PEM)}"
: "${SCAN_TLS_KEY:?SCAN_TLS_KEY is required (server private key, PEM)}"
: "${SCAN_TLS_CA:?SCAN_TLS_CA is required (the link CA certificate, PEM)}"

# 2. Runtime directories, one owner each.
umask 022
mkdir -p /run/kybr /run/kybr/tls /run/clamd /run/sanitizer-tmp
chown root:root /run/kybr && chmod 0755 /run/kybr
chown scanlink:scanlink /run/kybr/tls && chmod 0700 /run/kybr/tls
chown clamav:scanlink /run/clamd && chmod 0750 /run/clamd
chown sanitizer:sanitizer /run/sanitizer-tmp && chmod 0700 /run/sanitizer-tmp
(
  umask 077
  printf '%s\n' "$SCAN_TLS_CERT" > /run/kybr/tls/server.crt
  printf '%s\n' "$SCAN_TLS_KEY" > /run/kybr/tls/server.key
  printf '%s\n' "$SCAN_TLS_CA" > /run/kybr/tls/ca.crt
)
chown scanlink:scanlink /run/kybr/tls/*
chmod 0400 /run/kybr/tls/*
unset SCAN_TLS_CERT SCAN_TLS_KEY SCAN_TLS_CA

# 3. clamd on a Unix socket only. The shipped config must not choose a listener itself.
if grep -Eiq '^[[:space:]]*(TCPAddr|LocalSocket)' /etc/clamav/clamd.conf; then
  die "clamd.conf must not set TCPAddr or LocalSocket (the entrypoint pins the Unix socket)"
fi
grep -Eiv '^[[:space:]]*TCPSocket' /etc/clamav/clamd.conf > /run/kybr/clamd.conf
cat >> /run/kybr/clamd.conf <<'EOF'
LocalSocket /run/clamd/clamd.sock
LocalSocketGroup scanlink
LocalSocketMode 660
FixStaleSocket yes
EOF
chmod 0644 /run/kybr/clamd.conf

# 4. Signatures. /var/lib/clamav is a Fly volume, so the official databases survive stops and a wake-up
# only fetches increments. The Kybr files are re-copied from their root-owned originals on every boot.
chown clamav:clamav /var/lib/clamav && chmod 0755 /var/lib/clamav
for f in kybr.ndb kybr.ldb; do
  install -o root -g root -m 0644 "/etc/clamav/$f" "/var/lib/clamav/$f"
done
have_db() { ls /var/lib/clamav/main.c?d /var/lib/clamav/daily.c?d >/dev/null 2>&1; }
if have_db; then
  # Bounded update, so a slow or throttled CDN can't hold the wake-up past the API's 120 s budget. On
  # failure clamd starts with the volume's signatures; the freshclam daemon keeps trying, and the API
  # refuses to scan (and alerts) once the signatures are older than its maximum age.
  if timeout 45 su-exec clamav freshclam --config-file=/etc/clamav/freshclam.conf --foreground --stdout; then
    log "event=freshclam_boot_ok"
  else
    log "event=freshclam_boot_failed (starting with the signatures on the volume)"
  fi
else
  # First boot (empty volume): nothing to scan with until the full download succeeds.
  n=0
  until su-exec clamav freshclam --config-file=/etc/clamav/freshclam.conf --foreground --stdout; do
    n=$((n + 1)); [ "$n" -lt 5 ] || die "event=freshclam_boot_failed initial signature download failed 5 times"
    log "freshclam failed; retrying in 30 s ($n/5)"; sleep 30
  done
fi

(as clamav clamd --foreground --config-file=/run/kybr/clamd.conf) &
clamd_pid=$!
clamd_ping() { printf 'zPING\0' | timeout 5 socat - UNIX-CONNECT:/run/clamd/clamd.sock 2>/dev/null | tr -d '\0'; }
n=0
until [ "$(clamd_ping)" = "PONG" ]; do
  kill -0 "$clamd_pid" 2>/dev/null || die "clamd exited during startup"
  n=$((n + 1)); [ "$n" -lt 120 ] || die "clamd not ready after 4 min"
  sleep 2
done
log "clamd ready on /run/clamd/clamd.sock"

(as clamav freshclam --config-file=/etc/clamav/freshclam.conf --daemon --foreground) &
fresh_pid=$!

# 5. Photo sanitizer: the API image's jar, its own main class, its own user and temp directory. It refuses
# to start if any Kybr, database, cloud or e-mail setting is present (none exists in this org).
start_sanitizer() {
  (cd /opt/sanitizer && as sanitizer java -Xms256m -Xmx768m -XX:+ExitOnOutOfMemoryError \
    -Djava.awt.headless=true -Djava.io.tmpdir=/run/sanitizer-tmp \
    -cp application.jar com.matheusmelo.kybr.sanitizer.SanitizerApplication) &
  san_pid=$!
  log "sanitizer started (pid $san_pid)"
}
start_sanitizer

# 6. stunnel last: nothing is reachable from outside until clamd and the sanitizer are up.
(as scanlink stunnel /etc/stunnel/kybr.conf) &
stunnel_pid=$!

# 7. Supervise.
sigs_intact() {
  for f in kybr.ndb kybr.ldb; do
    cmp -s "/etc/clamav/$f" "/var/lib/clamav/$f" || return 1
  done
}
fails=0
while :; do
  sleep 10
  kill -0 "$clamd_pid" 2>/dev/null || die "clamd exited"
  kill -0 "$fresh_pid" 2>/dev/null || die "freshclam exited"
  kill -0 "$stunnel_pid" 2>/dev/null || die "stunnel exited"
  sigs_intact || die "a Kybr signature file changed on disk; restarting from the image"
  if kill -0 "$san_pid" 2>/dev/null && wget -q -T 5 -O /dev/null http://127.0.0.1:8090/health; then
    fails=0
  else
    fails=$((fails + 1))
    if [ "$fails" -ge 3 ] || ! kill -0 "$san_pid" 2>/dev/null; then
      log "sanitizer unhealthy; restarting it"
      kill -9 "$san_pid" 2>/dev/null || true
      # anything else left running as the sanitizer user (it could hold the port) goes too
      for p in $(ps -o pid,user | awk '$2 == "sanitizer" {print $1}'); do kill -9 "$p" 2>/dev/null || true; done
      wait "$san_pid" 2>/dev/null || true
      start_sanitizer
      fails=0
    fi
  fi
done
