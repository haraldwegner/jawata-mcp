#!/usr/bin/env bash
# Sprint 28k, E16 — P5 (deaf listener): a REAL headless resident, the built dist, whose HTTP listener is stopped under it, ends the
# process with status 70.
#
# WHY A SCRIPT AND NOT A TEST. The status is what the launcher exits with, and only a real process shows that: the in-process tests
# (HttpTransportReportsToTheHostTest, ResidentLifecycleTest) pin that the run loop returns and that the lifecycle says 70; this is
# the one place the number leaves a process. It takes about 90 seconds because the transport asks its own listener every 30 seconds
# and declares it deaf after three silences (REL44-32).
#
# HOW THE LISTENER IS MADE DEAF without a hook in the product: the resident is started with the JDK's debug agent, a debugger
# (jdb, shipped with every JDK) stops at the next request inside the HTTP handler, and from that thread stops the JDK HTTP server —
# the failure measured in the field (listener gone, process alive), reproduced from outside.
#
# Usage:  build/28k-e2e-deaf.sh [dist-dir]
# Exit:   0 = the process ended with status 70 · 1 = it ended with another status or stayed alive · 2 = could not run at all

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT=$(( ${JAWATA_PROBE_PORT:-8928} + 1 ))
DEBUG_PORT=$(( PORT + 1000 ))
TOKEN="deaf-28k-$$"

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }
command -v jdb >/dev/null 2>&1 || { echo "jdb is not on the path; it ships with the JDK" >&2; exit 2; }

WS="$(mktemp -d)"
STORE="$(mktemp -d)"
LOG="$WS/resident.log"
JDB_LOG="$WS/jdb.log"
PID=""
JDB_PID=""

cleanup() {
    [ -n "$PID" ] && kill -9 "$PID" 2>/dev/null
    [ -n "$JDB_PID" ] && kill -9 "$JDB_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT INT TERM HUP

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

# shellcheck disable=SC2086
java $VECTOR -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$DEBUG_PORT \
     -Djawata.experience.shared.dir="$STORE" -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
PID=$!
READY=0
for _ in $(seq 1 120); do
    grep -q "READY" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$PID" 2>/dev/null || { echo "resident died on startup:" >&2; tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "resident never printed READY in 120s" >&2; exit 2; }

# The debugger stops in the HTTP handler, waits for the request below, stops the server from that thread, and lets the thread go.
( echo 'stop in org.jawata.mcp.transport.HttpTransport$McpHandler.handle'
  sleep 25
  echo 'print exchange.getHttpContext().getServer().stop(0)'
  sleep 3
  echo 'cont'
  sleep 3
  echo 'quit' ) | jdb -attach "127.0.0.1:$DEBUG_PORT" > "$JDB_LOG" 2>&1 &
JDB_PID=$!
sleep 6
curl -s -m 60 -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' "http://127.0.0.1:$PORT/mcp" > /dev/null 2>&1 &

START=$(date +%s)
for _ in $(seq 1 150); do
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
done
if kill -0 "$PID" 2>/dev/null; then
    echo "  FAIL  P5 the listener was stopped and the process was still alive after $(( $(date +%s) - START ))s" >&2
    tail -10 "$JDB_LOG" >&2
    exit 1
fi
wait "$PID"; CODE=$?
PID=""
SECONDS_TAKEN=$(( $(date +%s) - START ))
if [ "$CODE" -eq 70 ] && grep -q "STOPPED ANSWERING" "$LOG"; then
    echo "  ok    P5 a deaf listener under the built dist ended the process with status 70 after ${SECONDS_TAKEN}s, and said why"
    exit 0
fi
echo "  FAIL  P5 the process ended with status $CODE after ${SECONDS_TAKEN}s (expected 70, with the log naming the deaf listener)" >&2
tail -10 "$LOG" >&2
exit 1
