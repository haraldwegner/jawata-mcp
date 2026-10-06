#!/usr/bin/env bash
# Sprint 28g, C13 — M9, THROUGH THE REAL FRONT DOOR, AGAINST THE BUILT ARTIFACT.
#
# M9, verbatim: "Duplicate-work detector, re-run on this tree: the first group no
#   longer collects the 19 unrelated methods it did, the finding count is reported
#   against the 581 baseline, and the parse-helper case it was built for is still
#   found — the last clause is what a detector reporting zero groups fails"
#
# WHY IT LOADS THE REAL PROJECT. The measure says "re-run on THIS TREE". A fixture
# answers a different question, and the 585 this is compared against was itself
# taken over this tree through the released resident.
#
# M10 IS NOT HERE, and the reason is not that it was skipped. Its measure is a
# post-reload query over the developer's own store, and that store's directory is
# held by the workspace guard — the same wall this plan records for D7a's root
# cause. It needs his approval or a resident reload, and both are his.
#
# Exit: 0 = every claim held · 1 = a claim failed · 2 = could not run at all.
#       "Could not run" is NEVER reported as a pass.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8907}"
TOKEN="c13-m9-probe-$$"

WS="$(mktemp -d)"
STORE="$(mktemp -d)"
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

# A SCRATCH store: this probe is about the detector and must never touch the real one.
# shellcheck disable=SC2086
java $VECTOR -Xmx3072m -Djawata.experience.shared.dir="$STORE" \
     -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
RESIDENT_PID=$!
READY=0
for _ in $(seq 1 180); do
    grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && { READY=1; break; }
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2
                                             tail -25 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "resident never announced readiness:" >&2
                        tail -25 "$LOG" >&2; exit 2; }

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_ROOT="$ROOT" python3 - << 'PY'
import json, os, urllib.request

PORT = os.environ["PROBE_PORT"]; TOKEN = os.environ["PROBE_TOKEN"]
ROOT = os.environ["PROBE_ROOT"]
passed = failed = 0
def ok(m):
    global passed; passed += 1; print("  ok    " + m)
def bad(m):
    global failed; failed += 1; print("  FAIL  " + m)

def call(tool, args, timeout=2400):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT,
        data=json.dumps(req).encode(), method="POST",
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        body = json.loads(resp.read().decode())
    txt = body.get("result", {}).get("content", [{}])[0].get("text", "{}")
    try:
        return json.loads(txt)
    except Exception:
        return {"_raw": txt}

print("== M9 — the duplicate-work detector, re-run on this tree ==")
lp = call("load_project", {"projectPath": ROOT})
loaded = (lp.get("data") or {})
print("  .. load: success=%s" % lp.get("success"))
if lp.get("success") is False:
    bad("could not load this tree: %r" % lp.get("error"))
else:
    res = call("find_quality_issue", {"kind": "re_derived_job", "limit": 2000})
    d = res.get("data") or {}
    count, findings = d.get("count"), (d.get("findings") or [])
    examined = d.get("methodsExamined")
    if count is None:
        bad("the detector did not answer a count: %r" % res)
    elif not examined:
        # A detector that examined nothing answers 0 findings, and 0 is also the
        # shape of a perfect result. Without this the pass is unreadable.
        bad("methodsExamined=%r — a scan that looked at nothing is not a clean tree"
            % examined)
    else:
        print("  .. count=%s methodsExamined=%s filesScanned=%s"
              % (count, examined, d.get("filesScanned")))
        first = [f for f in findings if f.get("group") == 1]
        size = first[0].get("groupSize") if first else 0
        if not findings:
            bad("ZERO groups — M9's own text names this the FAILING case, because the "
                "parse-helper clause is what a silent detector fails")
        elif size >= 19:
            bad("the first group still collects %d methods: %r"
                % (size, [f.get("symbol") for f in first][:6]))
        else:
            ok("the first group is %d method(s), not the 19 it was: %r"
               % (size, [f.get("symbol") for f in first][:6]))
        ok("count REPORTED against the 581 baseline (the measure's own word): %s" % count)
        parse = [f for f in findings if "#parse" in str(f.get("symbol", ""))]
        if parse:
            ok("the parse-helper case is still found: %d of the whole set of %d findings, e.g. %s"
               % (len(parse), len(findings), parse[0].get("symbol")))
        else:
            bad("no parse-helper instance in the whole set of %d findings — the clause M9 says a "
                "silent detector fails. The page was NOT capped here (limit 2000 over "
                "%d findings), so this is an absence from the tree rather than from a page"
                % (len(findings), len(findings)))

print("C13-M9-PROBE passed=%d failed=%d" % (passed, failed))
raise SystemExit(1 if failed else 0)
PY
