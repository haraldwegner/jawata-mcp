#!/usr/bin/env bash
# Sprint 28f Stage 9 step 1 — THE FOUR MOMENTS, FIRING UNPROMPTED.
#
# WHY THIS EXISTS. E10's first clause is that the four lane moments fire "unprompted in a
# live session". Every unit test in both products drives a moment BY CALLING IT, which is
# the one thing a real session never does: there, the moment has to arrive as a side effect
# of ordinary work. This repository has shipped a headline INERT past a full green suite
# exactly that way, and the record of it is why this file is a deliverable rather than a
# convenience.
#
# WHAT IT DRIVES. The engine over JSON-RPC on a scratch port, store and workspace — never
# the developer's own — with the payloads a session actually produces. Each moment is
# PASS / FAIL / UNPROVEN, and UNPROVEN is a first-class outcome: a moment this harness
# could not put in a position to fire is reported as unproven and never as a pass.
#
# THE CONTROL IS HALF THE POINT. A bare edit must trigger NOTHING. Without it, "the lanes
# fire" is satisfied by a hook that fires on everything, which is the state 4.2 was in and
# the reason D6 exists.
#
# Usage:  build/28f-live-moments.sh [dist-dir]
# Exit:   0 = every moment PASS · 1 = a moment FAILED · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_MOMENTS_PORT:-8915}"
TOKEN="live-moments-$$"

WS="$(mktemp -d)"       # throwaway workspace AND store: the smoke must never
STORE="$(mktemp -d)"    # read or write the developer's real one
LOG="$WS/resident.log"
RESIDENT_PID=""

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

# shellcheck disable=SC2086
java $VECTOR -Xmx3g -Djawata.experience.shared.dir="$STORE" \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
READY=0
for _ in $(seq 1 180); do
    grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2
                                             tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "resident never announced readiness in 180s:" >&2
                        tail -20 "$LOG" >&2; exit 2; }

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_ROOT="$ROOT" \
PROBE_HOOK="${JAWATA_HOOK_BIN:-}" python3 - << 'PY'
import json, os, urllib.request

PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]

passed = failed = unproven = 0
def ok(msg):
    global passed; passed += 1; print("  PASS      " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL      " + msg)
def unknown(msg):
    global unproven; unproven += 1; print("  UNPROVEN  " + msg)

def call(tool, args):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN,
                 "Mcp-Session-Id": "live-moments",
                 "Content-Type": "application/json"})
    raw = urllib.request.urlopen(r, timeout=900).read().decode()
    return json.loads(json.loads(raw)["result"]["content"][0]["text"])

def record(**args):
    reply = call("experience", dict(kind="record", **args))
    if not reply.get("success"):
        raise SystemExit("the fixture would not record, so nothing below measures "
                         "anything: %s" % json.dumps(reply)[:400])

# --- the corpus each moment is supposed to find -----------------------------------------
# Ordinary rows, not nonsense: a pile of gibberish ranks last against anything and every
# moment would appear to work by default.
record(type="job", symbol="com.example.CoverageRunner#runForked",
       summary="Run the project's tests in a forked machine and report which lines "
               "they reached")
record(type="area", packages=["com.example"],
       summary="The lane between an agent's questions and what this machine already learned.")
record(type="domain_fact", symbol="com.example.CoverageRunner",
       summary="A forked runner that reports fewer classes than it planned has lost a "
               "shard, and the missing lines read as untested code.")
record(type="lesson", symptoms=["the run reports fewer classes than it planned"],
       situation="when a forked test run reports fewer classes than were planned",
       verdict="worked",
       summary="A shard that dies takes its classes with it and the coverage report "
               "reads them as untested rather than as unrun.")

print("\n--- MOMENT 1: the map, at the prompt ---------------------------------------")
m = call("experience", {"kind": "nominate", "lane": "code", "format": "text",
                        "question": "which parts of the product did the checks "
                                    "actually exercise?"})
text = str(m.get("data", ""))
if not m.get("success"):
    unknown("nominate refused, so this moment could not be put in a position to fire: %s"
            % json.dumps(m)[:200])
elif text.startswith("JAWATA MAP —") and "job:" in text and "area:" in text:
    ok("a prompt yields the MAP block, areas before jobs, each job anchored")
else:
    bad("the prompt produced no map block: %r" % text[:240])

print("\n--- MOMENT 2: the experience lane, on a symptom -----------------------------")
e = call("experience", {"kind": "recall", "format": "text",
                        "symptom": "the run reports fewer classes than it planned"})
text = str(e.get("data", ""))
if "shard" in text:
    ok("a symptom cue reaches the lesson recorded for it")
elif "No cue" in text:
    bad("the symptom cue was not read as a cue at all: %r" % text[:200])
else:
    bad("the symptom lane answered nothing about the shard: %r" % text[:240])

print("\n--- MOMENT 3: the domain lane, on a type a tool call reads ------------------")
d = call("experience", {"kind": "recall", "format": "text",
                        "symbol": "com.example.CoverageRunner"})
text = str(d.get("data", ""))
if "lost a shard" in text or "untested code" in text:
    ok("a symbol cue reaches the domain fact anchored to that type")
else:
    bad("the domain lane did not answer for its own anchor: %r" % text[:240])

print("\n--- MOMENT 4: the duplicate gate, on a draft --------------------------------")
# The gate's ENGINE half. The hook half is Rust and is driven by its own suite; what a live
# session adds here is that the verb answers over the wire, on the dist, with no project
# loaded — which is the state a hook actually fires in.
g = call("experience", {"kind": "duplicate_check", "filePath": "/p/src/Other.java",
                        "draft": "/** Runs the tests in a forked machine and reports the "
                                 "lines they reached. */\n"
                                 "private static Report exercise(Suite s) { return null; }\n"})
