#!/usr/bin/env bash
# jawata-gate: probe
# Sprint 28h, Stage 27, rows (z30) and (z28) — a client that runs jawata WITHOUT the jawata chat collects a hire's report with `hire_wait`,
# and is told when a hire has gone quiet. Driven through the BUILT ARTIFACT's own front door, as a plain MCP client would: no
# Mcp-Session-Id header, so the engine knows it as a guest, and it hires as `main`, the one seat a guest may claim (z25).
#
# WHAT THIS PROVES THAT A UNIT TEST CANNOT: that `hire_wait` is PUBLISHED and WIRED, that `hire` answers at once with a hireId, that the
# report is collected once as new and then as already-read, and that a hire quiet past the first ceiling is answered as stalled over the wire.
#
# WHAT IT DELIBERATELY DOES NOT DO: spend a real brain. The hired brain is a stand-in on PATH (the c16 probe's measured lesson: a `binary`
# argument was inert and a real claude ran for $1.28); it speaks claude's own event shape. The client here is this script, not Claude Code,
# Cursor or Copilot: it proves the wire a plain client uses, not those clients' own timeouts, which are the test manager's probe (z15).
#
# Exits 0 on PASS, 1 on FAIL, 2 for "could not run at all", which is never a pass.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8908}"
TOKEN="z30-probe-$$"

WS="$(mktemp -d)"
STORE="$(mktemp -d)"
LOG="$WS/resident.log"
RESIDENT_PID=""
cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ] || { echo "no artifact at $JAR: build first" >&2; exit 2; }

cat > "$WS/pm.md" <<'SEAT'
---
name: pm
model: claude-sonnet-5
kind: code
effort: medium
tools: [inspect, hire]
gates: []
ttl_secs: 300
max_iterations: 1
cost_budget_usd: 1.0
max_tool_steps: 20
---
You are the pm seat.
SEAT

sed 's/^name: pm$/name: test-manager/; s/^tools: .*/tools: [inspect, run_tests]/' "$WS/pm.md" > "$WS/test-manager.md"
sed 's/^name: pm$/name: developer/; s/^tools: .*/tools: [inspect, run_tests]/' "$WS/pm.md" > "$WS/developer.md"
printf '# plan\n<!-- board:begin -->\n<!-- board:end -->\n' > "$WS/sprint-z30-plan.md"

# THE STAND-IN BRAINS, on PATH: slow when the file $WS/slow exists, quick otherwise.
BIN="$WS/bin"
mkdir -p "$BIN"
for brain in claude cursor-agent copilot codex; do
    cat > "$BIN/$brain" <<BRAIN
#!/bin/sh
if [ -f "$WS/slow" ]; then sleep "\$(cat "$WS/slow")"; fi
echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"the stand-in pm did the work"}]}}'
echo '{"type":"result","result":"the stand-in pm did the work","total_cost_usd":0.01}'
BRAIN
    chmod +x "$BIN/$brain"
done
export PATH="$BIN:$PATH"
[ "$(command -v claude)" = "$BIN/claude" ] || { echo "PATH substitution failed: refusing to run, the next step would spawn a real brain" >&2; exit 2; }

# THE FIXTURE PROJECT lives INSIDE the throwaway workspace: the engine refuses a project outside the folders it was given.
cp -r "$ROOT/org.jawata.core.tests/test-resources/sample-projects/simple-maven" "$WS/proj"

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 && VECTOR="--add-modules jdk.incubator.vector"

# THE FIRST CEILING IS A HOST SETTING (z28): three seconds here, so the probe does not wait five minutes. The stop ceiling is off.
# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$STORE" -Djawata.hire.stallSeconds=3 -Djawata.hire.stallStopSeconds=0 \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
READY=0
for _ in $(seq 1 120); do
    grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2; tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "resident never announced readiness in 120s:" >&2; tail -20 "$LOG" >&2; exit 2; }

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_WS="$WS" PROBE_PROJECT="$WS/proj" python3 -I - << 'PY'
import json, os, time, urllib.request

