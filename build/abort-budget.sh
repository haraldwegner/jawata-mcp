#!/usr/bin/env bash
# Sprint 28a (1b, M13) — every honest skip must be a decision somebody made.
#
# The suite's assumption-skips are a FEATURE: a cell that cannot be proven on
# this host aborts with its reason rather than passing. The risk is that the
# set grows quietly, and coverage leaves without anyone deciding to let it go.
#
# So: each abort the run reported must match a committed reason pattern for
# this operating system. An abort matching nothing fails the job, and the
# message names the abort so the reader can either fix it or add the line WITH
# its justification.
#
# Usage: build/abort-budget.sh <log-dir-or-file> [os]
#        (os defaults to the running platform: linux | windows | macos)
#
# Takes a DIRECTORY of shard logs (the local sharded runner) or a single log
# file (CI runs the suite unsharded into test-run.log) — the budget must apply
# to whichever way the suite was run, or it applies to neither.
set -uo pipefail

LOG_TARGET="${1:?usage: abort-budget.sh <log-dir-or-file> [os]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ $# -ge 2 ]; then
    OS="$2"
else
    case "$(uname -s 2>/dev/null || echo unknown)" in
        Linux*)              OS=linux ;;
        Darwin*)             OS=macos ;;
        MINGW*|MSYS*|CYGWIN*) OS=windows ;;
        *)                   OS=linux ;;
    esac
fi

EXPECTED="$ROOT/build/expected-aborts.$OS"
if [ ! -f "$EXPECTED" ]; then
    echo "FATAL: no committed abort budget for '$OS' ($EXPECTED)."
    echo "A platform with no budget file has no agreed skip set — write one,"
    echo "with a reason per line, rather than letting this pass unchecked."
    exit 2
fi

# Patterns: non-empty, non-comment lines.
#
# read-loop, not mapfile: macOS ships bash 3.2, where mapfile does not exist.
# The first CI run of this gate died there with "mapfile: command not found"
# followed by "PATTERNS: unbound variable" — a portability assumption inside
# the very script that polices portability assumptions.
PATTERNS=()
while IFS= read -r line; do
    PATTERNS+=("$line")
done < <(grep -v '^[[:space:]]*#' "$EXPECTED" | grep -v '^[[:space:]]*$')
if [ "${#PATTERNS[@]}" -eq 0 ]; then
    echo "FATAL: $EXPECTED lists no patterns — an empty budget would accept every skip."
    exit 2
fi

# Every abort line the run produced.
if [ -d "$LOG_TARGET" ]; then
    LOGS=("$LOG_TARGET"/shard-*.log)
elif [ -f "$LOG_TARGET" ]; then
    LOGS=("$LOG_TARGET")
else
    echo "FATAL: no log at '$LOG_TARGET' — a budget checked against nothing"
    echo "reports OK for every possible run, which is worse than no check."
    exit 2
fi
ABORTS=()
while IFS= read -r line; do
    ABORTS+=("$line")
done < <(cat "${LOGS[@]}" 2>/dev/null | sed -n 's/.*~~ ABORTED //p')

echo "abort budget [$OS]: ${#ABORTS[@]} abort(s) reported, ${#PATTERNS[@]} pattern(s) allowed"

UNMATCHED=0
for abort in "${ABORTS[@]}"; do
    matched=0
    for pattern in "${PATTERNS[@]}"; do
        case "$abort" in
            *"$pattern"*) matched=1; break ;;
        esac
    done
    if [ "$matched" -eq 0 ]; then
        UNMATCHED=$((UNMATCHED + 1))
        echo "UNBUDGETED ABORT: $abort"
    fi
done

if [ "$UNMATCHED" -gt 0 ]; then
    echo
    echo "$UNMATCHED abort(s) match no committed reason for $OS."
    echo "A test that stops running is a loss of coverage. Either restore it, or"
    echo "add its reason to $EXPECTED together with why it is acceptable."
    exit 1
fi

echo "abort budget [$OS]: OK — every skip is accounted for"
