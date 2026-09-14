#!/usr/bin/env bash
# Writes corpus-all.txt: every .kt file under corpus/ (no .kts, nothing under a build/ directory), with identical
# files deduplicated (the first path in sorted order is kept). Paths are relative to the repository root.
# check mode then filters this list down to corpus-accepted.txt.
set -euo pipefail
cd "$(dirname "$0")/.."

find corpus -name '*.kt' -type f -not -path '*/build/*' -not -path '*/.git/*' \
  | grep -v '^corpus/congo-grammars/' | cat - <(find corpus/congo-grammars/kotlin/testfiles -name '*.kt' -type f) \
  | LC_ALL=C sort \
  | while IFS= read -r f; do printf '%s %s\n' "$(shasum "$f" | cut -d' ' -f1)" "$f"; done \
  | awk '!seen[$1]++ { sub(/^[0-9a-f]+ /, ""); print }' \
  > corpus-all.txt
echo "corpus-all.txt: $(wc -l < corpus-all.txt | tr -d ' ') files"
