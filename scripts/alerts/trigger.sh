#!/usr/bin/env bash
# Synthetic alert triggers: one subcommand per alert signal (the `signal` label on each rule in
# deploy/alerting/rules/). Run against STAGING to prove each alert reaches the operator; record the
# alert, the trigger and the time the e-mail arrived in the private Deploy Checklist.
#
#   scripts/alerts/trigger.sh <signal>        scripts/alerts/trigger.sh list
#
# Environment (never pass secrets as arguments; they are read from the environment only):
#   API_BASE     staging API base URL, e.g. https://api-staging.kybr.com.br
#   BFF_KEY      the staging BFF key (from the password manager)
#   SLUG         a staging test link (seeded by the staging runbook); use a dedicated one for budgets
#   FLY_APP      default kybr-api-staging
#   ADMIN_TOKEN  the staging admin token (admin-auth-failed sends a WRONG token; spend-off/autofill-off use it)
#   MGMT_URL     default http://127.0.0.1:18081, with `fly proxy 18081:8081 -a $FLY_APP` running
#   COUNT        how many requests, for volume signals: set it above the alert's private threshold
#   HOLD         seconds to keep a switch flipped before restoring it (default 180)
#   STAGING_MONGODB_URI  only for `credits` (staging Atlas, fake data; exported env var, never an argument;
#                        read inside mongosh, so it never appears in argv), needs mongosh
# All client IPs used are RFC 5737 documentation addresses; CNPJs are stub fixtures or random valid
# numbers (the stub answers "not found" for any CNPJ without a fixture). No real data is involved.
set -euo pipefail

FLY_APP=${FLY_APP:-kybr-api-staging}
MGMT_URL=${MGMT_URL:-http://127.0.0.1:18081}
HOLD=${HOLD:-180}

die() { echo "trigger: $*" >&2; exit 1; }
need() { for v in "$@"; do [ -n "${!v:-}" ] || die "set $v"; done; }
log() { echo "[$(date -u +%FT%TZ)] $*"; }

[ "$FLY_APP" != "kybr-api" ] || [ "${1:-}" = "metrics-missing" ] || die "refusing to run against prod (kybr-api)"

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
umask 077

key_header() { printf 'X-Kybr-BFF-Key: %s\n' "$BFF_KEY" > "$tmp/bff.h"; }
admin_header() { printf 'Authorization: Bearer %s\n' "$1" > "$tmp/admin.h"; }

# 4 requests per synthetic IP keeps every per-IP limit unexhausted unless a signal wants 429s.
ip_for() { local i=$(( $1 / ${2:-4} )); printf '198.51.100.%d' $(( (i % 250) + 1 )); }

uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]'; }

# A random valid numeric CNPJ (check digits per the official rule); prefix 99 keeps it off the fixtures.
random_cnpj() {
  local base d i s w r
  base="99$(printf '%010d' $(( (RANDOM * 32768 + RANDOM) % 10000000000 )))"
  for w in "5 4 3 2 9 8 7 6 5 4 3 2" "6 5 4 3 2 9 8 7 6 5 4 3 2"; do
    s=0; i=0
    for d in $w; do s=$(( s + ${base:i:1} * d )); i=$((i + 1)); done
    r=$(( s % 11 )); [ $r -lt 2 ] && base="${base}0" || base="${base}$(( 11 - r ))"
  done
  echo "$base"
}

lookup() { # $1=cnpj $2=client ip or "" (no header) ; prints the status
  local args=(-sS -o /dev/null -w '%{http_code}' --proto '=https' -m 20 -H @"$tmp/bff.h")
  [ -n "${2:-}" ] && args+=(-H "X-Kybr-Client-IP: $2")
  curl "${args[@]}" "$API_BASE/public/v/$SLUG/lookup?cnpj=$1"
}

