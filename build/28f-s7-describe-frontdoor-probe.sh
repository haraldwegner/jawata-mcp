#!/usr/bin/env bash
# Sprint 28f Stage 7 — THE DESCRIBING QUEUE, THROUGH THE REAL FRONT DOOR.
#
# WHY THIS EXISTS. Stage 7 deliverable 6 is a RUN, not a unit test: it must produce real
# numbers over a real bundle. The in-process tests drive the verb handlers; this drives the
# WIRE, on the artifact that would ship, against a project loaded the way a user loads one.
#
# It also answers deliverable 6's precondition, which nothing else can: the live MCP server
# serves the RELEASED build, and `describe` does not exist there. A run against that server
# would refuse, and a refusal is not a measurement.
#
# WHAT IT PROVES, and every claim is about the wire rather than about a Java call:
#   * `describe action=next` answers over HTTP with units, their package, their members
#     and the contentHash `done` requires;
#   * `inScope` and `outstanding` are reported, so a short batch cannot be read as a
#     finished bundle;
#   * `done` closes a unit and the SAME `next` call stops offering it — resume, measured
#     end to end rather than asserted in process;
#   * `done` REFUSES without the hash, which is the over-claim guard;
#   * and `stats.describing` carries the progress, in its own block beside `catalogue`.
#
# Usage:  build/28f-s7-describe-frontdoor-probe.sh [dist-dir] [scope] [limit]
# Env:    PROBE_TRANSCRIPT=<path>   keep the request/response transcript
# Exit:   0 = every claim held · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
SCOPE="${2:-org.jawata.mcp.knowledge}"
LIMIT="${3:-5}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8907}"
TOKEN="describe-frontdoor-probe-$$"

WS="$(mktemp -d)"       # throwaway workspace AND store: the probe must never
STORE="$(mktemp -d)"    # read or write the developer's real one
LOG="$WS/resident.log"
TRANSCRIPT="${PROBE_TRANSCRIPT:-$WS/transcript.txt}"
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
java $VECTOR -Xmx3g -Djawata.experience.shared.dir="$STORE" \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
READY=0
for _ in $(seq 1 180); do
    grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2
                                             tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "resident never announced readiness in 180s:" >&2
                        tail -20 "$LOG" >&2; exit 2; }

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_TRANSCRIPT_PATH="$TRANSCRIPT" \
PROBE_ROOT="$ROOT" PROBE_SCOPE="$SCOPE" PROBE_LIMIT="$LIMIT" \
python3 - << 'PY'
import json, os, time, urllib.request

PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]
ROOT = os.environ["PROBE_ROOT"]
SCOPE = os.environ["PROBE_SCOPE"]
LIMIT = int(os.environ["PROBE_LIMIT"])
TRANSCRIPT = open(os.environ["PROBE_TRANSCRIPT_PATH"], "w")

passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def call(tool, args):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    body = json.dumps(req).encode()
    TRANSCRIPT.write("=== REQUEST\n%s\n" % json.dumps(req)[:2000])
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=body,
        headers={"Authorization": "Bearer " + TOKEN,
                 "Mcp-Session-Id": "describe-probe",
                 "Content-Type": "application/json"})
    raw = urllib.request.urlopen(r, timeout=900).read().decode()
    TRANSCRIPT.write("--- RESPONSE\n%s\n\n" % raw[:4000])
    TRANSCRIPT.flush()
    outer = json.loads(raw)
    return json.loads(outer["result"]["content"][0]["text"])

def xp(kind, **args):
    args["kind"] = kind
    reply = call("experience", args)
    if not reply.get("success"):
        return {"__refused__": reply}
    return reply.get("data", {})

# --- the project a user would load -----------------------------------------------------
t0 = time.time()
loaded = call("load_project", {"projectPath": ROOT})
if not loaded.get("success"):
    raise SystemExit("load_project refused: %s" % json.dumps(loaded)[:400])
print("  project loaded in %.0fs" % (time.time() - t0))

