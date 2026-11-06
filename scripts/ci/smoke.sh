#!/usr/bin/env bash
# Post-deploy smoke test against a Fly app's public edge.
#   scripts/ci/smoke.sh https://api-staging.kybr.com.br [max_wait_seconds]
# Fails (exit 1) if GET /livez over HTTPS (TLS >= 1.2) is not 200 with status UP within the wait window
# (staging auto-stops to zero, so the first request may cold-start a machine).
# Also reports whether plain http:// on port 80 answers at all (the edge should serve 443 only) as a warning.
set -euo pipefail

base=${1:?usage: smoke.sh <https-base-url> [max_wait_seconds]}
max_wait=${2:-120}
case "$base" in https://*) ;; *) echo "::error::base URL must be https://"; exit 1 ;; esac
base=${base%/}

deadline=$(( $(date +%s) + max_wait ))
attempt=0
while :; do
  attempt=$((attempt + 1))
  body=$(curl -sS --proto '=https' --tlsv1.2 -m 15 -w '\n%{http_code}' "$base/livez" 2>/dev/null || true)
  code=${body##*$'\n'}
  payload=${body%$'\n'*}
  if [ "$code" = "200" ] && printf '%s' "$payload" | grep -q '"UP"'; then
    echo "smoke: $base/livez -> 200 UP (attempt $attempt)"
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "::error title=Smoke test failed::$base/livez did not return 200 UP within ${max_wait}s (last status: ${code:-none})"
    exit 1
  fi
  sleep 5
done

# 443-only check (informational): any HTTP response on port 80 means the edge accepts plaintext requests.
host=${base#https://}; host=${host%%/*}
plain=$(curl -sS -m 5 -o /dev/null -w '%{http_code}' "http://$host/livez" 2>/dev/null || true)
if [ -n "$plain" ] && [ "$plain" != "000" ]; then
  echo "::warning title=Port 80 answers::http://$host/livez returned HTTP $plain. Expected no plaintext listener (443 only); review the edge/IP setup."
else
  echo "smoke: http://$host (port 80) gave no HTTP response, as expected"
fi