verify() { # $1=cnpj $2=client ip ; prints "status token"
  local out
  out=$(curl -sS --proto '=https' -m 20 -w '\n%{http_code}' -H @"$tmp/bff.h" -H "X-Kybr-Client-IP: $2" \
    -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
    -d "{\"cnpj\":\"$1\",\"responsavel\":\"Maria Teste Sintetica\"}" "$API_BASE/public/v/$SLUG/verify")
  printf '%s %s\n' "${out##*$'\n'}" "$(printf '%s' "${out%$'\n'*}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
}

contact() { # $1=token $2=email $3=ip ; prints the status
  curl -sS -o /dev/null -w '%{http_code}' --proto '=https' -m 20 -X PUT -H @"$tmp/bff.h" \
    -H "X-Kybr-Client-IP: $3" -H 'Content-Type: application/json' -d "{\"email\":\"$2\"}" \
    "$API_BASE/public/v/$SLUG/cases/$1/contact"
}

admin_post() { # $1=operation $2=json ; uses ADMIN_TOKEN through fly proxy
  admin_header "$ADMIN_TOKEN"
  curl -sS -o /dev/null -w '%{http_code}' -m 10 -H @"$tmp/admin.h" -H 'Content-Type: application/json' \
    -d "$2" "$MGMT_URL/actuator/admin/$1"
}

tally() { sort | uniq -c | sed 's/^/    /'; }

# Adds $2 credits to the $1 (YYYY-MM) staging ledger. The URI (with its password) never reaches argv:
# mongosh starts with --nodb and a 0600 script that reads STAGING_MONGODB_URI from the environment.
ledger_inc() {
  cat > "$tmp/ledger.js" <<JS
const target = connect(process.env.STAGING_MONGODB_URI).getSiblingDB(process.env.STAGING_DB || 'kybr');
const r = target.credit_ledger.updateOne({ _id: '$1' }, { \$inc: { usados: $2 } }, { upsert: true });
print('ledger $1: ' + ($2 > 0 ? '+' : '') + $2 + ' (matched ' + r.matchedCount + ', upserted ' + (r.upsertedId ? 1 : 0) + ')');
JS
  mongosh --nodb --quiet "$tmp/ledger.js"
}

