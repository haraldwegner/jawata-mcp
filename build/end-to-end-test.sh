#!/usr/bin/env bash
# end-to-end-test.sh — the end-to-end test: prove each promise works in the
# PRODUCT, not in unit tests.
#
# WHY THIS EXISTS
# ---------------
# v3.4.0 was released with its central feature completely inert. The unit suite
# was 1591/1591 four different ways and coverage ROSE on all three ratchets,
# because every test constructed the recall engine and handed it the embedding
# index by hand. Production never did. No test could notice: they were each
# supplying the very wiring that was missing.
#
# This script cannot do that. It has one way in — the same JSON-RPC endpoint an
# editor uses — so it can only ask the product to do the thing it claims. It
# starts the BUILT ARTIFACT, on a THROWAWAY store, asserts one live claim per
# deliverable, and tears the resident down.
#
# Sprint 27a (Stage 7): the store is POPULATED from the committed fixture
# (build/e2e-fixture/entries.json — invented entries, hash-compared pristine
# after the run), and the resident is started THREE times against the same
# store: lifecycle 1 seeds and checks the write side; lifecycle 2 proves the
# startup reconciliation embeds the restored rows and checks the read side;
# lifecycle 3 runs with the embedder DISABLED and proves the degrade paths.
# Checks new in 27a are labelled "27a-..." — they are the audited reasons this
# script must be RED against a v3.4.1 dist and GREEN against this build.
#
# It runs BEFORE the release sign-off ask, and its output belongs IN that ask.
#
# Usage:  build/end-to-end-test.sh [path/to/dist]
# Exit:   0 = every claim held · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

DIST="${1:-build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_GATE_PORT:-8899}"
TOKEN="end-to-end-test-$$"
WS="$(mktemp -d)"                 # throwaway workspace AND store: the gate must
STORE="$(mktemp -d)"              # never read or write the developer's real one
LOG="$WS/resident.log"
RESIDENT_PID=""
FIXTURE="$(cd "$(dirname "$0")" && pwd)/e2e-fixture/entries.json"

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT

fail() { printf '  FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }
pass() { printf '  ok    %s\n' "$1"; PASSED=$((PASSED + 1)); }
FAILED=0
PASSED=0

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }
[ -f "$FIXTURE" ] || { echo "no fixture at $FIXTURE" >&2; exit 2; }
FIXTURE_SHA_BEFORE="$(sha256sum "$FIXTURE" | cut -d' ' -f1)"

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

start_resident() {   # start_resident [extra JVM args...]
    LOG="$WS/resident-$((PASSED + FAILED)).log"
    # shellcheck disable=SC2086
    java $VECTOR "$@" -Djawata.experience.shared.dir="$STORE" \
         -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
    RESIDENT_PID=$!
    for _ in $(seq 1 120); do
        grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && break
        kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2
                                                 tail -20 "$LOG" >&2; exit 2; }
        sleep 1
    done
    call health_check '{}' | grep -q '"status"' \
        || { echo "resident never answered tools/call:" >&2; tail -20 "$LOG" >&2; exit 2; }
}

stop_resident() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    RESIDENT_PID=""
}

call() {   # call <tool> <json-args> -> the tool's answer, UNESCAPED
    # A tool result arrives as JSON nested inside a JSON string, so every quote
    # comes back backslash-escaped. Unescape before matching: a pattern written
    # in the readable form silently never matches the wire form — which would
    # make this gate fail everything, or worse, pass everything.
    curl -s --max-time 180 -X POST "http://127.0.0.1:$PORT/mcp" \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: e2e-$$" \
        -H 'Content-Type: application/json' \
        -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",
             \"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
        | sed 's/\\"/"/g'
}

call_file() {   # call_file <tool> <file-with-json-args> — for large payloads
    local body="$WS/body.json"
    python3 - "$1" "$2" > "$body" << 'PY'
import json, sys
tool, argfile = sys.argv[1], sys.argv[2]
print(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                  "params": {"name": tool, "arguments": json.load(open(argfile))}}))
PY
    curl -s --max-time 180 -X POST "http://127.0.0.1:$PORT/mcp" \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: e2e-$$" \
        -H 'Content-Type: application/json' \
        -d @"$body" | sed 's/\\"/"/g'
}

tool_names() {   # every registered tool name, space-separated — so a count
                 # mismatch says WHICH tools are there, not only how many
    curl -s --max-time 60 -X POST "http://127.0.0.1:$PORT/mcp" \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: e2e-$$" \
        -H 'Content-Type: application/json' \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
        | grep -oE '"name":"[a-z_]+"' | cut -d'"' -f4 | sort -u | tr '\n' ' '
}

no_score() {   # 27a: no similarity number in any user-facing payload
    if printf '%s' "$2" | grep -qE '0\.[0-9]{2}'; then
        fail "no-similarity-number $1 leaked a similarity number"
    else
        pass "no-similarity-number $1 carries no similarity number"
    fi
}

echo "end-to-end test — against $JAR"

# ============================ lifecycle 1 ====================================
start_resident

# --- embedder-loaded: the embedder is loaded AND says which backend actually won ---------
H="$(call health_check '{}')"
case "$H" in
    *'"available":true'*) pass "embedder-loaded embedder available; backend reported" ;;
    *) fail "embedder-loaded embedder not available in the running product" ;;
