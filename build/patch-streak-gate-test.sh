#!/usr/bin/env bash
#
# S9a.2c — the patch-streak gate, replayed against REAL history in both repos.
#
# A gate that cannot go red has not been tested, and a gate that goes red on
# everything is not a gate. Every case below is a real tag with a real date, and
# each names WHY it must land where it does.
#
# THE CASE THAT DECIDES THE DESIGN is studio v3.17.1. The first specification
# keyed the streak on the previous tag sharing a major.minor, and that version
# went GREEN here — v3.17.0 (an unrelated feature) landed mid-run and reset the
# lineage, so the FOURTH autocontinue fix looked like a first patch. If this
# case ever returns to GREEN, the gate has been reverted to the version that
# failed on its own motivating instance.
#
set -uo pipefail

MCP=/home/harald/CursorProjects/jawata-mcp
STUDIO=/home/harald/CursorProjects/jawata-studio
GATE="$MCP/build/patch-streak-gate.sh"

pass=0; fail=0

# expect <repo> <tag> <RED|GREEN> <why>
expect() {
    local repo="$1" tag="$2" want="$3" why="$4"
    local out rc
    out=$(cd "$repo" && env -u STREAK_OVERRIDE bash "$GATE" "$tag" 2>&1); rc=$?
    local got=GREEN; [ "$rc" -eq 1 ] && got=RED; [ "$rc" -eq 4 ] && got=ASK
    [ "$rc" -eq 2 ] && got="MISUSE"
    if [ "$got" = "$want" ]; then
        printf 'ok    %-8s %-9s %s\n' "$(basename "$repo")" "$tag" "$why"
        pass=$((pass+1))
    else
        printf 'FAIL  %-8s %-9s want %s got %s — %s\n' "$(basename "$repo")" "$tag" "$want" "$got" "$why"
        echo "$out" | sed 's/^/        /' | head -6
        fail=$((fail+1))
    fi
}

echo "=== THE 2026-08-29 AUTOCONTINUE RUN (studio) — four fixes, four different breakages ==="
expect "$STUDIO" v3.16.1 GREEN "the FIRST patch of v3.16: no other patch of v3.16.x inside the window (the 2026-09-01 predicate; this case still read RED here and had been stale since)"
expect "$STUDIO" v3.16.2 ASK   "second patch, same morning"
expect "$STUDIO" v3.16.3 ASK   "third patch, same day"
expect "$STUDIO" v3.17.1 GREEN "the first patch of v3.17: v3.17.0 is the feature release, not a streak member (stale since 2026-09-01, which moved the rule to the same major.minor)"
expect "$STUDIO" v3.17.2 ASK   "tonight's release — the fifth on this mechanism"

echo
echo "=== THE 2026-08-13 WINDOWS RUN (studio) — nine tags, one design flaw ==="
expect "$STUDIO" v3.7.9  ASK   "second of the run"
expect "$STUDIO" v3.7.16 ASK   "last of the run"

echo
echo "=== CONTROLS THAT MUST STAY GREEN — or the gate blocks everything ==="
expect "$STUDIO" v3.17.0 GREEN "a MINOR bump is a feature release, never a streak member"
expect "$MCP"    v3.16.0 GREEN "same, in the other repo"
expect "$MCP"    v3.17.0 GREEN "minor bump"

echo
echo "=== THE OVERRIDE — his value, and only his ==="
out=$(cd "$STUDIO" && STREAK_OVERRIDE="ran the architect, design fix" bash "$GATE" v3.17.2 2>&1); rc=$?
if [ "$rc" -eq 0 ] && echo "$out" | grep -q OVERRIDDEN; then
    echo "ok    studio   v3.17.2   his override clears a streak"
    pass=$((pass+1))
else
    echo "FAIL  studio   v3.17.2   override did not clear (rc=$rc)"; echo "$out" | sed 's/^/        /'
    fail=$((fail+1))