signal=${1:-list}
case "$signal" in
  list)
    sed -n 's/^  \([a-z0-9-]*\))$/\1/p' "$0" | grep -v '^list$'
    ;;

  lookup-volume)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    log "lookup-volume: $COUNT lookups (cache hits after the first) on $SLUG"
    for i in $(seq 0 $((COUNT - 1))); do lookup 12345678000195 "$(ip_for "$i")"; echo; done | tally
    ;;

  verify-volume)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    fixtures=(12345678000195 12ABC34501DE35 36925814000184 47158263000197 52000014000109 58293614000199 52000010000120 98765432000198)
    log "verify-volume: $COUNT verifications on $SLUG (fixture CNPJs, rotating IPs)"
    for i in $(seq 0 $((COUNT - 1))); do verify "${fixtures[$(( (i / 3) % ${#fixtures[@]} ))]}" "$(ip_for "$i" 3)" | cut -d' ' -f1; done | tally
    ;;

  cache-reads)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    log "cache-reads: $COUNT cached lookups on $SLUG within 24 h"
    lookup 12345678000195 198.51.100.250 >/dev/null
    for i in $(seq 0 $((COUNT - 1))); do lookup 12345678000195 "$(ip_for "$i" 25)"; echo; done | tally
    ;;

  contact-limited)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    log "contact-limited: $COUNT cases x 6 address changes (the 6th per case is refused)"
    for c in $(seq 1 "$COUNT"); do
      ip=$(printf '203.0.113.%d' $(( (c % 250) + 1 )))
      read -r st token < <(verify 12345678000195 "$ip")
      [ "$st" = 201 ] && [ -n "$token" ] || { echo "    verify returned $st"; continue; }
      for n in 1 2 3 4 5 6; do contact "$token" "teste$n.$c@example.invalid" "$ip"; echo; done
    done | tally
    ;;

  rate-limit-429)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    log "rate-limit-429: $COUNT lookups from one IP (limit is per minute)"
    for i in $(seq 1 "$COUNT"); do lookup 12345678000195 198.51.100.77; echo; done | tally
    ;;

  client-ip-absent)
    need API_BASE BFF_KEY SLUG; key_header
    log "client-ip-absent: 3 lookups with a valid key and no X-Kybr-Client-IP (a route that expects it)"
    for i in 1 2 3; do lookup 12345678000195 ""; echo; done | tally
    ;;

  ip-unknown)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    log "ip-unknown: $COUNT lookups without a client IP (shared-IP subject)"
    for i in $(seq 1 "$COUNT"); do lookup 12345678000195 ""; echo; done | tally
    ;;

  platform-header)
    log "platform-header: an invalid Fly-Client-IP sent from inside the machine (the edge would overwrite it)"
    fly ssh console --app "$FLY_APP" -C "wget -q -O /dev/null --header=Fly-Client-IP:not-an-ip http://127.0.0.1:8080/livez" || true
    ;;

  bff-key-rejected)
    need API_BASE COUNT
    log "bff-key-rejected: $COUNT keyless requests"
    for i in $(seq 1 "$COUNT"); do curl -sS -o /dev/null -w '%{http_code}\n' --proto '=https' -m 10 "$API_BASE/public/v/synthetic-check"; done | tally
    ;;

  admin-auth-failed)
    log "admin-auth-failed: one admin call with a wrong token through fly proxy ($MGMT_URL)"
    admin_header "synthetic-wrong-token-$(uuid)"
    curl -sS -o /dev/null -w '    %{http_code}\n' -m 10 -H @"$tmp/admin.h" "$MGMT_URL/actuator/admin"
    ;;

  credits)
    need API_BASE BFF_KEY SLUG STAGING_MONGODB_URI; key_header
    command -v mongosh >/dev/null || die "mongosh is required"
    case "$FLY_APP" in *staging*) ;; *) die "credits only runs on staging" ;; esac
    month=$(TZ=America/Sao_Paulo date +%Y-%m)
    credits=${MONTHLY_CREDITS:-10000}
    add=$(( credits * 85 / 100 ))
    log "credits: +$add credits on the $month ledger (85% of $credits), then one lookup to refresh the gauge"
    ledger_inc "$month" "$add"
    lookup "$(random_cnpj)" 198.51.100.200; echo
    log "holding ${HOLD}s for the 80% alert; then +15% for the exhausted alert"
    sleep "$HOLD"
    more=$(( credits * 15 / 100 + 1 ))
    ledger_inc "$month" "$more"
    lookup 12345678000195 198.51.100.201; echo
    sleep "$HOLD"
    log "restoring the ledger (-$((add + more)))"
    ledger_inc "$month" "-$((add + more))"
    lookup 12345678000195 198.51.100.202; echo
    ;;

  budget)
    need API_BASE BFF_KEY SLUG; key_header
    n=${COUNT:-52}
    log "budget: $n fresh lookups (random CNPJs) on $SLUG: the per-link daily lookup budget warns at 80% and is exhausted at 100%"
    for i in $(seq 0 $((n - 1))); do lookup "$(random_cnpj)" "$(ip_for "$i")"; echo; done | tally
    echo "    Note: $SLUG stays cache-only for lookups until midnight (Sao Paulo). Use a dedicated test link."
    ;;

  no-data-cap)
    need API_BASE BFF_KEY SLUG; key_header
    n=${COUNT:-23}
    log "no-data-cap: $n verifications with random CNPJs (stub: no data) on $SLUG"
    for i in $(seq 0 $((n - 1))); do verify "$(random_cnpj)" "$(ip_for "$i" 3)" | cut -d' ' -f1; done | tally
    echo "    Note: new verifications on $SLUG are deferred to manual review until midnight. Use a dedicated test link."
    ;;

  spend-off)
    need ADMIN_TOKEN
    log "spend-off: provider spend brake ON for ${HOLD}s"
    echo "    off: $(admin_post gasto-provedor '{"ativo":"false"}')"
    sleep "$HOLD"
    echo "    restored: $(admin_post gasto-provedor '{"ativo":"true"}')"
    ;;

  autofill-off)
    need ADMIN_TOKEN
    log "autofill-off: GLOBAL autofill kill switch on for ${HOLD}s (kybr_link_autofill_off = 1)"
    echo "    off: $(admin_post autofill-global '{"ativo":"false"}')"
    sleep "$HOLD"
    echo "    restored: $(admin_post autofill-global '{"ativo":"true"}')"
    ;;

  autofill-slug-off)
    need API_BASE BFF_KEY SLUG ADMIN_TOKEN; key_header
    log "autofill-slug-off: autofill off on $SLUG, lookups answer disabled, restore after ${HOLD}s"
    echo "    off: $(admin_post autofill-ativo "{\"slug\":\"$SLUG\",\"ativo\":\"false\"}")"
    sleep 12
    for i in 1 2 3; do lookup 12345678000195 "198.51.100.21$i"; echo; done | tally
    sleep "$HOLD"
    echo "    restored: $(admin_post autofill-ativo "{\"slug\":\"$SLUG\",\"ativo\":\"true\"}")"
    ;;

  provider-errors)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    log "provider-errors: $COUNT lookups of the 'provider unavailable' fixture (kybr_provider_calls{tipo=basic,resultado=error})"
    for i in $(seq 0 $((COUNT - 1))); do lookup 52000015000153 "$(ip_for "$i")"; echo; done | tally
    ;;

  http-5xx)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    log "http-5xx: $COUNT lookups answered 503 (provider unavailable fixture) within 5 min"
    for i in $(seq 0 $((COUNT - 1))); do lookup 52000015000153 "$(ip_for "$i")"; echo; done | tally
    ;;

  verification-failed)
    need API_BASE BFF_KEY SLUG; key_header
    log "verification-failed: one verification of the 'provider unavailable' fixture (fails after all retries, ~3 min)"
    verify 52000015000153 203.0.113.50 | cut -d' ' -f1 | tally
    ;;

  verification-slow)
    need API_BASE BFF_KEY SLUG; key_header
    log "verification-slow: one verification of the slow fixture (about 6 s)"
    verify 25873916000187 203.0.113.51 | cut -d' ' -f1 | tally
    ;;

  completion-missing)
    echo "completion-missing: not triggerable on a deployed app (it needs a code defect that the test"
    echo "profile already turns into an exception). Proven by the rule unit test in CI"
    echo "(scripts/ci/check-alerting.sh); record it in the checklist as 'unit-tested only'."
    ;;

  metrics-missing)
    [ "$FLY_APP" = "kybr-api" ] || die "metrics-missing is a prod rule: run with FLY_APP=kybr-api"
    [ "${CONFIRM_PRELAUNCH:-}" = "yes" ] || die "this stops prod for ~12 min: only before go-live, with CONFIRM_PRELAUNCH=yes"
    log "metrics-missing: scaling kybr-api to 0 for 12 min (pre-launch only), then back to 2"
    fly scale count 0 --app kybr-api --yes
    sleep 720
    fly scale count 2 --region gru --app kybr-api --yes
    ;;

  log-event)
    ev=${EVENT:-redirect_blocked}
    case "$ev" in redirect_blocked|cookie_blocked|idempotency_lease_lost|idempotency_decrypt_failed|idempotency_complete_failed|client_ip_platform_absent|unhandled_exception) ;;
      *) die "EVENT must be one of the alerted log events" ;; esac
    log "log-event: writing a synthetic 'event=$ev' line to the app's stdout (proves log shipper -> metric -> alert)"
    fly ssh console --app "$FLY_APP" -C "sh -c 'echo \"event=$ev synthetic_trigger=true\" > /proc/1/fd/1'"
    ;;

  *)
    die "unknown signal '$signal' (try: $0 list)"
    ;;
esac
