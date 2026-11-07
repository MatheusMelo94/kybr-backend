#!/usr/bin/env bash
# Public-text gate: the repository is public, so source code, comments, test names and the API
# contract must not reference private working documents (plans, reviews, decision records) or
# their internal ids. Scans every tracked file (every file when the root isn't a git work tree),
# except the private docs/ folder (only docs/openapi.yaml is public), .gitignore (which names that
# folder), and this script and its allowlist (which spell out the patterns). Exceptions live in
# scripts/ci/public-text-allowlist.txt, one per line, each with a comment saying why.
# Usage: scripts/ci/check-public-text.sh [repo-root]
set -euo pipefail

root=${1:-"$(dirname "$0")/../.."}
cd "$root"

# Perl-compatible patterns, one per kind of private reference.
patterns=(
  '(?<![/\w.-])docs/(?!openapi\.yaml\b)'   # a private document path (a docs/ route segment is fine)
  '§'                                       # section references into private documents
  '\b(?:SEC|NIT)-\d+\b'                     # review finding ids
  '\bCR-P?\d+\b'                            # review finding ids
  '\bF\d{2}\b'                              # threat-model finding ids
  '\bG\d{1,2}\b'                            # test-plan gap ids
  '\bB\d-\d{2}\b'                           # security finding ids
  '\bB-\d\b'                                # test-plan blocker ids
  '\bQ-\d{1,2}\b'                           # open-question ids
  '\b(?:ADR|DDR|PDR)-\d{4}\b'               # decision record ids
  '\bT\d{2}\b'                              # task ids
  '(?<![\w./-])(?!(?:UTF|SHA|ISO)-)[A-Z]{2,4}-\d{2}(?![\w.-])'  # test-case ids (not UTF-16, SHA-256)
  '(?i)handoff'                             # the private brief
)

self=scripts/ci/check-public-text.sh
allowlist=scripts/ci/public-text-allowlist.txt
if git rev-parse --is-inside-work-tree >/dev/null 2>&1 && [ "$(git rev-parse --show-toplevel)" = "$(pwd -P)" ]; then
  list_files() { git ls-files -z; }
else
  list_files() { find . \( -name .git -o -name target \) -prune -o -type f -print0 | sed -z 's|^\./||'; }
fi
files=()
while IFS= read -r -d '' file; do
  case "$file" in
    docs/openapi.yaml) ;;
    docs/*|.gitignore|"$self"|"$allowlist") continue ;;
  esac
  [ -f "$file" ] && files+=("$file")
done < <(list_files)
[ ${#files[@]} -eq 0 ] && { echo "Public-text check passed (nothing to scan)."; exit 0; }

combined=$(IFS='|'; echo "${patterns[*]}")
hits=$(printf '%s\0' "${files[@]}" | xargs -0 grep -nIHP "(?:$combined)" || true)

allowlist=scripts/ci/public-text-allowlist.txt
if [ -n "$hits" ] && [ -f "$allowlist" ]; then
  # Entry format: <path>|<exact text that may appear on that path's offending line>
  while IFS='|' read -r path text; do
    [ -z "$path" ] || [ "${path:0:1}" = "#" ] && continue
    hits=$(printf '%s\n' "$hits" | awk -v p="$path:" -v t="$text" \
      '!(index($0, p) == 1 && index(substr($0, length(p) + 1), t) > 0)')
  done < "$allowlist"
fi

hits=$(printf '%s\n' "$hits" | sed '/^$/d')
if [ -n "$hits" ]; then
  echo "::error title=Private reference in public text::rewrite these lines in plain words (or allowlist with a reason in $allowlist)"
  printf '%s\n' "$hits"
  exit 1
fi
echo "Public-text check passed (${#files[@]} files)."
