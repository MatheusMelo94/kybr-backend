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
[ "$fail" -eq 0 ] && echo "gitleaks rule check passed (kybr-env-secret, tink-keyset-base64)."
exit "$fail"
