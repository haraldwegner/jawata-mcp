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
    local got=GREEN; [ "$rc" -eq 1 ] && got=RED
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
expect "$STUDIO" v3.16.1 RED   "first of the day, but v3.16.0 was 2 days earlier — density catches it"
expect "$STUDIO" v3.16.2 RED   "second patch, same morning"
expect "$STUDIO" v3.16.3 RED   "third patch, same day"
expect "$STUDIO" v3.17.1 RED   "THE DECIDING CASE: the lineage rule went GREEN here"
expect "$STUDIO" v3.17.2 RED   "tonight's release — the fifth on this mechanism"

echo
echo "=== THE 2026-08-13 WINDOWS RUN (studio) — nine tags, one design flaw ==="
expect "$STUDIO" v3.7.9  RED   "second of the run"
expect "$STUDIO" v3.7.16 RED   "last of the run"

echo
echo "=== CONTROLS THAT MUST STAY GREEN — or the gate blocks everything ==="
expect "$STUDIO" v3.17.0 GREEN "a MINOR bump is a feature release, never a streak member"
expect "$MCP"    v3.16.0 GREEN "same, in the other repo"
expect "$MCP"    v3.17.0 GREEN "minor bump"

echo
echo "=== THE OVERRIDE — his value, and only his ==="
out=$(cd "$STUDIO" && STREAK_OVERRIDE="ran the architect, design fix" bash "$GATE" v3.17.1 2>&1); rc=$?
if [ "$rc" -eq 0 ] && echo "$out" | grep -q OVERRIDDEN; then
    echo "ok    studio   v3.17.1   his override clears a red streak"
    pass=$((pass+1))
else
    echo "FAIL  studio   v3.17.1   override did not clear (rc=$rc)"; echo "$out" | sed 's/^/        /'
    fail=$((fail+1))
fi

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