PORT = os.environ["PROBE_PORT"]; TOKEN = os.environ["PROBE_TOKEN"]; WS = os.environ["PROBE_WS"]
passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def rpc(method, params, session=None):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN}
    if session:
        headers["Mcp-Session-Id"] = session
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT, data=body, headers=headers)
    with urllib.request.urlopen(r, timeout=300) as resp:
        return json.loads(resp.read().decode())

def call(tool, args, session=None):
    answer = rpc("tools/call", {"name": tool, "arguments": args}, session)
    for part in answer.get("result", {}).get("content", []):
        if part.get("type") == "text":
            try:
                return json.loads(part["text"])
            except json.JSONDecodeError:
                return {"_text": part["text"]}
    return {"_raw": answer}

def hire(brief, hirer="main", seat="pm", session=None):
    return call("hire", {"hirer": hirer, "seat": seat, "seatFile": WS + "/" + seat + ".md", "brief": brief,
                         "boardFile": WS + "/sprint-z30-plan.md", "workingDirectory": WS,
                         "steps": [{"tool": "health_check", "arguments": {}}]}, session)

names = sorted(t["name"] for t in rpc("tools/list", {}).get("result", {}).get("tools", []))
if len(names) > 20: ok("PROOF OF LIFE: the front door publishes %d tools" % len(names))
else: bad("only %d tools published: the checks below mean nothing" % len(names))
if "hire_wait" in names: ok("hire_wait is PUBLISHED on the wire")
else: bad("hire_wait is NOT published")

# ---- 1. a quick hire: answered at once with a hireId, then collected with hire_wait
started = hire("z30 quick pm work")
d = started.get("data", {})
if started.get("success") and d.get("hireId") and d.get("state") == "running":
    ok("hire answered at once with hireId=%s state=running" % d["hireId"])
else:
    bad("hire did not answer at once with a hireId: %s" % json.dumps(started)[:400])
hid = d.get("hireId", "")
waited = call("hire_wait", {"hireId": hid, "waitSeconds": 40}).get("data", {})
if waited.get("state") == "finished" and waited.get("newToYou") is True and waited.get("report"):
    ok("hire_wait collected the report: state=finished newToYou=true")
else:
    bad("hire_wait did not return a finished, new report: %s" % json.dumps(waited)[:400])
again = call("hire_wait", {"hireId": hid, "waitSeconds": 0}).get("data", {})
if again.get("state") == "finished" and again.get("newToYou") is False:
    ok("CONTROL: the second hire_wait says the report is no longer new")
else:
    bad("the second hire_wait did not say newToYou=false: %s" % json.dumps(again)[:300])
unknown = call("hire_wait", {"hireId": "no-such-hire", "waitSeconds": 0}).get("data", {})
if unknown.get("state") == "unknown": ok("CONTROL: an id no hire has is answered unknown")
else: bad("an unknown id was not answered unknown: %s" % json.dumps(unknown)[:200])

# ---- 2. a hire that goes quiet: told as stalled past the first ceiling (3 s here), not before it
open(WS + "/slow", "w").write("12")
slow = hire("z30 quiet pm work").get("data", {})
sid = slow.get("hireId", "")
early = call("hire_wait", {"hireId": sid, "waitSeconds": 0}).get("data", {})
if early.get("state") == "running" and early.get("stalled") is False:
    ok("CONTROL: a hire that has just started is running and not stalled (idleSeconds=%s)" % early.get("idleSeconds"))
else:
    bad("a hire that had just started was not answered running and not stalled: %s" % json.dumps(early)[:300])
time.sleep(5)
late = call("hire_wait", {"hireId": sid, "waitSeconds": 0}).get("data", {})
if late.get("state") == "running" and late.get("stalled") is True:
    ok("a hire quiet past the ceiling is answered stalled (idleSeconds=%s)" % late.get("idleSeconds"))
else:
    bad("a quiet hire was not answered stalled: %s" % json.dumps(late)[:300])
done = call("hire_wait", {"hireId": sid, "waitSeconds": 40}).get("data", {})
if done.get("state") == "finished": ok("the quiet hire still finished and its report was collected")
else: bad("the quiet hire did not finish: %s" % json.dumps(done)[:300])

