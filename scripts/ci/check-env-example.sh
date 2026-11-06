#!/usr/bin/env bash
# Env-var parity gate: every ${VAR} referenced in src/main/resources/application*.yml|yaml|properties
# must be named in .env.example (commented or not). Keeps local, CI and the Fly secret list in sync.
set -euo pipefail

shopt -s nullglob
configs=(src/main/resources/application*.yml src/main/resources/application*.yaml src/main/resources/application*.properties)
if [ ${#configs[@]} -eq 0 ]; then
  echo "::warning title=Env parity check skipped::no src/main/resources/application* files yet (they land with the application skeleton)."
  exit 0
fi
[ -f .env.example ] || { echo "::error::.env.example is missing"; exit 1; }

documented=$(grep -Eo '^[#[:space:]]*[A-Z_][A-Z0-9_]*=' .env.example | sed -E 's/^[#[:space:]]*//; s/=$//' | sort -u)
referenced=$(grep -Eoh '\$\{[A-Z_][A-Z0-9_]*' "${configs[@]}" | sed 's/^\${//' | sort -u)

missing=$(comm -23 <(printf '%s\n' "$referenced") <(printf '%s\n' "$documented") | sed '/^$/d' || true)
if [ -n "$missing" ]; then
  echo "::error title=.env.example out of date::variables referenced in application config but not in .env.example: $(echo "$missing" | tr '\n' ' ')"
  exit 1
fi
echo "Env parity check passed ($(printf '%s\n' "$referenced" | sed '/^$/d' | wc -l | tr -d ' ') variables)."
