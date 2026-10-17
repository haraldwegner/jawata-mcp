#!/usr/bin/env bash
# Sprint 28g Stage 21, C21 — THE DISCUSSION LANE IN A LIVE SESSION, TRANSCRIPT AS THE EVIDENCE.
#
# WHY A SESSION. Every unit test drives a hook by calling it, which is the one thing a real session
# never does. This runs real `claude -p` sessions whose hooks are the new hook binaries, pointed at
# a private resident built from the new dist, over a scratch project and a scratch store — never the
# developer's own. A REAL compaction (`/compact`) is taken, and the stream-json transcript of every
# step is kept.
#
# WHAT IT ASKS (each result PASS / FAIL / UNPROVEN):
#   A. three turns are stored as they end: his words verbatim, a line for the tool call, no tool output
#   B. a real compaction: the sentence he said BEFORE it comes back after it, as a quote with its turn
#   C. words attributed to him: one he said is let through, one he never said is named back, once
#   D. the off switch: a resident started with the lane off removes every row and stores nothing
#
# Usage:  build/c21-live-session.sh <hook-binary> [dist-dir] [evidence-dir]
# Exit:   0 = every check PASSED · 1 = a FAIL · 2 = could not run · 3 = nothing failed but something UNPROVEN

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOK="${1:?hook binary required}"
DIST="${2:-$ROOT/build/dist/target/dist}"
EVIDENCE="${3:-$ROOT/build/c21-live-evidence}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_C21_PORT:-8931}"
TOKEN="c21-live-$$"
MODEL="${JW_MODEL:-haiku}"

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }
[ -x "$HOOK" ] || { echo "no hook binary at $HOOK" >&2; exit 2; }
command -v claude >/dev/null || { echo "no claude CLI on PATH" >&2; exit 2; }
mkdir -p "$EVIDENCE"

WS="$(mktemp -d)"; STORE="$(mktemp -d)"; STAGE="$(mktemp -d)"; PROJECT="$(mktemp -d)"
LOG="$WS/resident.log"; RESIDENT_PID=""
cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    cp "$LOG" "$EVIDENCE/resident.log" 2>/dev/null
    cp "$WS/resident-off.log" "$EVIDENCE/resident-off.log" 2>/dev/null
    cp "$STAGE/home/.claude/jawata-studio/outcomes.log" "$EVIDENCE/outcomes.log" 2>/dev/null
    rm -rf "$WS" "$STORE" "$STAGE" "$PROJECT"
}
trap cleanup EXIT INT TERM HUP

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 && VECTOR="--add-modules jdk.incubator.vector"

start_resident() {   # $1 = log file, $2.. = extra java args
    local log="$1"; shift
    # shellcheck disable=SC2086
    java $VECTOR -Xmx3g -Djawata.experience.shared.dir="$STORE" "$@" \
         -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$log" 2>&1 &
    RESIDENT_PID=$!
    local ready=0
    for _ in $(seq 1 180); do
        grep -q "READY\|Server started\|listening" "$log" 2>/dev/null && { ready=1; break; }
        kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2; tail -20 "$log" >&2; return 2; }
        sleep 1
    done
    [ "$ready" -eq 1 ] || { echo "resident never announced readiness" >&2; return 2; }
}

start_resident "$LOG" || exit 2

# The scratch project: a file the agent will read (its content is the TOOL OUTPUT that must not be
# kept), and the hooks wired as the studio wires them.
mkdir -p "$PROJECT/.claude"
echo "OUTPUTMARKERQX the first word of this file is banana" > "$PROJECT/zq7marker.txt"
for role in primer stop observer userprompt; do cp "$HOOK" "$STAGE/jawata-hook-$role"; done
mkdir -p "$STAGE/home"
cat > "$STAGE/hook_config.json" <<EOF
{"url": "http://127.0.0.1:$PORT/mcp", "token": "$TOKEN", "client": "claude-code", "timeout_ms": 20000}
EOF
cat > "$PROJECT/.claude/settings.json" <<EOF
{
  "hooks": {
    "SessionStart": [{"hooks": [{"type": "command", "command": "env HOME=$STAGE/home $STAGE/jawata-hook-primer"}]}],
    "UserPromptSubmit": [{"hooks": [{"type": "command", "command": "env HOME=$STAGE/home $STAGE/jawata-hook-userprompt"}]}],
    "PostToolUse": [{"matcher": "", "hooks": [{"type": "command", "command": "env HOME=$STAGE/home $STAGE/jawata-hook-observer"}]}],
    "Stop": [{"hooks": [{"type": "command", "command": "env HOME=$STAGE/home $STAGE/jawata-hook-stop"}]}]
  },
  "permissions": {"allow": ["Read"]}
}
EOF

PROBE_STAGE="$STAGE" PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_PROJECT="$PROJECT" PROBE_EVIDENCE="$EVIDENCE" \
PROBE_MODEL="$MODEL" PROBE_HOOK_DIR="$STAGE" python3 - << 'PY'
import json, os, subprocess, urllib.request, uuid