# ---- 3. THE SEATS ARE PLACED FROM THE PLAN ID A HIRED BRAIN PRESENTS (z25), and a full run is the test manager's decision with its reason kept (z27).
# A hired brain reaches the engine as its plan id in the session header, so the calls below are the ones a real hired brain makes.
open(WS + "/slow", "w").write("60")
loaded = call("load_project", {"projectPath": os.environ["PROBE_PROJECT"]})
if loaded.get("success"): ok("a fixture project is loaded")
else: bad("the fixture project did not load: %s" % json.dumps(loaded)[:200])
pm = hire("z27 the pm").get("data", {})
pm_id = pm.get("hireId", "")
tmR = hire("z27 the test manager", hirer="pm", seat="test-manager", session=pm_id)
tm = tmR.get("data", {})
tm_id = tm.get("hireId", "")
devR = hire("z27 the developer", hirer="pm", seat="developer", session=pm_id)
dev = devR.get("data", {})
dev_id = dev.get("hireId", "")
if pm_id and tm_id and dev_id: ok("a pm hired a test manager and a developer, each running, as its own plan id: %s, %s" % (tm_id[:13], dev_id[:13]))
else: bad("the chain of hires did not start: tm=%s dev=%s" % (json.dumps(tmR)[:300], json.dumps(devR)[:300]))

full = {"scope": {"kind": "package", "packageName": "sample"}, "fullRun": True}
byDev = call("run_tests", dict(full, reason="a developer's argument"), session=dev_id)
if not byDev.get("success", True) is False and byDev.get("error", {}).get("code") != "FULL_RUN_NOT_ALLOWED":
    bad("a developer's session ordered a full run over the wire: %s" % json.dumps(byDev)[:300])
else:
    ok("a developer's full run is refused over the wire: %s" % byDev.get("error", {}).get("code"))
noReason = call("run_tests", full, session=tm_id)
if noReason.get("error", {}).get("code") == "FULL_RUN_NOT_ALLOWED":
    ok("CONTROL: the test manager's full run with no reason is refused")
else:
    bad("the test manager's full run with no reason was not refused: %s" % json.dumps(noReason)[:300])
reasoned = call("run_tests", dict(full, reason="the probe's checkpoint gate"), session=tm_id)
if reasoned.get("error", {}).get("code") in ("FULL_RUN_NOT_ALLOWED", "PACKAGE_RUN_NOT_ALLOWED"):
    bad("the test manager's reasoned full run was refused by the rule: %s" % json.dumps(reasoned)[:300])
else:
    ok("the test manager's reasoned full run was let through the rule (answer: %s)" % ("success" if reasoned.get("success") else (reasoned.get("error", {}).get("code") or "an answer")))
    if reasoned.get("success") and (reasoned.get("data") or {}).get("fullRunReason") == "the probe's checkpoint gate":
        ok("the reason is repeated in the run's answer")
    elif reasoned.get("success"):
        bad("the run's answer does not repeat the reason: %s" % json.dumps(reasoned)[:300])
    else:
        print("  note  the run itself did not succeed on the fixture, so its answer has no reason to repeat: %s" % json.dumps(reasoned)[:200])
tmDone = call("hire_wait", {"hireId": tm_id, "waitSeconds": 50}).get("data", {})
for _ in range(3):
    if tmDone.get("state") == "finished": break
    tmDone = call("hire_wait", {"hireId": tm_id, "waitSeconds": 50}).get("data", {})
reasons = (tmDone.get("report") or {}).get("fullRunReasons")
if tmDone.get("state") == "finished" and reasons == ["the probe's checkpoint gate"]:
    ok("the test manager's report carries its reason: fullRunReasons=%s" % reasons)
else:
    bad("the test manager's report does not carry the reason: state=%s reasons=%s" % (tmDone.get("state"), reasons))

print("\n%d passed, %d failed" % (passed, failed))
raise SystemExit(1 if failed else 0)
PY
exit $?
