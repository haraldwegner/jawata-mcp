#!/usr/bin/env bash
# Sprint 28c — THE ANCHORLESS LANE, THROUGH THE REAL FRONT DOOR.
#
# This satisfies the FIRST of the architecture's reality-only checks:
#
#     "the built product records and retrieves an anchorless experience through
#      the nominate→decision JSON-RPC contract"
#     (ARCHITECTURE-knowledge-layer-rescue.md, End-state test surface)
#
# and nothing else. In particular it is NOT the architect-seat run. An earlier
# version of this file called itself "the architect seat's own protocol" while
# choosing its selection by looking the expected summary up in the fixture — a
# scripted client wearing the judge's name. The artifact forbids exactly that
# substitution, in as many words:
#
#     "The architect seat is the first production consumer: it calls nominate,
#      reads the candidates, and submits the decision. A scripted client cannot
#      silently replace this judgement in the live acceptance proof."
#     (ARCHITECTURE-knowledge-layer-rescue.md, Read ownership §3)
#
# So the naming here is deliberate and load-bearing: this script drives the
# CONTRACT. The judgement is proven separately by a real seat run whose
# transcript is captured, and this script never claims that half.
#
# WHAT IT PROVES, and what the earlier version could not:
#
#   * an entry with NO symbol, package, operation or snippet enters through the
#     record verb over the wire;
#   * nominate ranks and NEVER vouches;
#   * a selection decides to a MATCH CARRYING THAT ENTRY — not merely a match;
#   * an empty selection decides to an ABSENCE with zero entries, echoing its
#     own question;
#   * and the positive arm CAN FAIL ON RANKING, because the store holds seven
#     distractors as well as the five records, so a candidate list capped at
#     ExperienceRetrieval.MAX_CANDIDATES (8) cannot contain everything and a
#     top-N bound is a real bar. Without the distractors, five records against a
#     cap of eight means every record is always a candidate and the arm passes
#     with the ranking comparator replaced by a constant — the exact shape
#     AnchorlessRetrievalTest documents and guards, and which the first version
#     of this script reproduced.
#
# Usage:  build/anchorless-frontdoor-probe.sh [dist-dir]
# Env:    PROBE_TRANSCRIPT=<path>   keep the request/response transcript
# Exit:   0 = every claim held · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
FIXTURE="$ROOT/build/acceptance/anchorless-retrieval.json"
PORT="${JAWATA_PROBE_PORT:-8901}"
TOKEN="anchorless-frontdoor-probe-$$"

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
# EXIT alone is not enough: a shell killed by a signal can exit without running
# it, and the resident it started outlives the run. One leaked for two days and
# was found only because it still held the port a later probe wanted.
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ]     || { echo "no artifact at $JAR — build first" >&2; exit 2; }
[ -f "$FIXTURE" ] || { echo "no fixture at $FIXTURE" >&2; exit 2; }

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$STORE" \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
READY=0
for _ in $(seq 1 120); do
    grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2
                                             tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
# Falling out of the loop without READY is a real condition, not a slow start to
# be shrugged at: say so rather than letting the next call fail for a reason
# nobody can trace back to here.
[ "$READY" -eq 1 ] || { echo "resident never announced readiness in 120s:" >&2
                        tail -20 "$LOG" >&2; exit 2; }

# The whole protocol runs in ONE python process that speaks the front door
# directly. It parses the answers with a real JSON parser rather than scanning
# de-escaped text with a brace regex: the previous version matched candidates
# with r'\{[^{}]*\}', which reads FLAT objects only, so the first candidate to
# carry a nested field would have been skipped and the miss reported as a
# retrieval failure — a false red blamed on the engine.
PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_TRANSCRIPT_PATH="$TRANSCRIPT" \
python3 - "$FIXTURE" << 'PY'
import json, os, sys, urllib.request

FIXTURE = sys.argv[1]
PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]
TRANSCRIPT = open(os.environ["PROBE_TRANSCRIPT_PATH"], "w")

# The bar. Bounding the answer to the top of the ranking is what makes the
# positive arm a measurement rather than a membership test.
TOP_N = 3

passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    anchorless " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  anchorless " + msg)

