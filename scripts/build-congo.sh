#!/usr/bin/env bash
# Builds CongoCC's Kotlin parser from congo-grammars at a pinned revision and packages it as libs/congo-kotlin.jar.
#
# On macOS the build must run on a case-sensitive volume: the generated org/parsers/kotlin/ast/ directory holds both
# Annotation.java and ANNOTATION.java (and Operator/OPERATOR), which overwrite each other on APFS's default
# case-insensitive filesystem and javac then fails. A jar keeps both entries, so it can be used from anywhere.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
SHA=f1443fee4b58b1ff49721b27a43e9f1f2253acf6

mkdir -p .congo libs
WORK=$ROOT/.congo/work
if [ "$(uname)" = Darwin ]; then
  IMAGE=$ROOT/.congo/congocs.sparseimage
  [ -f "$IMAGE" ] || hdiutil create -quiet -size 500m -fs "Case-sensitive APFS" -volname congocs -type SPARSE \
    "${IMAGE%.sparseimage}"
  mkdir -p "$WORK"
  hdiutil attach -quiet "$IMAGE" -mountpoint "$WORK" -nobrowse
  trap 'hdiutil detach -quiet "$WORK"' EXIT
fi

rm -rf "$WORK/congo-grammars"
git clone -q https://github.com/congo-cc/congo-grammars.git "$WORK/congo-grammars"
git -C "$WORK/congo-grammars" checkout -q "$SHA"
git -C "$WORK/congo-grammars" submodule -q update --init   # bin/congocc.jar, pinned by the grammar repo

cd "$WORK/congo-grammars/kotlin"
ant -q test          # generates the parser, compiles it, and parses congo-grammars' own kotlin/testfiles
jar cf "$ROOT/libs/congo-kotlin.jar" -C . org
echo "libs/congo-kotlin.jar: $(unzip -l "$ROOT/libs/congo-kotlin.jar" | grep -c '\.class$') classes"
