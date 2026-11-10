#!/usr/bin/env bash
# jawata-author: Sprint 28i Stage 25 step A9, the live gate: a script that writes small scratch sources into a scratch copy of a sample tree and runs the extension's window test
# Sprint 28i, Stage 25, step A9: the person's Java work in a real VS Code window with the jawata Java extension and no other Java extension, against a real engine.
#
# Not a gate and not part of any suite: a named run that records what the window showed. It
#   1. copies the built distribution to a scratch folder (a build in the tree cannot change the engine under the run),
#   2. makes a scratch Maven tree from the selection-target sample (it has JUnit), adds the debug program, a failing test and the small classes the
#      refactorings are asked at, compiles the debug program with debug information, starts the engine and loads the tree through its MCP door,
#   3. runs the extension's stage 25 test (jawata-chat/packages/vscode-java, .vscode-test.stage25.mjs) in a VS Code of its own profile under xvfb:
#      refactorings offered, applied and undone by the editor; the tests listed and a failing one run; a program started from the editor stopped at a
#      breakpoint with its variables, an expression and a step; the running program profiled and its hot method named with its source,
#   4. reads, while the window runs, the process list: processes holding a Red Hat Java server (expected 0) and engine processes (expected 1).
#
# Usage:  build/s25-window-run.sh [dist-dir]
# Needs:  the extension built (`npm run build` and `npm run compile-live` in jawata-chat/packages/vscode-java), java and javac, python3, xvfb-run.
# Exit:   0 = every measure held and the process list was as expected · 1 = it did not · 2 = could not run

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST_SRC="${1:-$ROOT/build/dist/target/dist}"
CHAT="${JAWATA_CHAT_DIR:-$HOME/CursorProjects/jawata-chat}"
EXT="$CHAT/packages/vscode-java"
PORT="${JAWATA_PROBE_PORT:-8932}"
TOKEN="s25-window-$$"
SAMPLES="$ROOT/org.jawata.core.tests/test-resources/sample-projects"

[ -f "$DIST_SRC/jawata.jar" ] || { echo "no artifact at $DIST_SRC/jawata.jar: build first" >&2; exit 2; }
[ -f "$EXT/dist/extension.js" ] && [ -f "$EXT/dist/profile.js" ] && [ -f "$EXT/out/test-live/stage25.test.js" ] \
    || { echo "the extension is not built: run npm run build and npm run compile-live in $EXT" >&2; exit 2; }
command -v xvfb-run >/dev/null || { echo "xvfb-run is not installed" >&2; exit 2; }
command -v javac >/dev/null || { echo "javac is not installed" >&2; exit 2; }

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
    [ -n "${S25_KEEP_LOG:-}" ] && [ -f "$LOG" ] && cp "$LOG" "$S25_KEEP_LOG"
    [ -n "${S25_KEEP_OUT:-}" ] && [ -f "$OUT" ] && cp "$OUT" "$S25_KEEP_OUT"
    rm -rf "$WS"
}
trap cleanup EXIT INT TERM HUP

cp -r "$DIST_SRC" "$WS/dist"
cp -r "$SAMPLES/selection-target" "$WS/tree"
MAIN="$WS/tree/src/main/java/com/example"
TEST="$WS/tree/src/test/java/com/example/sel"
mkdir -p "$MAIN/debug" "$MAIN/sel" "$TEST"
cp "$SAMPLES/debug-target/src/main/java/com/example/debug/DebugTarget.java" "$MAIN/debug/DebugTarget.java"
cp "$SAMPLES/debug-target/src/main/java/com/example/debug/HotLoopTarget.java" "$MAIN/debug/HotLoopTarget.java"

# the classes the refactorings are asked at, and a test that fails
cat > "$MAIN/sel/Shared.java" <<'JAVA'
package com.example.sel;

public class Shared {

    public int value() {
        return 7;
    }

    public int add(int a, int b) {
        return a + b;
    }
}
JAVA
cat > "$MAIN/sel/SharedUser.java" <<'JAVA'
package com.example.sel;

public class SharedUser {

    public int twice(Shared shared) {
        return shared.value() * 2;
    }
}
JAVA
cat > "$MAIN/sel/Parent.java" <<'JAVA'
package com.example.sel;

public class Parent {
}
JAVA
cat > "$MAIN/sel/Child.java" <<'JAVA'
package com.example.sel;

public class Child extends Parent {

    public int doubled() {
        return 2;
    }
}
JAVA
cat > "$TEST/FailsTest.java" <<'JAVA'
package com.example.sel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FailsTest {

    @Test
    void addsUp() {
        assertEquals(4, 5, "expected the sum to be four");
    }
}
JAVA

mkdir -p "$WS/classes"
javac -g -d "$WS/classes" "$MAIN/debug/DebugTarget.java" "$MAIN/debug/HotLoopTarget.java" || { echo "the debug programs did not compile" >&2; exit 2; }

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

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_PATH="$WS/tree" python3 - << 'PY' || { echo "could not load the scratch tree into the engine" >&2; exit 2; }
import json, os, urllib.request

request = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "load_project", "arguments": {"projectPath": os.environ["PROBE_PATH"]}}}
r = urllib.request.Request("http://127.0.0.1:%s/mcp" % os.environ["PROBE_PORT"], data=json.dumps(request).encode(),
    headers={"Authorization": "Bearer " + os.environ["PROBE_TOKEN"], "Mcp-Session-Id": "s25-window", "Content-Type": "application/json"})
body = json.loads(urllib.request.urlopen(r, timeout=180).read().decode())
text = json.loads(body["result"]["content"][0]["text"])
if not text.get("success"):
    raise SystemExit("load_project failed: %s" % text)
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
        rival="$(pgrep -af "$WS/profile" 2>/dev/null | grep -ciE 'redhat\.java|jdt\.ls|org\.eclipse\.jdt\.ls')"
        [ "${rival:-0}" -gt "$rivals" ] && rivals="$rival"
        now="$(pgrep -af "$WS/dist/jawata.jar" 2>/dev/null | grep -vcE -- '--lsp|--dap')"
        [ "${now:-0}" -gt "$engines" ] && engines="$now"
        echo "$peak $rivals $engines" > "$SEEN"
        sleep 0.5
    done
) &
POLLER_PID=$!

cd "$EXT" || exit 2
JAWATA_LIVE_WORKSPACE="$WS/tree" JAWATA_LIVE_SERVER="$SERVER" JAWATA_LIVE_DEBUG_CLASSPATH="$WS/classes" JAWATA_LIVE_OUT="$OUT" \
    JAWATA_LIVE_USER_DATA="$WS/profile" xvfb-run -a npx vscode-test --config .vscode-test.stage25.mjs
STATUS=$?
sleep 2

[ -f "$OUT" ] || { echo "the window run wrote no numbers (exit $STATUS)" >&2; exit 1; }
read -r PEAK RIVAL ENGINES < "$SEEN"
echo "measured: $(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))' "$OUT")"
echo "measured: engine memory at its highest ${PEAK} kB; processes of the window holding a Red Hat Java server: ${RIVAL}; engine processes: ${ENGINES}"
if [ "$STATUS" -ne 0 ] || [ "${RIVAL:-1}" -ne 0 ] || [ "${ENGINES:-0}" -ne 1 ]; then
    echo "FAIL  the window test exited $STATUS, rival servers $RIVAL (expected 0), engine processes $ENGINES (expected 1)" >&2
    exit 1
fi
echo "ok    refactorings applied and undone by the editor, a failing test run with its message at its line, a program stopped at a breakpoint with its variables, an expression and a step, the running program profiled with its hot method's source, and one engine with no second Java server"
