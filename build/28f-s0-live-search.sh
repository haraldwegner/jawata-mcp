#!/usr/bin/env bash
# Sprint 28f Stage 0, measurement 1: the wall-clock a seat waits for a prose
# search for a capability across the loaded workspace (1061 sources).
# Read-only: it starts its OWN resident on a scratch workspace and a scratch
# store, so the developer's real store is never touched.
set -uo pipefail
ROOT=/home/harald/CursorProjects/jawata-mcp
JAR="$ROOT/build/dist/target/dist/jawata.jar"
PORT="${S0_PORT:-8917}"
TOKEN="s0-live-search-$$"
WS="$(mktemp -d)"; STORE="$(mktemp -d)"; LOG="$WS/resident.log"
RESIDENT_PID=""
cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT INT TERM HUP
[ -f "$JAR" ] || { echo "no artifact at $JAR - build first" >&2; exit 2; }

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

S0_PORT="$PORT" S0_TOKEN="$TOKEN" S0_PROJECT="$ROOT" python3 - << 'PY'
import json, os, statistics, sys, time, urllib.request

PORT = os.environ["S0_PORT"]; TOKEN = os.environ["S0_TOKEN"]
PROJECT = os.environ["S0_PROJECT"]
URL = f"http://127.0.0.1:{PORT}/mcp"
_id = [0]

def call(tool, args, timeout=300):
    _id[0] += 1
    body = json.dumps({"jsonrpc": "2.0", "id": _id[0], "method": "tools/call",
                       "params": {"name": tool, "arguments": args}}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {TOKEN}"})
    started = time.perf_counter()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read().decode()
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    for line in raw.splitlines():
        if line.startswith("data: "):
            raw = line[6:]; break
    return elapsed_ms, json.loads(raw)

print("loading the project (excluded from every timing below) ...", flush=True)
ms, res = call("load_project", {"projectPath": PROJECT})
print(f"  load_project: {ms/1000:.1f}s", flush=True)

QUERIES = [
    "backup the store before a destructive verb",
    "resolve a symbol to its declaration",
    "compute a meaning vector for an entry",
    "detect duplicated code across the workspace",
    "rename a symbol and update every reference",
    "parse a compilation unit into an abstract syntax tree",
    "write an archive before deleting entries",
    "measure which lines the tests covered",
    "refuse an entry whose form is incomplete",
    "rank candidates by distance and return the best",
]

print("warm-up (excluded) ...", flush=True)
call("find_string_literals", {"query": "warm up the scan", "maxResults": 5})

# THE CONTROL. Without it, ten zero-hit prose queries cannot tell "the prose is
# not in string literals" from "the instrument is broken". This needle IS a
# string literal in this workspace, so it MUST hit.
cms, cres = call("find_string_literals", {"query": "osgi.instance.area", "maxResults": 10})
cpay = json.loads(cres["result"]["content"][0]["text"])["data"]
print(f"  CONTROL {cms:8.1f} ms   hits={cpay.get('totalMatches')} "
      f"examined={cpay.get('filesExamined')}  literal: osgi.instance.area", flush=True)
CONTROL_HITS = cpay.get("totalMatches", 0)

rows = []
for q in QUERIES:
    ms, res = call("find_string_literals", {"query": q, "maxResults": 50})
    try:
        payload = json.loads(res["result"]["content"][0]["text"])
        d = payload.get("data", {})
        hits = d.get("totalMatches", 0); examined = d.get("filesExamined", 0)
    except Exception:
        hits = -1; examined = -1
    rows.append((ms, hits, examined, q))
    print(f"  {ms:8.1f} ms   hits={hits:<4} examined={examined:<6} {q}", flush=True)

times = sorted(r[0] for r in rows)
median = statistics.median(times)
k = 0.95 * (len(times) - 1)
lo, hi = int(k), min(int(k) + 1, len(times) - 1)
p95 = times[lo] + (k - lo) * (times[hi] - times[lo])
total_hits = sum(r[1] for r in rows if r[1] > 0)
found = sum(1 for r in rows if r[1] > 0)

print()
print("S0-MEASUREMENT-1 "
      f"queries={len(rows)} median_ms={median:.1f} p95_ms={p95:.1f} "
      f"min_ms={times[0]:.1f} max_ms={times[-1]:.1f} "
      f"files_examined={rows[0][2]} queries_with_any_hit={found} total_hits={total_hits} "
      f"control_hits={CONTROL_HITS}")

# THE CONTROL DECIDES THE EXIT CODE, not just the printout. Ten zero-hit
# queries mean "the prose is unreachable" only while the control proves the
# scan can reach anything at all; with a dead control they mean nothing, and a
# run that printed the same table and exited 0 would be indistinguishable.
# This is the same defect the derived bar had - a labelled conclusion whose
# own precondition went unevaluated by the thing printing it - fixed in the
# second of the three places it occurred.
if CONTROL_HITS < 1:
    print("S0-MEASUREMENT-1 INVALID: the control needle found nothing, so this run"
          " says nothing about the queries. The instrument, not the corpus, is what"
          " failed.")
    sys.exit(1)
PY