fi

echo
echo "=== THE QUESTION AND THE ANSWER (2026-10-16): the gate asks, evidence is required, the answer is logged ==="
LOGF="$(mktemp)"; rm -f "$LOGF"
ANS_BASE=(env -u STREAK_OVERRIDE STREAK_LOG="$LOGF" bash "$GATE" v3.17.2)
# The tags v3.17.2 has as neighbours inside its window, read from the unanswered run itself, so this
# case cannot rot when a tag is added: whatever the gate lists is what the answer must name.
ASK_OUT=$(cd "$STUDIO" && "${ANS_BASE[@]}" 2>&1)
NEIGH=$(echo "$ASK_OUT" | grep -oE '^  v[0-9]+\.[0-9]+\.[0-9]+' | tr -d ' ' | grep -v '^v3.17.2$' | tr '\n' ' ')
FULL="enhancement: $(for t in $NEIGH; do printf '%s was a patch of one mechanism; ' "$t"; done)"

check_rc() {  # check_rc <label> <want-rc> <command...>
    local label="$1" want="$2"; shift 2
    local out rc
    out=$(cd "$STUDIO" && "$@" 2>&1); rc=$?
    if [ "$rc" -eq "$want" ]; then
        echo "ok    studio   v3.17.2   $label"; pass=$((pass+1))
    else
        echo "FAIL  studio   v3.17.2   $label — want rc=$want got $rc"; echo "$out" | sed 's/^/        /' | head -6
        fail=$((fail+1))
    fi
}

if echo "$ASK_OUT" | grep -q "THE QUESTION" && [ -n "$NEIGH" ]; then
    echo "ok    studio   v3.17.2   the unanswered run PRINTS the question and the commits of each release (neighbours: $NEIGH)"
    pass=$((pass+1))
else
    echo "FAIL  studio   v3.17.2   the unanswered run did not print the question and the evidence"; fail=$((fail+1))
fi
check_rc "an answer that names no neighbouring release is REFUSED (rc 3), not accepted" 3 \
    env -u STREAK_OVERRIDE STREAK_LOG="$LOGF" bash "$GATE" v3.17.2 --kind enhancement --because "it was all fine"
check_rc "an ENHANCEMENT answer naming every release passes (rc 0)" 0 \
    env -u STREAK_OVERRIDE STREAK_LOG="$LOGF" bash "$GATE" v3.17.2 --kind enhancement --because "$FULL"
check_rc "a FIX-SERIES answer is the original alarm (rc 1)" 1 \
    env -u STREAK_OVERRIDE STREAK_LOG="$LOGF" bash "$GATE" v3.17.2 --kind fix-series --because "${FULL/enhancement/fix-series}"
if [ -f "$LOGF" ] && [ "$(grep -c . "$LOGF")" -eq 2 ] && grep -q "enhancement" "$LOGF" && grep -q "fix-series" "$LOGF"; then
    echo "ok    studio   v3.17.2   both answers are in the log with their evidence, for checking against the commits"
    pass=$((pass+1))
else
    echo "FAIL  studio   v3.17.2   the log does not hold exactly the two accepted answers"; fail=$((fail+1))
fi
check_rc "an answer outside the three kinds is refused as misuse (rc 2)" 2 \
    env -u STREAK_OVERRIDE STREAK_LOG="$LOGF" bash "$GATE" v3.17.2 --kind ordinary --because "$FULL"
rm -f "$LOGF"

echo
echo "=== MISUSE IS LOUD, not silently green ==="
out=$(cd "$STUDIO" && bash "$GATE" "not-a-version" 2>&1); rc=$?
if [ "$rc" -eq 2 ]; then
    echo "ok    studio   garbage   an unparseable tag is refused, never waved through"
    pass=$((pass+1))
else
    echo "FAIL  studio   garbage   want rc=2 got $rc"; fail=$((fail+1))
fi

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
