#!/usr/bin/env bash
# Self-test for build/tmp-sweep.sh. Drives the REAL script over a scratch root — no copy of
# its rule lives here, or this would test the copy and not the sweep.
#
# It exists because the sweep's FIRST version shipped with no control of any kind: it lived
# inline in run-suite.sh, so nothing short of a whole suite run could exercise it, and the
# defect it carried — deleting a live process's directory — was found by review rather than
# by anything that could fail. The case below is exactly that defect.
#
# Usage: tmp-sweep-test.sh [--quiet]
set -uo pipefail
SWEEP="$(cd "$(dirname "$0")" && pwd)/tmp-sweep.sh"
QUIET=0; [ "${1:-}" = "--quiet" ] && QUIET=1
FAILURES=0
say() { [ "$QUIET" -eq 1 ] || echo "$@"; }
check() {   # check <name> <condition-result>
    if [ "$2" = "0" ]; then say "  ok   $1"
    else echo "  FAIL $1"; FAILURES=$((FAILURES + 1)); fi
}

R="$(mktemp -d)"; trap 'rm -rf "$R"' EXIT
say "tmp-sweep self-test:"

# --- the state a real machine is in: something ALREADY there, and a resident writing to it.
mkdir -p "$R/jawata-test-ws-LIVE";   : > "$R/jawata-test-ws-LIVE/old"
mkdir -p "$R/jawata-runtime";        : > "$R/jawata-runtime/artifact"
mkdir -p "$R/not-ours";              : > "$R/not-ours/keep"

"$SWEEP" snapshot "$R" "$R/before" || { echo "  FAIL snapshot exited non-zero"; exit 1; }

# THE DEFECT THE FIRST VERSION HAD: the pre-existing directory is WRITTEN TO during the run,
# which moves its mtime past any marker taken at the start.
: > "$R/jawata-test-ws-LIVE/written-during-the-run"
# ...and the run makes one of its own.
mkdir -p "$R/jawata-cov-store-RUN";  : > "$R/jawata-cov-store-RUN/data"

OUT="$("$SWEEP" sweep "$R" "$R/before")"
say "  $OUT"

[ -d "$R/jawata-test-ws-LIVE" ]; check "a PRE-EXISTING directory survives even though it was written to during the run" "$?"
[ -d "$R/jawata-runtime" ];      check "the persistent artifact store is never a candidate" "$?"
[ -d "$R/not-ours" ];            check "a directory that is not ours is untouched" "$?"
[ ! -d "$R/jawata-cov-store-RUN" ]; check "the directory this run created is removed" "$?"
case "$OUT" in *"swept 1 "*) check "it reports exactly the one it removed" 0 ;;
               *) check "it reports exactly the one it removed" 1 ;; esac

# --- a run that created nothing says nothing.
"$SWEEP" snapshot "$R" "$R/before2"
Q="$("$SWEEP" sweep "$R" "$R/before2")"
[ -z "$Q" ]; check "a run that created nothing prints nothing" "$?"

# --- REFUSALS: without a snapshot every directory looks new, so sweeping is refused.
"$SWEEP" sweep "$R" "$R/no-such-snapshot" >/dev/null 2>&1
[ "$?" -eq 2 ]; check "sweeping with no snapshot is REFUSED, not treated as an empty set" "$?"
"$SWEEP" sweep "$R/no-such-root" "$R/before" >/dev/null 2>&1
[ "$?" -eq 2 ]; check "a missing root is refused" "$?"
"$SWEEP" >/dev/null 2>&1
[ "$?" -eq 2 ]; check "no arguments is refused" "$?"

if [ "$FAILURES" -ne 0 ]; then
    echo "tmp-sweep self-test: $FAILURES case(s) FAILED — the sweep is not safe to run."
    exit 1
fi
say "tmp-sweep self-test: all cases pass"
exit 0
