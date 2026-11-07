#!/usr/bin/env bash
# shellcheck disable=SC2016  # literal ${VAR} lists for envsubst are intended
# Validates the alerting app's config without any secret: renders the templates with the TEST values
# in deploy/alerting/tests/test-thresholds.vars (not the real thresholds), then
#   promtool check rules + promtool test rules (unit tests: every alert fires on synthetic series)
#   amtool check-config (Alertmanager routing)
# Needs promtool, amtool and envsubst on PATH.
set -euo pipefail

cd "$(dirname "$0")/../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

set -a
# shellcheck disable=SC1091
. deploy/alerting/tests/test-thresholds.vars
set +a

rule_vars='${APP_MATCH} ${T_LOOKUP_PER_SLUG_15M} ${T_VERIFY_PER_SLUG_15M} ${T_CACHE_READS_PER_SLUG_24H} ${T_CONTACT_LIMITED_1H} ${T_429_PER_POLICY_5M} ${T_IP_UNKNOWN_PER_POLICY_15M} ${T_BFF_REJECTED_5M} ${T_PROVIDER_ERRORS_10M} ${T_5XX_RATIO_5M} ${T_5XX_MIN_COUNT_5M}'
am_vars='${SMTP_SMARTHOST} ${SMTP_FROM} ${SMTP_USERNAME} ${ALERT_EMAIL_TO}'

# The entrypoint renders exactly these variables; keep the two lists identical.
grep -qF "RULE_VARS='$rule_vars'" deploy/alerting/entrypoint.sh || { echo "::error::rule variable list differs from deploy/alerting/entrypoint.sh"; exit 1; }
grep -qF "AM_VARS='$am_vars'" deploy/alerting/entrypoint.sh || { echo "::error::Alertmanager variable list differs from deploy/alerting/entrypoint.sh"; exit 1; }

envsubst "$rule_vars" < deploy/alerting/rules/kybr-api.rules.yml.tmpl > "$out/kybr-api.rules.yml"
if grep -n '\${' "$out/kybr-api.rules.yml"; then echo "::error::unrendered placeholder in the rules"; exit 1; fi

promtool check rules "$out/kybr-api.rules.yml"
cp deploy/alerting/tests/rules.test.yml "$out/"
(cd "$out" && promtool test rules rules.test.yml)

envsubst "$am_vars" < deploy/alerting/alertmanager.yml.tmpl > "$out/alertmanager.yml"
mkdir -p /tmp/alerting
[ -f /tmp/alerting/smtp_password ] || : > /tmp/alerting/smtp_password
[ -f /tmp/alerting/deadman_url ] || printf 'https://example.invalid/ping' > /tmp/alerting/deadman_url
amtool check-config "$out/alertmanager.yml"

# Every alert has a signal label that the synthetic-trigger script knows.
signals=$(grep -oE 'signal: [a-z0-9-]+' "$out/kybr-api.rules.yml" | awk '{print $2}' | sort -u | grep -vx watchdog)
for s in $signals; do
  grep -qE "^  $s\)" scripts/alerts/trigger.sh || { echo "::error::no synthetic trigger for signal '$s' in scripts/alerts/trigger.sh"; exit 1; }
done
echo "Alerting config OK: rules valid, unit tests pass, Alertmanager config valid, every signal has a trigger."
