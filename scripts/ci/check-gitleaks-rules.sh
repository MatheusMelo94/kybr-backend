#!/usr/bin/env bash
# Proves the project gitleaks rules (.gitleaks.toml) catch the secret formats Kybr actually uses
# (a rule that never fires is worse than none). Fixtures are generated at runtime into a temp dir, never committed, so no
# allowlist is needed.   Usage: scripts/ci/check-gitleaks-rules.sh [path-to-gitleaks]
set -euo pipefail

cd "$(dirname "$0")/../.."
gitleaks=${1:-gitleaks}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

rand_b64url() { head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
rand_from() { head -c 512 /dev/urandom | base64 | tr -dc "$1" | head -c "$2"; } # alphabet length
# PEM-armoured random bytes: the shape of the scanner's TLS variables, never a usable key.
fake_pem() { printf -- '-----BEGIN %s-----\n%s\n-----END %s-----' "$1" "$(head -c 256 /dev/urandom | base64 -w 64)" "$1"; }
# A Tink-shaped JSON keyset with random bytes (not a usable key), base64-encoded as the env var holds it.
fake_keyset_b64() {
  printf '{"primaryKeyId":%d,"key":[{"keyData":{"typeUrl":"type.googleapis.com/google.crypto.tink.AesGcmKey","value":"%s","keyMaterialType":"SYMMETRIC"},"status":"ENABLED","keyId":%d,"outputPrefixType":"TINK"}]}' \
    "$RANDOM$RANDOM" "$(rand_b64url)" "$RANDOM$RANDOM" | base64 | tr -d '\n'
}

mkdir -p "$work/fixtures"
printf 'KYBR_BFF_KEY_CURRENT=%s\n' "$(rand_b64url)" > "$work/fixtures/bff.env"
printf 'KYBR_HASH_SECRET_PREVIOUS: %s\n' "$(rand_b64url)" > "$work/fixtures/hash.yml"
printf 'idempotency_keyset = "%s"\n' "$(fake_keyset_b64)" > "$work/fixtures/keyset.txt"
printf 'KYBR_PII_ENC_KEY=%s\n' "$(fake_keyset_b64)" > "$work/fixtures/pii.env"
printf 'pii_keyset: "%s"\n' "$(fake_keyset_b64)" > "$work/fixtures/pii-keyset.yml"
printf 'KYBR_ADMIN_TOKEN=%s\n' "$(rand_b64url)" > "$work/fixtures/admin.env"
printf 'KYBR_ADMIN_TOKEN_PREVIOUS: %s\n' "$(rand_b64url)" > "$work/fixtures/admin-previous.yml"
printf 'KYBR_CNPJA_API_KEY=%s\n' "$(rand_b64url)" > "$work/fixtures/cnpja.env"
resend_key="re_$(rand_from 'A-Za-z0-9' 8)_$(rand_from 'A-Za-z0-9' 24)"
printf 'the provider key is %s\n' "$resend_key" > "$work/fixtures/resend.txt"
printf 'KYBR_RESEND_API_KEY=%s\n' "$resend_key" > "$work/fixtures/resend.env"
printf 'KYBR_SCANNER_CLIENT_KEY="%s"\n' "$(fake_pem 'PRIVATE KEY')" > "$work/fixtures/scanner-key.env"
printf 'KYBR_SCANNER_CLIENT_CERT: |\n  %s\n' "$(fake_pem CERTIFICATE)" > "$work/fixtures/scanner-cert.yml"
printf 'KYBR_SCANNER_CA=%s\n' "$(fake_pem CERTIFICATE)" > "$work/fixtures/scanner-ca.env"
printf 'SCAN_TLS_KEY="%s"\n' "$(fake_pem 'PRIVATE KEY')" > "$work/fixtures/scan-org-key.env"
printf 'KYBR_AWS_ACCESS_KEY_ID=AKIA%s\n' "$(rand_from 'A-Z2-7' 16)" > "$work/fixtures/aws-key-id.env"
printf 'KYBR_AWS_SECRET_ACCESS_KEY: %s\n' "$(rand_from 'A-Za-z0-9' 40)" > "$work/fixtures/aws-secret.yml"

"$gitleaks" dir "$work/fixtures" --config .gitleaks.toml --no-banner --log-level error \
  --report-format json --report-path "$work/report.json" --exit-code 0

fail=0
expect() { # file rule
  if ! python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if any(f["File"].endswith("/"+sys.argv[2]) and f["RuleID"]==sys.argv[3] for f in d) else 1)' \
      "$work/report.json" "$1" "$2"; then
    echo "::error title=gitleaks rule gap::rule $2 did not flag the synthetic $1 fixture"
    fail=1
  fi
}
expect bff.env kybr-env-secret
expect hash.yml kybr-env-secret
expect keyset.txt tink-keyset-base64
expect pii.env kybr-env-secret
expect pii-keyset.yml tink-keyset-base64
expect admin.env kybr-env-secret
expect admin-previous.yml kybr-env-secret
expect cnpja.env kybr-env-secret
expect resend.txt resend-api-key
expect resend.env kybr-env-secret
expect scanner-key.env kybr-scanner-pem
expect scanner-cert.yml kybr-scanner-pem
expect scanner-ca.env kybr-scanner-pem
expect scan-org-key.env kybr-scanner-pem
expect aws-key-id.env kybr-env-secret
expect aws-secret.yml kybr-env-secret
[ "$fail" -eq 0 ] && echo "gitleaks rule check passed (kybr-env-secret, tink-keyset-base64, resend-api-key, kybr-scanner-pem)."
exit "$fail"
