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
# EXIT alone is not enough: a shell killed by a signal can exit without running
# it, and the resident it started outlives the run. One leaked for two days and
# was found only because it still held the port a later probe wanted.
trap cleanup EXIT INT TERM HUP

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

# --- review-briefs-a-draft: the cold reader reaches the front door ----------
# Sprint 28c. The prompt existed for two sprints with NO production caller and sat
# in the unwired baseline saying so, while the template described the review as a
# working gate. This asserts the brief a real caller receives — because a unit test
# on an unwired component is exactly what let that stand.
RV="$(call experience '{"kind":"review","type":"lesson",
  "summary":"An amend always carries the TOTAL order size.",
  "situation":"I have a partial fill and want to move the limit - what goes in the quantity?",
  "verdict":"failed_avoid","symptoms":["fewer shares filled than intended"]}')"
case "$RV" in
    *'"formGate"'*'admitted'*) pass "review-briefs-a-draft the form gate reports on the draft" ;;
    *) fail "review-briefs-a-draft no form-gate verdict: $(printf '%s' "$RV" | head -c 200)" ;;
esac
# All four questions, the candidate, and an answer the caller can parse. Drop any
# one and the disagreement gate that keeps the human out of the loop cannot fire.
MISSING=""
for NEED in "WHICH KIND" "WHEN does this apply" "DO DIFFERENTLY" "RIGHT WIDTH" \
            "partial fill" "TOTAL order size" "failed_avoid" "VERDICT: keep"; do
    case "$RV" in *"$NEED"*) ;; *) MISSING="$MISSING [$NEED]" ;; esac
done
if [ -z "$MISSING" ]; then
    pass "review-briefs-a-draft the brief carries four questions, the candidate and a verdict line"
else
    fail "review-briefs-a-draft the brief is missing:$MISSING"
fi
# A SHAPE refusal is not a verdict on the story, so the brief is still produced and
# the caller decides. Withholding it would make the gate judge for the reader.
RB="$(call experience '{"kind":"review","type":"lesson","summary":"Test plan",
  "situation":"the build says success - did it run the tests?","verdict":"worked"}')"
case "$RB" in
    *'"formGate"'*'REFUSED'*'"prompt"'*)
        pass "review-briefs-a-draft a gate-refused draft is still briefed for the reader" ;;
    *) fail "review-briefs-a-draft a refused draft lost its brief: $(printf '%s' "$RB" | head -c 200)" ;;
esac

# --- catalogue-answers-design: D7's half that can fail silently -------------
# Sprint 28c D7. The seat text can say "consult the catalogue" and the store can
# still have nothing to give — that is the built-but-unreached shape, and only a
# real ask through the front door tells the two apart.
#
# The five questions are READ FROM the frozen fixture, never copied into this
# file: a hand-copied list of a live surface drifts, and the drift is invisible
# because both copies still look right. Frozen 2026-08-22, BEFORE the extractor
# existed, and none of them names its own pattern — a question naming its pattern
# would be answerable by string matching and would prove nothing.
#
# The assertion is D7's own wording: the fitting pattern is RETURNED. Where it
# ranked is printed rather than gated, because a rank threshold here would be a
# number I invented tonight rather than one anybody agreed.
CATQ="$(cd "$(dirname "$0")" && pwd)/acceptance/catalogue-questions.json"
if [ ! -f "$CATQ" ]; then
    fail "catalogue-answers-design the frozen question fixture is missing at $CATQ"
else
    CAT_OUT="$(python3 - "$CATQ" "http://127.0.0.1:$PORT/mcp" "$TOKEN" <<'EOF_CAT'
import json, subprocess, sys
fixture, url, token = sys.argv[1], sys.argv[2], sys.argv[3]
def call(args):
    body = json.dumps({"jsonrpc":"2.0","id":1,"method":"tools/call",
                       "params":{"name":"experience","arguments":args}})
    out = subprocess.run(["curl","-s","--max-time","120","-X","POST",url,
        "-H",f"Authorization: Bearer {token}","-H","Mcp-Session-Id: e2e-catalogue",
        "-H","Content-Type: application/json","-d",body], capture_output=True, text=True).stdout
    try:
        return json.loads(json.loads(out)["result"]["content"][0]["text"]).get("data", {})
    except Exception as e:
        return {"_broke": str(e)}
qs = json.load(open(fixture))["questions"]
missed, ranks = [], []
for q in qs:
    d = call({"kind":"nominate","question":q["question"]})
    cands = d.get("candidates") or []
    at = None
    for i, c in enumerate(cands):
        if q["expect_slug"] in str(c.get("address") or ""):
            at = i + 1
            break
    if at is None:
        missed.append(q["expect_slug"])
    else:
        ranks.append(f'{q["expect_slug"]}@{at}')
print(f'{len(qs) - len(missed)}/{len(qs)}|{" ".join(ranks)}|{" ".join(missed)}')
EOF_CAT
)"
    CAT_SCORE="${CAT_OUT%%|*}"; CAT_REST="${CAT_OUT#*|}"
    CAT_RANKS="${CAT_REST%%|*}"; CAT_MISSED="${CAT_REST#*|}"
    case "$CAT_SCORE" in
        5/5) pass "catalogue-answers-design all 5 frozen design questions return their pattern (ranks: $CAT_RANKS)" ;;
        *)   fail "catalogue-answers-design only $CAT_SCORE frozen questions returned their pattern; missing: $CAT_MISSED" ;;
    esac
    # D7's report owes an address the reader can OPEN, and the seat is told to read
    # it off the entry rather than compose one. That is only possible if the entry
    # carries it — the candidate map used to carry id, situation, principle, outcome
    # and scores, and nothing that locates anything.
    CADDR="$(call experience '{"kind":"nominate","question":"one of the services we depend on keeps going unresponsive and our retries are dragging the rest of the system down with it"}')"
    case "$CADDR" in
        *'"address":"catalogue:java-design-patterns/'*)
            pass "catalogue-answers-design a nominated pattern carries an openable canonical address" ;;
        *) fail "catalogue-answers-design no address on the nomination — the seat would have to invent one: $(printf '%s' "$CADDR" | head -c 300)" ;;
    esac
fi

