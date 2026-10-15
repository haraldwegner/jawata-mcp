#!/usr/bin/env bash
# Self-test of build/c16-probe-guard.sh (S16-28): the containment probe refuses a cell whose own log
# sits inside the work tree it measures. Proves the matching in both directions, and proves the TEST
# can fail by running it against a mutant of the guard.
#
# Usage: build/c16-probe-guard-test.sh [--quiet]
# Exit:  0 = all cases held and the mutant was caught · 1 = a case failed or the mutant survived
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1
FAILED=0

say() { [ "$QUIET" = "1" ] || echo "$@"; }
fail() { echo "FAIL: $*"; FAILED=1; }

check_cases() {   # check_cases <label> : runs the three cases against whatever guard is sourced
    local label="$1" r
    log_is_outside_tree "/o/out/cell.rawlog" "/o/out/work-1"; r=$?
    [ "$r" = "0" ] || fail "$label: a log beside the work tree was called inside it"
    log_is_outside_tree "/o/out/work-1/out.txt" "/o/out/work-1"; r=$?
    [ "$r" = "1" ] || fail "$label: a log inside the work tree was called outside it"
    log_is_outside_tree "/o/out/work-1x/out.txt" "/o/out/work-1"; r=$?
    [ "$r" = "0" ] || fail "$label: a sibling sharing a name prefix was called inside the work tree"
}

# 1. The real guard.
# shellcheck source=c16-probe-guard.sh
source "$HERE/c16-probe-guard.sh"
check_cases "guard"
[ "$FAILED" = "0" ] && say "ok    the guard separates inside from outside, including a sibling that shares a prefix"

# 2. A MUTANT of the guard (it always says "outside") must make the same cases fail — otherwise
#    this test would pass over a guard that guards nothing.
MUTANT="$(mktemp)"
trap 'rm -f "$MUTANT"' EXIT
sed 's/"\$2"\/\*) return 1 ;;/"\$2"\/\*) return 0 ;;/' "$HERE/c16-probe-guard.sh" > "$MUTANT"
if cmp -s "$MUTANT" "$HERE/c16-probe-guard.sh"; then
    fail "the mutant did not apply, so this test has not shown it can fail"
else
    BEFORE=$FAILED
    FAILED=0
    ( source "$MUTANT"; check_cases "mutant" ) > /dev/null 2>&1
    # check_cases ran in a subshell, so read its verdict by running the one decisive case here.
    ( source "$MUTANT"; log_is_outside_tree "/o/out/work-1/out.txt" "/o/out/work-1" )
    MUT_RESULT=$?
    FAILED=$BEFORE
    if [ "$MUT_RESULT" = "0" ]; then
        say "ok    the mutant (always 'outside') gets the inside case wrong, so the cases above can fail"
    else
        fail "the mutant still caught a log inside the tree, so this test cannot tell a broken guard from a good one"
    fi
fi

[ "$FAILED" = "0" ] && { say "c16-probe-guard-test: PASS"; exit 0; }
echo "c16-probe-guard-test: FAIL"
exit 1
