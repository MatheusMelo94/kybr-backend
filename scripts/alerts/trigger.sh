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
#   TIPO         document type to upload (default: the first one the pending test case asks for)
#   TEST_RECIPIENT  an address on staging's KYBR_EMAIL_TEST_RECIPIENTS (email-capped)
#   STAGING_KMS_KEY_ARN  the staging key ARN, to restore it after `storage-errors` (env only)
# Document signals use synthetic files from scripts/documents/make-test-files.py (uploads carry no key:
# the seller's browser calls that route directly).
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

files_dir() { # synthetic upload files, generated once per run
  [ -f "$tmp/files/plain.pdf" ] || python3 -I "$(dirname "$0")/../documents/make-test-files.py" "$tmp/files" >/dev/null
  echo "$tmp/files"
}

pending_case() { # prints "token tipo" for a new case that asks for documents (stub "pending" fixture)
  local st token body tipo n=0
  read -r st token < <(verify 52000001000130 "203.0.113.$(( (RANDOM % 200) + 20 ))")
  [ "$st" = 201 ] && [ -n "$token" ] || die "could not create a pending test case (verify returned $st)"
  while :; do
    body=$(curl -sS --proto '=https' -m 20 -H @"$tmp/bff.h" -H 'X-Kybr-Client-IP: 203.0.113.250' \
      "$API_BASE/public/v/$SLUG/cases/$token")
    tipo=${TIPO:-$(printf '%s' "$body" | sed -n 's/.*"documento_tipo":"\([A-Z_]*\)".*/\1/p' | head -n1)}
    [ -n "$tipo" ] && break
    n=$((n + 1)); [ "$n" -lt 15 ] || die "the test case asks for no document (set TIPO)"
    sleep 2
  done
  echo "$token $tipo"
}

upload() { # $1=token $2=tipo $3=file ; prints the status (no BFF key: browser-direct route)
  curl -sS -o /dev/null -w '%{http_code}' --proto '=https' -m 60 -F "tipo=$2" \
    -F "arquivos=@$3;type=application/octet-stream" "$API_BASE/public/v/$SLUG/cases/$1/documents"
}