# --- catalogue-namespaces: 28d Stage 2, through the front door ---------------
# Sprint 28d. A single global catalogue count cannot answer "WHICH catalogue is
# empty?" — and that is the question a degradation line has to answer the moment
# there is more than one source. The registry reports per namespace, and every
# REGISTERED namespace appears even when it holds nothing, because an absent key
# and a zero read identically to anyone parsing the answer.
NSTATS="$(call experience '{"kind":"stats"}')"
case "$NSTATS" in
    *'"byNamespace"'*)
        case "$NSTATS" in
            *'"java-design-patterns"'*)
                pass "catalogue-namespaces stats names each catalogue namespace, not one global total" ;;
            *) fail "catalogue-namespaces byNamespace is present but names no registered namespace: $(printf '%s' "$NSTATS" | head -c 300)" ;;
        esac ;;
    *) fail "catalogue-namespaces stats carries no per-namespace catalogue block — a reader cannot tell WHICH source is absent: $(printf '%s' "$NSTATS" | head -c 300)" ;;
esac

# --- own-samples-seeded: 28d Stage 3, the second source through the front door
# The fork covers the patterns it covers; cures it has no module for get their
# specimens in org.jawata.samples — built so the address cannot rot, absent from
# this dist, public so a reader can open it. This asserts the SECOND source
# reached the store on the same boot as the first, because a registry with one
# working source and one silently dead one looks identical from a total count.
# REGISTERED and SEEDED are different claims, and the count is what separates
# them: a source can be in the registry and have written nothing, which is
# exactly the silently-dead-second-source case this promise exists to catch.
case "$NSTATS" in
    *'"jawata-samples":0'*)
        fail "own-samples-seeded the specimen namespace is registered but EMPTY — the source is wired and wrote nothing, which a total count cannot distinguish from working: $(printf '%s' "$NSTATS" | head -c 300)" ;;
    *'"jawata-samples"'*)
        pass "own-samples-seeded the own-authored specimen source seeded its own namespace on a real boot" ;;
    *) fail "own-samples-seeded no jawata-samples namespace in stats — the second source did not reach the registry at all: $(printf '%s' "$NSTATS" | head -c 300)" ;;
esac

# --- samples-address-opens: 28d D10's last mile -----------------------------
# The promise above proves the specimen source WROTE rows. That is not D10's
# promise. D10 says a design question returns a samples-lane address AND that
# the address OPENS — two different failures, because a row can be seeded, be
# returned, carry a perfectly-formed address, and point at nothing.
#
# That exact shape was LIVE here on 2026-08-28: the samples module sat outside
# the analysis model, so "does the address open?" was VACUOUS rather than
# failing — 0 of 4 specimen types resolved and no check complained. A promise
# that cannot fail is the thing this whole script exists to refuse.
#
# "Opens" is checked against the CHECKOUT, not the dist, and that is deliberate
# rather than a shortcut: the samples module is excluded from the shipped
# artifact ON PURPOSE (D10's byte-identical clause), so the file is not in the
# dist to read. The product's job is to hand out an address that locates a real
# file in the repository a reader would open; this script runs FROM that
# repository, so it is the one place both halves are visible at once.
#
# The question below paraphrases the specimen's own "when to use" wording and
# names no pattern — but it was authored the same day as the specimen, by the
# same hand, so it proves the lane is REACHABLE and says nothing about
# retrieval quality. The frozen five-question fixture above is the instrument
# for that, and it predates the extractor for precisely this reason.
SAMPLE_Q="one method here validates the input, does the arithmetic and builds the output text in a single pass, and the shape of what it does cannot be seen without reading every mechanical detail"
SNOM="$(call experience "{\"kind\":\"nominate\",\"question\":\"$SAMPLE_Q\"}")"
SREF="$(printf '%s' "$SNOM" | grep -oE '"address":"catalogue:jawata-samples/[^"]+"' | head -1 | cut -d'"' -f4)"
if [ -z "$SREF" ]; then
    fail "samples-address-opens a design question returned no catalogue:jawata-samples/ address at all — the specimen lane is seeded but unreachable by asking: $(printf '%s' "$SNOM" | head -c 300)"
else
    pass "samples-address-opens a design question returns a specimen address ($SREF)"
    # The repository root, from the script's own location — never the caller's
    # working directory, which is not reliably this checkout.
    REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
    # The samples origin's workspace root, spelled once here. If CatalogueSources
    # ever renames it, THIS LINE is part of that change — the failure below says
    # which path it tried, so the fix is one edit rather than an investigation.
    SAMPLE_FILE="$REPO_ROOT/org.jawata.samples/${SREF#catalogue:jawata-samples/}"
    if [ -f "$SAMPLE_FILE" ]; then
        pass "samples-address-opens and the address OPENS: $SAMPLE_FILE"
    else
        fail "samples-address-opens THE ADDRESS DOES NOT OPEN. The product handed out
          $SREF
          which resolves to $SAMPLE_FILE — and there is no file there. A cure that
          resolves as a live row and points at nothing audits CLEAN while the reader
          who follows it finds nothing."
    fi
    # The control. Without it a green above is the same output whether the
    # resolution checked carefully or looked somewhere everything happens to
    # exist — and this project has shipped the second kind.
    if [ -f "$REPO_ROOT/org.jawata.samples/no-such-slug-exists/README.md" ]; then
        fail "samples-address-opens the resolution reports a MISSING file as present, so the check above could never fail"
    else
        pass "samples-address-opens the resolution can tell a missing file from a present one"
    fi
fi

# --- demand-and-delete: D14's ledger and the undo a delete owes -------------
# Sprint 28c D14. Two promises the review seat depends on, through the real front
# door. The FIRST is the one that carries the wiring: a question nobody could
# answer must still be recorded, because demand with no supply is the writing
# backlog — and "no candidates, nothing to count, skip it" is the optimisation
# that deletes exactly that signal.
DQ="a question about nothing this store has ever heard of, zylophantic breeb"
NOM="$(call experience "{\"kind\":\"nominate\",\"question\":\"$DQ\"}")"
case "$NOM" in
    *'"query_id"'*) pass "demand-and-delete a nomination returns a query id" ;;
    *) fail "demand-and-delete no query_id: $(printf '%s' "$NOM" | head -c 200)" ;;
