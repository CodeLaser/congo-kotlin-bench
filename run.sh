#!/usr/bin/env bash
# Full benchmark: every (parser, thread count) in its own JVM, repeated FORKS times, interleaved so that
# background noise and thermal drift spread over all parsers. Results are JSON lines in results.jsonl.
set -euo pipefail
cd "$(dirname "$0")"

JAVA=${JAVA:-/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home/bin/java}
LIST=${LIST:-corpus-accepted.txt}
FORKS=${FORKS:-3}
THREADS=${THREADS:-"1 16 24"}
PARSERS=${PARSERS:-"congo ast lighttree psi"}
OUT=${OUT:-results.jsonl}
JVM_OPTS=(-Xms8g -Xmx8g -XX:+AlwaysPreTouch -cp 'build/install/bench/lib/*')

{
  echo "# $(date -u +%FT%TZ)  $($JAVA -version 2>&1 | head -1)  $(sysctl -n machdep.cpu.brand_string)"
} >> "$OUT"

for fork in $(seq 1 "$FORKS"); do
  for t in $THREADS; do
    if [ "$t" = 1 ]; then warm=5; iters=10; else warm=10; iters=20; fi
    for p in $PARSERS; do
      line=$("$JAVA" "${JVM_OPTS[@]}" bench.ParserBench --mode bench --parser "$p" --threads "$t" \
               --warmup "$warm" --iters "$iters" --list "$LIST" 2>/dev/null | tail -1)
      echo "${line%\}},\"fork\":$fork}" | tee -a "$OUT"
    done
  done
done

for p in $PARSERS; do
  "$JAVA" "${JVM_OPTS[@]}" bench.ParserBench --mode footprint --parser "$p" --list "$LIST" 2>/dev/null \
    | tail -1 | tee -a "$OUT"
done
