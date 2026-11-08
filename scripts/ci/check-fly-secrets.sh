#!/usr/bin/env bash
# Pre-deploy gate: every required Fly secret NAME is set on the target app (values are never read).
#   scripts/ci/check-fly-secrets.sh <app> staging|staging-remote|prod
# staging-remote: staging pointed at the real scanner org (the documents gate run).
# Needs flyctl authenticated (FLY_API_TOKEN). Exit 1 lists the missing names.
set -euo pipefail

usage="usage: check-fly-secrets.sh <app> staging|staging-remote|prod"
app=${1:?$usage}
env=${2:?$usage}

required=(MONGODB_URI KYBR_BFF_KEY_CURRENT KYBR_HASH_SECRET KYBR_IDEMPOTENCY_ENC_KEY KYBR_PII_ENC_KEY KYBR_ADMIN_TOKEN)
# The scanner link: mutual TLS material and the pinned scanner key (also used by the photo sanitizer).
scan_link=(KYBR_SCANNER_CLIENT_CERT KYBR_SCANNER_CLIENT_KEY KYBR_SCANNER_CA KYBR_SCANNER_SPKI_SHA256)
case "$env" in
  prod)
    required+=(KYBR_CNPJA_API_KEY KYBR_CNPJA_MONTHLY_CREDITS KYBR_CNPJA_RATE_PER_MINUTE)
    required+=("${scan_link[@]}")
    # Document storage through the platform identity (no static keys) and the e-mail provider.
    required+=(KYBR_STORAGE_BUCKET KYBR_STORAGE_KMS_KEY_ID KYBR_STORAGE_QUARANTINE_BUCKET KYBR_STORAGE_QUARANTINE_KMS_KEY_ID)
    required+=(KYBR_AWS_ROLE_ARN AWS_ROLE_ARN KYBR_RESEND_API_KEY)
    ;;
  staging-remote) required+=("${scan_link[@]}" KYBR_SCANNER_MODE KYBR_IMAGE_MODE) ;;
  staging) ;;
  *) echo "::error::environment must be staging, staging-remote or prod"; exit 2 ;;
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