esac

# --- tool-count: no tool silently appeared or vanished -----------------------
# The release-note sentence "the tool count is still N" gets its live check
# (Sprint 28 outcome audit, F7 — the sentence had no check behind it).
#
# The number moves when a tool is added ON PURPOSE, and then this line is part
# of that change. It was not: 28b's `field` tool made it 46 and the assertion
# stayed at 45, so the gate failed on its own sprint's work and the failure
# said only "46" — a number, with nothing to tell you which tool it was (28b
# closing audit, F1). The failure now NAMES every registered tool, so the next
# addition is a one-line edit here rather than an investigation.
EXPECTED_TOOLS=46
TOOLS="$(printf '%s' "$H" | grep -oE '"toolCount"[[:space:]]*:[[:space:]]*[0-9]+' | grep -oE '[0-9]+')"
if [ "${TOOLS:-missing}" = "$EXPECTED_TOOLS" ]; then
    pass "tool-count-still-$EXPECTED_TOOLS health_check reports exactly $EXPECTED_TOOLS tools"
else
    fail "tool-count-still-$EXPECTED_TOOLS expected $EXPECTED_TOOLS tools, health_check reports:
          ${TOOLS:-no toolCount field at all}. Registered right now: $(tool_names)
          If a tool was added or removed on purpose, set EXPECTED_TOOLS in this
          script (and the release note's sentence) in that same change."
fi

# --- recall-by-meaning: recall by MEANING, the release's central claim ---------------------
call experience '{"kind":"record","type":"lesson",
  "summary":"the roof leaked because nobody swept the gutters in autumn",
  "situation":"when a season of leaf fall has passed without maintenance",
  "verdict":"failed_avoid",
  "operation":"end-to-end-test","language":"process"}' >/dev/null
R="$(call experience '{"kind":"recall",
  "symptom":"water came through the ceiling after the drains clogged with leaves",
  "format":"text"}')"
case "$R" in
    *gutters*) pass "recall-by-meaning a paraphrase sharing no words with the entry found it" ;;
    *) fail "recall-by-meaning RECALL BY MEANING IS NOT RUNNING IN THE PRODUCT" ;;
esac

# --- entry-form: the store refuses what is not knowledge, and says how to fix it ------
# Sprint 28c S3/S4. The gate lives at the record verb in the SHIPPED dist, not in a
# test harness: an outcome-less lesson is refused, the refusal teaches, nothing is
# stored, and the well-formed twin of the same knowledge comes back on recall. The
# pair is the point — a refusal alone could mean the verb is broken.
BAD="$(call experience '{"kind":"record","type":"lesson",
  "summary":"the pump seized after the filter was left unchanged for a year",
  "operation":"end-to-end-test","language":"process"}')"
case "$BAD" in
    *REPHRASE:*)
        pass "entry-form an outcome-less lesson is refused with a rephrase" ;;
    *)
        fail "entry-form the form gate is NOT running in the product; got: $(printf '%s' "$BAD" | head -c 200)" ;;
esac

call experience '{"kind":"record","type":"lesson",
  "summary":"the pump seized after the filter was left unchanged for a year",
  "situation":"when a service interval has been skipped on a sealed pump",
  "verdict":"failed_avoid",
  "operation":"end-to-end-test","language":"process"}' >/dev/null
FR="$(call experience '{"kind":"recall","symptom":"the pump seized","format":"text"}')"
case "$FR" in
    *"pump seized"*)
        pass "entry-form the well-formed twin is recalled back through the front door" ;;
    *)
        fail "entry-form a well-formed record did not come back on recall: $(printf '%s' "$FR" | head -c 200)" ;;
esac

# A domain fact owes NO outcome — the form binds where it means something, and this
# is the half a one-sided test cannot see (Harald, 2026-08-21: "you cannot just form
# everything upfront into lessons").
FACT="$(call experience '{"kind":"record","type":"domain_fact",
  "summary":"the resident writes its store as one file under the data directory",
  "operation":"end-to-end-test","language":"process"}')"
case "$FACT" in
    *REPHRASE:*)
        fail "entry-form a domain fact was held to the experience form: $(printf '%s' "$FACT" | head -c 200)" ;;
    *'"id"'*)
        pass "entry-form a domain fact is admitted without a verdict" ;;
    *)
        fail "entry-form a domain fact was rejected for some other reason: $(printf '%s' "$FACT" | head -c 200)" ;;
esac

# --- form-line: a recalled form-1 entry states its condition and its outcome ---------
# Sprint 28c S5. The line the deployed hooks pass through is the one asserted here:
# without the situation a reader can only judge an entry by resemblance, and
# without the outcome a practice that worked reads like one that cost a day.
call experience '{"kind":"record","type":"lesson",
  "summary":"the kiln cooled too fast and the glaze crazed across the shoulder",
  "situation":"when a load is drawn below 600C in under an hour",
  "verdict":"failed_avoid",
  "operation":"end-to-end-test","language":"process"}' >/dev/null
