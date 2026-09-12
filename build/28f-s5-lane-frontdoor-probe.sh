#!/usr/bin/env bash
# Sprint 28f Stage 5 — THE LANE SPLIT AND THE RULE LIFECYCLE, THROUGH THE REAL FRONT DOOR.
#
# WHY THIS EXISTS AT ALL. Stage 5 added three columns — lane, rule_version, retired_at —
# and this stage's own finding is that a column WRITTEN with no READER is not delivered:
# `retire_rule`'s javadoc promised that "list and get still answer, because 'what did this
# rule say, and until when' is a question the store should be able to answer", and until
# this stage `list` carried neither the version nor the date. The in-process tests drive
# the verb handlers; this drives the WIRE, which is what studio actually meets.
#
# WHAT IT PROVES:
#   * stats carries by_lane over the wire, and the groups ACCOUNT FOR every row;
#   * an entry whose type no lane rule covers is counted as its OWN group — the catch-all
#     this stage removed cannot come back in the reporting layer;
#   * list rows carry the lane, DERIVED per row (a lesson and a rule differ);
#   * a rule browses with its version, an amended one with the next version;
#   * and a RETIRED rule browses with the date it stopped applying, while a live one
#     carries no date at all — the control, without which "the retired one has a date" is
#     equally true of a list that stamps every row.
#
# Usage:  build/28f-s5-lane-frontdoor-probe.sh [dist-dir]
# Env:    PROBE_TRANSCRIPT=<path>   keep the request/response transcript
# Exit:   0 = every claim held · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8903}"
TOKEN="lane-frontdoor-probe-$$"

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
# EXIT alone is not enough: a shell killed by a signal can exit without running it, and
# the resident it started outlives the run (one leaked for two days once).
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

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_TRANSCRIPT_PATH="$TRANSCRIPT" \
python3 - << 'PY'
import json, os, urllib.request

PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]
TRANSCRIPT = open(os.environ["PROBE_TRANSCRIPT_PATH"], "w")

passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def call(tool, args):
    """One tools/call over the wire. Each request is written to the transcript BEFORE its
    answer is looked at, so a failing run still leaves what produced the failure."""
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    body = json.dumps(req).encode()
    TRANSCRIPT.write("=== REQUEST\n%s\n" % json.dumps(req))
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=body,
        headers={"Authorization": "Bearer " + TOKEN,
                 "Mcp-Session-Id": "lane-probe",
                 "Content-Type": "application/json"})
    raw = urllib.request.urlopen(r, timeout=180).read().decode()
    TRANSCRIPT.write("--- RESPONSE\n%s\n\n" % raw)
    TRANSCRIPT.flush()
    outer = json.loads(raw)
    return json.loads(outer["result"]["content"][0]["text"])

def xp(kind, **args):
    """One experience verb, UNWRAPPED past the {success, data, meta} envelope.

    A refused verb raises here rather than returning an envelope whose missing keys
    surface later as a KeyError about something unrelated — which is what the first
    version of this probe did, reporting a startup-order problem as a missing 'id'."""
    args["kind"] = kind
    reply = call("experience", args)
    if not reply.get("success"):
        raise SystemExit("verb '%s' was refused: %s" % (kind, json.dumps(reply)[:400]))
    return reply.get("data", {})

# --- seed ---------------------------------------------------------------------------
# A lesson (experience lane), a domain fact (domain lane), and one entry whose type no
# lane rule covers. The unruled one is the whole point of the (none) claim below.
lesson = xp("record", type="lesson",
            summary="the quokka ledger settles its totals at dusk, and a probe read at "
                    "noon reports a number nobody can reconcile",
            situation="when reconciling the quokka ledger before the close",
            verdict="worked")["id"]
xp("record", type="domain_fact",
   summary="the quokka ledger rounds half to even, which is why two independent sums of "
           "the same rows can differ by one cent")
