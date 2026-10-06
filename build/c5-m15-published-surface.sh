#!/usr/bin/env bash
# Sprint 28g C5 / M15 — THE PUBLISHED SURFACE, RECOMPUTED FROM THE SHIPPED LIST.
#
# WHY A PROBE AND NOT A UNIT TEST. C5's exit asks for the tool count and every per-door kind
# count "recomputed from the shipped list". The in-process tests build their OWN registry —
# measured at C5: HealthCheckToolTest asserts toolCount 56 and stayed green through D10 adding
# a published tool, because the registry it counts is its own fixture and not the application's.
# A count that cannot see a new tool is not a count of what ships. This reads `tools/list` off
# the built artifact over the wire, which is the only list a client ever meets.
#
# WHAT IT PROVES:
#   * D10's tool is PUBLISHED, not merely built and registered in a method nobody calls;
#   * the shipped surface is EXACTLY 43 tools, and health_check's toolCount is that same
#     43 — each pinned against the LITERAL, never against the other;
#   * every refactoring door publishes exactly the kind count C9 assigns it, with inline at
#     SIX since Sprint 28g's third change added `unreferenced_member`;
#   * and hierarchy is counted on `direction`, not `kind`, because that door selects on its
#     own discriminator and a probe asking for `kind` would report zero and read as a defect.
#
# Usage:  build/c5-m15-published-surface.sh [dist-dir]
# Exit:   0 = every claim held · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8917}"
TOKEN="c5-m15-probe-$$"

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

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }

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

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" python3 - << 'PY'
import json, os, urllib.request

PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]

passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def rpc(method, params):
    req = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN,
                 "Mcp-Session-Id": "c5-m15-probe",
                 "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(r, timeout=180).read().decode())

listed = rpc("tools/list", {})["result"]["tools"]
names = [t["name"] for t in listed]
by_name = {t["name"]: t for t in listed}

# THE COUNT, PINNED TO AN ABSOLUTE NUMBER. This replaces a `len(names) > 20` floor, and a
# C5 audit is why: a floor cannot see a tool LEAVE, since any single departure from 43 still
# lands above 20. The criterion asks for the toolCount "recomputed from the shipped list",
# and only a literal makes a vanished tool fail.
EXPECTED_TOOLS = 43
if len(names) == EXPECTED_TOOLS:
    ok("tools/list returns exactly %d tools" % len(names))
else:
    bad("the shipped surface is %d tools, expected %d. A tool was added or stopped "
        "shipping: %s" % (len(names), EXPECTED_TOOLS, sorted(names)))

# --- D10 is PUBLISHED -----------------------------------------------------------------
if "delete_anonymous_implementation" in names:
    ok("D10's delete_anonymous_implementation is published")
else:
    bad("D10's tool is NOT in tools/list — built and registered is not shipped. Published: %s"
        % sorted(names))

# --- health_check's count equals the list it describes ---------------------------------
hc = rpc("tools/call", {"name": "health_check", "arguments": {}})
data = json.loads(hc["result"]["content"][0]["text"])["data"]
reported = data.get("toolCount")
# PINNED TO THE LITERAL, NOT TO len(names). Comparing the two was a TAUTOLOGY and a C5 audit
# proved it by reading the production path: ToolRegistry.getToolCount() returns tools.size(),
# getToolDefinitions() emits one entry per element of the same map with no filter, and
# handleToolsList() returns that list unchanged — so both sides were tools.size() and a tool
# vanishing moved them together. The probe's own header claimed "the two disagreeing is how a
# tool ships invisibly"; they cannot disagree, so the claim described a check that could not
# fail. Each side now meets the number independently.
if reported == EXPECTED_TOOLS:
    ok("health_check toolCount is %s, the expected absolute" % reported)
else:
    bad("health_check says toolCount=%s, expected %d — pinned against the literal rather "
        "than against tools/list, because both read the same map" % (reported, EXPECTED_TOOLS))

# --- per-door kind counts, read off each door's own published schema --------------------
# hierarchy selects on `direction`; every other door on `kind`. Asking the wrong one reports
# zero and reads as a defect in the door rather than in the probe.
ASSIGNED = {"extract": ("kind", 11), "inline": ("kind", 6), "move": ("kind", 6),
            "apply_cleanup": ("kind", 10), "change_method_signature": ("kind", 11),
            "hierarchy": ("direction", 7), "data": ("kind", 11),
            "refactor_to_pattern": ("kind", 11)}
for door, (discriminator, expected) in sorted(ASSIGNED.items()):
    tool = by_name.get(door)
    if tool is None:
        bad("%s is not published at all" % door)
        continue
    enum = (tool.get("inputSchema", {}).get("properties", {})
                .get(discriminator, {}).get("enum"))
    if enum is None:
        bad("%s publishes no `%s` enum, so its kinds cannot be counted from the shipped "
            "schema" % (door, discriminator))
    elif len(enum) == expected:
        ok("%s publishes %d kinds on `%s`" % (door, expected, discriminator))
    else:
        bad("%s publishes %d kinds on `%s`, expected %d: %s"
            % (door, len(enum), discriminator, expected, sorted(enum)))

print("\nC5-M15 passed=%d failed=%d" % (passed, failed))
raise SystemExit(1 if failed else 0)
PY
PY_STATUS=$?
exit $PY_STATUS
