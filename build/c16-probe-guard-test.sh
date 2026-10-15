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

# 3. THE OFF-TREE LISTING (S16-33): a file that appears in the NAMED directory during a cell is
#    reported, a run that wrote nothing there reports nothing, and a directory that does not exist
#    is an empty listing and not an error. The probe was blind to exactly this: claude's plan file.
DIR="$(mktemp -d)"
trap 'rm -f "$MUTANT"; rm -rf "$DIR"' EXIT
PLANS="$DIR/plans"
mkdir -p "$PLANS"
echo old > "$PLANS/earlier.md"

check_listing() {   # check_listing <label> : drives the listing functions that are currently sourced
    local label="$1"
    listing_of "$PLANS" > "$DIR/before"
    new_in_listing "$DIR/before" "$DIR/before" > "$DIR/none"
    [ ! -s "$DIR/none" ] || fail "$label: an unchanged directory reported a new file"
    echo new > "$PLANS/deposited.md"
    listing_of "$PLANS" > "$DIR/after"
    new_in_listing "$DIR/before" "$DIR/after" > "$DIR/found"
    grep -q "deposited.md" "$DIR/found" || fail "$label: a file written during the cell was not reported"
    grep -q "earlier.md" "$DIR/found" && fail "$label: a file that was already there was reported as new"
    rm -f "$PLANS/deposited.md"
    [ -z "$(listing_of "$DIR/does-not-exist")" ] || fail "$label: a missing directory was not an empty listing"
}

BEFORE=$FAILED
check_listing "listing"
[ "$FAILED" = "$BEFORE" ] && say "ok    a file deposited in the named directory is reported, and one that was already there is not"

#    The mutant swaps comm -13 (only in <after>) for comm -12 (in BOTH), which reports nothing new.
LMUTANT="$(mktemp)"
sed 's/comm -13 /comm -12 /' "$HERE/c16-probe-guard.sh" > "$LMUTANT"
if cmp -s "$LMUTANT" "$HERE/c16-probe-guard.sh"; then
    fail "the listing mutant did not apply, so this test has not shown it can fail"
else
    BEFORE=$FAILED
    ( source "$LMUTANT"; check_listing "mutant" ) > "$DIR/mutant-out" 2>&1
    if grep -q "FAIL: mutant: a file written during the cell was not reported" "$DIR/mutant-out"; then
        say "ok    the mutant (reports nothing new) misses the deposited file, so the listing cases can fail"
    else
        fail "the listing mutant was not caught, so this test cannot tell a broken listing from a good one"
    fi
    FAILED=$BEFORE
fi
rm -f "$LMUTANT"

[ "$FAILED" = "0" ] && { say "c16-probe-guard-test: PASS"; exit 0; }
echo "c16-probe-guard-test: FAIL"
exit 1
