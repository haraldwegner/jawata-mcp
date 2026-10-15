#!/usr/bin/env bash
# readme-tool-count.sh — every tool COUNT written in the README's prose equals the number of
# tools the product publishes.
#
# S16-23 (Sprint 28g). The end-to-end gate holds the README's TOOLS:BEGIN/END table to the
# running product by set comparison, and nothing held the two numbers written in prose beside
# it. One had drifted into being accidentally correct: "44 tools total" stood while the product
# published 43, and became true only when a 44th tool arrived. A number beside a list has
# nothing holding it to the list unless something compares them, so this compares them.
#
# What it reads: every "<N> tools" and "<N> MCP tools" in the file. A count spelled in words is
# not seen; the pass line says how many numbers were checked, so a check that read none says so
# instead of passing silently on nothing.
#
# Usage:  readme-tool-count.sh <README.md> <published-tool-count>
# Exit:   0 = every count matches · 1 = a count differs (named, with its line) · 2 = bad usage
set -uo pipefail

README="${1:-}"
LIVE="${2:-}"
if [ -z "$README" ] || [ ! -f "$README" ] || ! [[ "$LIVE" =~ ^[0-9]+$ ]]; then
    echo "usage: readme-tool-count.sh <README.md> <published-tool-count>" >&2
    exit 2
fi

checked=0
wrong=""
while IFS=: read -r line phrase; do
    [ -z "$line" ] && continue
    checked=$((checked + 1))
    number="${phrase%% *}"
    if [ "$number" != "$LIVE" ]; then
        wrong="$wrong
          line $line says \"$phrase\""
    fi
done < <(grep -noE '\b[0-9]+ (MCP )?tools\b' "$README")

if [ -n "$wrong" ]; then
    echo "the README's prose disagrees with the $LIVE tools the product publishes:$wrong"
    exit 1
fi
echo "every tool count in the README's prose says $LIVE ($checked checked)"
exit 0