FL="$(call experience '{"kind":"recall","symptom":"the glaze crazed","format":"text"}')"
case "$FL" in
    *"when a load is drawn below 600C in under an hour"*)
        pass "form-line the recalled line carries the condition it applies under" ;;
    *)
        fail "form-line no situation on the rendered line: $(printf '%s' "$FL" | head -c 200)" ;;
esac
case "$FL" in
    *failed_avoid*)
        pass "form-line and the outcome, so a costly practice does not read as a safe one" ;;
    *)
        fail "form-line no verdict on the rendered line: $(printf '%s' "$FL" | head -c 200)" ;;
esac
# One entry is ONE line: a stored newline must not split an entry and hand the
# second half to a reader as though it were an entry of its own.
case "$FL" in
    *"when when"*)
        fail "form-line the line's own 'when' doubled the author's" ;;
    *)
        pass "form-line the condition reads once, not twice" ;;
esac

# --- write-dedup: recording a near-duplicate proposes a merge ------------------------
D="$(call experience '{"kind":"record","type":"lesson",
  "summary":"the roof leaked because nobody swept the gutters in autumn",
  "situation":"when a season of leaf fall has passed without maintenance",
  "verdict":"failed_avoid",
  "operation":"end-to-end-test","language":"process"}')"
case "$D" in
    *duplicate_of*) pass "write-dedup a re-recorded entry is flagged as a duplicate" ;;
    *) fail "write-dedup write-path dedup did not fire on an identical entry" ;;
esac
no_score "record(dedup-flag)" "$D"

# --- counters: the counters actually move, and say how to read themselves --------
S="$(call experience '{"kind":"stats"}')"
case "$S" in
    *unavailable*)      fail "counters the counter table is missing on this store" ;;
    *'"fired.'*|*question_hook*)
                        pass "counters counters advanced from the calls above" ;;
    *)                  fail "counters no counter moved despite live recalls" ;;
esac
case "$S" in
    *CORRELATION*) pass "counters the counts carry their how-to-read sentence" ;;
    *) fail "counters counts rendered without the correlation label" ;;
esac

# --- 27a: import the committed fixture through the front door ----------------
python3 - "$FIXTURE" > "$WS/import-args.json" << 'PY'
import json, sys
entries = json.load(open(sys.argv[1]))["data"]["entries"]
print(json.dumps({"kind": "import", "entries": entries}))
PY
IMP="$(call_file experience "$WS/import-args.json")"
case "$IMP" in
    *'"imported":48'*) pass "fixture-import the 48 committed entries imported" ;;
    *) fail "fixture-import import did not land 48 entries: $(printf '%s' "$IMP" | head -c 200)" ;;
esac

# --- backfill-pending-after-restore: a restored store is honestly PART-embedded ------------------
S1="$(call experience '{"kind":"stats"}')"
EMB="$(printf '%s' "$S1" | grep -o '"embedding".\{0,220\}')"
if [ -z "$EMB" ]; then
    fail "backfill-pending-after-restore stats carries no embedding block (v3.4.1 shape)"
else
    EMB_N="$(printf '%s' "$EMB" | grep -oE '"embedded":[0-9]+' | head -1 | cut -d: -f2)"
    TOT_N="$(printf '%s' "$EMB" | grep -oE '"total":[0-9]+' | head -1 | cut -d: -f2)"
    if [ -n "$EMB_N" ] && [ -n "$TOT_N" ] && [ "$EMB_N" -lt "$TOT_N" ]; then
        pass "backfill-pending-after-restore stats shows the restored rows honestly unembedded ($EMB_N/$TOT_N)"
    else
        fail "backfill-pending-after-restore expected n<total right after a restore, got ${EMB_N:-?}/${TOT_N:-?}"
    fi
fi

# --- admission-gate: a wrong-kind record is refused with the teaching redirect -----
# The situation and verdict are supplied so this probe can fail for exactly ONE
# reason — the misplaced SYMPTOM. Without them the form gate refuses first, on a
# different field, and the check passes while proving nothing about symptoms.
A="$(call experience '{"kind":"record","type":"lesson",
  "summary":"a lesson about the ordering notes",
  "situation":"when reading ordering notes alongside the code",
  "verdict":"worked",
  "symptoms":["client-app/docs/ordering-notes.md"]}')"
case "$A" in
    *"WHERE IT BELONGS"*) pass "admission-gate a path standing as a symptom is refused with the teaching message" ;;
    *'"stored":true'*) fail "admission-gate THE ADMISSION GATE IS NOT RUNNING (garbage stored)" ;;
    *) fail "admission-gate unexpected admission response: $(printf '%s' "$A" | head -c 200)" ;;
esac

# --- paraphrase-not-duplicate: a genuine paraphrase in different words is NOT flagged ---------
# (the corrected release-note claim, live: high-precision dedup only ever
# proposes, and only for near-identical wording)
P="$(call experience '{"kind":"record","type":"lesson",
  "summary":"crawling glaze at cone six traces back to dust left on the pots",
  "situation":"when pots are handled between bisque and glazing",
  "verdict":"failed_avoid",
  "operation":"end-to-end-test"}')"
case "$P" in
    *duplicate_of*) fail "paraphrase-not-duplicate a genuine paraphrase was flagged — the corrected claim is false" ;;
    *'"stored":true'*) pass "paraphrase-not-duplicate a paraphrase in different words is admitted unflagged" ;;
    *) fail "paraphrase-not-duplicate unexpected record response" ;;
