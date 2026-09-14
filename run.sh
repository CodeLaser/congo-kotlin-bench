#!/usr/bin/env bash
# Full benchmark: every (parser, thread count) in its own JVM, repeated FORKS times, interleaved so that
# background noise and thermal drift spread over all parsers. Results are JSON lines in results.jsonl.
set -euo pipefail
cd "$(dirname "$0")"

# The JDK: $JAVA, else $JAVA_HOME/bin/java, else (macOS) the JDK 21 that java_home finds.
if [ -z "${JAVA:-}" ]; then
  if [ -n "${JAVA_HOME:-}" ]; then
    JAVA=$JAVA_HOME/bin/java
  elif [ -x /usr/libexec/java_home ] && home=$(/usr/libexec/java_home -v 21 2>/dev/null); then
    JAVA=$home/bin/java
  else
    echo "set JAVA or JAVA_HOME to a JDK 21" >&2; exit 1
  fi
fi
version=$("$JAVA" -version 2>&1 | head -1)
case $version in
  *'"21'*) ;;
  *) echo "warning: the published results are on JDK 21, this is: $version" >&2 ;;
esac
LIST=${LIST:-corpus-accepted.txt}
FORKS=${FORKS:-3}
THREADS=${THREADS:-"1 16 24"}
PARSERS=${PARSERS:-"congo ast lighttree psi"}
OUT=${OUT:-results.jsonl}
JVM_OPTS=(-Xms8g -Xmx8g -XX:+AlwaysPreTouch -cp 'build/install/bench/lib/*')

# Refuse to start while something else is using CPU (the first results file was measured next to a busy IntelliJ).
busy=$(ps -Ao pcpu=,pid=,comm= | awk -v self=$$ '$1 >= 50 && $2 != self')
if [ -n "$busy" ] && [ "${BUSY_OK:-}" != 1 ]; then
  printf 'other processes are using CPU (%%CPU PID COMMAND), close them or set BUSY_OK=1:\n%s\n' "$busy" >&2
  exit 1
fi

{
  echo "# $(date -u +%FT%TZ)  $version  $(sysctl -n machdep.cpu.brand_string)  load $(sysctl -n vm.loadavg)"
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