esac
SWEEP="$(call experience '{"kind":"review_sweep","min_times":1,"min_shown":1}')"
case "$SWEEP" in
    *"zylophantic breeb"*)
        pass "demand-and-delete an unanswered question reaches the writing backlog" ;;
    *) fail "demand-and-delete the backlog lost the unanswered question: $(printf '%s' "$SWEEP" | head -c 300)" ;;
esac
case "$SWEEP" in
    *'"deletionList"'*'"writingBacklog"'*'"droppedWrites"'*)
        pass "demand-and-delete the sweep carries both lists and its own dropped-write count" ;;
    *) fail "demand-and-delete the sweep is missing a list or the drop count: $(printf '%s' "$SWEEP" | head -c 300)" ;;
esac
# A count that is PRESENT and unreadable is refused, never defaulted: defaulting
# would return lists computed at thresholds nobody chose, which read as facts.
BADC="$(call experience '{"kind":"review_sweep","min_times":"not-a-number"}')"
case "$BADC" in
    *min_times*) pass "demand-and-delete an unreadable threshold is refused by name" ;;
    *) fail "demand-and-delete an unreadable threshold was silently defaulted: $(printf '%s' "$BADC" | head -c 200)" ;;
esac
# The SECOND promise: a delete writes its undo before it removes anything. The
# assertion is that the archive CONTAINS the entry — an archive written after the
# delete parses, is named correctly, sits where the response says, and is empty.
DID="$(call experience '{"kind":"record","type":"lesson",
  "summary":"A zylophantic breeb is filed under nothing at all.",
  "situation":"when a delete must prove it archived before it removed",
  "verdict":"worked"}' | sed -n 's/.*"id"[^"]*"\([^"]*\)".*/\1/p' | head -1)"
if [ -n "$DID" ]; then
    DEL="$(call experience "{\"kind\":\"delete\",\"ids\":[\"$DID\"]}")"
    ARCH="$(printf '%s' "$DEL" | sed -n 's/.*"archive"[^"]*"\([^"]*\)".*/\1/p' | head -1)"
    if [ -n "$ARCH" ] && [ -f "$ARCH" ] && grep -q "zylophantic breeb is filed" "$ARCH"; then
        pass "demand-and-delete the pre-delete archive contains what was removed"
    else
        fail "demand-and-delete no usable archive at '$ARCH' — the delete has no undo"
    fi
else
    fail "demand-and-delete could not record the entry the delete promise needs"
fi

# --- repair-through-the-front-door: Stage 15's set_form, gated ---------------
# Sprint 28c Stage 15. The store could diagnose a badly-formed entry and not fix
# one: setForm had three references and no tool verb. These promises prove the
# repair verb IS the front door's — and that the gate stands at this door too,
# because a repair path that admits what record refuses is how the store filled
# with headings the first time.
RID_JSON="$(call experience '{"kind":"record","type":"lesson","summary":"the quokka ledger loses a fill when the amend races the cancel","situation":"when by construction","verdict":"failed_avoid"}')"
RID="$(printf '%s' "$RID_JSON" | sed -n 's/.*"id" *: *"\([0-9a-f-]*\)".*/\1/p' | head -1)"
if [ -n "$RID" ]; then
    pass "repair-front-door a poorly-formed lesson is in the store to repair"
else
    fail "repair-front-door could not seed the repair target: $(printf '%s' "$RID_JSON" | head -c 200)"
fi
FIX="$(call experience "{\"kind\":\"set_form\",\"id\":\"$RID\",\"situation\":\"when an amend and a cancel race on the same quokka ledger slot\"}")"
case "$FIX" in
    *'"seat_rewritten"'*) pass "repair-front-door set_form rewrites and stamps seat_rewritten" ;;
    *) fail "repair-front-door set_form did not rewrite: $(printf '%s' "$FIX" | head -c 300)" ;;
esac
FOUND="$(call experience '{"kind":"nominate","question":"what happens when an amend races a cancel on a quokka ledger slot"}')"
case "$FOUND" in
    *"$RID"*) pass "repair-front-door the rewritten entry answers by its NEW situation" ;;
    *) fail "repair-front-door the new situation does not surface the entry: $(printf '%s' "$FOUND" | head -c 300)" ;;
esac
BADFIX="$(call experience "{\"kind\":\"set_form\",\"id\":\"$RID\",\"situation\":\"docs/sprints/sprint-28c.md\"}")"
case "$BADFIX" in
    *"WHEN an entry applies"*) pass "repair-front-door a location-shaped repair is refused with the teaching text" ;;
    *) fail "repair-front-door a location-shaped situation was admitted: $(printf '%s' "$BADFIX" | head -c 300)" ;;
esac
QSWEEP="$(call experience '{"kind":"review_sweep"}')"
case "$QSWEEP" in
    *'"quality"'*'"findingsTotal"'*) pass "repair-front-door the sweep carries the quality lane beside usage" ;;
    *) fail "repair-front-door the quality lane is missing from the sweep: $(printf '%s' "$QSWEEP" | head -c 300)" ;;
esac

# --- origin-attribution: v13's stamp rides the tap, proven by its READER -----
# Sprint 28c D14 (v13). The record above (repair-front-door) reached the store
# through the REAL application wiring — protocol handler, EventTap, stamper.
# This session never sent an initialize, so ClientDirectory knows nothing about
# it and the honest stamp is 'unknown'. That value is the discriminator: a
# stamper that never ran leaves NULL, and a NULL row appears in no group — so
# an 'unknown' bucket with a count IS the proof the wiring fired end to end.
OSTATS="$(call experience '{"kind":"stats"}')"
case "$OSTATS" in
    *'"by_origin_client"'*'"unknown"'*)
        pass "origin-attribution a front-door record is stamped and countable by its recording client" ;;
    *) fail "origin-attribution no unknown bucket after a front-door record — the stamper never ran: $(printf '%s' "$OSTATS" | head -c 300)" ;;
