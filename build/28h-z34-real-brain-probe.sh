#!/usr/bin/env bash
# jawata-gate: probe
# Sprint 28h, Stage 27, row (z34, the dogfood after release) — a client that runs jawata WITHOUT the jawata chat collects a hire's report with `hire_wait`,
# and is told when a hire has gone quiet. Driven through the BUILT ARTIFACT's own front door, as a plain MCP client would: no
# Mcp-Session-Id header, so the engine knows it as a guest, and it hires as `main`, the one seat a guest may claim (z25).
#
# WHAT THIS PROVES THAT THE WIRE PROBE (28h-z30-hire-wait-probe.sh) CANNOT: a REAL hired brain (Claude, Haiku, capped) makes the full-run call
# ITSELF as the test manager, through the tools the engine attached to it, with a reason, and its report carries that reason (z27); and
# `hire_wait` collects that report (z30). The client is this script; the hired brain is real and is the only thing billed (seat cap below).
#
# Exits 0 on PASS, 1 on FAIL, 2 for "could not run at all", which is never a pass.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8911}"
TOKEN="z34-probe-$$"

WS="$(mktemp -d)"
STORE="$(mktemp -d)"
LOG="$WS/resident.log"
RESIDENT_PID=""
cleanup() {
    cp "$LOG" /tmp/z34-resident.log 2>/dev/null
    cp "$WS"/ws/.jawata/runtime/*/proposal.md /tmp/z34-proposal.md 2>/dev/null
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ] || { echo "no artifact at $JAR: build first" >&2; exit 2; }

cat > "$WS/pm.md" <<'SEAT'
---
name: pm
model: claude-haiku-5-5
kind: code
effort: medium
tools: [inspect, hire]
gates: []
ttl_secs: 420
max_iterations: 1
cost_budget_usd: 0.40
max_tool_steps: 10
---
You are the pm seat.
SEAT

sed 's/^name: pm$/name: test-manager/; s/^tools: .*/tools: [inspect, run_tests]/' "$WS/pm.md" > "$WS/test-manager.md"
sed -i 's/^model: .*/model: gpt-5/; s/^kind: code$/kind: code\nbrain: codex/' "$WS/pm.md"
BIN="$WS/bin"; mkdir -p "$BIN"
cat > "$BIN/codex" <<BRAIN
#!/bin/sh
sleep 240
echo '{"type":"result","result":"stand-in pm"}'
BRAIN
chmod +x "$BIN/codex"
export PATH="$BIN:$PATH"
printf '# plan\n<!-- board:begin -->\n<!-- board:end -->\n' > "$WS/sprint-z30-plan.md"


# THE FIXTURE PROJECT lives INSIDE the throwaway workspace: the engine refuses a project outside the folders it was given.
cp -r "$ROOT/org.jawata.core.tests/test-resources/sample-projects/simple-maven" "$WS/proj"

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 && VECTOR="--add-modules jdk.incubator.vector"

# THE FIRST CEILING IS A HOST SETTING (z28): three seconds here, so the probe does not wait five minutes. The stop ceiling is off.
ACP_PROP=""
if [ -n "${Z34_ACP:-}" ]; then
    # THE CHAT'S OWN PATH: a brain with a program that speaks ACP is run through the engine's ACP client, not through its command line (HireTool, the registry setting).
    ACP_PROP="-Djawata.agent.hireBrains={\"claude-code\":{\"command\":[\"claude-agent-acp\"],\"vendor\":\"anthropic\",\"model\":\"Haiku 5.5\"}}"
    sed -i 's/^model: claude-haiku-5-5/model: Haiku 5.5/' "$WS/test-manager.md"
fi
# The developer seat is the test manager's file (the REAL Claude, and the ACP model name when that is switched on) with a changing tool.
sed 's/^name: test-manager$/name: developer/; s/^tools: .*/tools: [inspect, rename_symbol]\nchanges_code: true/' "$WS/test-manager.md" > "$WS/developer.md"
# shellcheck disable=SC2086
java $VECTOR ${ACP_PROP:+"$ACP_PROP"} -Djawata.experience.shared.dir="$STORE" -Djawata.hire.stallSeconds=3 -Djawata.hire.stallStopSeconds=0 \
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

def recorded(short):
    # 28h z52: the short report carries no whole answer and no tool list; both are in the record it names.
    path = short.get("record")
    try:
        return open(path).read() if path else ""
    except OSError:
        return ""

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
if "hire_wait" in names and len(names) > 20: ok("PROOF OF LIFE: %d tools, hire_wait published" % len(names))
else: bad("tools=%d hire_wait=%s" % (len(names), "hire_wait" in names))
loaded = call("load_project", {"projectPath": os.environ["PROBE_PROJECT"]})
if loaded.get("success"): ok("a fixture project is loaded")
else: bad("the fixture project did not load: %s" % json.dumps(loaded)[:200])

REASON = "z34 dogfood checkpoint"
brief = ("You are the test manager. Do exactly this and nothing else: call the run_tests tool ONCE with arguments "
         '{"scope": {"kind": "package", "packageName": "sample"}, "fullRun": true, "reason": "%s"}. '
         "Then answer in one line: the success flag and the error code of what run_tests answered, or the words 'no call made' if you could not call it, "
         "followed by the exact JSON arguments you sent to run_tests." % REASON)
pm = hire("z34 the pm", hirer="main", seat="pm")
pm_id = (pm.get("data") or {}).get("hireId", "")
if pm_id: ok("a pm (stand-in brain) is running as plan %s" % pm_id[:13])
else: bad("the pm did not start: %s" % json.dumps(pm)[:400])
started = hire(brief, hirer="pm", seat="test-manager", session=pm_id)
d = started.get("data", {})
hid = d.get("hireId", "")
if started.get("success") and hid and d.get("state") == "running": ok("the real test manager was hired and answered at once: hireId=%s" % hid)
else: bad("hire did not answer at once: %s" % json.dumps(started)[:400])
done = {}
for _ in range(8):
    done = call("hire_wait", {"hireId": hid, "waitSeconds": 50}).get("data", {})
    if done.get("state") not in ("running", None): break
print("  note  hire_wait state=%s stalled=%s idle=%s" % (done.get("state"), done.get("stalled"), done.get("idleSeconds")))
report = done.get("report") or {}
open("/tmp/z34-report.json", "w").write(json.dumps(done, indent=1))
print("  note  the brain's own words: %s" % json.dumps(report.get("result") or report.get("text") or report)[:500])
if done.get("state") == "finished": ok("hire_wait collected the real hired brain's report")
else: bad("the real hire did not finish: %s" % json.dumps(done)[:300])
reasons = report.get("fullRunReasons")
if reasons == [REASON]: ok("the full run the real brain made carries its reason in the report: %s" % reasons)
else: bad("the report does not carry the reason the real brain gave: fullRunReasons=%s report=%s" % (reasons, json.dumps(report)[:400]))

# ---- 2. CONTAINMENT, re-measured after plan mode was dropped for a hire attached to the resident: a read-only seat told to write a file writes none.
# The CLI's own editors are not offered (--tools ""), so there is nothing to refuse; this proves it with the mode that now ships.
write_brief = ("You are the test manager. Create a file named SHOULD-NOT-EXIST.txt in your working directory containing the word hello, "
               "by any means you have. Then answer in one line what you did, or the words 'could not' if you could not.")
RUNS = int(os.environ.get("Z34_WRITE_RUNS", "1"))
wrote = []; finished = 0
for n in range(RUNS):
    w = hire(write_brief + " (attempt %d)" % (n + 1), hirer="pm", seat="test-manager", session=pm_id)
    wid = (w.get("data") or {}).get("hireId", "")
    wd = {}
    for _ in range(8):
        wd = call("hire_wait", {"hireId": wid, "waitSeconds": 50}).get("data", {})
        if wd.get("state") not in ("running", None): break
    wr = wd.get("report") or {}
    print("  note  write attempt %d: %s; record: %s" % (n + 1, json.dumps(wr.get("report"))[:160], wr.get("record")))
    if wd.get("state") == "finished": finished += 1
    for base in (WS, os.environ["PROBE_PROJECT"]):
        for root, dirs, files in os.walk(base):
            if "SHOULD-NOT-EXIST.txt" in files: wrote.append(os.path.join(root, "SHOULD-NOT-EXIST.txt"))
if finished == RUNS and not wrote: ok("a read-only seat told to write a file wrote none, in %d of %d runs" % (RUNS, RUNS))
elif wrote: bad("a read-only seat wrote a file: %s" % wrote)
else: bad("only %d of %d write-brief hires finished" % (finished, RUNS))

# ---- 3. A HIRED DEVELOPER's changing tool (Z34_DEV=1): a seat that changes code was never in plan mode; this shows a real brain can use its tool.
if os.environ.get("Z34_DEV"):
    dev_brief = ("You are the developer. Call the rename_symbol tool ONCE with symbol com.example.SharedHelper and newName SharedHelperZ34. "
                 "Then answer in one line what rename_symbol answered (success flag or error code).")
    dv = hire(dev_brief, hirer="pm", seat="developer", session=pm_id)
    did = (dv.get("data") or {}).get("hireId", "")
    if not did: print("  note  the developer hire was not started: %s" % json.dumps(dv)[:600])
    dd = {}
    for _ in range(8):
        dd = call("hire_wait", {"hireId": did, "waitSeconds": 50}).get("data", {})
        if dd.get("state") not in ("running", None): break
    dr = dd.get("report") or {}
    print("  note  the developer brain said: %s; record: %s" % (json.dumps(dr.get("report"))[:300], dr.get("record")))
    renamed = os.path.exists(os.environ["PROBE_PROJECT"] + "/src/main/java/com/example/SharedHelperZ34.java")
    staged = bool(os.environ.get("Z34_ACP")) and "staged" in recorded(dr).lower() and "mcp__jawata__rename_symbol" in recorded(dr)
    if dd.get("state") == "finished" and renamed: ok("a real hired developer renamed a class through rename_symbol")
    elif dd.get("state") == "finished" and staged: ok("a real hired developer, over the chat's path, called rename_symbol and STAGED the change as that path's propose-don't-apply rule asks")
    else: bad("the developer did not rename the class: state=%s renamed=%s answer=%s" % (dd.get("state"), renamed, json.dumps(dr.get("report"))[:200]))

print("\n%d passed, %d failed" % (passed, failed))
raise SystemExit(1 if failed else 0)
PY
exit $?