# --- 1. next answers over the wire -----------------------------------------------------
first = xp("describe", action="next", scope=SCOPE, limit=LIMIT)
if "__refused__" in first:
    raise SystemExit("describe action=next was refused over the wire: %s"
                     % json.dumps(first["__refused__"])[:600])
units = first.get("units") or []
if units:
    ok("next answered with %d unit(s) in %s (inScope=%s, outstanding=%s)"
       % (len(units), SCOPE, first.get("inScope"), first.get("outstanding")))
else:
    bad("next answered with NO units for %s — the scope names nothing, so every claim "
        "below would be vacuous: %s" % (SCOPE, json.dumps(first)[:400]))
    raise SystemExit(1)

u = units[0]
for key in ("unit", "contentHash", "package", "types"):
    if u.get(key) is not None:
        ok("each unit carries %s" % key)
    else:
        bad("a unit is missing %s: %s" % (key, json.dumps(u)[:300]))

if isinstance(first.get("inScope"), int) and isinstance(first.get("outstanding"), int):
    ok("inScope=%d and outstanding=%d are reported, so a short batch cannot read as a "
       "finished bundle" % (first["inScope"], first["outstanding"]))
else:
    bad("inScope/outstanding missing: %s" % json.dumps(first)[:300])

# --- 2. done REFUSES without the hash --------------------------------------------------
refused = xp("describe", action="done", unit=u["unit"])
if "__refused__" in refused:
    msg = json.dumps(refused["__refused__"])
    if "action=next" in msg:
        ok("done without a hash is refused, and the refusal names where the value comes from")
    else:
        bad("done was refused but did not say where the hash comes from: %s" % msg[:400])
else:
    bad("done ACCEPTED a unit with no hash — it would record today's text as described, "
        "retiring the unit with a description nobody wrote")

# --- 3. done closes the unit, and next stops offering it -------------------------------
closed = xp("describe", action="done", unit=u["unit"],
            contentHash=u["contentHash"], bundle=u.get("bundle"))
if closed.get("recorded") is True:
    ok("done recorded the unit")
else:
    bad("done did not record: %s" % json.dumps(closed)[:400])

again = xp("describe", action="next", scope=SCOPE, limit=LIMIT)
still = [x.get("unit") for x in (again.get("units") or [])]
if u["unit"] not in still:
    ok("the described unit is no longer offered — resume, measured over the wire")
else:
    bad("the described unit is STILL offered: the queue does not resume")
if again.get("outstanding") == first.get("outstanding", 0) - 1:
    ok("outstanding fell by exactly one (%s -> %s)"
       % (first.get("outstanding"), again.get("outstanding")))
else:
    bad("outstanding moved from %s to %s — one unit was described, so a different delta "
        "means the queue lost or gained units" % (first.get("outstanding"),
                                                  again.get("outstanding")))

# --- 4. stats carries the progress, in its own block -----------------------------------
stats = xp("stats")
describing = stats.get("describing")
if isinstance(describing, dict) and describing.get("describedPerBundle"):
    ok("stats.describing.describedPerBundle: %s"
       % json.dumps(describing["describedPerBundle"]))
else:
    bad("stats carries no describing block: %s" % json.dumps(list(stats.keys()))[:300])
if "catalogue" in stats:
    ok("and the imported PATTERN catalogue is still its own block — the two are not merged")
else:
    bad("stats lost its catalogue block")

print("\nUNITS THE FIRST BATCH OFFERED (scope=%s, limit=%d):" % (SCOPE, LIMIT))
for x in units:
    members = sum(len(t.get("members") or []) for t in (x.get("types") or []))
    print("  %-70s  %d member(s)" % (x["unit"].split("/")[-1], members))

print("\npassed=%d failed=%d" % (passed, failed))
raise SystemExit(0 if failed == 0 else 1)
PY
PROBE_STATUS=$?
echo "transcript: $TRANSCRIPT"
exit "$PROBE_STATUS"