esac

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
# THE BUDGET TRACKS THE WORK, and the work changed. Sprint 28c (v11) embeds a
# row FOUR times — the composite plus three per-field lanes — so this store's
# ~244 rows now cost ~976 embeddings where they cost ~244. The old 180 s budget
# was set against the one-vector cost and this check began failing at the
# arithmetic, not at a defect. Widened to match, and made to report PROGRESS so
# that a stall and a slow run are different findings rather than one timeout.
ENTRY_LANE=""; CONVERGED=""; SEEN=-1
for _ in $(seq 1 150); do
    S2="$(call experience '{"kind":"stats"}')"
    ENTRY_LANE="$(printf '%s' "$S2" | grep -o '"experience_entry":{[^}]*}')"
    ENTRY_TOT="$(printf '%s' "$ENTRY_LANE" | grep -oE '"total":[0-9]+' | cut -d: -f2)"
    if [ -n "$ENTRY_TOT" ] && [ "$ENTRY_TOT" -gt 0 ] \
            && lane_closed "$S2" "experience_entry" && lane_closed "$S2" "tool_experience"; then
        CONVERGED="yes"; break
    fi
    NOW="$(printf '%s' "$ENTRY_LANE" | grep -oE '"embedded":[0-9]+' | cut -d: -f2)"
    if [ "${NOW:-0}" != "$SEEN" ]; then
        SEEN="${NOW:-0}"
        printf '    ... backfill embedded %s of %s\n' "$SEEN" "${ENTRY_TOT:-?}"
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

# --- anchorless: the two-step lane, through the real front door -------------------------
# Sprint 28c D2. The question carries NO symbol, package or operation — the case the
# store could not serve, where the old path returned the maximum eleven candidates for
# each of seven nonsense questions. The claims asserted here are the design itself:
# nominating is not answering, and choosing nothing is a real answer.
NOM="$(call experience '{"kind":"nominate",
  "question":"the marzipan barometer forgot its velvet inventory"}')"
case "$NOM" in
    *'"result":"match"'*)
        fail "anchorless nominate returned a MATCH — ranking must never vouch: $(printf '%s' "$NOM" | head -c 200)" ;;
    *'"result":"nominated"'*)
        pass "anchorless nominate ranks candidates and does not call it a match" ;;
    *) fail "anchorless nominate produced no nomination: $(printf '%s' "$NOM" | head -c 200)" ;;
esac

QID="$(printf '%s' "$NOM" | sed -n 's/.*"query_id":"\([^"]*\)".*/\1/p')"
case "$QID" in
    "") fail "anchorless nominate returned no query_id, so nothing could be decided against it" ;;
    *)  pass "anchorless the nomination carries a query_id to decide against" ;;
esac

# Sprint 28c D13 — WHY THIS ONE. A ranking nobody can interrogate is a ranking
# nobody can correct: this sprint lost a day to a regression whose cause was one
# lane reading a different field set from its twin, and no response carried enough
# to see it. Every candidate now states the four proximities that placed it, over
# the wire and not only in a unit test. All FIVE keys are demanded — a response
# carrying `total` alone would satisfy a laxer check while saying nothing about
# which lane decided, which is the whole point.
#
# Matched as the exact key SEQUENCE the merge emits, not as five loose key names:
# "situation", "summary" and "details" are already candidate fields, so a loose
# check would pass on a response with no scores block at all.
case "$NOM" in
    *'"count":0'*)
        fail "anchorless the nonsense question produced NO candidates, so the scores"\
" clause could not be measured — nothing here filters on score, so an empty list is"\
" itself a finding" ;;
    *'"scores":{"situation":'*'"summary":'*'"details":'*'"words":'*'"total":'*)
        pass "anchorless every candidate carries the four lane scores and the total that placed it" ;;
    *'"scores"'*)
        fail "anchorless a scores block is present but not the four lanes plus the total: $(printf '%s' "$NOM" | head -c 300)" ;;
    *)
        fail "anchorless no candidate carried a scores block, so the ranking cannot be interrogated: $(printf '%s' "$NOM" | head -c 250)" ;;
esac

# THE HEADLINE: an empty selection on a nonsense question is an ABSENCE, with no
# entries and no consolation pile. This is the measured defect, inverted.
DEC="$(call experience "{\"kind\":\"decide\",\"query_id\":\"$QID\",\"selected_ids\":[]}")"
# The absence must be THE DECISION'S absence. Asserting only result=absence is not
# discriminating: recall answers a cue-less call with an absence too, so a decide
# verb bypassed to plain recall passed this arm. The decision echoes the QUESTION
# back, which nothing else on the answer surface does.
case "$DEC" in
    *'"result":"absence"'*marzipan*|*marzipan*'"result":"absence"'*)
        pass "anchorless choosing none of the candidates yields an ABSENCE for THAT question" ;;
    *) fail "anchorless an empty selection did not produce the decision's own absence: $(printf '%s' "$DEC" | head -c 250)" ;;
esac
case "$DEC" in
    *'"count":0'*) pass "anchorless the absence carries zero entries" ;;
    *) fail "anchorless the absence carried entries: $(printf '%s' "$DEC" | head -c 250)" ;;
esac

# And the door the query_id exists to close: an id the nomination never offered is
# refused, so a caller cannot vouch for an arbitrary entry through the decide verb.
#
# A FRESH nomination, because deciding CONSUMES one — the probe above already spent
# $QID. Reusing it here tested the already-decided refusal instead, which is a real
# behaviour but not this one; the tightened assertion is what surfaced the mix-up.
NOM2="$(call experience '{"kind":"nominate",
  "question":"the marzipan barometer forgot its velvet inventory"}')"
QID2="$(printf '%s' "$NOM2" | sed -n 's/.*"query_id":"\([^"]*\)".*/\1/p')"
BAD="$(call experience "{\"kind\":\"decide\",\"query_id\":\"$QID2\",\"selected_ids\":[\"not-a-candidate\"]}")"
# Positive evidence only. A bare catch-all here would pass on a transport error,
# on an empty body, and — measured — on the decide verb bypassed to plain recall.
case "$BAD" in
    *not-a-candidate*)
        pass "anchorless an id the nomination never offered is refused, and named" ;;
    *) fail "anchorless the refusal did not name the un-nominated id, so nothing proves it was checked: $(printf '%s' "$BAD" | head -c 250)" ;;
