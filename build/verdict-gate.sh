#!/usr/bin/env bash
# Every PLANNED test must have produced a verdict.
#
# Without this the runner is blind to the one failure mode it most needs to see:
# a container-level throw. A @BeforeAll that throws takes its whole class down
# BEFORE any test exists to blame, so JUnit attributes the loss to no test at
# all — the class's tests were DISCOVERED (so they are in `total`) and land in
# NO bucket. Asking only "did anything report failure?" reads that as failed=0
# and exits 0. It happened here, for two sprints: a committed corpus went
# missing from the branch, the calibration gate's @BeforeAll threw, both its
# tests silently stopped existing, and the suite reported green on every run.
#
# THE UNITS ARE NOT INTERCHANGEABLE, and getting that wrong re-opened the same
# hole this gate closes. `total` is JUnit's tests-FOUND count. `unloadable` is a
# count of CLASSES that failed Class.forName — and SpikeTestMain adds the
# selector inside the try, so an unloadable class never reaches the launcher and
# none of its tests are ever discovered. Its tests are therefore NOT in `total`,
# and adding `unloadable` into this identity over-counts by exactly that many.
# The damage is not a false green (unloadable is separately fatal in the caller)
# but something subtler: N unloadable classes silently absorb N lost verdicts,
# so the condition this gate exists to report goes unreported. Unloadable is
# gated by the caller; it does not belong in the arithmetic.
#
# Usage: verdict-gate.sh <total> <passed> <failed> <aborted> <skipped> [shard-glob]
# Exit:  0 = every planned test produced a verdict; 4 = some produced none.
set -uo pipefail

if [ "$#" -lt 5 ]; then
    echo "usage: $(basename "$0") <total> <passed> <failed> <aborted> <skipped> [shard-glob]" >&2
    exit 2
fi

TOT="$1"; PASS="$2"; FAIL="$3"; ABORT="$4"; SKIP="$5"; LOGS="${6:-}"

for n in "$TOT" "$PASS" "$FAIL" "$ABORT" "$SKIP"; do
    case "$n" in
        ''|*[!0-9]*) echo "verdict gate: non-numeric counter '$n'" >&2; exit 2 ;;
    esac
done

ACCOUNTED=$((PASS + FAIL + ABORT + SKIP))
if [ "$ACCOUNTED" -ne "$TOT" ]; then
    echo "FAILED: $((TOT - ACCOUNTED)) planned test(s) produced NO verdict —" \
         "neither passed, failed, aborted nor skipped."
    echo "  This is almost always a @BeforeAll/@BeforeEach or class-initializer" \
         "throw, or a missing test resource. Search the shard logs for the" \
         "class that reported fewer results than it planned:"
    echo "  If the named class instead ABORTS at the container level — a @BeforeAll" \
         "calling assumeTrue(false) — this gate is reporting a FALSE failure: JUnit" \
         "counts that in containersAborted, which the summary line does not carry, so" \
         "its tests stay in total and reach no bucket. Push the assumption down into" \
         "the @Test methods, where it is counted."
    [ -n "$LOGS" ] && echo "    grep -n 'FAILED:\|Exception\|Error' $LOGS | head"
    exit 4
fi
exit 0
