#!/usr/bin/env bash
# 28h S16a, the boundary clause: a real VS Code window with the jawata Java extension and no other Java extension, against a real engine.
#
# Not a gate and not part of any suite: a named run that records measured numbers. It
#   1. copies the built distribution to a scratch folder (a build in the tree cannot change the engine under the run),
#   2. starts the engine on a port with a token, loads a scratch copy of a sample tree through the engine's MCP door,
#   3. runs the extension's live test (jawata-chat/packages/vscode-java, `npm run test:live`) in a VS Code of its own profile under xvfb:
#      the test types a faulty method into a Java file without saving and waits for the editor to show the error at its characters,
#   4. reads, while the window runs, the process list: how many processes hold a Java language server of the window (the Red Hat one:
#      expected 0) and how many are the jawata engine (expected 1), and the engine's memory at its highest.
#
# Usage:  build/s16-window-run.sh [maven|plugins] [dist-dir]
#         maven   (default) the simple-maven sample, file com/example/Calculator.java
#         plugins the pair of Eclipse plug-in bundles pde-bundle-a and pde-bundle-b, file Caller.java of bundle a
# Needs:  the extension built (`npm run build` and `npm run compile-live` in jawata-chat/packages/vscode-java), java, python3, xvfb-run.
# Exit:   0 = the window showed the error at its characters and the process list was as expected · 1 = it did not · 2 = could not run

set -uo pipefail

TREE="${1:-maven}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST_SRC="${2:-$ROOT/build/dist/target/dist}"
CHAT="${JAWATA_CHAT_DIR:-$HOME/CursorProjects/jawata-chat}"
EXT="$CHAT/packages/vscode-java"
PORT="${JAWATA_PROBE_PORT:-8931}"
TOKEN="s16-window-$$"
SAMPLES="$ROOT/org.jawata.core.tests/test-resources/sample-projects"

[ -f "$DIST_SRC/jawata.jar" ] || { echo "no artifact at $DIST_SRC/jawata.jar: build first" >&2; exit 2; }
[ -f "$EXT/dist/extension.js" ] && [ -f "$EXT/out/test-live/problems.test.js" ] \
    || { echo "the extension is not built: run npm run build and npm run compile-live in $EXT" >&2; exit 2; }
command -v xvfb-run >/dev/null || { echo "xvfb-run is not installed" >&2; exit 2; }

WS="$(mktemp -d)"
LOG="$WS/engine.log"
OUT="$WS/measured.json"
SEEN="$WS/process-list.txt"
RESIDENT_PID=""
POLLER_PID=""

cleanup() {
    [ -n "$POLLER_PID" ] && kill "$POLLER_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    [ -n "${S16_KEEP_LOG:-}" ] && [ -f "$LOG" ] && cp "$LOG" "$S16_KEEP_LOG"   # a named run can keep the engine's log to read why a request failed
    rm -rf "$WS"
}
trap cleanup EXIT INT TERM HUP

cp -r "$DIST_SRC" "$WS/dist"
case "$TREE" in
    maven)
        cp -r "$SAMPLES/simple-maven" "$WS/tree"
        FILE="src/main/java/com/example/Calculator.java"
        RENAME_SYMBOL="com.example.Calculator#add"
        LOAD_PATHS="$WS/tree"
        FOLDER="$WS/tree"
        SCRATCH_DIR="src/main/java/com/example"
        SCRATCH_PACKAGE="com.example"
        ;;
    plugins)
        mkdir -p "$WS/tree"
        cp -r "$SAMPLES/pde-bundle-a" "$SAMPLES/pde-bundle-b" "$WS/tree/"
        FILE="pde-bundle-a/src/com/example/a/Caller.java"
        RENAME_SYMBOL="com.example.a.Caller#hello"
        LOAD_PATHS="$WS/tree/pde-bundle-b:$WS/tree/pde-bundle-a"
        FOLDER="$WS/tree"
        SCRATCH_DIR="pde-bundle-a/src/com/example/a"
        SCRATCH_PACKAGE="com.example.a"
        ;;
    *) echo "unknown tree '$TREE': maven or plugins" >&2; exit 2 ;;
