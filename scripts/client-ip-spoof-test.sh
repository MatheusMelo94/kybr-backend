#!/usr/bin/env bash
# Client-IP spoof test, a pre-deploy gate. Run against STAGING before any
# Vercel Preview talks to it. Requires: curl, flyctl (logged in with read access to the app).
#
#   API_BASE=https://api-staging.kybr.com.br FLY_APP=kybr-api-staging \
#   PREVIEW_URL=https://<preview>.vercel.app/<page-that-calls-the-api> \
#     scripts/client-ip-spoof-test.sh
#
# Case 2 (direct to the API, bypassing the BFF) is fully automated: no BFF key, forged Fly-Client-IP,
#   X-Forwarded-For and X-Kybr-Client-IP to GET /livez (expect 200) and GET /v1/anything (expect 403).
#   Both access-log lines must show the tester's real IP with client_ip_source=DIRECT.
# Case 1 (through the preview / BFF path) sends the forged headers to PREVIEW_URL; the resulting API
#   access-log line must show the tester's real IP with client_ip_source=FORWARDED. Correlation is by time
#   window, so the script prints the candidate lines for a human to confirm.
# Any API log line in the window containing 203.0.113.x or PLATFORM_ABSENT => NO-GO (the edge is not setting the client IP as expected).
# All addresses used are RFC 5737 documentation addresses. No secrets are needed or sent.
set -euo pipefail

API_BASE=${API_BASE:?set API_BASE, e.g. https://api-staging.kybr.com.br}
FLY_APP=${FLY_APP:?set FLY_APP, e.g. kybr-api-staging}
PREVIEW_URL=${PREVIEW_URL:-}
API_BASE=${API_BASE%/}

command -v flyctl >/dev/null 2>&1 || { echo "flyctl not found" >&2; exit 2; }

# Force IPv4 everywhere so the IP Fly sees matches the IP we detect.
TESTER_IP=${TESTER_IP:-$(curl -4 -sS -m 10 https://checkip.amazonaws.com | tr -d '[:space:]')}
[ -n "$TESTER_IP" ] || { echo "could not detect tester IPv4; set TESTER_IP" >&2; exit 2; }
echo "Tester IPv4: $TESTER_IP"

forged=(-H 'Fly-Client-IP: 203.0.113.8' -H 'X-Forwarded-For: 203.0.113.7' -H 'X-Kybr-Client-IP: 203.0.113.9')

request() { # $1=url $2=expected status; prints the X-Request-Id
  local hdrs status rid
  hdrs=$(curl -4 -sS --proto '=https' --tlsv1.2 -m 30 -o /dev/null -D - "${forged[@]}" "$1")
  status=$(printf '%s' "$hdrs" | awk 'toupper($1) ~ /^HTTP\// {s=$2} END {print s}')
  rid=$(printf '%s' "$hdrs" | awk -F': ' 'tolower($1)=="x-request-id" {gsub("\r","",$2); print $2}' | tail -n1)
  if [ "$status" != "$2" ]; then echo "FAIL: $1 returned $status, expected $2" >&2; exit 1; fi
  [ -n "$rid" ] || { echo "FAIL: $1 returned no X-Request-Id" >&2; exit 1; }
  echo "$rid"
}

echo "== Case 2: direct to the API (no BFF key) =="
rid_livez=$(request "$API_BASE/livez" 200)
rid_403=$(request "$API_BASE/v1/anything" 403)
echo "request ids: livez=$rid_livez, 403=$rid_403"

if [ -n "$PREVIEW_URL" ]; then
  echo "== Case 1: through the preview (BFF path) =="
  curl -4 -sS -m 30 -o /dev/null "${forged[@]}" "$PREVIEW_URL" || true
fi

echo "Waiting 15 s for logs to arrive ..."
sleep 15
logs=$(flyctl logs --app "$FLY_APP" --no-tail 2>/dev/null || true)

verdict=GO
check_line() { # $1=request id $2=expected source
  local line
  line=$(printf '%s\n' "$logs" | grep -F "$1" | tail -n1 || true)
  if [ -z "$line" ]; then echo "NO-GO: no access-log line found for request $1"; verdict=NO-GO; return; fi
  echo "  $line"
  if ! printf '%s' "$line" | grep -qF "$TESTER_IP"; then echo "NO-GO: line for $1 lacks tester IP $TESTER_IP"; verdict=NO-GO; fi
  if ! printf '%s' "$line" | grep -q "$2"; then echo "NO-GO: line for $1 lacks client_ip_source=$2"; verdict=NO-GO; fi
}

echo "== Access-log lines (case 2) =="
check_line "$rid_livez" DIRECT
check_line "$rid_403" DIRECT

if printf '%s\n' "$logs" | grep -Eq '203\.0\.113\.[0-9]+.*client_ip|PLATFORM_ABSENT'; then
  echo "NO-GO: a recent log line carries a forged 203.0.113.x client IP or PLATFORM_ABSENT:"
  printf '%s\n' "$logs" | grep -E '203\.0\.113\.[0-9]+.*client_ip|PLATFORM_ABSENT' | tail -n5
  verdict=NO-GO
fi

if [ -n "$PREVIEW_URL" ]; then
  echo "== Case 1 candidates (confirm manually: tester IP + FORWARDED) =="
  printf '%s\n' "$logs" | grep -F "$TESTER_IP" | grep FORWARDED | tail -n5 || echo "  (none found: NO-GO until confirmed)"
else
  echo "Case 1 not run (PREVIEW_URL unset). Both cases are required to pass the gate."
fi

echo "Case 2 verdict: $verdict"
[ "$verdict" = GO ]