esac

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
# Sprint 28d, for the cure-resolves promise below: compile-clean is clean by
# design, so it carries no OCP trace to cure. The gate builds its OWN target
# rather than hunting the repository for a class that happens to violate
# something — a gate that depends on somebody else's code failing is a gate
# that breaks when they fix it. A type-code constant group AND a switch over
# it: two traces, two findings, both cured by the same catalogue designs.
cat > "$WS/proj/src/main/java/com/example/OcpTarget.java" << 'EOF_OCP'
package com.example;

/** Deliberately closed for extension: a new status must EDIT both members. */
public class OcpTarget {
    public static final int STATUS_NEW = 0;
    public static final int STATUS_OPEN = 1;
    public static final int STATUS_CLOSED = 2;
    public static final int STATUS_VOID = 3;

    public String describe(int status) {
        switch (status) {
            case STATUS_NEW: return "new";
            case STATUS_OPEN: return "open";
            case STATUS_CLOSED: return "closed";
            default: return "void";
        }
    }
}
EOF_OCP
# Sprint 28d Stage 7 (S7.5): the Extract Class target. Written BEFORE load_project
# so the model sees it — a class added afterwards may or may not be picked up, and
# a promise that depends on a refresh race is a promise that fails for the wrong
# reason. The field pair {street, city} travels together; quantity does not, which
# is what makes the extraction a choice rather than "move everything".
cat > "$WS/proj/src/main/java/com/example/ExtractTarget.java" << 'EOF_EXTRACT'
package com.example;

/** A field group that travels together — the shape Extract Class moves out. */
public class ExtractTarget {
    private String street;
    private String city;
    private int quantity;

    public ExtractTarget(String street, String city, int quantity) {
        this.street = street;
        this.city = city;
        this.quantity = quantity;
    }

    public String label() {
        return street + ", " + city + " x" + quantity;
    }
}
EOF_EXTRACT
# 28d Stage 8 (S8.6): the rank-3 target, written BEFORE load_project for the same
# reason. A constructor plus a call site in a SECOND file — the call site is the
# point, since Replace Constructor with Factory Method exists to rewrite it, and a
# rewrite inside the declaring file would prove much less.
cat > "$WS/proj/src/main/java/com/example/FactoryTarget.java" << 'EOF_FACTORY'
package com.example;

/** The constructor Replace Constructor with Factory Method acts on. */
public class FactoryTarget {
    private final String name;

    public FactoryTarget(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
EOF_FACTORY
cat > "$WS/proj/src/main/java/com/example/FactoryCaller.java" << 'EOF_FACTORY_CALLER'
package com.example;

/** The CALL SITE, in a file other than the one declaring the constructor. */
public class FactoryCaller {
    public FactoryTarget make(String name) {
        return new FactoryTarget(name);
    }
}
EOF_FACTORY_CALLER
# 28d Stage 8 (S8.12): the rank-2 target, written BEFORE load_project for the same
# reason. The arms read a method PARAMETER on purpose: the generated behaviour
# method has to carry it, so this exercises the whole shape rather than the easy
# half where every arm touches only fields.
#
# The caret below is line 14, column 8 (both zero-based) — the `switch (signal)`
# line. Counted from this heredoc; if you edit the comment above the class, recount.
cat > "$WS/proj/src/main/java/com/example/SignalRouter.java" << 'EOF_SIGNAL'
package com.example;

/**
 * The switch Replace Conditional with Polymorphism acts on: an ENUM
 * discriminator, arrow arms, and a method PARAMETER the arms read.
 */
public class SignalRouter {

    public enum Signal { START, STOP, PAUSE }

    private int count;
    private int multiplier = 2;

    public void handle(Signal signal, int amount) {
        switch (signal) {
            case START -> {
                this.count = amount * multiplier;
            }
            case STOP -> {
                this.count = 0;
            }
            default -> {
                this.count = count + amount;
            }
        }
    }

    public int count() {
        return count;
    }
}
EOF_SIGNAL
LP="$(call load_project "{\"projectPath\":\"$WS/proj\"}")"
case "$LP" in
    *'"success":true'*|*sourceFiles*|*packages*) pass "choke-gate a real project loads in the throwaway resident" ;;
    *) fail "choke-gate load_project failed: $(printf '%s' "$LP" | head -c 200)" ;;
esac

# --- extract-class-stages: 28d Stage 7 (S7.5), the new operation at the front door
# C7 requires the E2E to add "a front-door refactoring(action=plan) staging each new
# kind". Read literally that does not fit: refactoring(action=plan)'s kind enum holds
# the multi-step PATTERN transforms (compose_method, refactor_to_state, ...), and
# Extract Class is an atomic operation reached as extract(kind=class). A one-step
# plan would be a degenerate wrapper invented to match a sentence.
#
# So the SUBSTANCE is asserted instead, and the deviation is named here rather than
# left for an auditor: the new kind is reachable through the real front door, and it
# STAGES — auto_apply=false returns a changeId and a diff and writes nothing, which
# is the staging contract refactoring(action=apply) then performs.
#
# Why this belongs at the front door at all: every unit test of an operation
# constructs the tool itself. Sprint 27a shipped a central feature that was 1591/1591
# green and inert in production, because the tests supplied the very wiring that was
# missing. Only the endpoint an editor uses can tell "wired" from "works when called".
EXTRACT_SRC="$WS/proj/src/main/java/com/example/ExtractTarget.java"
EX="$(call extract "{\"kind\":\"class\",\"filePath\":\"$EXTRACT_SRC\",\"line\":3,\"column\":13,
      \"newTypeName\":\"Address\",\"fields\":[\"street\",\"city\"],
      \"createGetterSetter\":false,\"auto_apply\":false}")"
case "$EX" in
    *'"changeId"'*)
        pass "extract-class-stages extract(kind=class) stages a change at the front door" ;;
    *'Unknown kind'*)
        fail "extract-class-stages the front door does not know kind=class — the operation
          exists but is NOT wired into extract's dispatch: $(printf '%s' "$EX" | head -c 300)" ;;
    *)
        fail "extract-class-stages no changeId from a staged extract(kind=class): $(printf '%s' "$EX" | head -c 300)" ;;
esac
# STAGED means NOTHING WAS WRITTEN. A tool that applies while reporting a staged
# change has taken the decision away from the caller, and the response looks the
# same either way.
if grep -q "private String street;" "$EXTRACT_SRC"; then
    pass "extract-class-stages staging wrote nothing — the source still holds its fields"