esac

# 28h S16c: the files the refactoring window test works on, written into the scratch copy before the engine loads it, so the engine holds them:
# one with an unresolved name and an import nothing uses (code actions), one to rename and one to move (file operations)
SCRATCH="$FOLDER/$SCRATCH_DIR"
printf 'package %s;\n\nimport java.util.List;\n\npublic class HasAProblem {\n    Object made = new ArrayList<String>();\n}\n' "$SCRATCH_PACKAGE" > "$SCRATCH/HasAProblem.java"
printf 'package %s;\n\npublic class Scratch {\n    public Scratch() {\n    }\n}\n' "$SCRATCH_PACKAGE" > "$SCRATCH/Scratch.java"
printf 'package %s;\n\npublic class Mover {\n}\n' "$SCRATCH_PACKAGE" > "$SCRATCH/Mover.java"

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 && VECTOR="--add-modules jdk.incubator.vector"
# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$WS/store" -jar "$WS/dist/jawata.jar" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
READY=0
for _ in $(seq 1 120); do
    grep -q "READY" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "the engine died on startup:" >&2; tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "the engine never printed READY in 120 s:" >&2; tail -20 "$LOG" >&2; exit 2; }

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_PATHS="$LOAD_PATHS" python3 - << 'PY' || { echo "could not load the sample tree into the engine" >&2; exit 2; }
import json, os, urllib.request

def call(name, arguments):
    request = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": arguments}}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % os.environ["PROBE_PORT"], data=json.dumps(request).encode(),
        headers={"Authorization": "Bearer " + os.environ["PROBE_TOKEN"], "Mcp-Session-Id": "s16-window", "Content-Type": "application/json"})
    body = json.loads(urllib.request.urlopen(r, timeout=180).read().decode())
    text = json.loads(body["result"]["content"][0]["text"])
    if not text.get("success"):
        raise SystemExit("%s failed: %s" % (name, text))

paths = os.environ["PROBE_PATHS"].split(":")
call("load_project", {"projectPath": paths[0]})
for extra in paths[1:]:
    call("project", {"action": "add", "projectPath": extra})
PY

SERVER="$(python3 -c 'import json,sys; print(json.dumps({"command":"java","args":["-jar",sys.argv[1],"--lsp","--url","http://127.0.0.1:"+sys.argv[2],"--key",sys.argv[3]]}))' "$WS/dist/jawata.jar" "$PORT" "$TOKEN")"

# the process list while the window runs: processes of the window that are a Java language server of the Red Hat kind, and the engine
(
    : > "$SEEN"
    peak=0
    rivals=0
    engines=0
    while kill -0 "$RESIDENT_PID" 2>/dev/null; do
        rss="$(awk '/VmHWM/ {print $2}' "/proc/$RESIDENT_PID/status" 2>/dev/null || echo 0)"
        [ "${rss:-0}" -gt "$peak" ] && peak="$rss"
        # the highest count seen at any second of the run, so a server that lives only while the window is open is still counted
        rival="$(pgrep -af "$WS/profile" 2>/dev/null | grep -ciE 'redhat\.java|jdt\.ls|org\.eclipse\.jdt\.ls')"
        [ "${rival:-0}" -gt "$rivals" ] && rivals="$rival"
        now="$(pgrep -af "$WS/dist/jawata.jar" 2>/dev/null | grep -vc -- '--lsp')"
        [ "${now:-0}" -gt "$engines" ] && engines="$now"
        echo "$peak $rivals $engines" > "$SEEN"
        sleep 0.5
    done
) &
POLLER_PID=$!