PORT = os.environ["PROBE_PORT"]; TOKEN = os.environ["PROBE_TOKEN"]
PROJECT = os.environ["PROBE_PROJECT"]; EVIDENCE = os.environ["PROBE_EVIDENCE"]
MODEL = os.environ["PROBE_MODEL"]; STAGE = os.environ["PROBE_STAGE"]
passed = failed = unproven = 0
def ok(m):
    global passed; passed += 1; print("  PASS      " + m)
def bad(m):
    global failed; failed += 1; print("  FAIL      " + m)
def unknown(m):
    global unproven; unproven += 1; print("  UNPROVEN  " + m)

def call(args):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": "experience", "arguments": args}}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": "c21-live",
                 "Content-Type": "application/json"})
    return json.loads(json.loads(urllib.request.urlopen(r, timeout=900).read().decode())
                      ["result"]["content"][0]["text"])

def recall(words, **extra):
    a = {"kind": "recall", "lanes": ["transcript"], "symptom": words}
    a.update(extra)
    d = call(a)
    return (d.get("data") or {}) if isinstance(d, dict) else {}

SESSION = str(uuid.uuid4())
def turn(name, prompt, first=False):
    out = os.path.join(EVIDENCE, name + ".jsonl")
    args = ["claude", "-p", prompt, "--setting-sources", "project", "--output-format", "stream-json",
            "--verbose", "--include-hook-events", "--max-turns", "6", "--model", MODEL,
            "--permission-mode", "acceptEdits"]
    args += ["--session-id", SESSION] if first else ["--resume", SESSION]
    run = subprocess.run(args, cwd=PROJECT, capture_output=True, text=True, timeout=900,
                         stdin=subprocess.DEVNULL)
    open(out, "w").write(run.stdout)
    return run.stdout

def events(text):
    for line in text.splitlines():
        try:
            yield json.loads(line)
        except ValueError:
            pass

HIS = ("The lighthouse keeper's rule is that every ledger entry must be signed twice before "
       "the morning watch, and nothing said here may be forgotten after a compaction.")

print("\n--- A. turns are stored as they end --------------------------------------------")
turn("1-first-turn", HIS + " Remember it. Also read the only .txt file in this directory and answer only yes or no: is it non-empty?", first=True)
turn("2-second-turn", "What is two plus two? Answer in one short sentence.")
turn("3-third-turn", "Name three colours in one short sentence.")
turn("4-fourth-turn", "Name three animals in one short sentence.")

found = recall("lighthouse keeper ledger entry signed twice morning watch")
rows = found.get("entries") or []
mine = [r for r in rows if r.get("type") == "transcript" and r.get("who") == "he"
        and "signed twice before the morning watch" in str(r.get("summary"))]
if mine:
    ok("his sentence is stored verbatim as HIS words, with its turn: %s" % mine[0].get("situation"))
else:
    bad("his sentence was not found among his words: %s" % json.dumps(rows)[:400])

scanned = (found.get("scanned") or {}).get("transcript", 0)
if scanned and scanned >= 3:
    ok("the lane holds %s turns after four prompts (every turn that ended was stored)" % scanned)
else:
    bad("expected at least 3 turns stored after four prompts, the lane holds %s" % scanned)

# The tool line carries the file's name, which none of his or the agent's words do (he is told to
# find it, and the agent is told to answer yes or no), so asking for it reaches the tool line.
tool = recall("zq7marker")
tool_rows = [r for r in (tool.get("entries") or []) if r.get("who") == "tool"]
read_happened = any(
    b.get("type") == "tool_use" and "zq7marker" in json.dumps(b.get("input"))
    for e in events(open(os.path.join(EVIDENCE, "1-first-turn.jsonl")).read())
    if e.get("type") == "assistant" for b in (e.get("message") or {}).get("content", []))
if tool_rows and "zq7marker" in str(tool_rows[0].get("summary")):
    ok("the tool call is one line (%s)" % str(tool_rows[0].get("summary"))[:100])
elif not read_happened:
    unknown("the agent never read the file in turn 1, so there is no tool call to find (see 1-first-turn.jsonl)")
else:
    bad("no tool line for the read of zq7marker.txt: %s" % json.dumps(tool)[:1800])

leak = recall("OUTPUTMARKERQX banana")
if any("OUTPUTMARKERQX" in str(r.get("summary")) for r in (leak.get("entries") or [])):
    bad("tool OUTPUT reached the lane: %s" % json.dumps(leak)[:300])
else:
    ok("the content of the file the tool read is NOT in the lane (no tool output)")

print("\n--- B. a real compaction -------------------------------------------------------")
comp = turn("5-compact", "/compact")
after = None
for e in events(comp):
    if e.get("type") == "system" and e.get("subtype") == "hook_response" and e.get("hook_event") == "SessionStart":
        out = str(e.get("output") or e.get("stdout") or "")
        if "AFTER A COMPACTION" in out:
            after = out
if after is None:
    unknown("no SessionStart hook output named a compaction (the CLI may not have compacted: see 5-compact.jsonl)")