else
    fail "extract-class-stages auto_apply=false MODIFIED the source; staging is not staging"
fi
if [ -e "$WS/proj/src/main/java/com/example/Address.java" ]; then
    fail "extract-class-stages auto_apply=false created the extracted class on disk"
else
    pass "extract-class-stages staging created no file"
fi

# --- factory-stages: 28d Stage 8 (S8.6), rank 3 at the front door ------------
# Same substance and the same declared deviation as the Stage 7 promise above:
# refactor_to_pattern(kind=replace_constructor_with_factory) is reachable through
# the real JSON-RPC endpoint an editor uses, and it STAGES.
#
# The kind was ADDED to a front door whose published schema had never carried its
# delegates' parameters — that defect shipped twice (extract, generate) before a
# guard existed. So "Unknown kind" gets its own failure arm here: it is the exact
# signature of an operation that exists and was never wired into dispatch, and it
# reads identically to any other failure unless it is separated.
FACTORY_SRC="$WS/proj/src/main/java/com/example/FactoryTarget.java"
FACTORY_CALLER="$WS/proj/src/main/java/com/example/FactoryCaller.java"
FX="$(call refactor_to_pattern "{\"kind\":\"replace_constructor_with_factory\",
      \"filePath\":\"$FACTORY_SRC\",\"line\":6,\"column\":11,
      \"factoryMethodName\":\"of\",\"auto_apply\":false}")"
case "$FX" in
    *'"changeId"'*)
        pass "factory-stages replace_constructor_with_factory stages at the front door" ;;
    *'Unknown kind'*)
        fail "factory-stages the front door does not know replace_constructor_with_factory —
          the operation exists but is NOT wired into dispatch: $(printf '%s' "$FX" | head -c 300)" ;;
    *)
        fail "factory-stages no changeId from a staged replace_constructor_with_factory: $(printf '%s' "$FX" | head -c 300)" ;;
esac
# STAGED means NOTHING WAS WRITTEN — asserted on BOTH files, because this operation
# is cross-file by nature and a staging leak would most likely show at the call site
# rather than at the declaration.
if grep -q "public FactoryTarget(String name)" "$FACTORY_SRC"; then
    pass "factory-stages staging left the constructor untouched"
else
    fail "factory-stages auto_apply=false MODIFIED the constructor; staging is not staging"
fi
if grep -q "new FactoryTarget(name)" "$FACTORY_CALLER"; then
    pass "factory-stages staging left the CALL SITE untouched"
else
    fail "factory-stages auto_apply=false rewrote the call site while reporting a staged change"
fi

# --- round-trip: 28d Stage 9 (S9.2), the fixed point OVER THE WIRE ------------
# Stage 9's named promise. The unit test proves the property in-process; this
# proves the two directions compose to the identity when driven through the real
# JSON-RPC endpoint, APPLIED rather than staged — which is the only way the undo
# path, the compile gate and the file writes are all in play.
#
# Both legs are asserted for success separately. A round trip that closes because
# NEITHER direction did anything is the failure mode this whole stage is about,
# so the midpoint is checked between them.
RT_SRC="$WS/proj/src/main/java/com/example/FactoryTarget.java"
RT_CALLER="$WS/proj/src/main/java/com/example/FactoryCaller.java"
RT_BEFORE_SRC="$(cat "$RT_SRC")"
RT_BEFORE_CALLER="$(cat "$RT_CALLER")"

# TOWARD, applied. protectConstructor=false is load-bearing: the default makes
# the constructor private, and inlining the factory would then rewrite call
# sites into something they cannot reach.
RT1="$(call refactor_to_pattern "{\"kind\":\"replace_constructor_with_factory\",
      \"filePath\":\"$RT_SRC\",\"line\":6,\"column\":11,
      \"factoryMethodName\":\"of\",\"protectConstructor\":false}")"
case "$RT1" in
    *'"undoChangeId"'*) pass "round-trip TOWARD applied at the front door" ;;
    *) fail "round-trip TOWARD did not apply: $(printf '%s' "$RT1" | head -c 300)" ;;
esac
if grep -q "FactoryTarget.of(name)" "$RT_CALLER"; then
    pass "round-trip the call site moved to the factory"
else
    fail "round-trip TOWARD reported success and the call site did not move — the AWAY leg would then close trivially: $(head -c 300 "$RT_CALLER")"
fi

# AWAY: inline the factory back. The caret is the generated method.
RT_OF_LINE="$(grep -n ' of(' "$RT_SRC" | head -1 | cut -d: -f1)"
RT_OF_LINE=$((RT_OF_LINE - 1))
RT_OF_COL="$(awk -v n="$((RT_OF_LINE + 1))" 'NR==n{print index($0, "of(") - 1}' "$RT_SRC")"
RT2="$(call inline "{\"kind\":\"method\",\"filePath\":\"$RT_SRC\",
      \"line\":$RT_OF_LINE,\"column\":$RT_OF_COL}")"
case "$RT2" in
    *'"undoChangeId"'*) pass "round-trip AWAY applied at the front door" ;;
    *) fail "round-trip AWAY did not apply — inline(kind=method) does not invert the factory over the wire: $(printf '%s' "$RT2" | head -c 300)" ;;
esac

# THE FIXED POINT. Byte-identical, both files, or the two directions do not
# compose to the identity outside the test harness.
if [ "$(cat "$RT_CALLER")" = "$RT_BEFORE_CALLER" ]; then
    pass "round-trip the CALL SITE returned byte-identically"
else
    fail "round-trip the call site did not return: $(diff <(printf '%s' "$RT_BEFORE_CALLER") "$RT_CALLER" | head -c 400)"
fi
if [ "$(cat "$RT_SRC")" = "$RT_BEFORE_SRC" ]; then
    pass "round-trip the DECLARING file returned byte-identically"
else
    fail "round-trip the declaring file did not return: $(diff <(printf '%s' "$RT_BEFORE_SRC") "$RT_SRC" | head -c 400)"
fi

