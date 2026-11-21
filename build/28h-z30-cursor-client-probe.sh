#!/usr/bin/env bash
# jawata-gate: probe
# Sprint 28h, Stage 27, row (z30) — a REAL Cursor agent (the `cursor-agent` command line, print mode), as a plain client with no jawata chat, hires
# through a throwaway engine built from the tree and collects the hire's report with `hire_wait`. Run on his word 2026-11-21: "you could try with
# cursor by your own".
#
# BOUNDED ON PURPOSE: the client runs in an empty temporary folder whose own .cursor/mcp.json names only the throwaway engine, so nothing is written
# to his Cursor settings; --force is needed because print mode cannot answer an approval prompt for an MCP call, and the sandbox is enabled; a small model; the HIRED brain is a stand-in on PATH, so only the client's few turns are billed.
# The engine in the person's session is not touched.
#
# Exits 0 on PASS, 1 on FAIL, 2 for "could not run at all", which is never a pass.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8910}"
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
# THE REAL cursor-agent, found BEFORE the stand-ins shadow its name on PATH: the client must be the real one, the hired brains the stand-ins.
REAL_CURSOR="$(command -v cursor-agent)" || { echo "no cursor-agent on PATH: cannot run the client" >&2; exit 2; }
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
java $VECTOR -Djawata.experience.shared.dir="$STORE" -Djawata.hire.stallStopSeconds=0 \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
READY=0
for _ in $(seq 1 120); do
    grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2; tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "resident never announced readiness in 120s:" >&2; tail -20 "$LOG" >&2; exit 2; }


# THE CLIENT: a real Cursor agent, isolated to a temporary folder with its own project-level server list.
echo 20 > "$WS/slow"
CLIENT_DIR="$WS/client"; mkdir -p "$CLIENT_DIR/.cursor"
cat > "$CLIENT_DIR/.cursor/mcp.json" <<JSON
{"mcpServers":{"z30":{"url":"http://127.0.0.1:$PORT/mcp","headers":{"Authorization":"Bearer $TOKEN"}}}}
JSON
PROMPT="Use the MCP tool named hire on the MCP server z30 with exactly these arguments: hirer=main, seat=pm, seatFile=$WS/pm.md, brief='z30 real cursor client probe', boardFile=$WS/sprint-z30-plan.md, workingDirectory=$WS, steps=[{\\"tool\\":\\"health_check\\",\\"arguments\\":{}}]. It answers at once with a hireId. Then call the MCP tool hire_wait on the same server with that hireId and waitSeconds=40, and call it again while its state is running. When the state is finished, reply with exactly: COLLECTED followed by the hire's answer text. Do not run shell commands and do not edit files."
cd "$CLIENT_DIR" || exit 2
OUT="$WS/client-output.json"
timeout 240 "$REAL_CURSOR" -p "$PROMPT" --approve-mcps --trust --force --sandbox enabled --model "${CURSOR_PROBE_MODEL:-gpt-5.3-codex-low}" \
       --workspace "$CLIENT_DIR" --output-format json > "$OUT" 2> "$WS/client-stderr.txt"
CODE=$?
echo "client exit status: $CODE"
python3 -I - "$OUT" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print("  FAIL  the client's output could not be read: %s" % e); raise SystemExit(1)
text = str(d.get("result", ""))
print("  ----  client result: %s" % text[:300].replace("\n", " "))
print("  ----  client fields: %s" % sorted(d.keys()))
if "COLLECTED" in text and "stand-in pm did the work" in text:
    print("  ok    a real Cursor agent hired and collected the report with hire_wait"); raise SystemExit(0)
print("  FAIL  the client did not report the collected hire"); raise SystemExit(1)
PY
RESULT=$?
grep -a "Tool hire_wait\|Tool hire completed\|hire_wait" "$LOG" | tail -5
exit $RESULT
