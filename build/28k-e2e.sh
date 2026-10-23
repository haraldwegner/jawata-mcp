#!/usr/bin/env bash
# Sprint 28k — THE END-TO-END GATE OF THE ENGINE, through MCP against the BUILT dist.
#
# Every stage of 28k adds one promise here and every later checkpoint re-runs ALL of them
# (plan §5.0, "The E2E gate"). A promise once added is never removed. The plug-in's promises
# live in jawata-eclipse/scripts/28k-e2e.sh, which runs this script first.
#
# WHY AGAINST THE BUILT DIST. The in-process tests build their own registry; only the artifact
# a client starts shows what ships. Same reasoning as build/c5-m15-published-surface.sh.
#
# PROMISES
#   P0 (Stage 0, baseline): the dist boots on a scratch workspace and prints its READY line in
#      the captured shape; health_check answers toolCount equal to the README's inventory (44 at
#      the baseline); tools/list equals the golden captured at the baseline — names, order and
#      input schemas — so nothing a headless client observes changes during the engine split.
#   P1 (Stage 1, the reconciler port): a copy of the compile-clean sample project is loaded over MCP;
#      compile_workspace answers 0 errors; a type error is written into its source file ON DISK, outside
#      the engine, and compile_workspace reports it in that file; the file is restored and
#      compile_workspace answers 0 again. compile_workspace refreshes and builds through the host's
#      Reconciler since E6, so this is the reconciler path, front door to built dist.
#
# Usage:  build/28k-e2e.sh [--capture] [dist-dir]
#         --capture writes the goldens (build/28k-golden-ready.txt, build/28k-golden-tools.json)
#         from the given dist; only Stage 0 uses it.
# Exit:   0 = every promise held · 1 = a promise failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

CAPTURE=0
if [ "${1:-}" = "--capture" ]; then CAPTURE=1; shift; fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8928}"
TOKEN="e2e-28k-$$"
GOLDEN_READY="$ROOT/build/28k-golden-ready.txt"
GOLDEN_TOOLS="$ROOT/build/28k-golden-tools.json"

WS="$(mktemp -d)"
STORE="$(mktemp -d)"
SAMPLE="$WS/sample/compile-clean"
mkdir -p "$WS/sample"
cp -r "$ROOT/org.jawata.core.tests/test-resources/sample-projects/compile-clean" "$SAMPLE"
LOG="$WS/resident.log"
RESIDENT_PID=""

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }
if [ "$CAPTURE" -eq 0 ]; then
    [ -f "$GOLDEN_READY" ] && [ -f "$GOLDEN_TOOLS" ] \
        || { echo "no goldens — run with --capture at the baseline first" >&2; exit 2; }
fi

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$STORE" \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
READY=0
for _ in $(seq 1 120); do
    grep -q "READY" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2
                                             tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "resident never printed READY in 120s:" >&2
                        tail -20 "$LOG" >&2; exit 2; }

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_ROOT="$ROOT" PROBE_LOG="$LOG" \
PROBE_CAPTURE="$CAPTURE" PROBE_GOLDEN_READY="$GOLDEN_READY" PROBE_GOLDEN_TOOLS="$GOLDEN_TOOLS" \
PROBE_SAMPLE="$SAMPLE" \
python3 - << 'PY'
import json, os, re, urllib.request

PORT, TOKEN, ROOT = os.environ["PROBE_PORT"], os.environ["PROBE_TOKEN"], os.environ["PROBE_ROOT"]
CAPTURE = os.environ["PROBE_CAPTURE"] == "1"

passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def rpc(method, params):
    req = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": "e2e-28k",
                 "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(r, timeout=180).read().decode())

# The READY line, with the run's own port and token replaced, is its SHAPE: a client parses it.
ready_lines = [l.rstrip("\n") for l in open(os.environ["PROBE_LOG"], encoding="utf-8", errors="replace")
               if "READY" in l]
ready = ready_lines[0] if ready_lines else ""
shape = ready.replace(PORT, "<port>").replace(TOKEN, "<token>")

listed = rpc("tools/list", {})["result"]["tools"]
surface = [{"name": t["name"], "inputSchema": t.get("inputSchema")} for t in listed]

