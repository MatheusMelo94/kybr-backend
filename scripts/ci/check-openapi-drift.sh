#!/usr/bin/env bash
# OpenAPI drift gate. Run after `./mvnw verify`.
# 1. The drift integration test (*OpenApiDrift*) must have run and not been skipped.
# 2. The build must not have rewritten the committed docs/openapi.yaml.
# Until a spec is committed the gate only warns.
set -euo pipefail

spec=docs/openapi.yaml

if [ ! -f "$spec" ]; then
  echo "::warning title=OpenAPI drift check skipped::$spec is not committed yet."
  exit 0
fi

shopt -s nullglob
reports=(target/failsafe-reports/TEST-*OpenApiDrift*.xml target/surefire-reports/TEST-*OpenApiDrift*.xml)
if [ ${#reports[@]} -eq 0 ]; then
  echo "::error title=OpenAPI drift test missing::$spec is committed but no *OpenApiDrift* test report was produced by ./mvnw verify."
  exit 1
fi

for r in "${reports[@]}"; do
  if grep -Eq '<testsuite[^>]* skipped="[1-9]' "$r"; then
    echo "::error title=OpenAPI drift test skipped::$r reports skipped tests; the drift gate must run."
    exit 1
  fi
  if grep -Eq '<testsuite[^>]* (failures|errors)="[1-9]' "$r"; then
    echo "::error title=OpenAPI drift::$r reports failures. Regenerate deliberately with ./mvnw verify -Dopenapi.write=true and commit."
    exit 1
  fi
done

if ! git diff --quiet -- "$spec"; then
  echo "::error title=OpenAPI spec modified by the build::The build rewrote $spec. Regenerate locally and commit it deliberately."
  git --no-pager diff --stat -- "$spec"
  exit 1
fi

echo "OpenAPI drift check passed (${reports[*]})."
