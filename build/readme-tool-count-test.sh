#!/usr/bin/env bash
# Self-test for build/readme-tool-count.sh. Drives the REAL check against fixture READMEs —
# no copy of its matching lives here, or this would test the copy and not the check.
#
# The case it exists for (S16-23): a prose count that disagrees with the published count while
# the README's tool table agrees — "44 tools total" stood while the product published 43.
#
# Usage: readme-tool-count-test.sh [--quiet]
set -uo pipefail
CHECK="$(cd "$(dirname "$0")" && pwd)/readme-tool-count.sh"
QUIET=0; [ "${1:-}" = "--quiet" ] && QUIET=1
FAILURES=0
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT

# expect <name> <wanted-exit> <wanted-substring-or--> <readme-text> <live-count>
expect() {
    local name="$1" want="$2" needle="$3" text="$4" live="$5"
    local readme="$DIR/README-$RANDOM.md" out rc
    printf '%s\n' "$text" > "$readme"
    out="$("$CHECK" "$readme" "$live" 2>&1)"; rc=$?
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

[ "$QUIET" -eq 1 ] || echo "readme tool-count self-test:"

expect "both prose counts matching the product pass" 0 "(2 checked)" \
"**44 MCP tools** cover the whole loop
*44 tools total; front doors ...*" 44

# THE CASE S16-23 RECORDED: line two still says 44 after the product went to 43.
expect "a stale count is named with its line" 1 'line 2 says "44 tools"' \
"**43 MCP tools** cover the whole loop
*44 tools total; front doors ...*" 43

expect "a front-page count that drifted is caught too" 1 'line 1 says "43 MCP tools"' \
"**43 MCP tools** cover the whole loop
*44 tools total*" 44

# Numbers that count something else are not tool counts.
expect "other counts are not read as tool counts" 0 "(1 checked)" \
"62 of Fowler's 66 refactorings, 22 of 24 smells
**44 MCP tools**" 44

# A README with no count in prose has nothing to drift — and the pass says it read none.
expect "a README with no count says it checked none" 0 "(0 checked)" \
"jawata analyses Java" 44

expect "no published count is a usage error, not a pass" 2 - "**44 MCP tools**" ""

if [ "$FAILURES" -gt 0 ]; then
    echo "readme tool-count self-test: $FAILURES case(s) failed"
    exit 1
fi
[ "$QUIET" -eq 1 ] || echo "readme tool-count self-test: all cases pass"
exit 0