# the planted break of the one-JDT check: with S16_PLANT_RIVAL=1 the window's own extensions folder holds a stand-in that carries the
# identifier of Red Hat's Java extension, which must turn the window test (and so this run) red
if [ "${S16_PLANT_RIVAL:-0}" = "1" ]; then
    mkdir -p "$WS/profile/extensions/redhat.java-0.0.1"
    printf '{"name":"java","publisher":"redhat","version":"0.0.1","engines":{"vscode":"^1.101.0"}}\n' > "$WS/profile/extensions/redhat.java-0.0.1/package.json"
fi

# the planted break of the process-list check: with S16_PLANT_RIVAL_PROCESS=1 a process whose command line names Red Hat's Java server
# and the window's profile lives while the window runs; the run must end red on the process count, not on the extension check
if [ "${S16_PLANT_RIVAL_PROCESS:-0}" = "1" ]; then
    (exec -a "java -jar $WS/profile/redhat.java/server/plugins/org.eclipse.equinox.launcher.jar" sleep 120) &
    RIVAL_PROCESS_PID=$!
    trap 'kill "$RIVAL_PROCESS_PID" 2>/dev/null; cleanup' EXIT
fi

cd "$EXT" || exit 2
JAWATA_LIVE_TREE="$TREE" JAWATA_LIVE_MCP_URL="http://127.0.0.1:$PORT" JAWATA_LIVE_MCP_KEY="$TOKEN" JAWATA_LIVE_RENAME_SYMBOL="$RENAME_SYMBOL" \
JAWATA_LIVE_WORKSPACE="$FOLDER" JAWATA_LIVE_SERVER="$SERVER" JAWATA_LIVE_FILE="$FILE" JAWATA_LIVE_OUT="$OUT" \
JAWATA_LIVE_SCRATCH_DIR="$SCRATCH_DIR" JAWATA_LIVE_SCRATCH_PACKAGE="$SCRATCH_PACKAGE" \
    JAWATA_LIVE_USER_DATA="$WS/profile" xvfb-run -a npx vscode-test --config .vscode-test.live.mjs
STATUS=$?
sleep 2

[ -f "$OUT" ] || { echo "the window run wrote no numbers (exit $STATUS)" >&2; exit 1; }
# 28h S16b: the navigation test writes the milliseconds each feature took; a run without them did not show the features
NAV="$OUT.navigation.$TREE.json"
[ -f "$NAV" ] || { echo "the window run wrote no navigation numbers (exit $STATUS)" >&2; exit 1; }
echo "measured navigation (tree $TREE, milliseconds): $(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))' "$NAV")"
# 28h S16c: the refactoring test writes the milliseconds rename, formatting, code actions and file operations took
REF="$OUT.refactoring.$TREE.json"
[ -f "$REF" ] || { echo "the window run wrote no refactoring numbers (exit $STATUS)" >&2; exit 1; }
echo "measured refactoring (tree $TREE, milliseconds): $(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))' "$REF")"
# 28h S16d: the structure test writes the milliseconds folding, selection, tokens, hints and lenses took
STRUCT="$OUT.structure.$TREE.json"
[ -f "$STRUCT" ] || { echo "the window run wrote no structure numbers (exit $STATUS)" >&2; exit 1; }
echo "measured structure (tree $TREE, milliseconds): $(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))' "$STRUCT")"
read -r PEAK RIVAL ENGINES < "$SEEN"
echo "measured (tree $TREE): $(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))' "$OUT")"
echo "measured: engine memory at its highest ${PEAK} kB; processes of the window holding a Red Hat Java server: ${RIVAL}; engine processes: ${ENGINES}"
if [ "$STATUS" -ne 0 ] || [ "${RIVAL:-1}" -ne 0 ] || [ "${ENGINES:-0}" -ne 1 ]; then
    echo "FAIL  the window test exited $STATUS, rival servers $RIVAL (expected 0), engine processes $ENGINES (expected 1)" >&2
    exit 1
fi
echo "ok    the window showed the error at its characters, every navigation feature over the typed text, and rename, formatting, code actions and file operations applied by the editor, and the process list holds one engine and no second Java server"