data = g.get("data", {})
nominees = data.get("nominees") or []
if not g.get("success"):
    bad("duplicate_check refused: %s" % json.dumps(g)[:240])
elif nominees and nominees[0].get("location"):
    ok("a draft re-deriving a recorded job is NAMED, with an address: %s"
       % nominees[0].get("location"))
    if data.get("existingKnown") is False and data.get("existingUnknownWhy"):
        ok("and what it could NOT check is spoken, not left to read as nothing: %r"
           % str(data["existingUnknownWhy"])[:90])
    else:
        bad("the unchecked half was silent, which is the one thing this sprint forbids: %s"
            % json.dumps(data)[:240])
else:
    bad("the draft reached the lane and nothing came back with an address: %s"
        % json.dumps(data)[:240])

print("\n--- THE CONTROL: a bare edit triggers nothing -------------------------------")
# Not a moment — the thing that makes the four above mean something. A cue-less recall is
# what a bare Edit produces once the hook has stopped inventing cues from a file name.
c = call("experience", {"kind": "recall", "format": "text"})
text = str(c.get("data", ""))
if "No cue" in text:
    ok("a call carrying no cue is refused rather than answered with a pile — without "
       "this, every moment above is satisfied by a store that answers anything")
else:
    bad("a cue-less recall answered something, so the moments above prove nothing "
        "about targeting: %r" % text[:240])

print("\n--- THE HOOK HALF: does any of this fire UNPROMPTED? ------------------------")
# Everything above drives the engine's verbs DIRECTLY, which is exactly what a real session
# never does. E10's clause is that the moments fire as a SIDE EFFECT of ordinary work, and
# only the hook binary can answer that. It is measured here or reported UNPROVEN; it is
# never inferred from the engine half passing.
import subprocess, shutil, tempfile

HOOK = os.environ.get("PROBE_HOOK", "")
if not HOOK or not os.path.exists(HOOK):
    unknown("no hook binary given (JAWATA_HOOK_BIN), so whether these fire UNPROMPTED is "
            "not measured. The engine half above says the verbs answer; it says nothing "
            "about anything calling them.")
else:
    # A STALE BINARY IS "COULD NOT MEASURE", NEVER "BROKEN" — and this guard exists
    # because the first run of this file reported two FAILURES that were neither. The
    # binary on disk was five days older than the source and predated the duplicate gate
    # entirely, so it answered with the recall gate's output on both payloads, including
    # the bare edit the current source is tested to stay silent on. A red that means
    # "you did not rebuild" is worse than no check: it sends a reader after a defect that
    # is not there.
    src = os.environ.get("PROBE_HOOK_SRC", "")
    newest = 0.0
    if src and os.path.isdir(src):
        for root, _, files in os.walk(src):
            for f in files:
                if f.endswith(".rs"):
                    newest = max(newest, os.path.getmtime(os.path.join(root, f)))
    stale = bool(newest) and os.path.getmtime(HOOK) < newest

if HOOK and os.path.exists(HOOK) and stale:
    unknown("the hook binary is OLDER than its own sources, so anything it says is about "
            "a build nobody is shipping. Rebuild it (cargo build --release --bin "
            "jawata-hook) and run this again — NOT measured, and NOT failed.")
elif HOOK and os.path.exists(HOOK):
    stage = tempfile.mkdtemp()
    try:
        # The role is read off the BINARY NAME, so the name IS the invocation.
        recall = os.path.join(stage, "jawata-hook-recall")
        shutil.copy2(HOOK, recall)
        with open(os.path.join(stage, "hook_config.json"), "w") as f:
            json.dump({"url": "http://127.0.0.1:%s/mcp" % PORT, "token": TOKEN,
                       "client": "claude-code", "timeout_ms": 20000}, f)

        # A real Write of a .java file, NESTED under tool_input the way one arrives — the
        # shape whose absence once made this gate inert on every real write.
        payload = json.dumps({
            "tool_name": "Write",
            "tool_input": {
                "file_path": "/p/src/Other.java",
                "content": "/** Runs the tests in a forked machine and reports the lines "
                           "they reached. */\nclass O { Report exercise(Suite s){return null;} }"
            }})
        run = subprocess.run([recall], input=payload, capture_output=True,
                             text=True, timeout=180)
        if "CoverageRunner#runForked" in run.stdout:
            ok("the hook binary, handed a real Write payload and told nothing else, "
               "reached the store and emitted the nominee — THIS is the clause")
        elif run.stdout.strip():
            bad("the hook emitted something that is not the nominee: %r" % run.stdout[:240])
        else:
            bad("the hook emitted NOTHING on a payload that must fire the duplicate gate "
                "(stderr: %r)" % run.stderr[:240])

        # THE CONTROL, through the same binary: a bare Edit must produce nothing at all.
        bare = json.dumps({"tool_name": "Edit",
                           "tool_input": {"file_path": "/p/src/Other.java"}})
        run2 = subprocess.run([recall], input=bare, capture_output=True,
                              text=True, timeout=180)
        if not run2.stdout.strip():
            ok("and a BARE edit through the same binary emits nothing — without this the "
               "line above is satisfied by a hook that fires on everything, which is the "
               "state D6 exists to end")
        else:
            bad("a bare edit produced output: %r" % run2.stdout[:240])
    finally:
        shutil.rmtree(stage, ignore_errors=True)

print("\n28F-LIVE-MOMENTS passed=%d failed=%d unproven=%d" % (passed, failed, unproven))
raise SystemExit(1 if failed else 0)
PY
