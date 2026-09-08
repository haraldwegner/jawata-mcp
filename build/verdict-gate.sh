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
# mcp#54 — OPTIONAL SEVENTH, and it does NOT enter the identity below. One aborted
# CONTAINER costs however many TESTS that class declared, so adding it to
# PASS+FAIL+ABORT+SKIP would hold only where every aborted class had exactly one
# test — the unit error this file's own header records for `unloadable`, repeated in
# the other direction. It is read for one purpose: to turn the speculation in the
# message below ("IF the named class instead aborts…") into a statement of fact.
CABORT="${7:-0}"

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
    [ -n "$LOGS" ] && echo "    grep -n 'FAILED:\|Exception\|Error' $LOGS | head"
    if [ "$CABORT" -gt 0 ]; then
        # mcp#54: the summary line carries containersAborted now, so this is no longer a
        # guess the reader has to check. It is still a REPORT rather than a pass — a
        # class whose tests never ran is a loss of coverage — but it names the cause, and
        # the abort budget is what decides whether that particular skip was agreed.
        echo "  CAUSE NAMED: $CABORT container(s) ABORTED — a @BeforeAll calling" \
             "assumeTrue(false). Their tests were discovered, so they are in total, and" \
             "they reach no bucket. This gate is NOT reporting a phantom: the coverage is" \
             "genuinely gone. Either push the assumption down into the @Test methods,"\
             "where it is counted, or budget the skip in build/expected-aborts.<os>."
    else
        echo "  No container abort was reported, so this is not the @BeforeAll" \
             "assumeTrue(false) case — look for a throw."
    fi
    exit 4
fi
exit 0
