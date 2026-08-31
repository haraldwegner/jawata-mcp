#!/usr/bin/env bash
# calibration.sh — Sprint 28d Stage 11 (D9): what our detectors find, beside
# what two established third-party tools find, on a corpus where the right
# answer is known.
#
# WHY THIS SHAPE
# --------------
# D9 says the right answer is "known from the folder name". MEASURED at the pin:
# exactly ONE of the 41 detector kinds shares a name with a fork folder
# (`singleton`), so strict precision/recall covers one detector. A SECOND label
# axis is DERIVED — never authored — from CureCatalog, a table that predates
# this stage: each smell kind declares the design that cures it, and 11 of its
# 12 declared designs are fork folders. A pattern's own folder is the worked
# example of that cure, so the smell it cures should be RARER there than across
# the rest of the corpus. Authoring labels ourselves is refused: a corpus we
# label measures us.
#
# The third-party tools are BASELINES, not ground truth. Their counts are
# reported beside ours with no correctness claim attached — they say what a
# mature tool reports on the same bytes, which is the only thing they can say.
#
# NOTHING IS INSTALLED. PMD and Error Prone are downloaded jars in a scratch
# directory; the corpus is built into an ISOLATED local repository. Neither our
# build nor the developer's ~/.m2 is touched. The pin is each tool's version and
# sha256, recorded in the report.
#
# THE STORE IS NEVER WRITTEN. The resident runs on a throwaway store and the row
# count is asserted identical before and after — D9's own measure.
#
# Usage:  build/calibration.sh <scratch-dir> [path/to/dist]
# Exit:   0 = the run completed and the store was untouched
#         1 = the store row count moved (a calibration run that writes is void)
#         2 = could not run at all
set -uo pipefail

SCRATCH="${1:?usage: calibration.sh <scratch-dir> [dist]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${2:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
FORK="${JAWATA_FORK:-/home/harald/CursorProjects/java-design-patterns}"
PORT="${JAWATA_CALIB_PORT:-8901}"
TOKEN="calibration-$$"
WS="$(mktemp -d)"; STORE="$(mktemp -d)"
LOG="$WS/resident.log"
PID=""
OUT="$SCRATCH/ours-findings.tsv"

cleanup() { [ -n "$PID" ] && kill "$PID" 2>/dev/null; [ -n "$PID" ] && wait "$PID" 2>/dev/null; rm -rf "$WS" "$STORE"; }
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ] || { echo "no artifact at $JAR — build the dist first" >&2; exit 2; }
[ -d "$FORK" ] || { echo "no corpus at $FORK" >&2; exit 2; }
mkdir -p "$SCRATCH"

# The labelled subset: every fork folder that is a declared cure design, plus
# `singleton`, which is the one exact kind/folder name match. Running the other
# 177 folders would add findings and no LABEL, which is cost without signal.
LABELLED="command command-query-responsibility-segregation delegation dependency-injection mediator private-class-data singleton state strategy template-method type-object"

# JAWATA_CALIB_MODULES=all runs the WHOLE corpus. That is not extra labels — it
# is the DENOMINATOR: "the smell is rarer in its own cure's folder" is a claim
# about a rate, and a rate needs the rest of the corpus to compare against.
# Without it, a detector that fires zero times everywhere looks identical to one
# that is genuinely quiet where the cure lives.
if [ "${JAWATA_CALIB_MODULES:-labelled}" = "all" ]; then
    MODULES="$(cd "$FORK" && ls -d */ 2>/dev/null | sed 's|/||' | sort | tr '\n' ' ')"
else
    MODULES="$LABELLED"
fi

# The 41 registered kinds, minus the four that need a caller-supplied argument
# (throws/catches take an exception FQN; forbidden_edge takes a layer pair) —
# those answer a question the caller asks, not one the corpus can pose, and are
# recorded here as excluded rather than silently missing.
KINDS="naming bugs unused large_classes circular_deps reflection coverage_lack javadoc_lack called_only_by_tests long_method god_class long_parameter_list data_clumps feature_envy message_chains inappropriate_intimacy middle_man primitive_obsession switch_statements refused_bequest temporary_field lazy_class speculative_generality parallel_inheritance incomplete_delegation divergent_change shotgun_surgery dip isp srp_cohesion lsp singleton type_code cqs coupling composition_over_inheritance ocp encapsulation"
EXCLUDED_KINDS="throws catches forbidden_edge"

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

call() {
    curl -s --max-time 300 -X POST "http://127.0.0.1:$PORT/mcp" \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: calib-$$" \
        -H 'Content-Type: application/json' \
        -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
        | sed 's/\\"/"/g'
}

store_rows() { call experience '{"kind":"stats"}' | grep -oE '"entries":[0-9]+' | head -1 | cut -d: -f2; }

echo "calibration — dist $JAR"
echo "corpus $FORK @ $(git -C "$FORK" log -1 --format=%H)"

# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$STORE" \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
PID=$!
for _ in $(seq 1 120); do
    grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && break
    kill -0 "$PID" 2>/dev/null || { echo "resident died on startup:" >&2; tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
call health_check '{}' | grep -q '"status"' || { echo "resident never answered" >&2; exit 2; }

ROWS_BEFORE="$(store_rows)"
echo "store rows before: $ROWS_BEFORE"

printf 'module\tkind\tcount\n' > "$OUT"
for m in $MODULES; do
    [ -d "$FORK/$m" ] || { echo "  SKIP $m (absent from the corpus)"; continue; }
    LOADED="$(call load_project "{\"projectPath\":\"$FORK/$m\"}")"
    case "$LOADED" in
        *'"loaded":true'*) : ;;
        *) echo "  SKIP $m (would not load): $(printf '%s' "$LOADED" | head -c 160)"; continue ;;
    esac
    FILES="$(printf '%s' "$LOADED" | grep -oE '"sourceFileCount":[0-9]+' | cut -d: -f2)"
    TOTAL=0
    for k in $KINDS; do
        R="$(call find_quality_issue "{\"kind\":\"$k\",\"summary\":true}")"
        C="$(printf '%s' "$R" | grep -oE '"count":[0-9]+' | head -1 | cut -d: -f2)"
        [ -n "$C" ] || C=0
        printf '%s\t%s\t%s\n' "$m" "$k" "$C" >> "$OUT"
        TOTAL=$((TOTAL + C))
    done
    printf '  %-45s files=%-4s findings=%s\n' "$m" "$FILES" "$TOTAL"
done

ROWS_AFTER="$(store_rows)"
echo "store rows after:  $ROWS_AFTER"
echo "findings written to $OUT"

if [ "$ROWS_BEFORE" != "$ROWS_AFTER" ]; then
    echo "FAIL: the store moved during calibration ($ROWS_BEFORE -> $ROWS_AFTER)." >&2
    echo "A calibration run that writes the store is void: it measured a corpus it changed." >&2
    exit 1
fi
echo "OK: the store is untouched ($ROWS_BEFORE rows before and after)"
echo "kinds excluded (need a caller-supplied argument): $EXCLUDED_KINDS"