def call(tool, args):
    """One tools/call over the wire. Every request is written to the transcript
    BEFORE its answer is looked at, so a run that fails still leaves the
    requests that produced the failure."""
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    body = json.dumps(req).encode()
    TRANSCRIPT.write("=== REQUEST  POST http://127.0.0.1:%s/mcp\n%s\n" % (PORT, json.dumps(req)))
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=body,
        headers={"Authorization": "Bearer " + TOKEN,
                 "Mcp-Session-Id": "anchorless-probe",
                 "Content-Type": "application/json"})
    raw = urllib.request.urlopen(r, timeout=180).read().decode()
    TRANSCRIPT.write("--- RESPONSE\n%s\n\n" % raw)
    TRANSCRIPT.flush()
    outer = json.loads(raw)
    # A tool result is JSON nested inside a JSON string. Parse both layers; an
    # error body has no content and must raise here rather than be scanned for
    # a substring that happens not to appear.
    return json.loads(outer["result"]["content"][0]["text"])

fx = json.load(open(FIXTURE))

# --- 1. seed: the five records, and the seven distractors that give ranking
#        something to be wrong about. The distractors are built from the
#        fixture's own unrelated questions, the way AnchorlessRetrievalTest
#        builds them — the fixture froze those as things the corpus must not
#        answer, so nobody chose to make them easy.
seeded = []
for r in fx["records"]:
    a = {"kind": "record", "type": r["type"], "summary": r["summary"],
         "situation": r["situation"], "verdict": r["verdict"], "status": "accepted"}
    seeded.append(call("experience", a))
for n, q in enumerate(fx["unrelated_questions"], 1):
    call("experience", {"kind": "record", "type": "lesson",
                        "summary": "Measure it before changing it, and write the number down (%d)." % n,
                        "situation": "when " + q, "verdict": "worked", "status": "accepted"})

if all("id" in json.dumps(s) for s in seeded) and len(seeded) == len(fx["records"]):
    ok("all %d records entered through the record verb, plus %d distractors"
       % (len(fx["records"]), len(fx["unrelated_questions"])))
else:
    bad("seeding did not return an id for every record")

# --- 1b. wait for the meaning lane to converge before asking anything.
#        Vectors arrive by an async backfill, and this store is no longer
#        twelve rows: the catalogue seeds itself at boot, so a question asked
#        one second after seeding races the backfill — some rows are scored by
#        meaning, the rest are invisible to it, and the "ranking" measured is
#        whichever rows happened to have vectors yet. The probe measures the
#        converged contract; the mid-backfill state is the degrade path and has
#        its own checks in the end-to-end gate.
#        THE BUDGET IS MEASURED, NOT GUESSED. On this machine a fresh store
#        seeds 187 catalogue patterns and the backfill converges them in ~172 s
#        (four vectors per row: the composite plus three per-field lanes). The
#        ceiling is set well above that so a loaded machine is slow rather than
#        red, and progress is printed so a STALL is distinguishable from
#        slowness — "it never finished" and "it finished in 300 s" are different
#        findings and a bare timeout reports them identically.
import time
converged = False
last = -1
for attempt in range(240):
    st = call("experience", {"kind": "stats"}).get("data", {})
    lanes = st.get("embedding", {})
    lane = lanes.get("experience_entry") if isinstance(lanes, dict) else None
    if isinstance(lane, dict) and lane.get("total", 0) > 0 \
            and lane.get("embedded") == lane.get("total"):
        converged = True
        ok("the meaning lane converged before the questions (%d of %d rows embedded, "
           "after ~%d s)" % (lane["embedded"], lane["total"], attempt * 2))
        break
    if isinstance(lane, dict) and lane.get("embedded", 0) != last:
        last = lane.get("embedded", 0)
        print("    ... embedding %d/%d" % (last, lane.get("total", 0)), flush=True)
    time.sleep(2)
if not converged:
    bad("the meaning lane never converged (stalled at %d embedded) — every ranking "
        "below would measure a race, not the scorer" % last)

def candidates(answer):
    d = answer.get("data", answer)
    for key in ("candidates", "entries", "nominees"):
        if isinstance(d.get(key), list):
            return d[key]
    return []