# --- polymorphism-stages: 28d Stage 8 (S8.12), rank 2 at the front door -------
# The same promise as the two above, for the stage's second floor operation:
# refactor_to_pattern(kind=replace_conditional_with_polymorphism) is reachable
# through the real JSON-RPC endpoint an editor uses, and it STAGES.
#
# "Unknown kind" keeps its own failure arm here for the reason it has one above:
# it is the exact signature of an operation that exists and was never wired into
# dispatch, and it reads identically to any other failure unless separated.
SIGNAL_SRC="$WS/proj/src/main/java/com/example/SignalRouter.java"
PX="$(call refactor_to_pattern "{\"kind\":\"replace_conditional_with_polymorphism\",
      \"filePath\":\"$SIGNAL_SRC\",\"line\":14,\"column\":8,\"auto_apply\":false}")"
case "$PX" in
    *'"changeId"'*)
        pass "polymorphism-stages replace_conditional_with_polymorphism stages at the front door" ;;
    *'Unknown kind'*)
        fail "polymorphism-stages the front door does not know replace_conditional_with_polymorphism —
          the operation exists but is NOT wired into dispatch: $(printf '%s' "$PX" | head -c 300)" ;;
    *'No switch statement'*)
        fail "polymorphism-stages the caret missed the switch — the heredoc above moved and the
          hardcoded line/column no longer point at it: $(printf '%s' "$PX" | head -c 300)" ;;
    *)
        fail "polymorphism-stages no changeId from a staged replace_conditional_with_polymorphism: $(printf '%s' "$PX" | head -c 300)" ;;
esac
# STAGED means NOTHING WAS WRITTEN.
if grep -q "switch (signal)" "$SIGNAL_SRC"; then
    pass "polymorphism-stages staging wrote nothing — the source still holds its switch"
else
    fail "polymorphism-stages auto_apply=false MODIFIED the source; staging is not staging"
fi
# The generated hierarchy is NESTED, so a staging leak shows as the interface
# appearing in the same file rather than as a new one — which the extract check
# above would not catch.
if grep -q "interface HandleBehaviour" "$SIGNAL_SRC"; then
    fail "polymorphism-stages auto_apply=false wrote the generated hierarchy into the source"
else
    pass "polymorphism-stages staging generated no nested types on disk"
fi

# --- cure-resolves: 28d Stage 5, a detector's cure carries a REAL address ----
# Sprint 28d. The cure lookup resolves a smell kind to the catalogue entries
# that cure it and reads the address off the row. Every unit test of it passed
# while production shipped it unreachable: the detectors were built by a
# registration path that had no store, so `store == null`, so every finding
# took the stated-DEGRADED branch — a degraded cure is still a cure, and
# nothing went red. Only the front door can prove otherwise, because the thing
# under test is a CONSTRUCTION LINE in the application, not a class.
#
# The two outcomes are deliberately distinguished. DEGRADED is the exact
# signature of the unwired build, so it gets its own failure message rather
# than falling into a catch-all that would read as "no findings".
OCPQ="$(call find_quality_issue '{"kind":"ocp"}')"
case "$OCPQ" in
    *'DEGRADED — catalogue namespace'*)
        # The exact prefix from the cure lookup, not a bare DEGRADED: the AST
        # scanner emits its own "DEGRADED SCAN:" steering on an unrelated
        # failure, and matching that here would report a true failure with an
        # entirely wrong diagnosis.
        fail "cure-resolves the detector answered DEGRADED — it reached no store, so the cure is the hardcoded map and not the catalogue. This is the unwired signature: $(printf '%s' "$OCPQ" | head -c 400)" ;;
    *'design(s) in the catalogue:'*)
        pass "cure-resolves an ocp finding carries a cure whose catalogue address resolved from the live store" ;;
    *'"findings":[]'*)
        fail "cure-resolves the ocp detector found nothing on a target built to violate it — the promise proves nothing about cures until it fires: $(printf '%s' "$OCPQ" | head -c 400)" ;;
    *) fail "cure-resolves no resolved cure and no degradation on the ocp findings — the cure sentence is neither the store's nor declared as a fallback: $(printf '%s' "$OCPQ" | head -c 400)" ;;
esac

# --- cure-tier-derived: 28d Stage 11a, the tier is DERIVED, not assigned -----
# The cure model: one runnable route whose step the front door publishes derives
# PERFORM, and the finding says so, naming what to run. OcpTarget carries both
# traces, so both perform-tier answers must appear — the switch trace's and the
# type-code trace's, each with its own step.
case "$OCPQ" in
    # v4.0.2: the FIFTH place this one fact was pinned — four test files and
    # this script each held 'which refactoring does the switch kind recommend'
    # as their own literal. The kind routes to the operation its own prose
    # names now, and every copy had to be found by a red run of its owner.
    *'TIER: PERFORM — run refactor_to_pattern kind=replace_conditional_with_polymorphism.'*)
        pass "cure-tier-derived the switch trace derives PERFORM and names replace_conditional_with_polymorphism" ;;
    *) fail "cure-tier-derived no PERFORM tier naming replace_conditional_with_polymorphism on the switch trace — the derivation did not reach the finding: $(printf '%s' "$OCPQ" | head -c 400)" ;;
esac
case "$OCPQ" in
    *'TIER: PERFORM — run refactor_to_pattern kind=replace_type_code_with_class.'*)
        pass "cure-tier-derived the type-code trace derives PERFORM and names replace_type_code_with_class" ;;
    *) fail "cure-tier-derived no PERFORM tier naming replace_type_code_with_class on the type-code trace: $(printf '%s' "$OCPQ" | head -c 400)" ;;