esac

# --- ingest-route-report: the memory-file ingest reports its routing -------------------
printf -- "---\nname: e2e-load-probe\ndescription: a probe note for the load report\ntype: reference\n---\nThe \`WidgetRenderer.paint()\` call fails on scale change.\n\n## Root cause:\n\nThe **native buffer** is sized before the scale factor arrives.\n" > "$WS/mem.md"
L="$(call experience "{\"kind\":\"load\",\"path\":\"$WS/mem.md\"}")"
case "$L" in
    *keywords_suppressed*) pass "ingest-route-report the load report carries the route/skip count" ;;
    *) fail "ingest-route-report NO ROUTE/SKIP REPORT from the ingest (v3.4.1 shape)" ;;
esac

# --- ingest-carries-the-form: a memory file DECLARING the form arrives as form-1 -------
# The probe above deliberately declares `reference`, which owes no situation, so it
# routes AROUND the form gate rather than through it. That leaves the ingest half of
# the gate — the second write surface — unexercised at the front door, which is where
# the C0 audit found the hole. This probe declares a `lesson` WITH its two required
# fields and proves the whole path: gate admits it, the situation is stored, and it
# comes back on a recall of the built product.
printf -- "---\nname: e2e-form-ingest\ndescription: size the native buffer only once the scale factor is known\ntype: lesson\nsituation: when a scale change arrives after the buffer is sized\nverdict: failed_avoid\n---\nSizing it earlier leaves the buffer wrong for the whole frame.\n" > "$WS/form.md"
FI="$(call experience "{\"kind\":\"load\",\"path\":\"$WS/form.md\"}")"
# The catch-all arm below used to be a bare `*)`, which passed on a transport
# error, an empty body, or any response that simply failed to mention refusal —
# an absence read as a success. It now demands positive evidence that the load
# actually ran: the report names the source it ingested.
case "$FI" in
    *form_refused*) fail "ingest-carries-the-form a well-formed lesson was REFUSED by the ingest gate: $(printf '%s' "$FI" | head -c 300)" ;;
    *'"loaded":1'*) pass "ingest-carries-the-form the ingest admits a lesson that declares situation and verdict" ;;
    *) fail "ingest-carries-the-form the load reported neither a refusal nor a loaded file — the ingest did not run: $(printf '%s' "$FI" | head -c 300)" ;;
esac
# Reachability is asserted by the PRINCIPLE's words, not the situation's, and the
# distinction is the whole sprint. Written first the other way round, this probe FAILED:
# querying the situation the file itself declares returned the entry only as an
# "In a similar situation" nominee, never as an answer. That is the defect the rescue
# exists to fix, reproduced at the front door.
#
# It is NOT asserted here, and the reason is worth stating precisely rather than
# gesturing at another gate. build/acceptance/ FREEZES the questions and their expected
# answers — a digest pin, so they cannot be quietly relaxed once a reading is in hand —
# but no committed gate READS them against a running store yet. The "0 of 5" recorded in
# AcceptanceFixtureTest's javadoc is a measurement taken by hand against the abandoned
# build, not a number any gate produces today. The gate that produces it is C1 clause 2,
# and until that exists this defect has a frozen instrument and no automated reading.
# What THIS probe owns is the ingest write surface Stage 0 added, and nothing more.
FR="$(call experience '{"kind":"recall","symptom":"size the native buffer once the scale factor is known","format":"text"}')"
case "$FR" in
    *"scale factor is known"*) pass "ingest-carries-the-form the ingested lesson round-trips to a recall of the built product" ;;
    *) fail "ingest-carries-the-form the ingested lesson did not come back: $(printf '%s' "$FR" | head -c 300)" ;;
esac

# The other half, and the one that makes the pair discriminating: an ingested lesson
# that declares NO situation must be refused, or the gate is not running on this
# surface at all and the admission above proves nothing.
printf -- "---\nname: e2e-form-ingest-bad\ndescription: an ingested lesson missing its form\ntype: lesson\n---\nThe buffer is sized before the scale factor arrives.\n" > "$WS/formbad.md"
FB="$(call experience "{\"kind\":\"load\",\"path\":\"$WS/formbad.md\"}")"
case "$FB" in
    *REPHRASE*) pass "ingest-carries-the-form an outcome-less lesson is refused AT THE INGEST too" ;;
    *) fail "ingest-carries-the-form THE FORM GATE IS NOT RUNNING ON THE INGEST: $(printf '%s' "$FB" | head -c 300)" ;;
esac

# ==================== 28b: the field lane, at the front door =================
# Sprint 28b's Stage-6 proof was run by hand and left NOTHING behind, so its
# claims would not exist at release time or in CI (28b closing audit, F9).
# These are that proof, as assertions. They belong here rather than in a unit
# test for the reason this whole script exists: the field lane's promise is
# about what LEAVES the product, and only the front door can see that.
FIELD_PILE="$WS/ws/field/pile.jsonl"    # the resident records under -data

# --- field-tool-answers: the /report seat's one front door replies -----------
FP="$(call field '{"action":"pile"}')"
case "$FP" in
    *'"shapes"'*'"shapeCount"'*) pass "field-tool-answers field(pile) answers with ranked shapes" ;;
    *) fail "field-tool-answers field(pile) did not answer: $(printf '%s' "$FP" | head -c 200)" ;;
