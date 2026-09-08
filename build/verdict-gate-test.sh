#!/usr/bin/env bash
# Self-test for build/verdict-gate.sh. Drives the REAL gate script — no copy of
# its arithmetic lives here, or this would test the copy and not the gate.
#
# It exists because the gate's unit error (adding a CLASS count into a TEST-count
# identity) was invisible to every run: live suites report unloadable=0, so the
# wrong term was always zero and the arithmetic looked right for two rounds.
# The cases below are the ones a real run almost never produces.
#
# Usage: verdict-gate-test.sh [--quiet]
set -uo pipefail
GATE="$(cd "$(dirname "$0")" && pwd)/verdict-gate.sh"
QUIET=0; [ "${1:-}" = "--quiet" ] && QUIET=1
FAILURES=0

# expect <name> <wanted-exit> <wanted-substring-or--> <args...>
expect() {
    local name="$1" want="$2" needle="$3"; shift 3
    local out rc
    out="$("$GATE" "$@" 2>&1)"; rc=$?
    if [ "$rc" -ne "$want" ]; then
        echo "  FAIL $name: exit $rc, wanted $want"; echo "    output: $out"
        FAILURES=$((FAILURES + 1)); return
    fi
    if [ "$needle" != "-" ] && [[ "$out" != *"$needle"* ]]; then
        echo "  FAIL $name: output does not contain '$needle'"; echo "    output: $out"
        FAILURES=$((FAILURES + 1)); return
    fi
    [ "$QUIET" -eq 1 ] || echo "  ok   $name"
}

[ "$QUIET" -eq 1 ] || echo "verdict gate self-test:"

# A healthy run: every found test reached a bucket.
expect "a healthy run passes" 0 - 1986 1983 0 3 0

# The condition the gate exists for: a @BeforeAll throw takes two tests down.
# They were DISCOVERED (so they are in total) and reach no bucket.
#
# RENAMED at mcp#51, because the old name claimed more than the case checks. It was
# "a container throw is named, with the count" — and it passes FIVE arguments, so no
# container counter reaches the gate and nothing about a throw can be named. What it
# actually pins is the LOSS COUNT, which is the half that was true.
expect "the count of lost verdicts is reported" 4 "2 planned test(s) produced NO verdict" \
    1986 1982 0 2 0
# ...and with no cause counters, the gate must say it cannot name one rather than
# guessing. This is the other half of the case above, and it had none.
expect "with no cause counters the gate declines to name one" 4 "look further" \
    1986 1982 0 2 0

# mcp#51 — A CLASS-LEVEL THROW, NOW COUNTED. Same identity shortfall as above; the
# eighth argument is what turns "look further" into a diagnosis.
expect "a container failure is named, with the count" 4 "2 container(s) FAILED" \
    1986 1982 0 2 0 "" 0 2
# The two causes are DIFFERENT diagnoses and must not shadow each other: an abort is
# budgetable, a throw is not. A chain would have reported only the first.
expect "both causes are named when both fired (abort)" 4 "1 container(s) ABORTED" \
    1986 1982 0 2 0 "" 1 1
expect "both causes are named when both fired (failure)" 4 "1 container(s) FAILED" \
    1986 1982 0 2 0 "" 1 1
# A BALANCED run is the case mcp#51 is really about: an @AfterAll throws AFTER every
# test in its class has reported, so this identity holds and this gate is silent — by
# design. run-suite.sh checks containersFailed separately, and this case pins that the
# gate does NOT start failing balanced runs.
expect "a container failure does not break a balanced run's identity" 0 - \
    1986 1983 0 3 0 "" 0 2

# THE UNIT ERROR, case 1. An unloadable class is not in `total` at all — its
# tests are never discovered. Adding it here produced a NEGATIVE loss count and
# a spurious exit 4 on a run whose verdicts were all present.
expect "an unloadable class alone is not a lost verdict" 0 - 1984 1984 0 0 0

# THE UNIT ERROR, case 2 — the one that matters. Two unloadable classes and two
# genuinely lost verdicts cancelled exactly, so the gate fell silent about the
# very condition it was written to report.
expect "lost verdicts are reported even when classes also failed to load" 4 \
    "2 planned test(s) produced NO verdict" 1986 1984 0 0 0

# Aborted and skipped are verdicts: a run full of them is accounted for.
expect "aborted and skipped count as verdicts" 0 - 100 90 0 7 3

# A garbled summary line must not be read as a healthy run.
expect "a non-numeric counter is refused, not defaulted" 2 "non-numeric" 1986 "" 0 0 0
# TOTAL specifically: it is the left-hand side of the identity, so a mutation that drops
# it from the guard loop makes the gate return 0 on a garbled summary — a false green,
# and the only mutation of the gate the other cases here do not catch.
expect "a non-numeric TOTAL is refused too" 2 "non-numeric" "abc" 1983 0 3 0
expect "too few counters are refused" 2 "usage" 1986 1983 0

if [ "$FAILURES" -ne 0 ]; then
    echo "verdict gate self-test: $FAILURES case(s) FAILED — the suite gate is not trustworthy."
    exit 1
fi
[ "$QUIET" -eq 1 ] || echo "verdict gate self-test: all cases pass"
exit 0