esac
# THE CONTROL: the missing-step signature must NOT fire on a real build. It
# appears only when the cure table declares a step the shipped front door does
# not publish — which is exactly the drift the derivation exists to surface,
# and exactly what must not be true of a dist we are about to trust.
case "$OCPQ" in
    *'not in the operation registry'*)
        fail "cure-tier-derived the table declares a step the shipped front door does not publish: $(printf '%s' "$OCPQ" | head -c 400)" ;;
    *)
        pass "cure-tier-derived no declared step is missing from the shipped operation registry" ;;
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
# THE BUDGET TRACKS THE WORK, and the work changed. Sprint 28c (v11) embeds a row
# FOUR times — the composite plus three per-field lanes — so ~190 upgraded rows
# now cost ~760 embeddings where they cost ~190. The old 180 s budget was set
# against the one-vector cost and this check began failing at the arithmetic, not
# at a defect. Widened to match, and made to report PROGRESS: a stall and a slow
# run are different findings, and a bare timeout reports them identically.
UPCONV=""
UPLAST=-1
for _ in $(seq 1 150); do
    UP="$(call experience '{"kind":"stats"}')"
    if lane_closed "$UP" "experience_entry"; then UPCONV="yes"; break; fi
    UPNOW="$(printf '%s' "$UP" | grep -o '"experience_entry":{[^}]*}' \
             | grep -oE '"embedded":[0-9]+' | cut -d: -f2)"
    if [ "${UPNOW:-0}" != "$UPLAST" ]; then
        UPLAST="${UPNOW:-0}"
        printf '    ... backfill embedded %s\n' "$UPLAST"
    fi
    sleep 3
done
if [ -n "$UPCONV" ]; then
    pass "upgrade-earns-vectors backfill embedded every pre-embedding row"
else
    fail "upgrade-earns-vectors rows written before embeddings never earned vectors (stalled at ${UPLAST} embedded)"
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

# --- migrate-dry-run-writes-nothing: the confirm gate is real ----------------
# Run the dry run TWICE. If the first one had written, the second would find
# every row already formed and report migrated=0. Same number twice is the
# front door proving non-mutation without reaching into the database.
mf_count() { printf '%s' "$1" | grep -oE "\"$2\":[0-9]+" | head -1 | cut -d: -f2; }
DRY1="$(call experience '{"kind":"migrate_form"}')"
DRY2="$(call experience '{"kind":"migrate_form"}')"
D1M="$(mf_count "$DRY1" migrated)"; D2M="$(mf_count "$DRY2" migrated)"
case "$DRY1" in
    *'"applied":false'*) : ;;
    *) fail "migrate-dry-run-writes-nothing the dry run reported itself as applied" ;;
esac
if [ -n "$D1M" ] && [ "$D1M" = "$D2M" ]; then
    pass "migrate-dry-run-writes-nothing two dry runs agree (migrated=$D1M), so neither wrote"
else
    fail "migrate-dry-run-writes-nothing THE DRY RUN MUTATED THE STORE: first=$D1M second=$D2M"
fi

# --- migrate-accounts-for-every-row: no row is silently dropped --------------
D1S="$(mf_count "$DRY1" sourceEntries)"; D1K="$(mf_count "$DRY1" legacyKept)"
if [ -n "$D1S" ] && [ "$D1S" -eq $(( ${D1M:-0} + ${D1K:-0} )) ]; then
    pass "migrate-accounts-for-every-row $D1S in = $D1M migrated + $D1K kept, nothing disposed"
else
    fail "migrate-accounts-for-every-row COUNTS DO NOT RECONCILE: $D1S vs $D1M + $D1K"
fi

# --- migrate-confirm-applies-once: the write path, then idempotence ----------
# Safe here BECAUSE this is the scratch copy of the fixture; the committed
# slice is checked byte-identical below. The real store is never touched by
# this gate.
APPLIED="$(call experience '{"kind":"migrate_form","confirm":true}')"
AGAIN="$(call experience '{"kind":"migrate_form"}')"
AM="$(mf_count "$APPLIED" migrated)"; RM="$(mf_count "$AGAIN" migrated)"
case "$APPLIED" in
    *'"applied":true'*) : ;;
    *) fail "migrate-confirm-applies-once confirm:true did not report itself as applied" ;;
esac
if [ "${AM:-0}" -gt 0 ] && [ "${RM:-1}" -eq 0 ]; then
    pass "migrate-confirm-applies-once wrote $AM rows, and a re-run finds nothing left to do"
else
    fail "migrate-confirm-applies-once expected a write then a no-op, got applied=$AM rerun=$RM"
fi

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

# --- the anchorless lane, end to end ----------------------------------------
# Sprint 28c. The block above proves the two verbs answer on a nonsense question;
# this proves the whole CONTRACT over the wire on the frozen fixture — an entry
# with no code anchor of any kind recorded, ranked into the top 3 against seven
# distractors, and decided to a match carrying it; and an empty selection decided
# to an absence on each of seven unrelated questions.
#
# It is deliberately NOT called the architect-seat run. The architecture says a
# scripted client cannot replace the seat's judgement in the live acceptance
# proof, and this script chooses its own selection — so it stands for the
# artifact's FIRST reality-only check (the JSON-RPC contract) and never the
# second (a real seat run), which is proven by a captured seat transcript.
#
# It runs here, after stop_resident, on its own port and its own throwaway store,
# because it boots a resident of its own. Calling it from the gate is the point:
# a probe nothing invokes is the unwired-capability failure this sprint keeps
# finding, wearing a gate's clothes.
#
# THE TRANSCRIPT IS KEPT. Clause 7's evidence is "captured front-door requests",
# and the probe defaults its transcript inside its own $WS, which its cleanup
# trap deletes — so a gate run that did not name a path destroyed the only proof
# the run had happened. It is written beside the dist instead, where it outlives
# the run and anyone can read the exact requests back.
LANE_PROBE="$(cd "$(dirname "$0")" && pwd)/anchorless-frontdoor-probe.sh"
LANE_TRANSCRIPT="$DIST/anchorless-frontdoor-transcript.txt"
if [ -x "$LANE_PROBE" ]; then
    if JAWATA_PROBE_PORT="${JAWATA_LANE_PROBE_PORT:-8901}" \
       PROBE_TRANSCRIPT="$LANE_TRANSCRIPT" "$LANE_PROBE" "$DIST" > "$WS/lane-probe.log" 2>&1; then
        pass "anchorless-lane the nominate-decide contract answers all 12 frozen questions ($(grep -c '  ok' "$WS/lane-probe.log") claims; $(grep -c '=== REQUEST' "$LANE_TRANSCRIPT" 2>/dev/null || echo 0) front-door requests captured at $LANE_TRANSCRIPT)"
    else
        fail "anchorless-lane the anchorless contract failed over the front door:"
        sed 's/^/        /' "$WS/lane-probe.log" >&2
    fi
else
    fail "anchorless-lane the probe is missing or not executable at $LANE_PROBE"
fi

echo "end-to-end test: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ] || exit 1
