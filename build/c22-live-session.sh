#!/usr/bin/env bash
# Sprint 28g Stage 22, C22 clause 4 — THE LANES IN A LIVE SESSION, TRANSCRIPT AS THE EVIDENCE.
#
# WHY A SESSION. Every unit test in both products drives a hook by calling it, which is the one
# thing a real session never does. This runs a real `claude -p` session whose hooks are the new
# hook binaries, pointed at a private resident built from the new dist, over a scratch project and
# a scratch store — never the developer's own. The stream-json transcript of each prompt is kept.
#
# WHAT IT ASKS (each prompt is one fresh session, each result PASS / FAIL / UNPROVEN):
#   1. a typed message that mentions a lesson's words      -> the lesson arrives, no code map
#   2. a request to write a method a recorded job already does
#                                                          -> the write is REFUSED, naming the job
#   3. the same task with a genuinely NEW method           -> exactly one jawata-distinct line
# and, over the whole run, the resident's log shows no refusal for a missing lane.
#
# Usage:  build/c22-live-session.sh <hook-binary> [dist-dir] [evidence-dir]
#           <hook-binary>  the jawata-hook built from the studio at the commit under test
# Exit:   0 = every check PASSED · 1 = a FAIL · 2 = could not run · 3 = nothing failed but something is UNPROVEN
#         (UNPROVEN is never a pass, so it does not exit 0)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOK="${1:?hook binary required}"
DIST="${2:-$ROOT/build/dist/target/dist}"
EVIDENCE="${3:-$ROOT/build/c22-live-evidence}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_C22_PORT:-8917}"
TOKEN="c22-live-$$"
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
    rm -rf "$WS" "$STORE" "$STAGE" "$PROJECT"
}
trap cleanup EXIT INT TERM HUP

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 && VECTOR="--add-modules jdk.incubator.vector"
# shellcheck disable=SC2086
java $VECTOR -Xmx3g -Djawata.experience.shared.dir="$STORE" \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
READY=0
for _ in $(seq 1 180); do
    grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2; tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "resident never announced readiness" >&2; exit 2; }

# The scratch project: one Java file, and the hooks wired exactly as the studio wires them.
mkdir -p "$PROJECT/src/com/example" "$PROJECT/.claude"
cat > "$PROJECT/src/com/example/Util.java" <<'JAVA'
package com.example;

public final class Util {
    private Util() { }
}
JAVA
for role in userprompt recall guard; do cp "$HOOK" "$STAGE/jawata-hook-$role"; done
mkdir -p "$STAGE/home"
cat > "$STAGE/hook_config.json" <<EOF
{"url": "http://127.0.0.1:$PORT/mcp", "token": "$TOKEN", "client": "claude-code", "timeout_ms": 20000}
EOF
cat > "$PROJECT/.claude/settings.json" <<EOF
{
  "hooks": {
    "UserPromptSubmit": [{"hooks": [{"type": "command", "command": "env HOME=$STAGE/home $STAGE/jawata-hook-userprompt"}]}],
    "PreToolUse": [
      {"matcher": "Write|Edit|MultiEdit", "hooks": [{"type": "command", "command": "env HOME=$STAGE/home $STAGE/jawata-hook-recall"}]},
      {"matcher": "Bash", "hooks": [{"type": "command", "command": "env HOME=$STAGE/home $STAGE/jawata-hook-guard"}]}
    ]
  },
  "permissions": {"allow": ["Write", "Edit", "MultiEdit", "Bash(echo:*)", "Read"]}
}
EOF

PROBE_STAGE="$STAGE" PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_PROJECT="$PROJECT" PROBE_EVIDENCE="$EVIDENCE" \
PROBE_MODEL="$MODEL" PROBE_RESIDENT_LOG="$LOG" python3 - << 'PY'
import json, os, subprocess, urllib.request

PORT = os.environ["PROBE_PORT"]; TOKEN = os.environ["PROBE_TOKEN"]
PROJECT = os.environ["PROBE_PROJECT"]; EVIDENCE = os.environ["PROBE_EVIDENCE"]
MODEL = os.environ["PROBE_MODEL"]; RLOG = os.environ["PROBE_RESIDENT_LOG"]
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
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": "c22-live",
                 "Content-Type": "application/json"})
    return json.loads(json.loads(urllib.request.urlopen(r, timeout=900).read().decode())
                      ["result"]["content"][0]["text"])

def record(**a):
    if not call(dict(kind="record", **a)).get("success"):
        raise SystemExit("fixture would not record: %s" % a)

record(type="job", symbol="com.example.Money#roundToCents",
       summary="Rounds a money amount to the nearest cent, halves going away from zero")
record(type="lesson", symptoms=["rounding drift in the ledger totals"],
       situation="when ledger totals drift by a cent after rounding",
       verdict="worked",
       summary="Round once at the edge, never per line, or the cents drift apart")