esac
FS="$(call field '{"action":"silence"}')"
case "$FS" in
    *'"nudges"'*'"silenced"'*) pass "field-tool-answers field(silence) reports both switches" ;;
    *) fail "field-tool-answers field(silence) reported no switches: $(printf '%s' "$FS" | head -c 200)" ;;
esac

# --- field-answers-carry-no-path: the seat drafts a PUBLIC issue from these --
# (28b closing audit, F8: `pile` used to answer with the absolute pile path and
# `silence` with the state path, both carrying the user's account name.)
case "$FP$FS" in
    */home/*|*/Users/*|*"$WS"*)
        fail "field-answers-carry-no-path a field answer carries a filesystem path — the /report seat drafts a public issue body from exactly this" ;;
    *)  pass "field-answers-carry-no-path no field answer carries a filesystem path" ;;
esac

# --- field-records-failures: a failing call lands a shape WITH its error code -
# No project is loaded in this lifecycle, so the refusal is deterministic. The
# needle rides in the ARGUMENT: the failure must be recorded, the symbol must
# not be.
call analyze '{"kind":"type","typeName":"com.acme.SecretLedger"}' >/dev/null
if [ -f "$FIELD_PILE" ] \
        && grep -q '"tool":"analyze"[^}]*"ok":false[^}]*"code":"[A-Z_]\{2,\}"' "$FIELD_PILE"; then
    pass "field-records-failures the failed call is in the pile as a shape with an error CODE"
else
    fail "field-records-failures no failed-analyze shape with a code in the pile: $(tail -3 "$FIELD_PILE" 2>/dev/null | head -c 300)"
fi

# --- field-pile-carries-no-content: shapes, never content --------------------
# Four needles pushed in through four different inputs — a symbol argument, a
# path argument, free prose, and the bearer token this run authenticates with.
# The pile may carry that each call failed; it may carry none of these.
call load_project "{\"projectPath\":\"$WS/no-such-project-here\"}" >/dev/null
call experience '{"kind":"recall","symptom":"marzipan-barometer-needle","format":"text"}' >/dev/null
LEAKED="$(grep -oE "com\.acme\.SecretLedger|no-such-project-here|marzipan-barometer-needle|$TOKEN" \
    "$FIELD_PILE" 2>/dev/null | sort -u | tr '\n' ' ')"
if [ -z "$LEAKED" ]; then
    pass "field-pile-carries-no-content none of the leak needles reached the pile"
else
    fail "field-pile-carries-no-content THE PILE CARRIES CONTENT, not shapes: $LEAKED"
fi

# --- field-contract-header: the seam version rides the response --------------
# The hook refuses to inject under an unverified seam by comparing its own
# contract against this echo (D7). No header, no refusal — it would inject
# blind, which is the failure mode the typed mismatch exists to prevent.
CH="$(curl -s -D - -o /dev/null --max-time 60 -X POST "http://127.0.0.1:$PORT/mcp" \
    -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: e2e-$$" \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
         "params":{"name":"field","arguments":{"action":"pile"}}}' \
    | grep -i '^x-jawata-contract:' | tr -d '\r')"
if printf '%s' "$CH" | grep -qiE '^x-jawata-contract:[[:space:]]*[0-9]+$'; then
    pass "field-contract-header the response carries the seam version ($CH)"
else
    fail "field-contract-header no X-Jawata-Contract header on the response — the hook cannot detect a seam mismatch and would inject under an unverified contract"
fi

stop_resident

# ============================ lifecycle 2 ====================================
# Same store, fresh resident: the STARTUP RECONCILIATION must embed the
# restored rows to convergence — D5's second half, proven on the artifact.
start_resident

# Scoped PER LANE (C7 audit F2): the store's own "total" comes first in the
# stats payload, so an unscoped grep compares the entry lane's embedded count
# against the store total — cross-block and only coincidentally equal. Both
# lanes must close: D5's amended body says "per lane".
lane_closed() {   # lane_closed <stats-payload> <lane-name> -> 0 when embedded==total
    local block
    block="$(printf '%s' "$1" | grep -o "\"$2\":{[^}]*}")"
    [ -n "$block" ] || return 1
    local e t
    e="$(printf '%s' "$block" | grep -oE '"embedded":[0-9]+' | cut -d: -f2)"
    t="$(printf '%s' "$block" | grep -oE '"total":[0-9]+' | cut -d: -f2)"
    [ -n "$e" ] && [ -n "$t" ] && [ "$e" -eq "$t" ]
}
ENTRY_LANE=""; CONVERGED=""
for _ in $(seq 1 60); do
    S2="$(call experience '{"kind":"stats"}')"
    ENTRY_LANE="$(printf '%s' "$S2" | grep -o '"experience_entry":{[^}]*}')"
    ENTRY_TOT="$(printf '%s' "$ENTRY_LANE" | grep -oE '"total":[0-9]+' | cut -d: -f2)"
    if [ -n "$ENTRY_TOT" ] && [ "$ENTRY_TOT" -gt 0 ] \
            && lane_closed "$S2" "experience_entry" && lane_closed "$S2" "tool_experience"; then
        CONVERGED="yes"; break
    fi
    sleep 3
done
if [ -n "$CONVERGED" ]; then
    pass "backfill-closes-both-lanes the startup reconciliation converged, BOTH lanes ($ENTRY_LANE)"
else
    fail "backfill-closes-both-lanes the backfill never closed both lanes (entry lane: ${ENTRY_LANE:-absent})"
fi

# --- restored-found-by-meaning: fixture knowledge is reachable by MEANING ---------------------
M="$(call experience '{"kind":"recall",
  "symptom":"my sourdough fell in on itself after I let it rise for too long",
  "format":"text"}')"
case "$M" in
    *poke-test*|*sourdough*) pass "restored-found-by-meaning a fixture lesson is found by meaning after the restore" ;;
    *) fail "restored-found-by-meaning the restored fixture is invisible to meaning recall" ;;
esac
no_score "recall(meaning)" "$M"

# --- nonsense-never-vouched: nonsense produces NO VOUCHED ANSWER, nominees labelled --------
N="$(call experience '{"kind":"recall",
  "symptom":"the marzipan barometer forgot its velvet inventory",
  "format":"text"}')"
case "$N" in
    *'"result":"match"'*) fail "nonsense-never-vouched nonsense produced a VOUCHED answer" ;;
    *) pass "nonsense-never-vouched nonsense is never vouched" ;;
esac
case "$N" in
    *meaning-near*|*"shares distinctive wording"*|*analogy*)
        pass "nonsense-never-vouched whatever nonsense surfaces is labelled a nominee (basis in words)" ;;
    *) fail "nonsense-never-vouched nominees rendered without their basis labels" ;;
esac
no_score "recall(nonsense)" "$N"

# --- rejected-stays-gone: a rejected note stays gone BY MEANING -------------------------
G="$(call experience '{"kind":"recall",
  "symptom":"when in the lunar cycle is the right time to prune fruit trees",
  "format":"text"}')"
case "$G" in
    *"moon phase determines"*) fail "rejected-stays-gone the REJECTED note came back through the meaning path" ;;
    *) pass "rejected-stays-gone the rejected note stays gone by meaning" ;;
esac

# --- past-run-dispatch: dispatch rides recall — the seeded seat run is found ----------
DS="$(call experience '{"kind":"recall",
  "symptom":"how was the scheduler retry loop covered before its refactor",
  "format":"json"}')"
case "$DS" in
    *dispatch*) pass "past-run-dispatch the seeded seat run arrives dispatch-decorated" ;;
    *) fail "past-run-dispatch no dispatch decoration on the seat-run recall" ;;
esac
no_score "recall(dispatch)" "$DS"

# --- choke-gate: the LIVE warning cycle on a real project --------------------
# fire (a reverted refactor becomes a precedent) → warn (advisory steer,
# uncharged) → charge (the identity tier refuses an unjustified repeat) →
# pay (a written justification proceeds) → the outcome-after counter fills.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cp -r "$REPO_ROOT/org.jawata.core.tests/test-resources/sample-projects/compile-clean" "$WS/proj"
LP="$(call load_project "{\"projectPath\":\"$WS/proj\"}")"
case "$LP" in
    *'"success":true'*|*sourceFiles*|*packages*) pass "choke-gate a real project loads in the throwaway resident" ;;
    *) fail "choke-gate load_project failed: $(printf '%s' "$LP" | head -c 200)" ;;
esac

# pre-advice seeding: a prose lesson the refactor's pre-advice can reach
call experience '{"kind":"record","type":"lesson",
  "summary":"renaming a method that a test references by its string name breaks the test silently",
  "situation":"when renaming a method a test names as a string",
  "verdict":"failed_avoid",
  "operation":"end-to-end-test"}' >/dev/null

REN="$(call rename_symbol '{"symbol":"com.example.Clean#greet","newName":"salute"}')"
UNDO_ID="$(printf '%s' "$REN" | grep -oE '"undoChangeId":"[^"]+"' | head -1 | cut -d'"' -f4)"
if [ -n "$UNDO_ID" ]; then
    pass "choke-gate the rename ran and returned its undo handle"
else
    fail "choke-gate rename_symbol gave no undoChangeId: $(printf '%s' "$REN" | head -c 200)"
fi
UN="$(call refactoring "{\"action\":\"undo\",\"undoChangeId\":\"$UNDO_ID\"}")"
case "$UN" in
    *'"success":true'*) : ;;
    *) fail "choke-gate the undo itself failed: $(printf '%s' "$UN" | head -c 160)" ;;
esac
# the JDT model re-serves the restored member a moment after the undo — settle
for _ in $(seq 1 30); do
    call analyze '{"kind":"type","typeName":"com.example.Clean"}' | grep -q '"greet"' && break
    sleep 1
done

AN="$(call analyze '{"kind":"type","typeName":"com.example.Clean"}')"
case "$AN" in
    *'⚠ PRECEDENT'*) pass "choke-gate the IDENTITY tier WARNS on the reverted target (the steer that arms the charge; the call itself ran)" ;;
    *) fail "choke-gate no precedent warning surfaced after the revert" ;;
esac
# the ADVISORY tier is the different-target line (C7 audit F1 — it needs its
# OWN probe, not the identity warn wearing its name): a call on a target the
# precedent's situation does NOT contain retrieves it by meaning and renders
# it advisory-only.
AD="$(call analyze '{"kind":"type","typeName":"com.example.CleanSupport"}')"
case "$AD" in
    *'Similar past case'*) pass "choke-gate the ADVISORY tier speaks on a different target (advisory only, uncharged)" ;;
    *) fail "choke-gate the advisory tier never spoke on a meaning-near different target" ;;
esac
# arm the charge on the EXACT (tool, target) pair the repeat will use — the
# ledger is exact-match by design (a warning about the class does not tax the
# member); a read on the member surfaces the warning for that member.
call find_references '{"kind":"references","symbol":"com.example.Clean#greet"}' >/dev/null

R2="$(call rename_symbol '{"symbol":"com.example.Clean#greet","newName":"salute2"}')"
case "$R2" in
    *precedentOverride*) pass "choke-gate the identity tier CHARGES an unjustified repeat (and names the payment)" ;;
    *'"success":true'*) fail "choke-gate the repeat ran uncharged — the justification-cost is words only" ;;
    *) fail "choke-gate unexpected charge response: $(printf '%s' "$R2" | head -c 200)" ;;
esac

R3="$(call rename_symbol '{"symbol":"com.example.Clean#greet","newName":"salute2",
  "precedentOverride":"the earlier undo was an experiment; this rename is intended"}')"
case "$R3" in
    *filesModified*|*'"success":true'*) pass "choke-gate a written justification PAYS the cost — the call proceeds" ;;
    *) fail "choke-gate the paid call did not proceed: $(printf '%s' "$R3" | head -c 200)" ;;
esac
call compile_workspace '{}' >/dev/null    # the gate call that classifies the outcome

# the pre-advice surface consults on the PLAN flow (its wired home)
call refactoring "{\"action\":\"plan\",\"kind\":\"compose_method\",
  \"filePath\":\"$WS/proj/src/main/java/com/example/Clean.java\",
  \"sections\":[{\"startLine\":18,\"startColumn\":8,\"endLine\":18,\"endColumn\":40,\"methodName\":\"partOne\"},
                {\"startLine\":22,\"startColumn\":8,\"endLine\":22,\"endColumn\":25,\"methodName\":\"partTwo\"}]}" >/dev/null

SQ="$(call experience '{"kind":"stats"}')"
FIRED_BLOCK="$(printf '%s' "$SQ" | grep -o '"recalls_fired":{[^}]*}')"
case "$FIRED_BLOCK" in
    *choke_*) pass "choke-gate a choke surface FIRED into the quality counters (not merely consulted)" ;;
    *) fail "choke-gate no choke surface fired through the whole cycle (fired block: ${FIRED_BLOCK:-absent})" ;;
esac
case "$SQ" in
    *pre_advice*) pass "choke-gate the pre-advice surface was consulted (counter present)" ;;
    *) fail "choke-gate the pre-advice surface never consulted" ;;
esac
case "$SQ" in
    *'outcome_after":{"'*) pass "choke-gate the outcome-after counter FILLS — the cycle closes" ;;
    *) fail "choke-gate the warning cycle never closed (outcome_after empty)" ;;
esac

# --- two-stage delivery: NOT IN THE RESCUE -----------------------------------
# The abandoned branch probed a fetch/dispose chain and a frozen code slice here.
# Snippets, the advice journal and the fetch verb are out of this sprint by the
# rescue plan, so those checks are removed rather than left to fail. Stated here
# rather than silently deleted: a missing probe should say why it is missing.

stop_resident

# ======================= lifecycle 2b: THE UPGRADE PATH ======================
# Sprint 28 outcome audit F6. Everything above enters through the IMPORT path,
# so every row lands at the CURRENT schema — restore + backfill were proven,
# an UPGRADE never was, and that is the half three of the four v3.4.0 defects
# lived in. This store was written by the RELEASED v3.3.1 — pre-embeddings:
# no vectors, no quality-counter tables, the old schema on disk — and holds
# four invented entries (kiln, tides, sourdough-starter, telescope). The
# committed file is copied before use and hash-checked pristine after; the
# resident works on the copy only.
OLD_SRC="$(cd "$(dirname "$0")" && pwd)/e2e-fixture/old-store-v3.3.1/experience.mv.db"
[ -f "$OLD_SRC" ] || { echo "no old-schema store at $OLD_SRC" >&2; exit 2; }
OLD_SHA_BEFORE="$(sha256sum "$OLD_SRC" | cut -d' ' -f1)"
MAIN_STORE="$STORE"
STORE="$(mktemp -d)"
cp "$OLD_SRC" "$STORE/experience.mv.db"
start_resident

# --- upgrade-rows-survive: the old rows are still there after the migration --
UP="$(call experience '{"kind":"stats"}')"
UP_TOT="$(printf '%s' "$UP" | grep -o '"experience_entry":{[^}]*}' | grep -oE '"total":[0-9]+' | cut -d: -f2)"
if [ "${UP_TOT:-0}" -ge 4 ]; then
    pass "upgrade-rows-survive the v3.3.1 rows opened at the current schema (total=$UP_TOT)"
else
    fail "upgrade-rows-survive expected the 4 old-schema rows, stats says: ${UP_TOT:-none}"
fi

# --- upgrade-earns-vectors: rows written before embeddings existed get them --
UPCONV=""
for _ in $(seq 1 60); do
    UP="$(call experience '{"kind":"stats"}')"
    if lane_closed "$UP" "experience_entry"; then UPCONV="yes"; break; fi
    sleep 3
done
if [ -n "$UPCONV" ]; then
    pass "upgrade-earns-vectors backfill embedded every pre-embedding row"
else
    fail "upgrade-earns-vectors rows written before embeddings never earned vectors"
fi

# --- upgrade-found-by-meaning: an OLD row answers a paraphrase sharing no words
UPM="$(call experience '{"kind":"recall",
  "symptom":"why did my pottery oven shelving bend after rapid chilling from peak heat",
  "format":"text"}')"
case "$UPM" in
    *kiln*|*warps*|*quartz*) pass "upgrade-found-by-meaning a v3.3.1 row is findable by MEANING after upgrade" ;;
    *) fail "upgrade-found-by-meaning THE UPGRADED STORE IS INVISIBLE TO MEANING RECALL: $(printf '%s' "$UPM" | head -c 200)" ;;
esac
no_score "recall(upgraded)" "$UPM"

# --- upgrade-counters-present: the counter tables an old install never had ---
case "$UPM$UP" in
    *unavailable*) fail "upgrade-counters-present the quality-counter table is missing on the upgraded store" ;;
    *) pass "upgrade-counters-present the upgraded store carries the counter tables it was born without" ;;
esac

# --- upgrade-fixture-pristine: the committed slice was never touched ---------
OLD_SHA_AFTER="$(sha256sum "$OLD_SRC" | cut -d' ' -f1)"
if [ "$OLD_SHA_BEFORE" = "$OLD_SHA_AFTER" ]; then
    pass "upgrade-fixture-pristine the committed v3.3.1 slice is byte-identical after the run"
else
    fail "upgrade-fixture-pristine THE GATE MUTATED ITS OWN FIXTURE"
fi

stop_resident
rm -rf "$STORE"
STORE="$MAIN_STORE"

# ============================ lifecycle 3 ====================================
# Embedder DISABLED: the degrade contract — the store still answers by WORDS
# (D9), and the write-side gates hold without any model.
start_resident -Djawata.embed.disabled=true

H3="$(call health_check '{}')"
case "$H3" in
    *'"available":false'*|*'"available": false'*)
        pass "degrade-is-honest the resident is honestly degraded (embedder off)" ;;
    *) fail "degrade-is-honest the disable switch did not take" ;;
esac

W="$(call experience '{"kind":"recall",
  "symptom":"why did my cone-six glaze crawl on the bisque",
  "format":"text"}')"
case "$W" in
    *crawl*|*cone-six*|*bisque*) pass "words-only-answers with the embedder OFF the store answers a prose question by WORDS" ;;
    *) fail "words-only-answers KEYWORD-ONLY DEGRADE CANNOT ANSWER PROSE (v3.4.1 shape)" ;;
esac
no_score "recall(words-only)" "$W"

# the FULL degrade pass (C7 audit F3): the recall-honesty checks re-run
# words-only — the contract holds without any model, not just the happy path.
N3="$(call experience '{"kind":"recall",
  "symptom":"the marzipan barometer forgot its velvet inventory",
  "format":"text"}')"
case "$N3" in
    *'"result":"match"'*) fail "nonsense-never-vouched-degraded nonsense got VOUCHED with the embedder off" ;;
    *) pass "nonsense-never-vouched-degraded nonsense is never vouched, words-only included" ;;
esac
G3="$(call experience '{"kind":"recall",
  "symptom":"when in the lunar cycle is the right time to prune fruit trees",
  "format":"text"}')"
case "$G3" in
    *"moon phase determines"*) fail "rejected-stays-gone-degraded the rejected note returned through the WORD path" ;;
    *) pass "rejected-stays-gone-degraded the rejected note stays gone by words too" ;;
esac

# Same discipline as the embedder-on probe above: give it a valid form so the
# only thing left wrong is the flag standing as a symptom.
A3="$(call experience '{"kind":"record","type":"lesson",
  "summary":"another lesson about ordering notes",
  "situation":"when a preview flag is needed to reproduce",
  "verdict":"worked",
  "symptoms":["--enable-preview"]}')"
case "$A3" in
    *"WHERE IT BELONGS"*) pass "admission-gate-degraded the admission gate holds with no embedder" ;;
    *) fail "admission-gate-degraded the admission gate needs the embedder (it must not)" ;;
esac

stop_resident

# --- the fixture is PRISTINE ------------------------------------------------
FIXTURE_SHA_AFTER="$(sha256sum "$FIXTURE" | cut -d' ' -f1)"
if [ "$FIXTURE_SHA_BEFORE" = "$FIXTURE_SHA_AFTER" ]; then
    pass "fixture-import the committed fixture is byte-identical after the run"
else
    fail "fixture-import THE RUN MUTATED THE COMMITTED FIXTURE"
fi

echo "end-to-end test: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ] || exit 1
