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
#      compile_workspace answers 0 again. This proves the RECONCILER PATH as a whole, front door to
#      built dist: the per-call syncBeforeCall and compile_workspace's rebuild (E6) both refresh and
#      build, so P1 stays green if only one of them works (a do-nothing reconciler turns it red; the
#      rebuild route alone is CompileWorkspaceToolTest's job, C1 audit finding 6).
#
#   P2 (Stage 2, the change context): on the same sample, rename_symbol over MCP renames a method everywhere it is used and
#      reports applied; the compile check runs (compile_workspace answers 0 errors on the renamed source); refactoring
#      undo restores the files byte for byte. This is the change lifecycle through ChangeContext, front door to built dist: the
#      rename is applied by GatedApply (verdict, paths, writes), and the undo goes through ChangeEngine under the same context.
#
#   P3 (Stage 3, memory and the test runner ask the Java provider): on the same sample, experience(record) with a symbol anchor and
#      experience(recall, symbol=...) answer the anchor resolved to its CURRENT file and line (read back from the file on disk, not
#      from the store); then run_tests on a class of a second sample (simple-maven, which has JUnit tests) runs and reports its count.
#      Memory's anchor goes through SymbolAnchors and the runner's classpath through TestRuntime, front door to built dist.
#
#   P4 (Stage 4, the change lifecycle in its own package and the one tool producer): tools/list, rendered the way the golden was written,
#      equals build/28k-golden-tools.json byte for byte (P0 compares parsed names, order and schemas; this is the file itself); and on a
#      copy of compose-clean, refactoring(plan, compose_method) -> apply_plan -> undo_plan changes ComposeMethodTargets.java and then
#      leaves it byte-identical to the original. The plan lifecycle is ChangeEngine, GatedApply, ParityGate and PlanStore in
#      org.jawata.mcp.change, front door to built dist.
#
#   P5 (Stage 5, the host ports: lifecycle and settings): a second start on the port the first holds exits 13 with the captured text on
#      stderr; a resident whose HTTP listener is stopped under it exits 70 (build/28k-e2e-deaf.sh, about 90 seconds), both leaving
#      the process as the application's result; and a setting passed at start (-Djawata.backups.depth=3, default 10) takes effect, visible
#      in the answer of experience(kind=restore), because the engine's readers ask the host's settings. Front door to the built dist.
#
#   P6 (Stage 6, the one entry and the plug-in slot): the Java provider is bound by the component runtime. A copy of the dist whose engine
#      bundle lacks the provider's descriptor boots, says it is loading through health_check, and publishes only the tools that name no language
#      (no search_symbols); the dist as shipped (P0) publishes all 44 once the provider has bound. The engine is "loading" until the
#      Java provider arrives, front door to the built dist.
#
#   P7 (Stage 7, the headless host bundle): the resident starts from the new bundle layout (org.jawata.resident holds the application,
#      the engine and core jars do not), a -token-file start prints the golden READY line and writes the token with mode 600, and
#      build/two-resident-probe.sh shows two residents each loading the project their own workspace.json names, the first reaching the
#      second through residents.json. Front door to the built dist.
#
#   P8-S4 (Stage 8, contract S4): a workspace.json carrying "sourceRoots" for studio's two IntelliJ fixtures (intellij-root-plain with a
#      Maven module nested in it, intellij-without-build) is loaded; inspect(project_structure) shows exactly the declared folders for the
#      members that declare them, and the Maven module nested in the root module is a project of its own, discovered as before.
#   P8 (Stage 8, contract §14.1): a resident told to read a scratch folder of engine notes (jawata.engine.notes.dir) answers a miss from
#      the engine behind a LIVE note (workspace and project named, one consulted of one listed, asked once as a peek and without a key),
#      and never asks the server behind a STALE note; the malformed and the newer-major note are not listed. The probe process is the
#      live dummy; the notes are the canonical fixtures with pid, start time and address patched.
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
TESTS_SAMPLE="$WS/sample/simple-maven"
cp -r "$ROOT/org.jawata.core.tests/test-resources/sample-projects/simple-maven" "$TESTS_SAMPLE"
COMPOSE_SAMPLE="$WS/sample/compose-clean"
cp -r "$ROOT/org.jawata.core.tests/test-resources/sample-projects/compose-clean" "$COMPOSE_SAMPLE"
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
java $VECTOR -Djawata.experience.shared.dir="$STORE" -Djawata.backups.depth=3 \
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
PROBE_SAMPLE="$SAMPLE" PROBE_TESTS_SAMPLE="$TESTS_SAMPLE" PROBE_COMPOSE_SAMPLE="$COMPOSE_SAMPLE" \
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