elif "signed twice before the morning watch" in after:
    ok("after the compaction the sentence from before it came back as his words, with its turn")
else:
    bad("the thread after the compaction lacks his sentence: %s" % after[:400])

ondemand = recall("lighthouse keeper signed twice")
pointer = [r for r in (ondemand.get("entries") or []) if r.get("who") == "he" and r.get("turn")]
if pointer:
    ok("asked on demand, the quote carries its pointer (session %s, turn %s)"
       % (str(pointer[0].get("session"))[:8], pointer[0].get("turn")))
else:
    bad("on demand returned no quote with a turn pointer: %s" % json.dumps(ondemand)[:300])

print("\n--- C. words attributed to him -------------------------------------------------")
def stop_hook(final_text, name):
    path = os.path.join(EVIDENCE, name + "-transcript.jsonl")
    lines = [
        {"type": "user", "uuid": name + "-u", "timestamp": "2026-10-17T12:00:00Z",
         "message": {"role": "user", "content": "go on"}},
        {"type": "assistant", "uuid": name + "-a",
         "message": {"content": [{"type": "text", "text": final_text}]}},
    ]
    open(path, "w").write("\n".join(json.dumps(l) for l in lines))
    payload = json.dumps({"session_id": "attr-" + name, "transcript_path": path,
                          "hook_event_name": "Stop", "stop_hook_active": False})
    env = dict(os.environ, HOME=os.path.join(STAGE, "home"))
    r = subprocess.run([os.path.join(STAGE, "jawata-hook-stop")], input=payload, text=True,
                       capture_output=True, env=env, timeout=60)
    return r.stdout.strip()

said = stop_hook('He said "every ledger entry must be signed twice before the morning watch" earlier.', "said")
if '"block"' in said and "signed twice" in said:
    bad("a sentence he DID say was named back: %s" % said[:300])
else:
    ok("a sentence he said is let through (stop output: %r)" % said[:80])

never = stop_hook('He said "we should rewrite the whole engine in Rust tonight" earlier.', "never")
if '"block"' in never and "rewrite the whole engine" in never:
    ok("a sentence he never said is named back with a block")
else:
    bad("an invented quote was not named back: %r" % never[:300])

again = stop_hook('He said "we should rewrite the whole engine in Rust tonight" earlier.', "never")
if '"block"' in again:
    bad("the same invented quote held the stop twice: %r" % again[:200])
else:
    ok("named once: the same sentence does not hold the stop again")

print("\n--- D. the off switch ------------------------------------------------------------")
before = (call({"kind": "stats"}).get("data") or {}).get("transcript", {}).get("turns")
if not before or before < 1:
    bad("setup: the lane held no turns before the switch was turned off (%s)" % before)
else:
    print("  (the lane holds %s turns before the switch)" % before)
print("C21-PHASE-D-BEFORE=%s" % before)

print("\nC21-LIVE-SESSION passed=%d failed=%d unproven=%d" % (passed, failed, unproven))
open(os.path.join(EVIDENCE, "phase1-result.json"), "w").write(
    json.dumps({"passed": passed, "failed": failed, "unproven": unproven, "before": before}))
raise SystemExit(0)
PY

# Phase D needs the resident restarted with the switch off, over the SAME store.
kill "$RESIDENT_PID" 2>/dev/null; wait "$RESIDENT_PID" 2>/dev/null; RESIDENT_PID=""
start_resident "$WS/resident-off.log" -Djawata.transcript.enabled=false || exit 2

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_EVIDENCE="$EVIDENCE" python3 - << 'PY'
import json, os, urllib.request
PORT = os.environ["PROBE_PORT"]; TOKEN = os.environ["PROBE_TOKEN"]; EVIDENCE = os.environ["PROBE_EVIDENCE"]
prior = json.load(open(os.path.join(EVIDENCE, "phase1-result.json")))
passed, failed, unproven = prior["passed"], prior["failed"], prior["unproven"]
def call(args):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": "experience", "arguments": args}}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": "c21-off",
                 "Content-Type": "application/json"})
    return json.loads(json.loads(urllib.request.urlopen(r, timeout=900).read().decode())
                      ["result"]["content"][0]["text"])
def ok(m):
    global passed; passed += 1; print("  PASS      " + m)
def bad(m):
    global failed; failed += 1; print("  FAIL      " + m)

turns = (call({"kind": "stats"}).get("data") or {}).get("transcript", {})
if turns.get("enabled") is False and turns.get("turns") == 0:
    ok("a resident started with the lane off holds zero turns (it removed %s on start)" % prior.get("before"))
else:
    bad("the lane was switched off and still reports %s" % json.dumps(turns))
r = call({"kind": "transcript_append", "session": "off", "key": "k1", "his": "this must not be kept"})
if (r.get("data") or {}).get("appended") == "disabled":
    ok("an append while off is refused: nothing is captured")
else:
    bad("an append while off answered %s" % json.dumps(r)[:200])

print("\nC21-LIVE-SESSION passed=%d failed=%d unproven=%d" % (passed, failed, unproven))
raise SystemExit(1 if failed else (3 if unproven else 0))
PY
