#!/usr/bin/env bash
# jawata-gate: probe
# Sprint 28h, Stage 27, row (z53) — a hire takes the brain and model STORED for its seat, and nothing else. Driven through the BUILT ARTIFACT's own
# front door, as a plain client would: no Mcp-Session-Id, so the engine knows it as a guest, which may hire as `main` only.
#
# WHAT THIS PROVES THAT A UNIT TEST CANNOT: that the engine process, started from the dist, opens the seat store at its start; that a hire with
# nothing stored is refused and starts NO process (the stand-in brain logs every start); that a stored choice reaches the program's own command
# line (the stand-in logs its arguments); that a change between two hires changes the second and not the first; and that the choice survives an
# engine restart. The hired brain is a stand-in on PATH, so this spends nothing.
#
# Exits 0 on PASS, 1 on FAIL, 2 for "could not run at all", which is never a pass.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8914}"
TOKEN="z53-probe-$$"

WS="$(mktemp -d)"
STORE="$(mktemp -d)"
LOG="$WS/resident.log"
RESIDENT_PID=""
cleanup() {
    cp "$LOG" /tmp/z53-resident.log 2>/dev/null
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ] || { echo "no artifact at $JAR: build first" >&2; exit 2; }

# A seat file that names NO brain and NO model: the stored choice is the only source.
cat > "$WS/pm.md" <<'SEAT'
---
name: pm
kind: code
effort: medium
tools: [inspect, hire]
gates: []
ttl_secs: 120
max_iterations: 1
cost_budget_usd: 1.0
max_tool_steps: 20
---
You are the pm seat.
SEAT
printf '# plan\n<!-- board:begin -->\n<!-- board:end -->\n' > "$WS/sprint-z53-plan.md"

# THE STAND-IN BRAINS, on PATH. Each start is logged with its arguments, so "no process started" and "the model reached the program" are both read.
BIN="$WS/bin"
mkdir -p "$BIN"
for brain in claude cursor-agent copilot codex; do
    # Z53_REAL_ACP=1: the chat's own agent connection with the REAL Claude (capped by the seat file's cost budget); the other brains stay stand-ins.
    [ -n "${Z53_REAL_ACP:-}" ] && [ "$brain" = "claude" ] && continue
    cat > "$BIN/$brain" <<BRAIN
#!/bin/sh
echo "$brain \$*" >> "$WS/started.log"
echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"the stand-in did the work"}]}}'
echo '{"type":"result","result":"the stand-in did the work","total_cost_usd":0.01}'
BRAIN
    chmod +x "$BIN/$brain"
done
export PATH="$BIN:$PATH"
if [ -z "${Z53_REAL_ACP:-}" ]; then
    [ "$(command -v claude)" = "$BIN/claude" ] || { echo "PATH substitution failed: refusing to run, the next step would spawn a real brain" >&2; exit 2; }
else
    command -v claude-agent-acp >/dev/null || { echo "no claude-agent-acp on PATH: cannot run the chat's own connection" >&2; exit 2; }
fi
: > "$WS/started.log"

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 && VECTOR="--add-modules jdk.incubator.vector"

# The registry says which brains the engine may start (no `command`, so the command-line path, which the stand-in answers).
REGISTRY='{"claude-code":{"vendor":"anthropic","model":"Haiku 5.5"}}'
# The chat's own path: a brain with a program that speaks the agent protocol is run through the engine's client, not through its command line.
[ -n "${Z53_REAL_ACP:-}" ] && REGISTRY='{"claude-code":{"command":["claude-agent-acp"],"vendor":"anthropic","model":"Haiku 5.5"}}'

start_engine() {
    # shellcheck disable=SC2086
    java $VECTOR -Djawata.experience.shared.dir="$STORE" -Djawata.seats.brains.file="$WS/seats-brains" \
         "-Djawata.agent.hireBrains=$REGISTRY" -Djawata.hire.stallSeconds=60 -Djawata.hire.stallStopSeconds=0 \
         -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
    RESIDENT_PID=$!
    for _ in $(seq 1 120); do
        grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && return 0
        kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2; tail -20 "$LOG" >&2; return 2; }
        sleep 1
    done
    echo "resident never announced readiness in 120s:" >&2; tail -20 "$LOG" >&2; return 2
}

stop_engine() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    RESIDENT_PID=""
}

