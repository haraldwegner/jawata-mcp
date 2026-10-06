#!/usr/bin/env bash
# Ask a running resident which test classes the working-tree diff impacts, and
# print them one per line, sorted.
#
# Prints NOTHING and exits non-zero with a one-line reason on stderr whenever
# the answer cannot be trusted. run-suite.sh treats every such exit as "run the
# full suite, and say why" — the mechanism may narrow a run only when the
# evidence carries the claim.
#
# Env:  JAWATA_URL    e.g. http://127.0.0.1:8899/mcp
#       JAWATA_TOKEN  the resident's bearer token
#       JAWATA_DIFF   worktree (default) | staged | range
#       JAWATA_RANGE  required when JAWATA_DIFF=range
#       JAWATA_SYMBOLS  comma-separated FQNs; bypasses the diff derivation
#
# THIS IS A THIN CALLER (Sprint 28g S12a / D21). THE STALENESS RULES MOVED.
# They used to live here as ~80 lines of Python that re-derived the changed set
# from git, re-read the coverage report over a second round-trip, and judged
# the tool's answer from outside. They are now the tool's own verdict,
# `selectionVerdict`, which this reads in one field. Three reasons, measured
# rather than argued:
#
#   1. The RUN PATH narrows too, and a judgement living in a shell script
#      cannot reach it. Two implementations of "may this narrow?" disagree
#      eventually, and the disagreement is SILENT on the side that runs fewer
#      tests.
#   2. One of the four rules was DEAD here. The partial-evidence check read
#      `data.partial` and `data.evidenceComplete`; this response has never
#      carried either field, so `.get()` returned None and the rule passed
#      every time since it was written. The tool reads the artifact's own
#      manifest and cannot miss it.
#   3. The independence this bought was over the DERIVATION, not over the
#      evidence — and the tool now states what it could not speak for
#      (filesWithoutEvidence, staleClasses) rather than leaving a caller to
#      infer it. A cross-check of a fact its subject now declares is a second
#      reader of one answer, not a second opinion.
set -uo pipefail

[ -n "${JAWATA_URL:-}" ]   || { echo "JAWATA_URL is not set" >&2; exit 3; }
[ -n "${JAWATA_TOKEN:-}" ] || { echo "JAWATA_TOKEN is not set" >&2; exit 3; }

SYMBOLS="${JAWATA_SYMBOLS:-}"
DIFF="${JAWATA_DIFF:-worktree}"
RANGE="${JAWATA_RANGE:-}"

case "$DIFF" in
    worktree|staged|range) ;;
    *) echo "unknown JAWATA_DIFF '$DIFF'" >&2; exit 3 ;;
esac

python3 - "$JAWATA_URL" "$JAWATA_TOKEN" "$DIFF" "$RANGE" "$SYMBOLS" <<'PY'
import json, sys, urllib.request

url, token, diff, rng = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
symbols = [x.strip() for x in (sys.argv[5] if len(sys.argv) > 5 else "").split(",") if x.strip()]
args = {"action": "coverage_impacted_tests"}
if symbols:
    args["symbols"] = symbols
else:
    args["diff"] = diff
    if diff == "range":
        if not rng:
            print("JAWATA_DIFF=range needs JAWATA_RANGE", file=sys.stderr)
            sys.exit(3)
        args["range"] = rng

body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                   "params": {"name": "run_tests", "arguments": args}}).encode()
req = urllib.request.Request(url, data=body, headers={
    "Authorization": "Bearer " + token,
    "Mcp-Session-Id": "impacted-tests",
    "Content-Type": "application/json"})
try:
    with urllib.request.urlopen(req, timeout=120) as r:
        payload = json.loads(json.load(r)["result"]["content"][0]["text"])
except Exception as e:                                    # noqa: BLE001
    print("the resident did not answer: %s" % e, file=sys.stderr)
    sys.exit(4)

if not payload.get("success", False):
    print("the tool refused: %s" % str(payload.get("error"))[:160], file=sys.stderr)
    sys.exit(5)

data = payload.get("data", {})

# THE VERDICT. Absent means a resident older than this script, and an absent
# verdict is not a permissive one: a build that cannot state the rules has not
# applied them, so the run stays full.
verdict = data.get("selectionVerdict")
if not isinstance(verdict, dict):
    print("this resident states no selectionVerdict — it predates the staleness rules, "
          "so nothing here can say the evidence still describes this tree", file=sys.stderr)
    sys.exit(6)
if not verdict.get("mayNarrow"):
    print(str(verdict.get("reason") or "the evidence may not narrow this run"), file=sys.stderr)
    sys.exit(7)

# RULE 2, applied to the scope THIS caller narrows — the whole suite. The tool
# states what the recording run selected; only a run that selected nothing in
# particular saw everything. jawata's own suite splits between plain-JVM tests
# and tests that need the Eclipse workspace, and the forked runner cannot start
# the latter — so an artifact it produced names only the plain ones, and a
# class covered by both would come back claimed by one. The tool cannot apply
# this rule itself: it does not know what scope its caller is about to run.
if not verdict.get("evidenceCoversWholeSuite"):
    print("the evidence was recorded from a run that selected %s, so it cannot speak for "
          "the whole suite" % (verdict.get("evidenceScope") or "a narrower scope"),
          file=sys.stderr)
    sys.exit(7)

classes = set()
for row in (data.get("impactedTests") or []):
    name = row.get("test") if isinstance(row, dict) else str(row)
    if name:
        classes.add(name.split("#", 1)[0])
if not classes:
    print("the tool named no impacted test classes", file=sys.stderr)
    sys.exit(8)
for name in sorted(classes):
    print(name)
PY
