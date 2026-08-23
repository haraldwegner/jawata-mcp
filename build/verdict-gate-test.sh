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
expect "a container throw is named, with the count" 4 "2 planned test(s) produced NO verdict" \
    1986 1982 0 2 0

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
expect "too few counters are refused" 2 "usage" 1986 1983 0

if [ "$FAILURES" -ne 0 ]; then
    echo "verdict gate self-test: $FAILURES case(s) FAILED — the suite gate is not trustworthy."
    exit 1
fi
[ "$QUIET" -eq 1 ] || echo "verdict gate self-test: all cases pass"
exit 0
