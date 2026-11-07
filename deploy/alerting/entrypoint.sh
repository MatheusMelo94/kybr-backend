#!/bin/sh
# shellcheck disable=SC2016  # literal ${VAR} lists for envsubst are intended
# Renders the rule and Alertmanager templates from the environment (Fly secrets), validates them,
# then runs Alertmanager and vmalert. If either process exits, the container exits and Fly restarts it.
set -eu

die() { echo "alerting: $*" >&2; exit 1; }

RULE_VARS='${APP_MATCH} ${T_LOOKUP_PER_SLUG_15M} ${T_VERIFY_PER_SLUG_15M} ${T_CACHE_READS_PER_SLUG_24H} ${T_CONTACT_LIMITED_1H} ${T_429_PER_POLICY_5M} ${T_IP_UNKNOWN_PER_POLICY_15M} ${T_BFF_REJECTED_5M} ${T_PROVIDER_ERRORS_10M} ${T_5XX_RATIO_5M} ${T_5XX_MIN_COUNT_5M}'
AM_VARS='${SMTP_SMARTHOST} ${SMTP_FROM} ${SMTP_USERNAME} ${ALERT_EMAIL_TO}'

for v in $RULE_VARS $AM_VARS; do
  name=$(echo "$v" | tr -d '${}')
  eval "val=\${$name:-}"
  [ -n "$val" ] || die "required setting $name is not set"
done
: "${FLY_ORG:?FLY_ORG is required}"
: "${FLY_PROM_TOKEN:?FLY_PROM_TOKEN is required (fly tokens create readonly <org>)}"
: "${SMTP_PASSWORD:?SMTP_PASSWORD is required}"

umask 077
mkdir -p /tmp/alerting/rules /tmp/alerting/am-data
printf '%s' "$SMTP_PASSWORD" > /tmp/alerting/smtp_password
unset SMTP_PASSWORD

for f in /etc/alerting/rules/*.yml.tmpl; do
  # shellcheck disable=SC2094  # reads the template, writes a different file
  envsubst "$RULE_VARS" < "$f" > "/tmp/alerting/rules/$(basename "$f" .tmpl)"
done

envsubst "$AM_VARS" < /etc/alerting/alertmanager.yml.tmpl > /tmp/alerting/alertmanager.yml
# Required: both listeners are localhost-only, so Fly has no HTTP check on this app. The Watchdog alert
# pinging an external dead-man check every 5 min is what reveals a dead or stuck alerter.
: "${DEADMAN_WEBHOOK_URL:?DEADMAN_WEBHOOK_URL is required (the only external liveness signal)}"
printf '%s' "$DEADMAN_WEBHOOK_URL" > /tmp/alerting/deadman_url
unset DEADMAN_WEBHOOK_URL

# Validate before starting anything (fails the deploy's health check instead of running blind).
/vmalert-prod -dryRun -rule='/tmp/alerting/rules/*.yml' >/dev/null || die "rule validation failed"

alertmanager --config.file=/tmp/alerting/alertmanager.yml --storage.path=/tmp/alerting/am-data \
  --web.listen-address=127.0.0.1:9093 --cluster.listen-address= &
am_pid=$!

# The Fly token carries commas; vmalert splits multiple headers on '^^' only. Passed via env, never
# argv; vmalert registers datasource.headers as a secret flag, shown as "secret" on /flags and in its
# startup flag log. The listener is localhost-only: nothing in the Fly org can reach the UI or /flags.
export datasource_headers="Authorization:${FLY_PROM_TOKEN}"
unset FLY_PROM_TOKEN
/vmalert-prod -envflag.enable \
  -rule='/tmp/alerting/rules/*.yml' \
  -datasource.url="https://api.fly.io/prometheus/${FLY_ORG}/" \
  -datasource.queryStep=1m \
  -notifier.url=http://127.0.0.1:9093 \
  -evaluationInterval=1m \
  -httpListenAddr=127.0.0.1:8880 &
vm_pid=$!

# Exit as soon as either process dies.
while kill -0 "$am_pid" 2>/dev/null && kill -0 "$vm_pid" 2>/dev/null; do sleep 5; done
die "a process exited (alertmanager alive: $(kill -0 "$am_pid" 2>/dev/null && echo yes || echo no))"
