#!/usr/bin/env bash
# Sprint 28f Stage 8 D5 — THE RE-DERIVED JOB, OVER JAWATA'S OWN TREE.
#
# WHY THIS EXISTS. The unit test proves the detector on a fixture built for it, which is
# necessary and is not the claim E11 makes. E11 says the compiler net names the populations
# this project's predecessors found BY HAND, and a fixture cannot say anything about that:
# those populations live in jawata-mcp, and only a run over jawata-mcp reaches them.
#
# It also answers a precondition nothing else can: the live MCP server serves the RELEASED
# build, where `re_derived_job` does not exist. A run against that server would refuse, and
# a refusal is not a measurement.
#
# WHAT IT MEASURES, and the second is the half a fixture cannot supply:
#   * the parse-helper population — ~34 private static parse helpers over a compilation
#     unit, recorded by hand at C7 of the previous sprint and never merged — is NAMED;
#   * how much of that population the TOKEN finder can see. On the fixture the answer is
#     none, by construction. On real code it is a number nobody has measured, so this
#     REPORTS it rather than asserting one, and asserts only what the detector is for:
#     that it names members the token finder does not group.
#
# Usage:  build/28f-s8-rederived-job-probe.sh [dist-dir]
# Exit:   0 = every claim held · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8913}"
TOKEN="rederived-probe-$$"

WS="$(mktemp -d)"       # throwaway workspace AND store: the probe must never
STORE="$(mktemp -d)"    # read or write the developer's real one
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

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_ROOT="$ROOT" python3 - << 'PY'
import json, os, time, urllib.request

PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]
ROOT = os.environ["PROBE_ROOT"]

passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def call(tool, args):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN,
                 "Mcp-Session-Id": "rederived-probe",
                 "Content-Type": "application/json"})
    raw = urllib.request.urlopen(r, timeout=1800).read().decode()
    return json.loads(json.loads(raw)["result"]["content"][0]["text"])

t0 = time.time()
loaded = call("load_project", {"projectPath": ROOT})
if not loaded.get("success"):
    raise SystemExit("load_project refused: %s" % json.dumps(loaded)[:400])
print("  project loaded in %.0fs" % (time.time() - t0))

# --- 1. the detector answers over the wire ---------------------------------------------
t0 = time.time()
reply = call("find_quality_issue", {"kind": "re_derived_job"})
if not reply.get("success"):
    raise SystemExit("re_derived_job was refused over the wire: %s"
                     % json.dumps(reply)[:600])
data = reply.get("data", {})
findings = data.get("findings") or []
print("  scanned in %.0fs: filesScanned=%s methodsExamined=%s bindingsUnresolved=%s "
      "findings=%d" % (time.time() - t0, data.get("filesScanned"),
                       data.get("methodsExamined"), data.get("bindingsUnresolved"),
                       len(findings)))

if data.get("methodsExamined"):
    ok("the scan examined %s methods across %s files — a zero here would make every claim "
       "below vacuous" % (data.get("methodsExamined"), data.get("filesScanned")))
else:
    bad("the scan examined nothing: %s" % json.dumps(data)[:400])
    raise SystemExit(1)

# --- 2. the parse-helper population is named -------------------------------------------
# SELECTED BY SHAPE, NOT BY NAME, and the first version of this probe did it by name — which
# counted HsErrParser#parse(Path), FieldsProjection#parse(JsonNode) and GitDiff#parse(String)
# as members of one population and reported "35 members in 35 groups", a number whose own
# shape said it was wrong. A symbol search returns a name; the population is what the
# signatures say. That is why the finding carries `shape` as a field.
SHAPE = ("(org.eclipse.jdt.core.ICompilationUnit)"
         "->org.eclipse.jdt.core.dom.CompilationUnit")
population = [f for f in findings if f.get("shape") == SHAPE]
parse_hits = sorted({f.get("symbol") for f in population})
groups = {f.get("group") for f in population}
if len(parse_hits) >= 20 and len(groups) == 1:
    ok("the parse-helper population is NAMED: %d members in ONE group. This is the "
       "population the previous sprint recorded by hand at C7 and left unmerged, and it "
       "is one job derived %d times rather than %d coincidences."
       % (len(parse_hits), len(parse_hits), len(parse_hits)))
elif len(parse_hits) >= 20:
    bad("the population is named (%d members) but split across %d groups — on real code "
        "it must cluster as one job: %s" % (len(parse_hits), len(groups), sorted(groups)))
else:
    bad("expected the hand-recorded parse population (~34 members) at shape %s; got %d: %s"
        % (SHAPE, len(parse_hits), parse_hits[:10]))

for s in parse_hits[:8]:
    print("        %s" % s)
if len(parse_hits) > 8:
    print("        ... and %d more" % (len(parse_hits) - 8))

# --- 3. what the TOKEN finder can see of that same population --------------------------
tok = call("find_duplicate_code", {"minTokens": 5, "limit": 100000})
if not tok.get("success"):
    raise SystemExit("find_duplicate_code refused: %s" % json.dumps(tok)[:400])
grouped = set()
for g in (tok.get("data", {}).get("groups") or []):
    for inst in (g.get("instances") or []):
        if inst.get("methodName") == "parse":
            grouped.add(inst.get("filePath"))

# A finding's symbol is pkg.Type#parse; the token finder reports a FILE. They are compared
# on the type's simple name, which is that file's stem. Stated rather than assumed, because
# the two tools address a method differently and a silent mismatch here would print as a
# clean separation when it was really a failed join.
tok_types = {str(p).rsplit("/", 1)[-1].replace(".java", "") for p in grouped}
mine_types = {s.rsplit("#", 1)[0].rsplit(".", 1)[-1] for s in parse_hits}
only_mine = sorted(mine_types - tok_types)

# THE TWO COUNTS DO NOT SUBTRACT, and printing them alone invited exactly that: a C8
# auditor read "grouped 28 ... named 34" and reported the 7 below as an arithmetic error,
# because 34 - 28 is 6. The sets OVERLAP PARTIALLY — the token finder also groups parse
# methods this detector does not name — so the answer is a set difference and the overlap
# is what makes it legible. It is printed rather than left to be inferred.
print("  the token finder grouped %d parse method(s) at minTokens=5; this detector named %d;"
      " they agree on %d, so the token finder groups %d this detector does not name"
      % (len(tok_types), len(mine_types), len(mine_types & tok_types),
         len(tok_types - mine_types)))
if only_mine:
    ok("%d member(s) are named by THIS detector and not grouped by the token finder — "
       "which is what it is for. First few: %s" % (len(only_mine), only_mine[:6]))
else:
    bad("every member this detector named was also grouped by the token finder, so on this "
        "tree it found nothing the shipped finder could not. mine=%s token=%s"
        % (sorted(mine_types)[:10], sorted(tok_types)[:10]))

print("\nD5-LIVE passed=%d failed=%d" % (passed, failed))
raise SystemExit(1 if failed else 0)
PY
