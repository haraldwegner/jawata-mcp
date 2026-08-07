#!/usr/bin/env bash
# Sprint 28 (D-UNWIRED) — the release gate for hollow wiring.
#
# Runs the called_only_by_tests detector through the product's OWN FRONT DOOR,
# against the BUILT DIST, over jawata's own repository, and fails on any
# finding not in the committed baseline.
#
# Why the front door and not a unit test: a unit test constructs the detector
# and hands it its own wiring, so it cannot tell a registered detector from an
# unregistered one, and it runs against a build tree rather than the artifact
# being published. mcp#9 was CLOSED by a green unit test while the defect was
# live for two sprints. This gate calls the shipped jar over HTTP.
#
# Usage:  build/unwired-gate.sh [--update-baseline]
# Exit:   0 = no new findings · 1 = new findings (or a broken invariant)
#         2 = DID NOT RUN (no dist, resident died, empty scan) — not a pass
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/build/dist/target/dist/jawata.jar"
BASELINE="$ROOT/build/unwired-baseline.txt"
UPDATE=0
[ "${1:-}" = "--update-baseline" ] && UPDATE=1

WORK="$(mktemp -d -t jawata-unwired-XXXXXX)"
PORT="${UNWIRED_PORT:-18749}"
TOKEN="unwired-gate-$$"
RESIDENT_PID=""
cleanup() { [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

if [ ! -f "$JAR" ]; then
    echo "gate: RESULT=not-built — no artifact at $JAR."
    echo "gate: a gate that cannot run is UNPROVEN, not passing. exit 2"
    exit 2
fi

# A throwaway workspace and store: the gate must never touch the developer's.
mkdir -p "$WORK/ws" "$WORK/store"
java -Djawata.experience.shared.dir="$WORK/store" \
     -jar "$JAR" -data "$WORK/ws" -port "$PORT" -token "$TOKEN" \
     > "$WORK/resident.log" 2>&1 &
RESIDENT_PID=$!
for _ in $(seq 1 120); do
    grep -q "READY" "$WORK/resident.log" 2>/dev/null && break
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "gate: RESULT=resident-died on startup"
                                             tail -20 "$WORK/resident.log"; exit 2; }
    sleep 1
done
grep -q "READY" "$WORK/resident.log" || { echo "gate: RESULT=resident-never-ready"; exit 2; }

call() {   # call <tool> <json-args>
    curl -s --max-time 900 -X POST "http://127.0.0.1:$PORT/mcp" \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: unwired-gate-$$" \
        -H 'Content-Type: application/json' \
        -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",
             \"params\":{\"name\":\"$1\",\"arguments\":$2}}"
}

call load_project "{\"projectPath\":\"$ROOT\"}" > "$WORK/load.json"
# family="quality", NOT kind="called_only_by_tests". Naming the kind would keep
# this gate green even if the detector were dropped from the standard sweep —
# the gate would be reaching it by a route no ordinary user takes. Sweeping the
# family means the gate ALSO proves the detector fires unprompted.
call find_quality_issue '{"family":"quality","limit":10000}' > "$WORK/findings.json"

# Parse, and REFUSE a scan that examined nothing or admits partiality — an
# empty result from a failed scan reported as "clean" is the lie this whole
# sprint exists to stop.
python3 - "$WORK/findings.json" "$WORK/current.txt" <<'PY'
import json, sys
raw = json.load(open(sys.argv[1]))
if "result" not in raw:
    print("gate: RESULT=tool-error —", json.dumps(raw)[:400]); sys.exit(2)
payload = json.loads(raw["result"]["content"][0]["text"])
if not payload.get("success"):
    print("gate: RESULT=tool-refused —", json.dumps(payload.get("error"))[:400]); sys.exit(2)
d = payload["data"]
findings = d.get("findings") or []
if d.get("truncated"):
    print("gate: RESULT=truncated-sweep — the findings list was capped, so absent"
          " symbols cannot be told from dropped ones.")
    sys.exit(2)
ours = [f for f in findings if f.get("kind") == "called_only_by_tests"]
kinds = sorted({f.get("kind") for f in findings})
print("gate: family sweep returned %d finding(s) across %d kind(s); %d of kind"
      " called_only_by_tests" % (len(findings), len(kinds), len(ours)))
# The detector must be REACHED by the family sweep. Nothing in the request names
# it; if the standard sweep no longer carries it, the gate must not read that as
# "no hollow members".
if "called_only_by_tests" not in kinds:
    print("gate: RESULT=detector-not-in-sweep — find_quality_issue(family='quality')"
          " returned no called_only_by_tests findings AT ALL. Either the detector was"
          " dropped from the standard sweep, or it refused. Kinds seen:", kinds)
    sys.exit(2)
open(sys.argv[2], "w").write("\n".join(sorted(f["symbol"] for f in ours)) + "\n")
PY
rc=$?
[ $rc -ne 0 ] && exit $rc

# THE TIME BUDGET. A whole-workspace AST pass that quietly grows into minutes
# stops being run, and a gate nobody runs is not a gate. Measured on this
# 660-file workspace: 6.8s / 8.7s / 10.3s idle, 11.6s / 13.2s with the full test
# suite running in parallel on the same machine. Budget 30s — ~2.3x the worst
# observed, so machine load never trips it and a regression in the pass itself
# does. This call names the kind DELIBERATELY: it measures the
# detector, while the findings above come from the unprompted family sweep.
call find_quality_issue '{"kind":"called_only_by_tests"}' > "$WORK/timed.json"
python3 - "$WORK/timed.json" "${UNWIRED_BUDGET_MS:-30000}" <<'PY'
import json, sys
payload = json.loads(json.load(open(sys.argv[1]))["result"]["content"][0]["text"])
d = payload["data"]
ms, budget = d.get("elapsedMs"), int(sys.argv[2])
print("gate: scan listed=%s examined=%s tracked=%s elapsedMs=%s (budget %sms)"
      % (d.get("filesListed"), d.get("filesExamined"), d.get("publicMainMembersTracked"), ms, budget))
if d.get("filesExamined", 0) == 0:
    print("gate: RESULT=examined-nothing — 'no findings' would be a claim about code never opened.")
    sys.exit(2)
if d.get("scanIncomplete"):
    print("gate: RESULT=partial-scan — some files were unreadable; a baseline diff over a"
          " partial scan silently reads missing findings as fixed.")
    sys.exit(2)
if ms is None:
    print("gate: RESULT=no-timing — the scan stopped reporting its own cost.")
    sys.exit(2)
if ms > budget:
    print("gate: RESULT=over-budget — %sms > %sms." % (ms, budget))
    sys.exit(1)
PY
rc=$?
[ $rc -ne 0 ] && exit $rc

# THE ONE-CLASSIFIER INVARIANT. Both consumers of main-vs-test must go through
# SourceRootClassifier; a second place to know test-ness is how mcp#9 was born.
call get_call_hierarchy \
  '{"direction":"incoming","symbol":"org.jawata.core.project.SourceRootClassifier#classify"}' \
  > "$WORK/hierarchy.json"
python3 - "$WORK/hierarchy.json" <<'PY'
import json, sys
raw = json.load(open(sys.argv[1]))
payload = json.loads(raw["result"]["content"][0]["text"])
callers = payload["data"]["callers"]
prod = sorted({c["callerClass"] for c in callers if ".tests/" not in c["filePath"]})
expected = ["org.jawata.mcp.tools.CompileWorkspaceTool",
            "org.jawata.mcp.tools.smell.AbstractAstDetector",
            "org.jawata.mcp.tools.smell.TestOnlyCallerDetector"]
print("gate: SourceRootClassifier production callers:", prod)
if prod != expected:
    print("gate: RESULT=classifier-invariant-broken — expected exactly", expected)
    print("gate: a consumer that stopped calling it grew its own test-ness heuristic;")
    print("gate: a new one must be added here deliberately, not discovered later.")
    sys.exit(1)
PY
rc=$?
[ $rc -ne 0 ] && exit $rc

if [ "$UPDATE" = "1" ]; then
    cp "$WORK/current.txt" "$BASELINE"
    echo "gate: baseline UPDATED — $(wc -l < "$BASELINE") finding(s). Commit it with the change that justifies it."
    exit 0
fi

[ -f "$BASELINE" ] || { echo "gate: RESULT=no-baseline at $BASELINE"; exit 2; }
# LC_ALL=C on BOTH the sort and the comm. The symbols are sorted by codepoint
# when written; a comm running under a locale whose collation disagrees warns
# "file is not in sorted order" and its output is then unreliable — a diff gate
# that can silently mis-pair lines is worse than no gate.
LC_ALL=C sort "$BASELINE" > "$WORK/baseline.sorted"
LC_ALL=C sort "$WORK/current.txt" > "$WORK/current.sorted"
NEW=$(LC_ALL=C comm -13 "$WORK/baseline.sorted" "$WORK/current.sorted")
FIXED=$(LC_ALL=C comm -23 "$WORK/baseline.sorted" "$WORK/current.sorted")

if [ -n "$FIXED" ]; then
    echo "gate: $(printf '%s\n' "$FIXED" | wc -l) baseline finding(s) FIXED — re-baseline to keep the ratchet tight:"
    printf '  - %s\n' $FIXED
fi

if [ -n "$NEW" ]; then
    echo "gate: FAIL — $(printf '%s\n' "$NEW" | wc -l) NEW hollow member(s); every caller is test code:"
    printf '  + %s\n' $NEW
    echo "gate: wire it from production, delete it, or run --update-baseline with the reason."
    exit 1
fi

echo "gate: PASS — no new test-only-called members ($(wc -l < "$BASELINE") in baseline, unchanged)."
exit 0
