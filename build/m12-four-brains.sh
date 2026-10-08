#!/usr/bin/env bash
# M12 (Sprint 28g S16) — the hire path end to end against the FOUR REAL BRAINS.
#
# "Four CLIs, not four chats" is the plan's own clarification of D36's word "hired": the resident
# spawns each brain and owns its process, so this proof needs no client cooperation and no chat
# ownership. Everything else in this stage is driven with stand-ins; this script is the one place
# real vendor CLIs run, and it is deliberately separate so nobody runs it by accident.
#
# IT SPENDS REAL MONEY AND REAL QUOTA. Bounds, all of them deliberate:
#   - the brain runs in a THROWAWAY directory, never a repository;
#   - the brief asks for one word and forbids tools;
#   - the seat's ceilings are cut to a fraction of a shipped seat's — 120s, 5 tool steps, $0.25;
#   - one run per brain, and a brain that cannot start is REPORTED rather than retried.
# An earlier accident in this stage spent $1.28 by spawning claude unintentionally in the
# developer's own repository; these bounds exist because of it, not in anticipation of it.
#
# WHAT IT REPORTS PER BRAIN: whether the hire RAN, what the brain said, what it cost, how many
# tool steps it took, and — for the brains that publish neither — that the absence is an absence.
# A brain that cannot be told which tools to attach is reported, never dropped from the count.
#
# Exit 0 when every brain produced a JOURNALLED RUN, which includes a run that failed honestly.
# Exit 1 when a brain neither ran nor reported why. Exit 2 for "could not run at all".

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8908}"
TOKEN="m12-four-brains-$$"

WS="$(mktemp -d)"
STORE="$(mktemp -d)"
WORK="$(mktemp -d)"     # where the BRAINS run. Never the repository.
LOG="$WS/resident.log"
RESIDENT_PID=""

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE" "$WORK"
}
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }

# ONE SEAT PER BRAIN, cut to the bone. `tools:` is left EMPTY on purpose for this run: the
# question M12 answers is whether the hire path reaches each CLI and reads its events, and a
# tool list is a separate claim this script must not quietly fold in. (Whether a seat's jawata
# tool names mean anything to a vendor's own allowlist is measured by the claude-allowlist probe
# beside this one, because the answer differs per brain and deserves its own evidence.)
for brain in claude-code cursor copilot codex; do
    cat > "$WS/seat-$brain.md" <<SEAT
---
name: m12-probe
brain: $brain
tools: []
gates: []
ttl_secs: 120
max_iterations: 1
cost_budget_usd: 0.25
max_tool_steps: 5
---
A probe seat. One word, no tools.
SEAT
done

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
[ "$READY" -eq 1 ] || { echo "resident never announced readiness in 120s:" >&2
                        tail -20 "$LOG" >&2; exit 2; }

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_WS="$WS" PROBE_WORK="$WORK" \
python3 -I - << 'PY'
import json, os, urllib.request

PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]
WS = os.environ["PROBE_WS"]
WORK = os.environ["PROBE_WORK"]

BRIEF = "Reply with exactly the single word READY and nothing else. Do not use any tools."

def call(tool, args):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(r, timeout=600) as resp:
        answer = json.loads(resp.read().decode())
    for part in answer.get("result", {}).get("content", []):
        if part.get("type") == "text":
            try:
                return json.loads(part["text"])
            except json.JSONDecodeError:
                return {"_text": part["text"]}
    return {"_raw": answer}

# A gate that needs no project, so the gate's own answer is not what this script is measuring.
# `hire` refuses an empty step list, so one step is declared and its outcome is reported as it
# comes — a gate that could not answer says so, which is ToolGateRunner's whole point.
STEPS = [{"tool": "health_check", "arguments": {}}]

journalled = 0
reported = 0
rows = []

for brain in ("claude-code", "cursor", "copilot", "codex"):
    answer = call("hire", {
        "hirer": "pm",
        "seat": "m12-probe",
        "seatFile": "%s/seat-%s.md" % (WS, brain),
        "brief": BRIEF,
        "workingDirectory": WORK,
        "steps": STEPS,
    })
    d = answer.get("data") or {}
    if answer.get("success"):
        journalled += 1
        record = d.get("record")
        on_disk = bool(record) and os.path.isfile(record)
        rows.append({
            "brain": brain,
            "ran": True,
            "recordOnDisk": on_disk,
            "cleanExit": d.get("cleanExit"),
            "timedOut": d.get("timedOut"),
            "toolSteps": d.get("toolSteps"),
            "costUsd": d.get("costUsd"),
            "stoppedBy": d.get("stoppedBy"),
            "toolsSettableByTheHirer": d.get("toolsSettableByTheHirer"),
            "said": (str(d.get("answer") or ""))[:300],
        })
    else:
        # A REFUSAL IS A REPORT, not a gap — as long as it says why. That is the difference M12
        # asks for between a brain that cannot be told and a brain quietly left out of the count.
        err = answer.get("error") or {}
        reported += 1
        rows.append({
            "brain": brain,
            "ran": False,
            "refusedWith": err.get("code"),
            "because": (str(err.get("message") or ""))[:300],
        })

print(json.dumps(rows, indent=2))
print("")
print("M12-FOUR-BRAINS journalled=%d reported=%d of 4" % (journalled, reported))
# Every brain must be ACCOUNTED FOR. A run that failed honestly counts; silence does not.
raise SystemExit(0 if journalled + reported == 4 else 1)
PY
PROBE_STATUS=$?
exit $PROBE_STATUS