# --- P2: a rename through the change lifecycle applies, compiles, and undoes byte-identically (Stage 2) -------------------
if loaded.get("success"):
    before_bytes = open(source, "rb").read()
    renamed = call("rename_symbol", {"symbol": "com.example.Clean#greet", "newName": "welcome"})
    data = renamed.get("data") or {}
    after_rename = open(source, "rb").read()
    compiled = (call("compile_workspace", {}).get("data") or {}).get("errorCount")
    if not renamed.get("success") or data.get("applied") is not True:
        bad("P2 rename_symbol was not applied: %s" % (renamed.get("error") or data))
    elif b"welcome(" not in after_rename or b"greet(" in after_rename:
        bad("P2 the rename did not reach Clean.java")
    elif compiled != 0:
        bad("P2 compile_workspace after the rename answers %s errors" % compiled)
    else:
        undone = call("refactoring", {"action": "undo", "undoChangeId": data.get("undoChangeId")})
        restored = open(source, "rb").read()
        if not undone.get("success"):
            bad("P2 refactoring undo failed: %s" % undone.get("error"))
        elif restored != before_bytes:
            bad("P2 refactoring undo did not restore Clean.java byte for byte")
        else:
            ok("P2 rename_symbol applied (greet -> welcome), compile_workspace 0 errors, refactoring undo restored Clean.java byte for byte")

# --- P3: memory's anchor resolves to the symbol's current place, and the runner runs a test class (Stage 3) -----------------
if loaded.get("success"):
    greet_line = next((n for n, text in enumerate(open(source, encoding="utf-8").read().splitlines(), 1)
                       if re.search(r"\bString\s+greet\s*\(", text)), None)
    recorded = call("experience", {"kind": "record", "type": "domain_fact", "symbol": "com.example.Clean#greet",
                                   "summary": "The sample's greeting is the one method its callers print, so a rename of it reaches every caller."})
    recalled = call("experience", {"kind": "recall", "lane": "domain", "symbol": "com.example.Clean#greet"})
    entries = (recalled.get("data") or {}).get("entries") or []
    pointer = (entries[0].get("resolved_pointer") if entries else None) or {}
    if not recorded.get("success"):
        bad("P3 experience(record) with a symbol anchor was refused: %s" % recorded.get("error"))
    elif greet_line is None:
        bad("P3 the sample no longer declares greet; the probe cannot say where it lives")
    elif pointer.get("resolved") is not True or not str(pointer.get("file", "")).endswith("Clean.java"):
        bad("P3 the recalled anchor did not resolve to Clean.java: %s" % pointer)
    elif pointer.get("line") != greet_line:
        bad("P3 the anchor resolved to line %s, greet is declared on line %s of the file on disk" % (pointer.get("line"), greet_line))
    else:
        ok("P3 experience(record) + recall(symbol): com.example.Clean#greet resolves to Clean.java line %d, the line the file on disk declares it on"
           % greet_line)

    opened = call("load_project", {"projectPath": os.environ["PROBE_TESTS_SAMPLE"]})
    if not opened.get("success"):
        bad("P3 load_project of the test sample refused: %s" % opened.get("error"))
    else:
        ran = call("run_tests", {"scope": {"kind": "class", "typeName": "com.example.SampleTest"},
                                 "framework": "junit5", "timeoutSeconds": 120})
        summary = (ran.get("data") or {}).get("summary") or {}
        if not ran.get("success"):
            bad("P3 run_tests refused: %s" % ran.get("error"))
        elif summary.get("total") != 7 or summary.get("evidenceFinalized") is not True:
            bad("P3 run_tests on SampleTest did not report its 7 tests: %s" % summary)
        else:
            ok("P3 run_tests on com.example.SampleTest ran and reported its count: total %d, passed %d, failed %d, skipped %d"
               % (summary["total"], summary.get("passed", -1), summary.get("failed", -1), summary.get("skipped", -1)))

# --- P4: the published surface is the golden byte for byte, and a plan applies and undoes byte-identically (Stage 4) --------
rendered = (json.dumps(surface, indent=1, sort_keys=True) + "\n").encode("utf-8")
golden_bytes = open(os.environ["PROBE_GOLDEN_TOOLS"], "rb").read()
if rendered == golden_bytes:
    ok("P4 tools/list rendered as the golden was is build/28k-golden-tools.json byte for byte: %d tools, %d bytes"
       % (len(surface), len(golden_bytes)))
else:
    bad("P4 tools/list is not the golden byte for byte: %d bytes against the golden's %d" % (len(rendered), len(golden_bytes)))

compose = os.environ["PROBE_COMPOSE_SAMPLE"]
target = os.path.join(compose, "src/main/java/com/example/ComposeMethodTargets.java")
composed = call("load_project", {"projectPath": compose})
if not composed.get("success"):
    bad("P4 load_project of the compose sample refused: %s" % composed.get("error"))
