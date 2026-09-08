#!/usr/bin/env bash
# The runner's CONTAINER-LEVEL reporting, proved against classes that really produce it.
#
# mcp#54 taught the runner to print a container ABORT under the `~~ ABORTED` marker and to
# carry containersAborted; mcp#51 taught it to carry containersFailed and to FAIL on it.
# Both shipped with the same hole, and both recorded it rather than implying coverage: NO
# CLASS IN THE SUITE PRODUCES EITHER OUTCOME, so the two markers, the two counters, and the
# verdict gate's cause-naming were exercised by nothing at all. This is that demonstration.
#
# It cannot be an ordinary test class, and that is the point rather than a workaround: the
# fixes make a container abort or failure FAIL the run, so a fixture that deliberately
# breaks its container cannot live inside the suite it breaks. org.jawata.mcp
# .ContainerMarkerProbes is therefore named so run-suite's `grep 'Test\.class$'` discovery
# skips it, and is reachable only by an explicit class list — this one.
#
# ONE invocation, three classes, deliberately: it is the only way to reach the case both
# counters exist for AT ONCE — containersAborted=1 and containersFailed=1 on one summary
# line, with the identity also short. That is the shape mcp#51's independent (rather than
# chained) cause-naming was written for, and nothing had ever produced it.
#
# Usage: container-marker-gate.sh [--quiet]
# Exit:  0 = the runner reports container outcomes correctly; 1 = it does not; 2 = cannot run.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/build/dist/target/dist"
QUIET=0; [ "${1:-}" = "--quiet" ] && QUIET=1
FAILURES=0
say() { [ "$QUIET" -eq 1 ] || echo "$@"; }

[ -f "$DIST/jawata.jar" ] || { echo "container-marker gate: no dist at $DIST/jawata.jar"; exit 2; }

OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
cat > "$OUT/classlist.txt" <<'EOF'
org.jawata.mcp.ContainerMarkerProbes$Aborting
org.jawata.mcp.ContainerMarkerProbes$Failing
org.jawata.mcp.ContainerMarkerProbes$Healthy
EOF

JVM_OPTS="${JVM_OPTS:--XX:ActiveProcessorCount=2 -Xmx1g}"
java $JVM_OPTS -Djawata.test.classlist="$OUT/classlist.txt" \
     -jar "$DIST/jawata.jar" -runTests > "$OUT/run.log" 2>&1
RUNNER_EXIT=$?

LINE=$(grep 'SPIKE-TESTS' "$OUT/run.log" | tail -1)
if [ -z "$LINE" ]; then
    echo "container-marker gate: the runner produced no summary line. Log tail:"
    tail -20 "$OUT/run.log"
    exit 2
fi
say "container-marker gate:"
say "  $LINE"

check() {  # check <name> <actual> <wanted>
    if [ "$2" = "$3" ]; then say "  ok   $1 = $3"
    else echo "  FAIL $1: got '$2', wanted '$3'"; FAILURES=$((FAILURES + 1)); fi
}
field() { sed "s/.*$1=\([0-9]*\).*/\1/" <<< "$LINE"; }

# The three classes contribute 2 tests each. The ABORTING class's two are DISCOVERED, so
# they are in total, and they reach no bucket — which is exactly why the identity breaks.
check "total"             "$(field total)"             "6"
check "succeeded"         "$(field succeeded)"         "4"
check "failed"            "$(field failed)"            "0"
check "aborted"           "$(field aborted)"           "0"
check "unloadable"        "$(field unloadable)"        "0"
check "containersAborted" "$(field containersAborted)" "1"
check "containersFailed"  "$(field containersFailed)"  "1"

# The MARKERS, which are what a human greps a shard log for. mcp#54's whole second half was
# that a container abort printed under `^^` where the abort budget greps `~~ ABORTED`.
grep -q '~~ ABORTED' "$OUT/run.log" \
    && say "  ok   the abort marker is printed" \
    || { echo "  FAIL no '~~ ABORTED' marker for the aborting container"; FAILURES=$((FAILURES + 1)); }
grep -q '\^\^ FAILED' "$OUT/run.log" \
    && say "  ok   the failure marker is printed" \
    || { echo "  FAIL no '^^ FAILED' marker for the failing container"; FAILURES=$((FAILURES + 1)); }

# mcp#51: a failed container must reach the EXIT CODE, or a targeted run reports success.
if [ "$RUNNER_EXIT" -ne 0 ]; then say "  ok   the runner exits non-zero ($RUNNER_EXIT)"
else echo "  FAIL the runner exited 0 with a failed container"; FAILURES=$((FAILURES + 1)); fi

# And the verdict gate, driven with THIS RUN's real numbers rather than invented ones, must
# name BOTH causes. A chained if/elif would report only the first.
GATE=$("$ROOT/build/verdict-gate.sh" "$(field total)" "$(field succeeded)" "$(field failed)" \
        "$(field aborted)" "$(field skipped)" "" "$(field containersAborted)" \
        "$(field containersFailed)" 2>&1)
[[ "$GATE" == *"container(s) ABORTED"* ]] \
    && say "  ok   the verdict gate names the abort" \
    || { echo "  FAIL the verdict gate did not name the abort:"; echo "$GATE"; FAILURES=$((FAILURES + 1)); }
[[ "$GATE" == *"container(s) FAILED"* ]] \
    && say "  ok   the verdict gate names the failure too" \
    || { echo "  FAIL the verdict gate did not name the failure:"; echo "$GATE"; FAILURES=$((FAILURES + 1)); }

if [ "$FAILURES" -ne 0 ]; then
    echo "container-marker gate: $FAILURES check(s) FAILED — the runner's container reporting is not trustworthy."
    exit 1
fi
say "container-marker gate: the runner reports container aborts and failures correctly"
exit 0
