#!/usr/bin/env bash
# mcp#44 — remove the temp directories ONE RUN created, and nothing else.
#
# It lives in its own script for the reason build/verdict-gate.sh does: inline in
# run-suite.sh the rule could only ever be exercised by a whole suite run, so its first
# version shipped with no control at all and a defect nothing could have caught. See
# build/tmp-sweep-test.sh, which drives THIS script — no copy of the rule lives there.
#
# THE RULE IS A SET, NOT A TIMESTAMP, and that is the whole correctness argument. The first
# version used `find -newer <marker>`; a directory's mtime moves when its CONTENTS change, so
# a directory created long before a run matches the moment anything writes into it. With
# jawata residents live — they keep jawata-test-ws and jawata-boot-config directories under
# /tmp — that rule would delete a RUNNING process's working directory. A name absent from the
# recorded set cannot have that failure.
#
# Usage:  tmp-sweep.sh snapshot <root> <out-file>
#         tmp-sweep.sh sweep    <root> <before-file>
# Exit:   0 = done (sweep prints a line only when it removed something); 2 = bad usage.
set -uo pipefail

MODE="${1:-}"; ROOT="${2:-}"; FILE="${3:-}"
if [ -z "$MODE" ] || [ -z "$ROOT" ] || [ -z "$FILE" ]; then
    echo "usage: $(basename "$0") snapshot|sweep <root> <file>" >&2
    exit 2
fi
[ -d "$ROOT" ] || { echo "tmp-sweep: no such root '$ROOT'" >&2; exit 2; }

# jawata-runtime is the PERSISTENT artifact store, not run debris, so it is never a candidate
# — on either side, so that it can never enter the difference.
candidates() {
    find "$ROOT" -maxdepth 1 -name 'jawata-*' ! -name 'jawata-runtime' 2>/dev/null | sort
}

case "$MODE" in
    snapshot)
        candidates > "$FILE"
        ;;
    sweep)
        [ -f "$FILE" ] || { echo "tmp-sweep: no snapshot at '$FILE' — refusing to sweep," \
                                 "because without one EVERY directory looks new" >&2; exit 2; }
        AFTER="$(mktemp)"; NEW="$(mktemp)"
        trap 'rm -f "$AFTER" "$NEW"' EXIT
        candidates > "$AFTER"
        comm -13 "$FILE" "$AFTER" > "$NEW"
        COUNT=$(wc -l < "$NEW")
        if [ "$COUNT" -gt 0 ]; then
            KB=$(xargs -r -a "$NEW" du -sk 2>/dev/null | awk '{s+=$1} END {print s+0}')
            xargs -r -a "$NEW" rm -rf 2>/dev/null
            echo "swept $COUNT /tmp working director(ies) this run created ($((KB / 1024)) MB)"
        fi
        ;;
    *)
        echo "usage: $(basename "$0") snapshot|sweep <root> <file>" >&2
        exit 2
        ;;
esac
exit 0
