#!/usr/bin/env bash
# shellcheck disable=SC2016  # literal ${VAR} lists for envsubst are intended
# Renders the documents-bucket policy templates for ONE environment into a private directory outside
# the repository (account ids and ARNs stay out of the public repo). Every variable is required.
#
#   AWS_ACCOUNT_ID=... FLY_ORG=... FLY_APP=kybr-api BUCKET=... AUDIT_BUCKET=... TRAIL_NAME=... \
#   KMS_KEY_ARN=arn:aws:kms:sa-east-1:...:key/... ROLE_ARN=arn:aws:iam::...:role/... \
#   ADMIN_ARN=arn:aws:iam::...:user/... QUARANTINE_BUCKET=... QUARANTINE_KMS_KEY_ARN=... \
#   deploy/aws/render-documents.sh <out-dir>
#
# KMS_KEY_ARN must be the full key ARN: it is also the value of KYBR_STORAGE_KMS_KEY_ID, because the
# bucket policy compares it with the key id the API sends on every put.
set -euo pipefail

out=${1:?usage: render-documents.sh <out-dir outside the repository>}
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
mkdir -p "$out"
case "$(cd "$out" && pwd)/" in "$repo"/*) echo "refusing: $out is inside the repository" >&2; exit 1 ;; esac

vars=(AWS_ACCOUNT_ID FLY_ORG FLY_APP BUCKET AUDIT_BUCKET TRAIL_NAME KMS_KEY_ARN ROLE_ARN ADMIN_ARN QUARANTINE_BUCKET QUARANTINE_KMS_KEY_ARN)
for v in "${vars[@]}"; do [ -n "${!v:-}" ] || { echo "set $v" >&2; exit 1; }; done
case "$FLY_APP" in kybr-api|kybr-api-staging) ;; *) echo "FLY_APP must be kybr-api or kybr-api-staging" >&2; exit 1 ;; esac
[[ "$KMS_KEY_ARN" == arn:aws:kms:sa-east-1:* ]] || { echo "KMS_KEY_ARN must be a full key ARN in sa-east-1" >&2; exit 1; }
[[ "$QUARANTINE_KMS_KEY_ARN" == arn:aws:kms:sa-east-1:* ]] || { echo "QUARANTINE_KMS_KEY_ARN must be a full key ARN in sa-east-1" >&2; exit 1; }
[ "$QUARANTINE_KMS_KEY_ARN" != "$KMS_KEY_ARN" ] || { echo "the quarantine needs its own KMS key" >&2; exit 1; }
[ "$QUARANTINE_BUCKET" != "$BUCKET" ] || { echo "the quarantine needs its own bucket" >&2; exit 1; }

list=$(printf '${%s} ' "${vars[@]}")
for t in "$here"/documents/*.tmpl; do
  f="$out/$(basename "$t" .tmpl)"
  envsubst "$list" < "$t" > "$f"
  python3 -m json.tool "$f" > /dev/null
  if grep -q '\${' "$f"; then echo "unrendered placeholder in $f" >&2; exit 1; fi
done
cp "$here"/documents/*.json "$out/"
echo "Rendered $(find "$out" -maxdepth 1 -type f | wc -l | tr -d ' ') documents for $FLY_APP into $out"