# --- 2. the positives: nominate, then select the fitting candidate BY ITS
#        SITUATION, and require the decision to be a match CARRYING that entry.
#
#        The selection is still made by the script, so this arm proves the
#        CONTRACT, not the judgement — see the header. What makes it a real
#        measurement rather than a membership test is the TOP_N bound against
#        twelve live entries and a candidate cap of eight.
pos_ok = 0
for i, pq in enumerate(fx["positive_questions"], 1):
    q = pq["question"]
    want = next(r for r in fx["records"] if r["id"] == pq["expect_id"])
    nom = call("experience", {"kind": "nominate", "question": q})
    data = nom.get("data", nom)
    if data.get("result") == "match":
        bad("positive %d nominate VOUCHED — ranking must never answer" % i); continue
    if data.get("result") != "nominated":
        bad("positive %d produced no nomination: %r" % (i, data.get("result"))); continue
    qid = data.get("query_id")
    if not qid:
        bad("positive %d nomination carried no query_id" % i); continue

    ranked = candidates(nom)
    # THE BAR: the fitting experience must be in the top TOP_N of the ranking,
    # not merely somewhere in the offered list.
    top = ranked[:TOP_N]
    pick = None
    for c in top:
        if want["situation"][:40] in json.dumps(c) or want["summary"][:40] in json.dumps(c):
            pick = c.get("id"); break
    if not pick:
        bad("positive %d the fitting experience was not in the top %d of %d candidates: %s"
            % (i, TOP_N, len(ranked), q)); continue

    dec = call("experience", {"kind": "decide", "query_id": qid, "selected_ids": [pick]})
    dd = dec.get("data", dec)
    if dd.get("result") != "match":
        bad("positive %d a selected candidate did not decide to a MATCH: %r" % (i, dd.get("result"))); continue
    # And it must carry the entry that was selected — a match with the wrong
    # body, or with none, is not an answer.
    if want["summary"][:40] not in json.dumps(dd.get("entries", [])):
        bad("positive %d the match did not carry the selected entry" % i); continue
    pos_ok += 1

if pos_ok == len(fx["positive_questions"]):
    ok("all %d positives rank the fitting experience in the top %d against %d distractors, "
       "and a selection decides to a match carrying it"
       % (pos_ok, TOP_N, len(fx["unrelated_questions"])))
else:
    bad("only %d of %d positives held" % (pos_ok, len(fx["positive_questions"])))

# --- 3. the unrelated controls: an empty selection is an ABSENCE with no
#        entries, and it must echo THAT question — asserting result=absence
#        alone is not discriminating, because plain recall answers a cue-less
#        call with an absence too, so a decide verb bypassed to recall passes.
abs_ok = 0
for i, q in enumerate(fx["unrelated_questions"], 1):
    nom = call("experience", {"kind": "nominate", "question": q})
    qid = nom.get("data", nom).get("query_id")
    if not qid:
        bad("unrelated %d nominate returned no query_id" % i); continue
    dec = call("experience", {"kind": "decide", "query_id": qid, "selected_ids": []})
    dd = dec.get("data", dec)
    if dd.get("result") != "absence":
        bad("unrelated %d selecting nothing did not yield an absence: %r" % (i, dd.get("result"))); continue
    if dd.get("count") != 0 or dd.get("entries"):
        bad("unrelated %d the absence carried entries" % i); continue
    # The echo, derived here rather than in shell: an empty echo word would have
    # made the shell pattern `*""*` match anything and silently disarm this half.
    echo = max(q.split(), key=len)
    if len(echo) < 5:
        bad("unrelated %d has no distinctive word to check the echo with" % i); continue
    if echo not in json.dumps(dd.get("question", "")):
        bad("unrelated %d the absence did not echo its own question (%s)" % (i, echo)); continue
    abs_ok += 1

if abs_ok == len(fx["unrelated_questions"]):
    ok("all %d unrelated questions: an empty selection is an absence with no entries, "
       "echoing its own question" % abs_ok)
else:
    bad("only %d of %d unrelated questions produced an empty absence"
        % (abs_ok, len(fx["unrelated_questions"])))

TRANSCRIPT.close()
print("\nanchorless-frontdoor-probe: %d passed, %d failed" % (passed, failed))
sys.exit(1 if failed else 0)
PY
STATUS=$?

if [ -n "${PROBE_TRANSCRIPT:-}" ]; then
    echo "transcript: $TRANSCRIPT ($(grep -c '=== REQUEST' "$TRANSCRIPT") front-door requests)"
fi
exit $STATUS