xp("record", type="probe_unruled_type",
   summary="an entry of a type no lane rule covers, so the split has something to be "
           "honest about")

rule = xp("promote_rule", summary="settle the quokka ledger before the inspection",
          ids=[lesson])["id"]
live = xp("promote_rule", summary="keep the numbat ledger open until the close",
          ids=[lesson])["id"]
amended = xp("amend_rule", id=rule,
             summary="settle the quokka ledger before the inspection, and note the rate")["id"]
retired = xp("retire_rule", id=amended)
if retired.get("retired") is not True:
    bad("PROOF OF LIFE: retire_rule must actually retire, or every claim below is vacuous")

# --- 1. stats carries the split, over the wire ----------------------------------------
stats = xp("stats")
total = stats.get("total")
lanes = stats.get("by_lane")
if not isinstance(total, int) or total <= 0:
    bad("PROOF OF LIFE: stats must report a positive total; got %r" % (total,))
elif not isinstance(lanes, dict) or not lanes:
    bad("stats carries by_lane — got %r. A lane column with no reader is not delivered."
        % (lanes,))
else:
    ok("stats carries by_lane over the wire: %s" % lanes)

    summed = sum(v for v in lanes.values() if isinstance(v, int))
    if summed == total:
        ok("the split accounts for every row (%d)" % total)
    else:
        bad("the split must account for every row: groups sum to %d, total is %d — a "
            "split that DROPS rows reads as a shorter list: %s" % (summed, total, lanes))

    # THE DISCRIMINATOR: the unruled entry is its OWN group. Restore any default and this
    # key is gone, because the row is then inside some lane's count.
    if lanes.get("(none)") == 1:
        ok("the unclassified entry is counted as unclassified, not folded into a lane")
    else:
        bad("the unclassified entry must be its own group — folding it back into a lane "
            "is the catch-all this stage removed, restored one layer up: %s" % lanes)

# --- 2. list carries the lane, derived per row ----------------------------------------
rows = {r["id"]: r for r in xp("list", limit=200).get("entries", [])}

def row(name, entry_id):
    r = rows.get(entry_id)
    if r is None:
        bad("list must return the %s (%s); it returned %d rows"
            % (name, entry_id, len(rows)))
    return r

lesson_row = row("source lesson", lesson)
live_row = row("live rule", live)
retired_row = row("retired rule", amended)

if lesson_row and live_row:
    if live_row.get("lane") == "rules" and lesson_row.get("lane") == "experience":
        ok("list derives the lane PER ROW: the rule browses as rules, the lesson as "
           "experience")
    else:
        bad("the lane must be derived per row, not written on everything this verb "
            "lists: rule=%r lesson=%r"
            % (live_row.get("lane"), lesson_row.get("lane")))

# --- 3. the version, and the date it stopped applying ---------------------------------
if live_row and retired_row:
    if live_row.get("rule_version") == 1 and retired_row.get("rule_version") == 2:
        ok("the version is the row's own: promoted browses as 1, amended as 2")
    else:
        bad("a hard-coded version satisfies one row and not the other: live=%r amended=%r"
            % (live_row.get("rule_version"), retired_row.get("rule_version")))

    if retired_row.get("retired_at"):
        ok("UNTIL WHEN: the retired rule browses with %s" % retired_row["retired_at"])
    else:
        bad("a retired rule must browse with the date it stopped applying — the half "
            "`get` cannot carry, because it returns the frozen body: %r" % (retired_row,))

    if "retired_at" not in live_row:
        ok("AND THE CONTROL: the live rule carries no date at all")
    else:
        bad("a live rule must carry NO date — absent means not retired, and a list that "
            "stamped every row would satisfy the claim above while saying nothing: %r"
            % (live_row,))

print("")
print("LANE-FRONTDOOR passed=%d failed=%d" % (passed, failed))
raise SystemExit(1 if failed else 0)
PY
STATUS=$?

echo ""
echo "transcript: $TRANSCRIPT"
exit "$STATUS"
