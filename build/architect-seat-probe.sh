#!/usr/bin/env bash
# Sprint 28c Stage 1, clause 7 — THE ARCHITECT SEAT'S OWN PROTOCOL, THROUGH THE
# REAL FRONT DOOR.
#
# The seat's D-FOUR stance says: a design question carries no symbol, no package
# and no operation, so before proposing a target the seat calls
# experience(kind=nominate) with the question in prose, READS each candidate's
# situation, decides which actually apply, and calls experience(kind=decide).
# It also says, in capitals, that SELECTING NOTHING IS THE RIGHT ANSWER MORE
# OFTEN THAN NOT.
#
# This script executes exactly that protocol over JSON-RPC against a freshly
# booted resident, for all twelve questions the frozen fixture carries:
#
#   * 5 POSITIVES  — the seat selects the candidate whose situation fits, and
#                    the decision must come back a MATCH carrying that entry.
#   * 7 UNRELATED  — the seat selects NOTHING, and the decision must come back
#                    an ABSENCE carrying zero entries.
#
# Why a separate script and not another block in end-to-end-test.sh: this is the
# CONSUMER-SIDE proof. The e2e proves the verbs answer; this proves the seat's
# documented loop runs end to end over the wire and produces both outcomes. It
# writes a full request/response transcript, because a claim that the front door
# was used is worth exactly the captured requests behind it.
#
# Usage:  build/architect-seat-probe.sh [dist-dir]
# Exit:   0 = all twelve behaved · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
FIXTURE="$ROOT/build/acceptance/anchorless-retrieval.json"
PORT="${JAWATA_PROBE_PORT:-8901}"
TOKEN="architect-seat-probe-$$"

WS="$(mktemp -d)"       # throwaway workspace AND store: the probe must never
STORE="$(mktemp -d)"    # read or write the developer's real one
LOG="$WS/resident.log"
TRANSCRIPT="${PROBE_TRANSCRIPT:-$WS/transcript.txt}"
RESIDENT_PID=""

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT

FAILED=0; PASSED=0
fail() { printf '  FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }
pass() { printf '  ok    %s\n' "$1"; PASSED=$((PASSED + 1)); }

[ -f "$JAR" ]     || { echo "no artifact at $JAR — build first" >&2; exit 2; }
[ -f "$FIXTURE" ] || { echo "no fixture at $FIXTURE" >&2; exit 2; }

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$STORE" \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
for _ in $(seq 1 120); do
    grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && break
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2
                                             tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done

# Every call is APPENDED TO THE TRANSCRIPT before its answer is looked at, so a
# run that fails still leaves the requests that produced the failure.
call() {   # call <tool> <json-args> -> the tool's answer, UNESCAPED
    local req answer
    req="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}"
    { echo "=== REQUEST  POST http://127.0.0.1:$PORT/mcp"; echo "$req"; } >> "$TRANSCRIPT"
    answer="$(curl -s --max-time 180 -X POST "http://127.0.0.1:$PORT/mcp" \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: architect-probe-$$" \
        -H 'Content-Type: application/json' -d "$req" | sed 's/\\"/"/g')"
    { echo "--- RESPONSE"; echo "$answer"; echo; } >> "$TRANSCRIPT"
    printf '%s' "$answer"
}

call health_check '{}' | grep -q '"status"' \
    || { echo "resident never answered tools/call:" >&2; tail -20 "$LOG" >&2; exit 2; }

# ---------------------------------------------------------------------------
# 1. Seed the five frozen records through the RECORD VERB — no symbol, no
#    package, no operation. Supplying one to make an entry reachable fails the
#    sprint by definition (the fixture's own contract says so), so the payloads
#    are built from the fixture and carry nothing but type/summary/situation/
#    verdict.
# ---------------------------------------------------------------------------
python3 - "$FIXTURE" "$WS/records.txt" << 'PY'
import json, sys
fx = json.load(open(sys.argv[1]))
with open(sys.argv[2], "w") as out:
    for r in fx["records"]:
        out.write(json.dumps({
            "kind": "record",
            "type": r["type"],
            "summary": r["summary"],
            "situation": r["situation"],
            "verdict": r["verdict"],
            "status": "accepted",
        }) + "\n")
PY

SEEDED=0
while IFS= read -r args; do
    OUT="$(call experience "$args")"
    case "$OUT" in
        *'"id"'*) SEEDED=$((SEEDED + 1)) ;;
        *) fail "seeding refused a frozen record: $(printf '%s' "$OUT" | head -c 250)" ;;
    esac
done < "$WS/records.txt"
[ "$SEEDED" -eq 5 ] \
    && pass "seat-probe all five anchorless records entered through the record verb" \
    || fail "seat-probe seeded $SEEDED of 5 records"

# ---------------------------------------------------------------------------
# 2. THE POSITIVES. For each question the seat nominates, reads the candidates'
#    situations, selects the one that fits, and decides. The decision must be a
#    MATCH carrying that entry.
#
#    The id is resolved from the nomination's OWN candidate list by matching the
#    fixture's summary text — the store mints its own ids, so the frozen ids are
#    stable NAMES for expectations, never keys. Selecting an id the nomination
#    did not offer is refused by design, which is what makes this a real
#    consumption of the nomination rather than a lookup around it.
# ---------------------------------------------------------------------------
POS_MATCHED=0
POS_TOTAL="$(python3 -c "import json;print(len(json.load(open('$FIXTURE'))['positive_questions']))")"

for i in $(seq 0 $((POS_TOTAL - 1))); do
    Q="$(python3 -c "import json;print(json.load(open('$FIXTURE'))['positive_questions'][$i]['question'])")"
    EXPECT_ID="$(python3 -c "import json;print(json.load(open('$FIXTURE'))['positive_questions'][$i]['expect_id'])")"
    WANT_SUMMARY="$(python3 -c "
