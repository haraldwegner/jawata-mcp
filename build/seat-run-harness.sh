#!/usr/bin/env bash
# Sprint 28c Stage 1 clause 7 — the harness for a REAL architect-seat run.
#
# Clause 7 asks for "a real architect-seat run through JSON-RPC". The architecture
# says why a script cannot stand in for it:
#
#     "The architect seat is the first production consumer: it calls nominate,
#      reads the candidates, and submits the decision. A scripted client cannot
#      silently replace this judgement in the live acceptance proof."
#     (ARCHITECTURE-knowledge-layer-rescue.md, Read ownership §3)
#
# So this file does NOT decide anything. It only prepares the world the seat runs
# against and then gets out of the way:
#
#   1. boots a resident on a throwaway store and workspace;
#   2. seeds the frozen fixture — five anchorless records and seven distractors,
#      none carrying a symbol, package, operation or snippet;
#   3. writes the twelve questions SHUFFLED and UNLABELLED to a file, so the
#      judge cannot tell a positive from a control by position;
#   4. prints the port and token, and stays in the foreground until killed.
#
# The ground truth is written to a SEPARATE file the judge is never given. That
# separation is the whole point: the run is evidence only if the seat could have
# got it wrong.
#
# Usage:  build/seat-run-harness.sh <out-dir> [dist-dir]
#         (blocks; kill it when the seat run is finished)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: seat-run-harness.sh <out-dir> [dist-dir]}"
DIST="${2:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
FIXTURE="$ROOT/build/acceptance/anchorless-retrieval.json"
PORT="${JAWATA_SEAT_PORT:-8907}"
TOKEN="seat-run-$$"

WS="$(mktemp -d)"
STORE="$(mktemp -d)"
LOG="$WS/resident.log"
RESIDENT_PID=""

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT

mkdir -p "$OUT"
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
[ "$READY" -eq 1 ] || { echo "resident never announced readiness:" >&2; tail -20 "$LOG" >&2; exit 2; }

SEAT_PORT="$PORT" SEAT_TOKEN="$TOKEN" SEAT_OUT="$OUT" python3 - "$FIXTURE" << 'PY'
import json, os, sys, urllib.request

fx = json.load(open(sys.argv[1]))
PORT, TOKEN, OUT = os.environ["SEAT_PORT"], os.environ["SEAT_TOKEN"], os.environ["SEAT_OUT"]

def call(args):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": "experience", "arguments": args}}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT,
        data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": "seat-harness",
                 "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(r, timeout=180).read().decode())

# The five, anchorless — no symbol, package, operation or snippet, which the
# fixture's own contract requires and which is the whole claim being proven.
for r in fx["records"]:
    call({"kind": "record", "type": r["type"], "summary": r["summary"],
          "situation": r["situation"], "verdict": r["verdict"], "status": "accepted"})
# The seven distractors, built from the fixture's own unrelated questions so
# nobody chose to make the ranking easy.
for n, q in enumerate(fx["unrelated_questions"], 1):
    call({"kind": "record", "type": "lesson",
          "summary": "Measure it before changing it, and write the number down (%d)." % n,
          "situation": "when " + q, "verdict": "worked", "status": "accepted"})

# Shuffle deterministically WITHOUT random (a fixed permutation keeps the run
# reproducible while still denying the judge any positional signal).
labelled = ([{"q": p["question"], "truth": "positive", "expect": p["expect_id"]}
             for p in fx["positive_questions"]]
            + [{"q": q, "truth": "unrelated", "expect": None}
               for q in fx["unrelated_questions"]])
order = [7, 0, 9, 3, 11, 1, 5, 8, 2, 10, 4, 6]
shuffled = [labelled[i] for i in order]

with open(os.path.join(OUT, "questions.txt"), "w") as f:
    for n, item in enumerate(shuffled, 1):
        f.write("%d. %s\n" % (n, item["q"]))
with open(os.path.join(OUT, "ground-truth.json"), "w") as f:
    json.dump([{"n": n, **item} for n, item in enumerate(shuffled, 1)], f, indent=2)

print("SEEDED %d records + %d distractors" % (len(fx["records"]), len(fx["unrelated_questions"])))
print("PORT %s" % PORT)
print("TOKEN %s" % TOKEN)
print("QUESTIONS %s/questions.txt" % OUT)
print("HARNESS READY")
PY

wait "$RESIDENT_PID"
