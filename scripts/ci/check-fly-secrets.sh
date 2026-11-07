#!/usr/bin/env bash
# Pre-deploy gate: every required Fly secret NAME is set on the target app (values are never read).
#   scripts/ci/check-fly-secrets.sh <app> staging|prod
# Needs flyctl authenticated (FLY_API_TOKEN). Exit 1 lists the missing names.
set -euo pipefail

app=${1:?usage: check-fly-secrets.sh <app> staging|prod}
env=${2:?usage: check-fly-secrets.sh <app> staging|prod}

required=(MONGODB_URI KYBR_BFF_KEY_CURRENT KYBR_HASH_SECRET KYBR_IDEMPOTENCY_ENC_KEY KYBR_PII_ENC_KEY KYBR_ADMIN_TOKEN)
case "$env" in
  prod) required+=(KYBR_CNPJA_API_KEY KYBR_CNPJA_MONTHLY_CREDITS KYBR_CNPJA_RATE_PER_MINUTE) ;;
  staging) ;;
  *) echo "::error::environment must be staging or prod"; exit 2 ;;
esac

present=$(flyctl secrets list --app "$app" --json | jq -r '.[] | (.Name // .name)' | sort -u)

missing=()
for name in "${required[@]}"; do
  grep -qx "$name" <<<"$present" || missing+=("$name")
done

if [ ${#missing[@]} -gt 0 ]; then
  echo "::error title=Missing Fly secrets on $app::${missing[*]}"
  exit 1
fi
echo "All ${#required[@]} required Fly secrets are set on $app."