if CAPTURE:
    open(os.environ["PROBE_GOLDEN_READY"], "w", encoding="utf-8").write(shape + "\n")
    with open(os.environ["PROBE_GOLDEN_TOOLS"], "w", encoding="utf-8") as f:
        json.dump(surface, f, indent=1, sort_keys=True)
        f.write("\n")
    print("captured: READY shape and %d tools" % len(surface))
    raise SystemExit(0)

# --- P0: READY shape ------------------------------------------------------------------------
golden_shape = open(os.environ["PROBE_GOLDEN_READY"], encoding="utf-8").read().rstrip("\n")
if shape == golden_shape:
    ok("P0 READY line has the captured shape")
else:
    bad("P0 READY line changed: %r, golden %r" % (shape, golden_shape))

# --- P0: the count, against the README's inventory, never a literal (c5-m15, C9a-1) ----------
with open(os.path.join(ROOT, "README.md"), encoding="utf-8") as readme:
    block = re.search(r"<!-- TOOLS:BEGIN.*?<!-- TOOLS:END", readme.read(), re.S)
expected = len(set(re.findall(r"`([a-z_]+)`", block.group(0)))) if block else 0
if not expected:
    bad("P0 the README's TOOLS:BEGIN/END block is missing or empty")
data = json.loads(rpc("tools/call", {"name": "health_check", "arguments": {}})
                  ["result"]["content"][0]["text"])["data"]
if data.get("toolCount") == expected:
    ok("P0 health_check toolCount is %s, the README's inventory" % expected)
else:
    bad("P0 health_check toolCount %s, the README names %d" % (data.get("toolCount"), expected))

# --- P0: tools/list equals the golden: names, order and schemas -------------------------------
golden = json.load(open(os.environ["PROBE_GOLDEN_TOOLS"], encoding="utf-8"))

# Exact, enum order included: the two tools that published Set.of enums (whose order Java picks per process start; 4 of 6 starts
# differed at C0) publish ordered lists since Stage 1's first commit, so a changed order is a changed surface again.
def canon(node):
    return node

if [t["name"] for t in surface] != [t["name"] for t in golden]:
    bad("P0 tool names or order changed: %s" % [t["name"] for t in surface])
else:
    changed = [t["name"] for t, g in zip(surface, golden)
               if canon(t["inputSchema"]) != canon(g["inputSchema"])]
    if changed:
        bad("P0 input schemas changed for: %s" % changed)
    else:
        ok("P0 tools/list equals the golden: %d names in order, schemas identical" % len(golden))

# --- P1: an edit made on disk reaches compile_workspace through the host's reconciler (Stage 1) --
def call(name, arguments):
    reply = rpc("tools/call", {"name": name, "arguments": arguments})
    return json.loads(reply["result"]["content"][0]["text"])

def errors():
    d = call("compile_workspace", {}).get("data") or {}   # the sample is the only project loaded
    return d.get("errorCount"), [x.get("filePath", "") for x in d.get("diagnostics", [])]

sample = os.environ["PROBE_SAMPLE"]
source = os.path.join(sample, "src/main/java/com/example/Clean.java")
loaded = call("load_project", {"projectPath": sample})
if not loaded.get("success"):
    bad("P1 load_project of the sample refused: %s" % loaded.get("error"))
else:
    before = errors()
    original = open(source, encoding="utf-8").read()
    with open(source, "w", encoding="utf-8") as f:
        f.write(original.rstrip().rstrip("}") + "\n    int broken() { return \"not an int\"; }\n}\n")
    during = errors()
    with open(source, "w", encoding="utf-8") as f:
        f.write(original)
    after = errors()
    if before[0] != 0:
        bad("P1 the clean sample does not compile clean: %s errors" % before[0])
    elif not (during[0] and during[0] > 0 and any(p.endswith("Clean.java") for p in during[1])):
        bad("P1 an error written on disk did not reach compile_workspace: %s errors in %s" % during)
    elif after[0] != 0:
        bad("P1 after the file was restored compile_workspace still reports %s errors" % after[0])
    else:
        ok("P1 compile_workspace: 0, then %d error(s) in Clean.java after an edit on disk, then 0 after restoring it"
           % during[0])

print("\n28k-E2E passed=%d failed=%d" % (passed, failed))
raise SystemExit(1 if failed else 0)
PY
exit $?