import json
fx=json.load(open('$FIXTURE'))
print(next(r['summary'] for r in fx['records'] if r['id']=='$EXPECT_ID'))")"

    NOM="$(call experience "$(python3 -c "
import json,sys;print(json.dumps({'kind':'nominate','question':sys.argv[1]}))" "$Q")")"

    case "$NOM" in
        *'"result":"match"'*)
            fail "seat-probe positive $((i+1)) nominate VOUCHED — ranking must never answer" ; continue ;;
        *'"result":"nominated"'*) : ;;
        *) fail "seat-probe positive $((i+1)) produced no nomination: $(printf '%s' "$NOM" | head -c 200)" ; continue ;;
    esac

    QID="$(printf '%s' "$NOM" | sed -n 's/.*"query_id":"\([^"]*\)".*/\1/p')"
    # The seat's read step: find, among the offered candidates, the one whose
    # content is the fitting experience. Absent from the list => the seat has
    # nothing to select and the positive is a genuine miss, reported as one.
    PICK="$(printf '%s' "$NOM" | python3 -c "
import json,re,sys
body=sys.stdin.read()
want=sys.argv[1]
# the candidates arrive as objects carrying an id and their situation/summary;
# take the id of the first whose text contains the record's principle
best=''
for m in re.finditer(r'\{[^{}]*\}', body):
    chunk=m.group(0)
    if want[:40] in chunk:
        idm=re.search(r'\"id\":\"([^\"]+)\"', chunk)
        if idm:
            best=idm.group(1); break
print(best)" "$WANT_SUMMARY")"

    if [ -z "$PICK" ]; then
        fail "seat-probe positive $((i+1)) the fitting experience was not among the candidates: $Q"
        continue
    fi

    DEC="$(call experience "{\"kind\":\"decide\",\"query_id\":\"$QID\",\"selected_ids\":[\"$PICK\"]}")"
    case "$DEC" in
        *'"result":"match"'*)
            POS_MATCHED=$((POS_MATCHED + 1)) ;;
        *) fail "seat-probe positive $((i+1)) a selected candidate did not decide to a MATCH: $(printf '%s' "$DEC" | head -c 250)" ;;
    esac
done

[ "$POS_MATCHED" -eq "$POS_TOTAL" ] \
    && pass "seat-probe all $POS_TOTAL positives: the seat selected a candidate and the decision came back a MATCH" \
    || fail "seat-probe only $POS_MATCHED of $POS_TOTAL positives decided to a match"

# ---------------------------------------------------------------------------
# 3. THE UNRELATED CONTROLS. The seat reads the candidates, finds nothing that
#    applies, and selects NOTHING — the stance's own preferred answer. The
#    decision must be an ABSENCE with zero entries, and it must echo THAT
#    question back: asserting result=absence alone is not discriminating,
#    because plain recall answers a cue-less call with an absence too, so a
#    decide verb bypassed to recall would pass.
# ---------------------------------------------------------------------------
ABS=0
UNREL_TOTAL="$(python3 -c "import json;print(len(json.load(open('$FIXTURE'))['unrelated_questions']))")"

for i in $(seq 0 $((UNREL_TOTAL - 1))); do
    Q="$(python3 -c "import json;print(json.load(open('$FIXTURE'))['unrelated_questions'][$i])")"
    NOM="$(call experience "$(python3 -c "
import json,sys;print(json.dumps({'kind':'nominate','question':sys.argv[1]}))" "$Q")")"
    QID="$(printf '%s' "$NOM" | sed -n 's/.*"query_id":"\([^"]*\)".*/\1/p')"
    if [ -z "$QID" ]; then
        fail "seat-probe unrelated $((i+1)) nominate returned no query_id"; continue
    fi

    DEC="$(call experience "{\"kind\":\"decide\",\"query_id\":\"$QID\",\"selected_ids\":[]}")"

    # A distinctive word from the question itself, so the absence is provably
    # THIS question's and not a generic one.
    ECHO_WORD="$(printf '%s' "$Q" | tr ' ' '\n' | awk '{ if (length($0) > 6) print }' | tail -1)"
    case "$DEC" in
        *'"result":"absence"'*)
            case "$DEC" in
                *"$ECHO_WORD"*) : ;;
                *) fail "seat-probe unrelated $((i+1)) absence did not echo its own question ($ECHO_WORD)"; continue ;;
            esac ;;
        *) fail "seat-probe unrelated $((i+1)) selecting nothing did not yield an absence: $(printf '%s' "$DEC" | head -c 250)"; continue ;;
    esac
    case "$DEC" in
        *'"count":0'*) ABS=$((ABS + 1)) ;;
        *) fail "seat-probe unrelated $((i+1)) the absence carried entries: $(printf '%s' "$DEC" | head -c 250)" ;;
    esac
done

[ "$ABS" -eq "$UNREL_TOTAL" ] \
    && pass "seat-probe all $UNREL_TOTAL unrelated questions: the seat selected nothing and got an ABSENCE with no entries" \
    || fail "seat-probe only $ABS of $UNREL_TOTAL unrelated questions produced an empty absence"

# The transcript is the evidence the clause asks for; say where it is even on
# success, because a claim that the front door was used is worth exactly the
# captured requests behind it.
if [ "${PROBE_TRANSCRIPT:-}" != "" ]; then
    echo "transcript: $TRANSCRIPT ($(grep -c '=== REQUEST' "$TRANSCRIPT") front-door requests)"
else
    echo "transcript: $(grep -c '=== REQUEST' "$TRANSCRIPT") front-door requests (set PROBE_TRANSCRIPT to keep it)"
fi

echo
echo "architect-seat-probe: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ] || exit 1
