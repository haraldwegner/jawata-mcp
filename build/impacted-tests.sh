#!/usr/bin/env bash
# Stage 6b (G4) — ask a running resident which test classes the working-tree
# diff impacts, and print them one per line, sorted.
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
# THE STALENESS RULE, and it is EXECUTED here rather than merely documented:
# attribution evidence may narrow a run only while it still describes this
# tree. All four must hold, and any one failing prints its reason and leaves
# the run FULL:
#   1. attribution exists at all (attributionAvailable);
#   2. the artifact does not declare itself partial;
#   3. the artifact-s git revision is an ANCESTOR of HEAD — evidence from a
#      rewritten or divergent history describes code this tree does not have;
#   4. every changed source file since that revision (committed or not) is
#      accounted for in the derived symbol set.
# Rule 4 subsumes "the evidence is older than my change": stale evidence and a
# dropped derivation are the same failure from the selector-s side — a file
# whose tests were never considered.
set -uo pipefail

[ -n "${JAWATA_URL:-}" ]   || { echo "JAWATA_URL is not set" >&2; exit 3; }
[ -n "${JAWATA_TOKEN:-}" ] || { echo "JAWATA_TOKEN is not set" >&2; exit 3; }

# JAWATA_SYMBOLS bypasses the diff derivation with an explicit list. The
# derivation is broken for modified files (jawata-mcp#40); the symbols path
# is the half that works, and it is what the inner loop can use today.
SYMBOLS="${JAWATA_SYMBOLS:-}"
DIFF="${JAWATA_DIFF:-worktree}"
RANGE="${JAWATA_RANGE:-}"

# THE COMPLETENESS CROSS-CHECK (Stage 6b). Measured 2026-08-19: the tool's
# diff→symbol derivation yields symbols for ADDED files and NOTHING for
# MODIFIED ones (jawata-mcp#40). A diff that modifies production code and adds
# a test therefore comes back naming only the test's own symbols — a non-empty,
# plausible, WRONG narrow answer, which is precisely the false green the safety
# rule forbids. So the script checks the tool's work against the git diff it
# was given: every changed .java file must be accounted for in the derived
# symbol set, or the evidence is incomplete and the run stays full.
case "$DIFF" in
    worktree) CHANGED="$(git diff --name-only -- '*.java')" ;;
    staged)   CHANGED="$(git diff --cached --name-only -- '*.java')" ;;
    range)    CHANGED="$(git diff --name-only "$RANGE" -- '*.java')" ;;
    *)        echo "unknown JAWATA_DIFF '$DIFF'" >&2; exit 3 ;;
esac

[ -n "$SYMBOLS" ] && CHANGED=""   # explicit symbols answer for themselves

python3 - "$JAWATA_URL" "$JAWATA_TOKEN" "$DIFF" "$RANGE" "$CHANGED" "$SYMBOLS" <<'PY'
import json, sys, urllib.request

url, token, diff, rng = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
changed_files = [f for f in (sys.argv[5] if len(sys.argv) > 5 else "").split() if f]
symbols = [x for x in (sys.argv[6] if len(sys.argv) > 6 else "").split(",") if x.strip()]
args = {"action": "coverage_impacted_tests"}
if symbols:
    args["symbols"] = [x.strip() for x in symbols]
else:
    args["diff"] = diff
if not symbols and diff == "range":
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
if not data.get("attributionAvailable"):
    print("no attribution evidence exists — nothing has been run with attribution=true",
          file=sys.stderr)
    sys.exit(6)

# PARTIAL EVIDENCE MUST NOT NARROW. An artifact produced by the forked runner
# cannot contain the tests that need the Eclipse workspace (they cannot start
# there), so a class covered by BOTH a plain test and a workspace test would
# come back naming only the plain one — under-selection that looks like a
# clean answer. Only evidence that declares itself complete may narrow a run.
if data.get("partial") or data.get("evidenceComplete") is False:
    print("the attribution evidence is PARTIAL — it cannot see the whole suite",
          file=sys.stderr)
    sys.exit(7)

# STALENESS RULES 3 + 4. The artifact-s own revision decides which changes the
# evidence has never seen; those files must be accounted for too.
import subprocess
try:
    rep_body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                           "params": {"name": "run_tests",
                                      "arguments": {"action": "coverage_report"}}}).encode()
    rep_req = urllib.request.Request(url, data=rep_body, headers={
        "Authorization": "Bearer " + token,
        "Mcp-Session-Id": "impacted-tests",
        "Content-Type": "application/json"})
    with urllib.request.urlopen(rep_req, timeout=120) as r:
        rep = json.loads(json.load(r)["result"]["content"][0]["text"])
    prov = rep.get("data", {}).get("provenance", {}) or {}
except Exception:                                          # noqa: BLE001
    prov = {}

rev = prov.get("gitRevision")
if rev:
    anc = subprocess.run(["git", "merge-base", "--is-ancestor", rev, "HEAD"],
                         capture_output=True)
    if anc.returncode != 0:
        print("the attribution evidence was recorded at %s, which is not an ancestor of "
              "HEAD — it describes a different tree" % rev[:12], file=sys.stderr)
        sys.exit(9)
    since = subprocess.run(["git", "diff", "--name-only", rev, "--", "*" + ".java"],
                           capture_output=True, text=True)
    changed_files = sorted(set(changed_files)
                           | {f for f in since.stdout.split() if f})

derived = {s.split("#", 1)[0].rsplit(".", 1)[-1] for s in (data.get("symbols") or [])}
unaccounted = [f for f in changed_files
               if f.rsplit("/", 1)[-1][:-len(".java")] not in derived]
if unaccounted:
    print("the derivation dropped %d of %d changed file(s) (e.g. %s) — see jawata-mcp#40;"
          " narrowing on this would skip the tests that cover them"
          % (len(unaccounted), len(changed_files),
             unaccounted[0].rsplit("/", 1)[-1]), file=sys.stderr)
    sys.exit(8)

rows = data.get("impactedTests") or []
classes = set()
for row in rows:
    name = row.get("test") if isinstance(row, dict) else str(row)
    if name:
        classes.add(name.split("#", 1)[0])
for name in sorted(classes):
    print(name)
PY