def session(name, prompt, turns=10):
    out = os.path.join(EVIDENCE, name + ".jsonl")
    run = subprocess.run(
        ["claude", "-p", prompt, "--setting-sources", "project", "--output-format",
         "stream-json", "--verbose", "--include-hook-events", "--max-turns", str(turns), "--model", MODEL,
         "--permission-mode", "acceptEdits"],
        cwd=PROJECT, capture_output=True, text=True, timeout=900)
    open(out, "w").write(run.stdout)
    return run.stdout

def events(text):
    for line in text.splitlines():
        try:
            yield json.loads(line)
        except ValueError:
            pass

print("\n--- 1. a typed message: the lesson arrives, no code map -----------------------")
t1 = session("1-typed-message",
             # The hook takes its cues from the FIRST words of a message (cue.rs: the first trigram and
             # two bigrams), so the lesson's subject comes first, as a person describing a problem writes it.
             "Rounding drift in the ledger totals: have we learned anything about that? "
             "No changes please.")
if "JAWATA MAP" in t1:
    bad("a typed message brought the code map")
elif "Round once at the edge" in t1:
    ok("the lesson recorded for the typed message's words reached the session, with no code map")
else:
    unknown("no code map (right), but the lesson did not show in the transcript either")

print("\n--- 2. writing a method a recorded job already does ---------------------------")
t2 = session("2-rederiving-write",
             "In src/com/example/Util.java add a static method that rounds a money amount "
             "to the nearest cent, halves away from zero. Use the Write tool on that file.")
denied = [e for e in events(t2) if "roundToCents" in json.dumps(e) and "HELD" in json.dumps(e)]
if denied:
    ok("the write was HELD and the refusal named the recorded job (Money#roundToCents)")
else:
    bad("no refusal naming the recorded job appeared in the transcript")

print("\n--- 3. a genuinely new method --------------------------------------------------")
t3 = session("3-new-method",
             "In src/com/example/Util.java add a static method celsiusToFahrenheit(double). "
             "Use the Write tool on that file. If the tooling holds the write, follow what it says.")
evs = list(events(t3))
def bash_commands(evs):
    out = []
    for i, e in enumerate(evs):
        content = (e.get("message") or {}).get("content") if isinstance(e.get("message"), dict) else None
        for block in content if isinstance(content, list) else []:
            if isinstance(block, dict) and block.get("type") == "tool_use" and block.get("name") == "Bash":
                out.append((i, str((block.get("input") or {}).get("command", ""))))
    return out
held_at = next((i for i, e in enumerate(evs) if "HELD" in json.dumps(e)), None)
said = [(i, c) for i, c in bash_commands(evs) if "jawata-distinct:" in c or "jawata-duplicate:" in c]
wrote = "celsiusToFahrenheit" in open(os.path.join(PROJECT, "src/com/example/Util.java")).read()
if not wrote:
    bad("the new method never reached the file")
elif held_at is None:
    unknown("the new method was written and no write was HELD, so the declaration route was not exercised")
elif len(said) != 1:
    bad("a genuinely new method must cost exactly ONE declaration line, and the session made %d" % len(said))
elif said[0][0] < held_at:
    bad("the declaration came BEFORE the refusal it should answer")
else:
    ok("the new method was HELD once, answered with exactly one declaration line, and then written")

print("\n--- the declaration reaches the architect's watch (the gate's signal log) --------")
signal_log = os.path.join(os.environ["PROBE_STAGE"], "home", ".claude", "jawata-studio", "outcomes.log")
try:
    lines = [l for l in open(signal_log).read().splitlines()
             if "duplicate-distinct-declared" in l or "duplicate-declared" in l]
except OSError:
    lines = []
if any(" :: " in l for l in lines):
    ok("the declaration is logged WITH the job it was given about: %s" % lines[-1][-160:])
else:
    bad("no declaration line with a job location in the signal log (%d lines found)" % len(lines))

print("\n--- the whole run: no hook call was refused for a missing lane ------------------")
import re
def lane_refusals():
    return len(re.findall(r"lane refused for kind=", open(RLOG).read()))
before = lane_refusals()
# A gate that cannot see zero is not a gate: make one lane-less call ON PURPOSE and require the
# log to count it, so a zero before it means something.
call({"kind": "recall", "symptom": "a probe that names no lane on purpose"})
after = lane_refusals()
if after != before + 1:
    unknown("the resident log did not count a deliberate lane refusal (before %d, after %d), so a "
            "zero before it proves nothing" % (before, after))
elif before == 0:
    ok("the engine refused no hook call for a missing lane during the session (and its log "
       "counted the deliberate one)")
else:
    bad("%d call(s) were refused for a missing lane during the session" % before)

print("\nC22-LIVE-SESSION passed=%d failed=%d unproven=%d" % (passed, failed, unproven))
raise SystemExit(1 if failed else (3 if unproven else 0))
PY