else:
    original = open(target, "rb").read()
    def section(line, name):
        return {"startLine": line, "startColumn": 8, "endLine": line, "endColumn": 44, "methodName": name}
    planned = call("refactoring", {"action": "plan", "kind": "compose_method", "filePath": target,
                                   "sections": [section(13, "writeHeader"), section(18, "writeFooter")]})
    plan_id = (planned.get("data") or {}).get("planId")
    if not planned.get("success") or not plan_id:
        bad("P4 refactoring(plan, compose_method) did not return a plan: %s" % (planned.get("error") or planned.get("data")))
    else:
        applied = call("refactoring", {"action": "apply_plan", "planId": plan_id})
        applied_data = applied.get("data") or {}
        composed_bytes = open(target, "rb").read()
        if not applied.get("success") or applied_data.get("applied") is not True or applied_data.get("stepsRun") != 2:
            bad("P4 apply_plan did not apply both steps: %s" % (applied.get("error") or applied_data))
        elif composed_bytes == original or b"void writeHeader()" not in composed_bytes or b"void writeFooter()" not in composed_bytes:
            bad("P4 apply_plan answered applied and the file did not change as planned")
        else:
            undone = call("refactoring", {"action": "undo_plan", "planId": plan_id})
            restored = open(target, "rb").read()
            if not undone.get("success"):
                bad("P4 undo_plan failed: %s" % undone.get("error"))
            elif restored != original:
                bad("P4 undo_plan did not restore ComposeMethodTargets.java byte for byte")
            else:
                ok("P4 refactoring plan -> apply_plan (2 steps) -> undo_plan: ComposeMethodTargets.java changed, then restored byte for byte")

# --- P5: a setting passed at start takes effect, visible in a tool answer (Stage 5, E17) -------------------------------------
# The resident was started with -Djawata.backups.depth=3 (the default is 10), and experience(kind=restore) with no name lists the copies
# and says how many it keeps: the engine's reader asked the host's settings, and the host answered with what the process was given.
listing = (call("experience", {"kind": "restore"}).get("data") or {})
if listing.get("depth") == 3:
    ok("P5 -Djawata.backups.depth=3 passed at start takes effect: experience(kind=restore) answers depth 3, not the default 10")
else:
    bad("P5 experience(kind=restore) answers depth %r after the resident was started with -Djawata.backups.depth=3" % listing.get("depth"))

print("\n28k-E2E passed=%d failed=%d" % (passed, failed))
raise SystemExit(1 if failed else 0)
PY
PY_STATUS=$?
[ "$CAPTURE" -eq 1 ] && exit "$PY_STATUS"

# --- P5 (Stage 5, E16): the exit statuses a supervisor reads are the captured ones, on the built dist -------------------------
P5_FAILED=0

# A second resident on the port the first one holds ends with 13 and the golden text on stderr.
STORE2="$(mktemp -d)"
EXPECTED_TAKEN="$(sed -n 's/^port taken:.*stderr "\(.*\)"$/\1/p' "$ROOT/build/28k-golden-boot.txt" | sed "s/<port>/$PORT/")"
# shellcheck disable=SC2086
TAKEN_ERR="$(timeout 180 java $VECTOR -Djawata.experience.shared.dir="$STORE2" \
    -jar "$JAR" -data "$WS/ws-second" -port "$PORT" -token "$TOKEN" 2>&1 >/dev/null)"
TAKEN_STATUS=$?
rm -rf "$STORE2"
if [ "$TAKEN_STATUS" -eq 13 ] && printf '%s\n' "$TAKEN_ERR" | grep -qxF "$EXPECTED_TAKEN"; then
    echo "  ok    P5 a second start on the taken port exits 13 with the golden text"
else
    echo "  FAIL  P5 a second start on the taken port exited $TAKEN_STATUS; expected 13 and the line [$EXPECTED_TAKEN], got [$(printf '%s' "$TAKEN_ERR" | tail -3)]" >&2
    P5_FAILED=1
fi

# A deaf listener ends the process with 70 (about 90 seconds: three silent probes, thirty seconds apart).
bash "$ROOT/build/28k-e2e-deaf.sh" "$DIST" || P5_FAILED=1