# Temporarily overrides one staging setting with a Fly secret, checks it took effect, and records how to
# restore it. Staging only.
override() { # $1=NAME $2=value (non-secret values only)
  case "$FLY_APP" in *staging*) ;; *) die "overrides only run on staging" ;; esac
  printf '%s=%s\n' "$1" "$2" | fly secrets import --app "$FLY_APP" >/dev/null
  log "override $1 set; waiting for the restart"
  sleep 60
  [ "$(fly ssh console --app "$FLY_APP" -C "printenv $1" 2>/dev/null | tr -d '\r')" = "$2" ] \
    || die "the override of $1 didn't take effect; restore with: fly secrets unset $1 --app $FLY_APP"
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
    sed -n 's/^  \([a-z0-9|-]*\))$/\1/p' "$0" | tr '|' '\n' | grep -v '^list$'
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

  upload-formato)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    d=$(files_dir); read -r token tipo < <(pending_case)
    log "upload-formato: $COUNT uploads of a text file on one case (415 before any scan)"
    for i in $(seq 1 "$COUNT"); do upload "$token" "$tipo" "$d/not-a-document.txt"; echo; done | tally
    ;;

  upload-limited)
    need API_BASE BFF_KEY SLUG COUNT; key_header
    d=$(files_dir); read -r token tipo < <(pending_case)
    log "upload-limited: 60 + $COUNT uploads on one case (the per-case daily limit is 60)"
    for i in $(seq 1 $((60 + COUNT))); do upload "$token" "$tipo" "$d/not-a-document.txt"; echo; done | tally
    ;;

  malware)
    need API_BASE BFF_KEY SLUG; key_header
    d=$(files_dir); read -r token tipo < <(pending_case)
    log "malware: one valid PDF carrying the scanner self-test marker (415; the stub and real scanner both flag it)"
    upload "$token" "$tipo" "$d/marker.pdf"; echo
    ;;

  signature-age)
    echo "signature-age: not triggerable on demand (signatures must be 26 h / 48 h old). Proven by the"
    echo "rule unit test; record it in the checklist as 'unit-tested only'. Live check: the gauge"
    echo "kybr_scanner_signature_age_hours stays below 26 in Fly's Grafana while freshclam runs."
    ;;

  scanner-unavailable)
    need API_BASE BFF_KEY SLUG; key_header
    n=${COUNT:-5}
    d=$(files_dir); read -r token tipo < <(pending_case)
    log "scanner-unavailable: staging points the scanner client at the sanitizer port (TLS works, clamd doesn't answer)"
    override KYBR_SCANNER_PORT 3311
    for i in $(seq 1 "$n"); do upload "$token" "$tipo" "$d/plain.pdf"; echo; done | tally
    sleep "$HOLD"
    fly secrets unset KYBR_SCANNER_PORT --app "$FLY_APP" >/dev/null
    log "restored KYBR_SCANNER_PORT (fly.toml value applies again)"
    ;;

  reencode-failures)
    need API_BASE BFF_KEY SLUG; key_header
    n=${COUNT:-5}
    d=$(files_dir); read -r token tipo < <(pending_case)
    log "reencode-failures: staging points the sanitizer client at the clamd port (no HTTP answer)"
    override KYBR_SANITIZER_PORT 3310
    for i in $(seq 1 "$n"); do upload "$token" "$tipo" "$d/photo.png"; echo; done | tally
    sleep "$HOLD"
    fly secrets unset KYBR_SANITIZER_PORT --app "$FLY_APP" >/dev/null
    log "restored KYBR_SANITIZER_PORT (fly.toml value applies again)"
    ;;

  storage-errors)
    need API_BASE BFF_KEY SLUG STAGING_KMS_KEY_ARN; key_header
    d=$(files_dir); read -r token tipo < <(pending_case)
    log "storage-errors: staging sends puts under a key the bucket policy refuses (no such key)"
    override KYBR_STORAGE_KMS_KEY_ID "arn:aws:kms:sa-east-1:000000000000:key/00000000-0000-0000-0000-000000000000"
    upload "$token" "$tipo" "$d/plain.pdf"; echo
    sleep "$HOLD"
    printf 'KYBR_STORAGE_KMS_KEY_ID=%s\n' "$STAGING_KMS_KEY_ARN" | fly secrets import --app "$FLY_APP" >/dev/null
    log "restored KYBR_STORAGE_KMS_KEY_ID from STAGING_KMS_KEY_ARN"
    ;;

  email-failed)
    echo "email-failed: not triggerable while the only outbox notice (contact changed) is switched off"
    echo "for lack of copy. Proven by the rule unit test; record it as 'unit-tested only' and trigger it"
    echo "once the notice ships (a recipient the provider refuses)."
    ;;

  email-capped)
    need ADMIN_TOKEN TEST_RECIPIENT
    n=${COUNT:-2}
    log "email-capped: 3 + $n operator test e-mails to one address within the hour (the cap is 3 per hour)"
    for i in $(seq 1 $((3 + n))); do admin_post email-teste "{\"email\":\"$TEST_RECIPIENT\"}"; echo; done | tally
    ;;

  scan-wake-timeout|scan-rescheduled|scan-backlog)
    # Staging points its scanner client at a port nothing listens on, so every wake-up times out (as when
    # the scanner can't boot or download signatures); uploads stay in quarantine and are rescheduled.
    need API_BASE BFF_KEY SLUG; key_header
    n=${COUNT:-3}
    hold=$HOLD; [ "$signal" = scan-backlog ] && hold=$(( HOLD > 1020 ? HOLD : 1020 ))
    d=$(files_dir); read -r token tipo < <(pending_case)
    log "$signal: scanner unreachable for ${hold}s; $n uploads queued (each wake-up times out after the API's budget)"
    override KYBR_SCANNER_PORT 3399
    for i in $(seq 1 "$n"); do upload "$(pending_case | cut -d' ' -f1)" "$tipo" "$d/plain.pdf"; echo; done | tally
    sleep "$hold"
    fly secrets unset KYBR_SCANNER_PORT --app "$FLY_APP" >/dev/null
    log "restored KYBR_SCANNER_PORT; the queued uploads are scanned at the next attempt"
    : "$token"
    ;;

  scan-wakeups)
    # Separate scan sessions: one upload, then wait past the session (and, by default, past the scanner's
    # idle stop) before the next, so each one wakes the scanner. COUNT above the private threshold.
    need API_BASE BFF_KEY SLUG COUNT; key_header
    gap=${GAP:-360}
    d=$(files_dir)
    log "scan-wakeups: $COUNT uploads ${gap}s apart (one scanner wake-up each; takes ~$((COUNT * gap / 60)) min)"
    for i in $(seq 1 "$COUNT"); do
      read -r token tipo < <(pending_case)
      echo "    upload $i: $(upload "$token" "$tipo" "$d/plain.pdf")"
      [ "$i" -lt "$COUNT" ] && sleep "$gap"
    done
    ;;

  scan-failed|scan-stuck)
    echo "$signal: not triggerable on demand (a file refused after 6 attempts takes ~4.5 h; stuck means"
    echo "pending for 24 h). Proven by the rule unit test; record it as 'unit-tested only'."
    ;;

  retention-failed)
    echo "retention-failed: not triggerable on demand (it needs a case past retention whose deletion"
    echo "fails). Proven by the rule unit test; record it as 'unit-tested only'."
    ;;

  *)
    die "unknown signal '$signal' (try: $0 list)"
    ;;
esac