run_phase() {
    PROBE_PHASE="$1" PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_WS="$WS" python3 -I - << 'PY'
import json, os, time, urllib.request

PORT = os.environ["PROBE_PORT"]; TOKEN = os.environ["PROBE_TOKEN"]; WS = os.environ["PROBE_WS"]; PHASE = os.environ["PROBE_PHASE"]
passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT, data=body,
                               headers={"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(r, timeout=300) as resp:
        return json.loads(resp.read().decode())

def call(tool, args):
    answer = rpc("tools/call", {"name": tool, "arguments": args})
    for part in answer.get("result", {}).get("content", []):
        if part.get("type") == "text":
            try: return json.loads(part["text"])
            except json.JSONDecodeError: return {"_text": part["text"]}
    return {"_raw": answer}

def started():
    return [l for l in open(WS + "/started.log").read().splitlines() if l.strip()]

def hire_pm():
    return call("hire", {"hirer": "main", "seat": "pm", "seatFile": WS + "/pm.md", "brief": "z53 stored choice probe " + str(time.time()),
                         "boardFile": WS + "/sprint-z53-plan.md", "workingDirectory": WS, "steps": [{"tool": "health_check", "arguments": {}}]})

def finished_report(answer):
    hid = (answer.get("data") or {}).get("hireId", "")
    done = {}
    for _ in range(6):
        done = call("hire_wait", {"hireId": hid, "waitSeconds": 40}).get("data", {})
        if done.get("state") not in ("running", None): break
    return done.get("report") or {}

names = sorted(t["name"] for t in rpc("tools/list", {}).get("result", {}).get("tools", []))
if "brains" in names: ok("PROOF OF LIFE: brains is published (%d tools)" % len(names))
else: bad("brains is not published: %s" % names[:8])

if PHASE == "first":
    # ---- 1. NOTHING STORED: the hire is refused and no process starts
    before = len(started())
    refused = hire_pm()
    err = refused.get("error") or {}
    text = json.dumps(refused)
    if not refused.get("success") and "pm" in text and ("Seats page" in text or "drop-down" in text):
        ok("with nothing stored the hire is refused, naming the seat and where to set it: %s" % (err.get("code") or "an error"))
    else:
        bad("a hire with nothing stored was not refused with the seat and where to set it: %s" % text[:400])
    if len(started()) == before: ok("CONTROL: the refused hire started no process")
    else: bad("a process was started although the hire was refused: %s" % started()[before:])
    ch = call("brains", {"action": "choices"})
    if ch.get("success") and (ch.get("data") or {}).get("seats") == []: ok("choices answers an empty list, not a default")
    else: bad("choices with nothing stored did not answer an empty list: %s" % json.dumps(ch)[:300])

    # ---- 2. A WRONG BRAIN IS NOT STORED
    wrong = call("brains", {"action": "set", "seat": "pm", "brain": "no-such-brain", "model": "x"})
    after = call("brains", {"action": "choices"})
    if not wrong.get("success") and (after.get("data") or {}).get("seats") == []: ok("a brain the engine cannot start is refused and not stored")
    else: bad("a wrong brain was stored or not refused: %s / %s" % (json.dumps(wrong)[:200], json.dumps(after)[:200]))

    # ---- 3. A STORED CHOICE IS WHAT THE HIRE RUNS ON, AND REACHES THE PROGRAM
    setp = call("brains", {"action": "set", "seat": "pm", "brain": "claude-code", "model": "Haiku 5.5"})
    if setp.get("success"): ok("a choice is stored: %s" % json.dumps(setp.get("data"))[:160])
    else: bad("set was refused: %s" % json.dumps(setp)[:300])
    before = len(started())
    h1 = hire_pm()
    r1 = finished_report(h1)
    b1 = r1.get("brain") or {}
    if b1.get("brain") == "claude-code" and b1.get("model") == "Haiku 5.5": ok("the first hire recorded brain claude-code and model Haiku 5.5")
    else: bad("the first hire's record is not the stored pair: %s" % json.dumps(b1))
    new1 = started()[before:]
    # THE PROGRAM TAKES ITS OWN VALUE, NOT THE DISPLAY NAME: measured 2026-11-22, `claude --model "Haiku 5.5"` is refused (unrecognized_model);
    # `haiku` and `claude-haiku-5-5` are accepted. So the line must carry a value the program accepts and never the name with a space.
    import re
    if any(re.search(r"--model (haiku|claude-haiku[\w.-]*)(\s|$)", l) and "Haiku 5.5" not in l for l in new1): ok("the stored model reached the program as a value it accepts: %s" % new1[-1][:160])
    else: bad("the program was not started with a Haiku value it accepts (the display name is refused by the real program): %s" % new1)

    # ---- 4. A CHANGE BETWEEN TWO HIRES CHANGES THE SECOND, NOT THE FIRST
    call("brains", {"action": "set", "seat": "pm", "brain": "claude-code", "model": "Sonnet 5.5"})
    before = len(started())
    h2 = hire_pm()
    r2 = finished_report(h2)
    b2 = r2.get("brain") or {}
    if b2.get("model") == "Sonnet 5.5": ok("the second hire recorded the changed model Sonnet 5.5")
    else: bad("the second hire did not take the changed model: %s" % json.dumps(b2))
    new2 = started()[before:]
    if any(re.search(r"--model (sonnet|claude-sonnet[\w.-]*)(\s|$)", l) and "Sonnet 5.5" not in l for l in new2): ok("the changed model reached the program as a value it accepts: %s" % new2[-1][:160])
    else: bad("the program was not started with a Sonnet value it accepts: %s" % new2)
    if (r1.get("brain") or {}).get("model") == "Haiku 5.5": ok("CONTROL: the first hire's record still says Haiku 5.5")
    else: bad("the first hire's record changed after the second store")

elif PHASE == "acp":
    # THE REAL BRAIN OVER THE CHAT'S OWN CONNECTION: ask it which model it runs as, with Haiku stored and then Sonnet stored. A model can misreport itself,
    # so the check is that the two answers differ and name the stored family; the hire's record must also name the stored model.
    def ask(model):
        s = call("brains", {"action": "set", "seat": "pm", "brain": "claude-code", "model": model})
        if not s.get("success"): bad("could not store %s: %s" % (model, json.dumps(s)[:200])); return {}, ""
        h = call("hire", {"hirer": "main", "seat": "pm", "seatFile": WS + "/pm.md",
                          "brief": "Answer in one short line with only the name of the model you are running as. No tools. (" + str(time.time()) + ")",
                          "boardFile": WS + "/sprint-z53-plan.md", "workingDirectory": WS, "steps": [{"tool": "health_check", "arguments": {}}]})
        r = finished_report(h)
        return r, str(r.get("answer") or "")
    r1, a1 = ask("Haiku 5.5")
    print("  note  stored Haiku 5.5, the brain said: %s | cost %s" % (a1[:120], r1.get("costUsd")))
    if (r1.get("brain") or {}).get("model") == "Haiku 5.5": ok("the hire's record names the stored model Haiku 5.5")
    else: bad("the record does not name Haiku 5.5: %s" % json.dumps(r1.get("brain")))
    if "haiku" in a1.lower(): ok("the real brain over the chat's connection says it runs as Haiku")
    else: bad("the brain did not say Haiku: %r" % a1[:200])
    r2, a2 = ask("Sonnet 5.5")
    print("  note  stored Sonnet 5.5, the brain said: %s | cost %s" % (a2[:120], r2.get("costUsd")))
    if (r2.get("brain") or {}).get("model") == "Sonnet 5.5": ok("the second hire's record names the changed model Sonnet 5.5")
    else: bad("the record does not name Sonnet 5.5: %s" % json.dumps(r2.get("brain")))
    if "sonnet" in a2.lower(): ok("after the change the real brain says it runs as Sonnet")
    else: bad("the brain did not say Sonnet after the change: %r" % a2[:200])

elif PHASE == "second":
    # ---- 5. THE CHOICE SURVIVED AN ENGINE RESTART
    ch = call("brains", {"action": "choices"})
    seats = (ch.get("data") or {}).get("seats") or []
    row = [s for s in seats if s.get("seat") == "pm"]
    if row and row[0].get("brain") == "claude-code" and row[0].get("model") == "Sonnet 5.5": ok("after an engine restart the stored choice is still pm = claude-code / Sonnet 5.5")
    else: bad("the stored choice did not survive the restart: %s" % json.dumps(ch)[:300])
    before = len(started())
    r = finished_report(hire_pm())
    if (r.get("brain") or {}).get("model") == "Sonnet 5.5": ok("a hire after the restart runs on the stored model")
    else: bad("a hire after the restart did not take the stored model: %s" % json.dumps(r.get("brain")))

print("\n%d passed, %d failed" % (passed, failed))
raise SystemExit(1 if failed else 0)
PY
}

if [ -n "${Z53_REAL_ACP:-}" ]; then
    start_engine || exit 2
    run_phase acp
    exit $?
fi
start_engine || exit 2
run_phase first; FIRST=$?
stop_engine
start_engine || exit 2
run_phase second; SECOND=$?
[ "$FIRST" -eq 0 ] && [ "$SECOND" -eq 0 ] && exit 0
exit 1