# --- P6 (Stage 6, E19): the engine is "loading" until the Java provider binds, and publishes everything once it has --------------
# The Java provider is a declarative service (OSGI-INF/org.jawata.mcp.java.JavaProvider.xml in the engine bundle), so the component
# runtime binds it. A dist copy whose engine bundle has that descriptor removed is an engine whose language never arrives: it must say
# it is loading and publish only the tools that name no language (health_check, the project tools, memory, the hire path ...), and must
# not publish a Java tool. The dist as shipped (P0, above) is the other half: all 44, once the provider has bound.
# (Removing the component runtime itself would prove nothing: the application's own bundles need it and the process would not start.)
P6_FAILED=0
P6_PORT=$((PORT + 1))
P6_DIST="$(mktemp -d)"
P6_STORE="$(mktemp -d)"
P6_LOG="$WS/resident-p6.log"
mkdir -p "$P6_DIST/bundles"
for entry in "$DIST"/*; do
    [ "$(basename "$entry")" = "bundles" ] || ln -s "$entry" "$P6_DIST/$(basename "$entry")"
done
for bundle in "$DIST"/bundles/*; do
    case "$(basename "$bundle")" in
        org.jawata.mcp-[0-9]*.jar)
            python3 - "$bundle" "$P6_DIST/bundles/$(basename "$bundle")" << 'ZIP'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == "OSGI-INF/org.jawata.mcp.java.JavaProvider.xml":
            continue
        zout.writestr(item, zin.read(item.filename))
ZIP
            ;;
        *) ln -s "$bundle" "$P6_DIST/bundles/$(basename "$bundle")" ;;
    esac
done
# shellcheck disable=SC2086
java $VECTOR -Djawata.boot.home="$P6_DIST" -Djawata.experience.shared.dir="$P6_STORE" \
     -jar "$JAR" -data "$WS/ws-p6" -port "$P6_PORT" -token "$TOKEN" > "$P6_LOG" 2>&1 &
P6_PID=$!
P6_READY=0
for _ in $(seq 1 120); do
    grep -q "READY" "$P6_LOG" 2>/dev/null && { P6_READY=1; break; }
    kill -0 "$P6_PID" 2>/dev/null || break
    sleep 1
done
if [ "$P6_READY" -ne 1 ]; then
    echo "  FAIL  P6 the resident whose engine bundle has no Java provider descriptor never printed READY: $(tail -3 "$P6_LOG")" >&2
    P6_FAILED=1
else
    PROBE_PORT="$P6_PORT" PROBE_TOKEN="$TOKEN" PROBE_ROOT="$ROOT" python3 - << 'PY' || P6_FAILED=1
import json, os, re, urllib.request

PORT, TOKEN, ROOT = os.environ["PROBE_PORT"], os.environ["PROBE_TOKEN"], os.environ["PROBE_ROOT"]

def rpc(method, params):
    req = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": "e2e-28k-p6", "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(r, timeout=60).read().decode())

with open(os.path.join(ROOT, "README.md"), encoding="utf-8") as readme:
    block = re.search(r"<!-- TOOLS:BEGIN.*?<!-- TOOLS:END", readme.read(), re.S)
expected = len(set(re.findall(r"`([a-z_]+)`", block.group(0)))) if block else 0

names = [t["name"] for t in rpc("tools/list", {})["result"]["tools"]]
data = json.loads(rpc("tools/call", {"name": "health_check", "arguments": {}})["result"]["content"][0]["text"])["data"]
failed = False
if "Loading" in str(data.get("status")):
    print("  ok    P6 with no language bound the engine says it is loading: status %r" % data.get("status"))
else:
    print("  FAIL  P6 with no language bound health_check answers status %r, not loading" % data.get("status")); failed = True
if "health_check" in names and "search_symbols" not in names and 0 < len(names) < expected and data.get("toolCount") == len(names):
    print("  ok    P6 only the tools that name no language are published: %d of %d, no search_symbols" % (len(names), expected))
else:
    print("  FAIL  P6 published %d tools (%d expected with a language), toolCount %s, search_symbols present: %s"
          % (len(names), expected, data.get("toolCount"), "search_symbols" in names)); failed = True
raise SystemExit(1 if failed else 0)
PY
fi
kill "$P6_PID" 2>/dev/null; wait "$P6_PID" 2>/dev/null
rm -rf "$P6_DIST" "$P6_STORE"

# --- P7 (Stage 7, E21): the resident starts from the new bundle layout and every observation of Stage 0 still holds -----------------
# The headless host is its own bundle (org.jawata.resident): the application, the lifecycle, the workspace manager, the importer and the
# resolver live in its jar and in no other. Three promises, all on the built dist:
#   (a) the layout: the resident jar holds the application class and the extension that names it under the unchanged id
#       org.jawata.mcp.application; the engine jar holds no application class; the core jar holds no workspace-building class.
#   (b) the token file: a start with -token-file prints the READY line of the golden shape and writes the token with mode 600.
#   (c) workspace.json and residents.json: build/two-resident-probe.sh starts two residents, each loading the project named in its own
#       workspace.json, and a search on the first reaches the second through residents.json, as before the move.
P7_FAILED=0

python3 - "$DIST" << 'PY' || P7_FAILED=1
import glob, sys, zipfile

dist = sys.argv[1]
def jar(prefix):
    found = glob.glob("%s/bundles/%s-[0-9]*.jar" % (dist, prefix))
    return zipfile.ZipFile(found[0]) if len(found) == 1 else None

failed = False
resident, engine, core = jar("org.jawata.resident"), jar("org.jawata.mcp"), jar("org.jawata.core")
if not (resident and engine and core):
    print("  FAIL  P7 the dist does not hold exactly one jar each of org.jawata.resident, org.jawata.mcp, org.jawata.core"); sys.exit(1)
names = lambda z: set(z.namelist())
checks = [
    ("the resident jar holds the application class", "org/jawata/resident/JawataApplication.class" in names(resident)),
    ("the resident jar holds the workspace manager and the importer",
     {"org/jawata/resident/workspace/WorkspaceManager.class", "org/jawata/resident/project/ProjectImporter.class"} <= names(resident)),
    ("the resident extension names the application under the unchanged id",
     b'id="org.jawata.mcp.application"' in resident.read("plugin.xml")
     and b'class="org.jawata.resident.JawataApplication"' in resident.read("plugin.xml")),
    ("the engine jar holds no application class and no extension", "org/jawata/mcp/JawataApplication.class" not in names(engine)
     and "plugin.xml" not in names(engine)),
    ("the core jar holds no workspace-building class", not any(n.startswith((
     "org/jawata/core/workspace/WorkspaceManager", "org/jawata/core/workspace/WorkspaceFileWatcher",
     "org/jawata/core/workspace/WorkspaceSettings", "org/jawata/core/workspace/StrictDiskSync", "org/jawata/core/workspace/OwnedReconciler",
     "org/jawata/core/JdtServiceImpl", "org/jawata/core/HeadlessModelSource",
     "org/jawata/core/project/ProjectImporter", "org/jawata/core/project/ClasspathApplier", "org/jawata/core/project/ClasspathCache",
     "org/jawata/core/project/ExternalBundlePool", "org/jawata/core/project/ImportResult",
     "org/jawata/core/project/DependencyResolutionException", "org/jawata/core/resolve/")) for n in names(core))),
    ("the Gradle tooling jar travels with the resident, not with core",
     "lib/gradle-tooling-api-8.10.jar" in names(resident) and "lib/gradle-tooling-api-8.10.jar" not in names(core)),
]
for label, held in checks:
    print(("  ok    P7 " if held else "  FAIL  P7 ") + label)
    failed = failed or not held
sys.exit(1 if failed else 0)
PY

P7_PORT=$((PORT + 2))
P7_DIR="$(mktemp -d)"
P7_STORE="$(mktemp -d)"
P7_TOKEN_FILE="$P7_DIR/token"
P7_LOG="$P7_DIR/resident.log"
# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$P7_STORE" -jar "$JAR" -data "$P7_DIR/ws" -port "$P7_PORT" \
     -token-file "$P7_TOKEN_FILE" > "$P7_LOG" 2>&1 &
P7_PID=$!
P7_READY=0
for _ in $(seq 1 120); do
    grep -q "READY" "$P7_LOG" 2>/dev/null && { P7_READY=1; break; }
    kill -0 "$P7_PID" 2>/dev/null || break
    sleep 1
done
if [ "$P7_READY" -ne 1 ]; then
    echo "  FAIL  P7 the resident started with -token-file never printed READY: $(tail -3 "$P7_LOG")" >&2
    P7_FAILED=1
else
    P7_SHAPE="$(grep -m1 'READY' "$P7_LOG" | sed -e "s/:$P7_PORT/:<port>/" -e "s#token-file=.*#token-file=<path>#")"
    P7_GOLDEN="$(sed -n 's/^READY (token given with -token-file): //p' "$ROOT/build/28k-golden-boot.txt")"
    if [ "$P7_SHAPE" = "$P7_GOLDEN" ]; then
        echo "  ok    P7 the READY line of a -token-file start has the golden shape"
    else
        echo "  FAIL  P7 the READY line is [$P7_SHAPE], the golden is [$P7_GOLDEN]" >&2
        P7_FAILED=1
    fi
    if [ "$(stat -c %a "$P7_TOKEN_FILE" 2>/dev/null)" = "600" ] && [ "$(wc -l < "$P7_TOKEN_FILE")" -eq 1 ] && [ -s "$P7_TOKEN_FILE" ]; then
        echo "  ok    P7 the token file holds the token and a line separator, mode 600"
    else
        echo "  FAIL  P7 the token file is missing, not mode 600, or not one line: $(ls -l "$P7_TOKEN_FILE" 2>&1)" >&2
        P7_FAILED=1
    fi
fi
kill "$P7_PID" 2>/dev/null; wait "$P7_PID" 2>/dev/null
rm -rf "$P7_DIR" "$P7_STORE"

# (c) two residents, each loading the project its own workspace.json names, the first reaching the second through residents.json
bash "$ROOT/build/two-resident-probe.sh" "$DIST" || P7_FAILED=1

# --- P8-S4 (Stage 8, contract S4): a member that declares its source folders mounts exactly those -------------------------------------
# workspace.json carries, beside the project paths, "sourceRoots" for studio's two IntelliJ fixtures: intellij-root-plain (a module that
# names src with core/java merged into it, and a Maven module lib nested in it and listed beside it) and intellij-without-build (core with
# a main and a test folder, tools with src). The dist loads them, and inspect(project_structure) shows the declared folders.
P8_FAILED=0
P8_DIR="$(mktemp -d)"
P8_STORE="$(mktemp -d)"
P8_LOG="$P8_DIR/resident.log"
mkdir -p "$P8_DIR/ws" "$P8_DIR/fx"
cp -r "$ROOT/org.jawata.core.tests/test-resources/sample-projects/intellij-root-plain" \
      "$ROOT/org.jawata.core.tests/test-resources/sample-projects/intellij-without-build" "$P8_DIR/fx/"
FX="$P8_DIR/fx"
cat > "$P8_DIR/ws/workspace.json" << JSON
{"version":1,"name":"p8s4","projects":["$FX/intellij-root-plain","$FX/intellij-root-plain/lib","$FX/intellij-without-build/core","$FX/intellij-without-build/tools"],
 "sourceRoots":{"$FX/intellij-root-plain":{"main":["$FX/intellij-root-plain/src","$FX/intellij-root-plain/core/java"],"test":[]},
 "$FX/intellij-without-build/core":{"main":["$FX/intellij-without-build/core/java"],"test":["$FX/intellij-without-build/core/test"]},
 "$FX/intellij-without-build/tools":{"main":["$FX/intellij-without-build/tools/src"],"test":[]}}}
JSON
P8_PORT=$((PORT + 3))
# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$P8_STORE" -jar "$JAR" -data "$P8_DIR/ws" -port "$P8_PORT" -token "$TOKEN" > "$P8_LOG" 2>&1 &
P8_PID=$!
P8_READY=0
for _ in $(seq 1 120); do
    grep -q "READY" "$P8_LOG" 2>/dev/null && { P8_READY=1; break; }
    kill -0 "$P8_PID" 2>/dev/null || break
    sleep 1
done
if [ "$P8_READY" -ne 1 ]; then
    echo "  FAIL  P8-S4 the resident started on the IntelliJ fixtures never printed READY: $(tail -3 "$P8_LOG")" >&2
    P8_FAILED=1
else
    PROBE_PORT="$P8_PORT" PROBE_TOKEN="$TOKEN" python3 - << 'PY' || P8_FAILED=1
import json, os, time, urllib.request

PORT, TOKEN = os.environ["PROBE_PORT"], os.environ["PROBE_TOKEN"]

def call(name, arguments):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": arguments}}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": "e2e-28k-p8", "Content-Type": "application/json"})
    return json.loads(json.loads(urllib.request.urlopen(r, timeout=120).read().decode())["result"]["content"][0]["text"])

# The load is asynchronous: wait until all four members are in.
keys = []
for _ in range(120):
    projects = (call("health_check", {}).get("data") or {}).get("workspace", {}).get("projects", [])
    keys = sorted(p["projectKey"] for p in projects)
    if len(keys) == 4:
        break
    time.sleep(1)

failed = False
def check(label, held):
    global failed
    print(("  ok    P8-S4 " if held else "  FAIL  P8-S4 ") + label)
    failed = failed or not held

def roots(key):
    data = (call("inspect", {"kind": "project_structure", "projectKey": key}).get("data") or {})
    return sorted(r["path"].split("/")[-1] for r in data.get("sourceRoots", []))

check("the four members load, the Maven module lib nested in the root module as a project of its own: %s" % keys,
      keys == ["core", "intellij-root-plain", "lib", "tools"])
# The folder names are the importer's linked-folder names: src-<n>-<path with dashes>.
check("the root module mounts exactly the folders it declares, src and the merged core/java: %s" % roots("intellij-root-plain"),
      roots("intellij-root-plain") == ["src-0-src", "src-1-core-java"])
check("core mounts its main and its test folder: %s" % roots("core"), roots("core") == ["src-0-java", "src-1-test"])
check("tools mounts its src: %s" % roots("tools"), roots("tools") == ["src-0-src"])
check("lib, which declares nothing, is discovered as before (Maven layout): %s" % roots("lib"), roots("lib") == ["src-0-src-main-java"])
raise SystemExit(1 if failed else 0)
PY
fi
kill "$P8_PID" 2>/dev/null; wait "$P8_PID" 2>/dev/null
rm -rf "$P8_DIR" "$P8_STORE"

# --- P8 (Stage 8, contract §18 ring 1): a second language bound beside Java publishes its own tool, and Java's tools stay ------------
# org.jawata.standin is the stand-in language (language "fake", tool fake_echo) built as a test bundle beside the dist, not in it. A dist
# copy that has it in bundles/ is an engine with two languages: health_check lists both, tools/list holds the 44 and the stand-in's one.
P8L_PORT=$((PORT + 7))
P8L_DIST="$(mktemp -d)"
P8L_STORE="$(mktemp -d)"
P8L_LOG="$WS/resident-p8-standin.log"
P8L_JAR="$(ls "$DIST"/test-bundles/org.jawata.standin-[0-9]*.jar 2>/dev/null | head -1)"
mkdir -p "$P8L_DIST/bundles"
for entry in "$DIST"/*; do
    case "$(basename "$entry")" in bundles|test-bundles) ;; *) ln -s "$entry" "$P8L_DIST/$(basename "$entry")" ;; esac
done
for bundle in "$DIST"/bundles/*; do
    ln -s "$bundle" "$P8L_DIST/bundles/$(basename "$bundle")"
done
if [ -z "$P8L_JAR" ]; then
    echo "  FAIL  P8 the stand-in language bundle is not in the dist's test-bundles folder" >&2
    P8_FAILED=1
else
    ln -s "$P8L_JAR" "$P8L_DIST/bundles/$(basename "$P8L_JAR")"
    # shellcheck disable=SC2086
    java $VECTOR -Djawata.boot.home="$P8L_DIST" -Djawata.experience.shared.dir="$P8L_STORE" \
         -jar "$JAR" -data "$WS/ws-p8-standin" -port "$P8L_PORT" -token "$TOKEN" > "$P8L_LOG" 2>&1 &
    P8L_PID=$!
    P8L_READY=0
    for _ in $(seq 1 120); do
        grep -q "READY" "$P8L_LOG" 2>/dev/null && { P8L_READY=1; break; }
        kill -0 "$P8L_PID" 2>/dev/null || break
        sleep 1
    done
    if [ "$P8L_READY" -ne 1 ]; then
        echo "  FAIL  P8 the resident with the stand-in language beside Java never printed READY: $(tail -3 "$P8L_LOG")" >&2
        P8_FAILED=1
    else
        PROBE_PORT="$P8L_PORT" PROBE_TOKEN="$TOKEN" python3 - << 'PY' || P8_FAILED=1
import json, os, time, urllib.request

PORT, TOKEN = os.environ["PROBE_PORT"], os.environ["PROBE_TOKEN"]

def rpc(method, params):
    req = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": "e2e-28k-p8l", "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(r, timeout=60).read().decode())

def tools_called(name, arguments):
    return json.loads(rpc("tools/call", {"name": name, "arguments": arguments})["result"]["content"][0]["text"])

failed = False
def check(label, held):
    global failed
    print(("  ok    P8 " if held else "  FAIL  P8 ") + label)
    failed = failed or not held

names = []
for _ in range(60):     # the second language binds when its component starts, just after READY
    names = [t["name"] for t in rpc("tools/list", {})["result"]["tools"]]
    if "fake_echo" in names:
        break
    time.sleep(1)
health = tools_called("health_check", {}).get("data") or {}
languages = health.get("languages")
check("health_check lists the stand-in language beside java: %s" % languages, isinstance(languages, list) and "fake" in languages and "java" in languages)
check("the stand-in's tool is published beside the 44: %d tools, fake_echo present: %s" % (len(names), "fake_echo" in names),
      "fake_echo" in names and len(names) == 45 and "search_symbols" in names)
echoed = tools_called("fake_echo", {"text": "hello"})
check("the stand-in's tool answers: %s" % json.dumps(echoed)[:160], echoed.get("success") and (echoed.get("data") or {}).get("echo") == "hello")
raise SystemExit(1 if failed else 0)
PY
    fi
    kill "$P8L_PID" 2>/dev/null; wait "$P8L_PID" 2>/dev/null
fi
rm -rf "$P8L_DIST" "$P8L_STORE"

# --- P8 notes (Stage 8, contract §14.1): the sibling path reaches the engine behind a live note and never the one behind a stale note ----
# The probe process is the live dummy: it serves the one question a sibling is asked (inspect source) on a loopback port and is the
# process the live note names. The notes are the canonical fixtures (org.jawata.mcp.tests/test-resources/engine-notes) with their
# process id, start time and address patched, in a scratch folder the resident is told to read (jawata.engine.notes.dir). A second
# server answers at the stale note's address, as can happen after a pid and a port are reused; it must never be asked.
P8N_DIR="$(mktemp -d)"
P8N_STORE="$(mktemp -d)"
P8N_LOG="$P8N_DIR/resident.log"
P8N_SAMPLE="$P8N_DIR/compile-clean"
mkdir -p "$P8N_DIR/ws" "$P8N_DIR/engines"
cp -r "$ROOT/org.jawata.core.tests/test-resources/sample-projects/compile-clean" "$P8N_SAMPLE"
printf '{"version":1,"name":"p8notes","projects":["%s"]}\n' "$P8N_SAMPLE" > "$P8N_DIR/ws/workspace.json"
# An empty registry of residents beside the workspace: the resident looks for the nearest residents.json above its data folder, and a
# stray one further up (a test of studio leaves one in the temp folder) would add a sibling to the count this promise asserts on.
printf '{"residents":[]}\n' > "$P8N_DIR/residents.json"
P8N_PORT=$((PORT + 4))
# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$P8N_STORE" -Djawata.engine.notes.dir="$P8N_DIR/engines" \
     -jar "$JAR" -data "$P8N_DIR/ws" -port "$P8N_PORT" -token "$TOKEN" > "$P8N_LOG" 2>&1 &
P8N_PID=$!
P8N_READY=0
for _ in $(seq 1 120); do
    grep -q "READY" "$P8N_LOG" 2>/dev/null && { P8N_READY=1; break; }
    kill -0 "$P8N_PID" 2>/dev/null || break
    sleep 1
done
if [ "$P8N_READY" -ne 1 ]; then
    echo "  FAIL  P8 the resident that reads a scratch notes folder never printed READY: $(tail -3 "$P8N_LOG")" >&2
    P8_FAILED=1
else
    PROBE_PORT="$P8N_PORT" PROBE_TOKEN="$TOKEN" PROBE_NOTES="$P8N_DIR/engines" PROBE_FIXTURES="$ROOT/org.jawata.mcp.tests/test-resources/engine-notes" \
    PROBE_LIVE_PORT=$((PORT + 5)) PROBE_STALE_PORT=$((PORT + 6)) python3 - << 'PY' || P8_FAILED=1
import datetime, json, os, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, TOKEN, NOTES, FIX = os.environ["PROBE_PORT"], os.environ["PROBE_TOKEN"], os.environ["PROBE_NOTES"], os.environ["PROBE_FIXTURES"]
LIVE_PORT, STALE_PORT = int(os.environ["PROBE_LIVE_PORT"]), int(os.environ["PROBE_STALE_PORT"])

asked = {"live": [], "stale": []}
def engine(kind):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            asked[kind].append({"authorization": self.headers.get("Authorization"), "peek": self.headers.get("X-Jawata-Peek")})
            tool = {"success": True, "data": {"typeName": "com.probe.eclipse.OnlyInEclipse", "projectKey": "eclipse-project"}}
            body = json.dumps({"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": json.dumps(tool)}]}}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        def log_message(self, *args): pass
    return Handler

for kind, port in (("live", LIVE_PORT), ("stale", STALE_PORT)):
    server = ThreadingHTTPServer(("127.0.0.1", port), engine(kind))
    threading.Thread(target=server.serve_forever, daemon=True).start()

def started_at(pid):
    """The process start time as the OS reports it; absent where this probe cannot read it (the contract: 'where the OS reports it')."""
    try:
        stat = open("/proc/%d/stat" % pid).read().rsplit(")", 1)[1].split()
        ticks = int(stat[19]); hz = os.sysconf("SC_CLK_TCK")
        boot = next(int(l.split()[1]) for l in open("/proc/stat") if l.startswith("btime"))
        return datetime.datetime.fromtimestamp(boot + ticks / hz, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except Exception:
        return None

def note(fixture, name, patch):
    n = json.load(open(os.path.join(FIX, fixture)))
    n.update(patch)
    json.dump(n, open(os.path.join(NOTES, name), "w"))

me = os.getpid()
live_patch = {"pid": me, "endpoint": "http://127.0.0.1:%d/mcp" % LIVE_PORT}
if started_at(me): live_patch["processStartedAt"] = started_at(me)
note("eclipse-1111111111111111.json", "eclipse-1111111111111111.json", live_patch)
note("eclipse-2222222222222222.json", "eclipse-2222222222222222.json", {"endpoint": "http://127.0.0.1:%d/mcp" % STALE_PORT})
for name in ("eclipse-4444444444444444.json", "eclipse-5555555555555555.json"):   # malformed, newer major: copied as they are
    open(os.path.join(NOTES, name), "w").write(open(os.path.join(FIX, name)).read())

def call(name, arguments):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": arguments}}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": "e2e-28k-p8n", "Content-Type": "application/json"})
    return json.loads(json.loads(urllib.request.urlopen(r, timeout=120).read().decode())["result"]["content"][0]["text"])

for _ in range(120):    # the load is asynchronous; a miss against a half-loaded workspace is not the miss this is about
    if (call("health_check", {}).get("data") or {}).get("workspace", {}).get("projects"):
        break
    time.sleep(1)

failed = False
def check(label, held):
    global failed
    print(("  ok    P8 " if held else "  FAIL  P8 ") + label)
    failed = failed or not held

miss = call("find_references", {"kind": "references", "symbol": "com.probe.eclipse.OnlyInEclipse"})
hint = (miss.get("error") or {}).get("hint") or ""
check("a symbol this resident lacks is answered from the live Eclipse note: ...%s" % hint[-230:],
      not miss.get("success") and "workspace 'live' has it, in project 'eclipse-project'" in hint)
check("exactly one engine was consulted of one listed: the stale, malformed and newer-major notes were not listed",
      "consulted 1 of 1" in hint)
check("the live engine was asked once, as a peek, without a key: %s" % asked["live"],
      len(asked["live"]) == 1 and asked["live"][0]["authorization"] is None and asked["live"][0]["peek"] == "1")
check("the server behind the stale note was never asked: %s" % asked["stale"], asked["stale"] == [])
raise SystemExit(1 if failed else 0)
PY
fi
kill "$P8N_PID" 2>/dev/null; wait "$P8N_PID" 2>/dev/null
rm -rf "$P8N_DIR" "$P8N_STORE"

[ "$PY_STATUS" -eq 0 ] && [ "$P5_FAILED" -eq 0 ] && [ "$P6_FAILED" -eq 0 ] && [ "$P7_FAILED" -eq 0 ] && [ "$P8_FAILED" -eq 0 ]
exit $?
