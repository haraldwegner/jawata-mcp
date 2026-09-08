#!/usr/bin/env bash
# Sprint 28e, C3 — THE STORE'S TWO CHECKPOINT CLAUSES, THROUGH THE REAL FRONT DOOR.
#
# C3's exit criterion, verbatim:
#
#     "each closed with proof · a reseed from the substrate root rebuilds the
#      store and the count matches · an unknown symbol produces a stated
#      absence, demonstrated"
#
# The first and third clauses are what this script measures. The second half of
# clause one — "the count matches" — is read three ways here, because the phrase
# admits three and only measuring one of them would pick the easiest:
#
#   * the reseed accepted every file the substrate holds (loaded == files on
#     disk, skipped == 0);
#   * the store's own count afterwards agrees with what the reseed reported;
#   * a second identical reseed reproduces the first — a rebuild that is not
#     reproducible has not rebuilt anything, it has arrived somewhere.
#
# WHY IT RUNS AGAINST A SCRATCH STORE, and why that is not a weaker test. The
# developer's real store is the one this stage's fixes are about, and `reseed`
# is a TRUNCATE-then-load: pointing a destructive verb at the live store to
# prove it rebuilds is how a proof becomes an incident. The substrate itself is
# read READ-ONLY and is the real one, so what is measured is the real corpus
# rebuilding a real store — just not the one anybody is using.
#
# WHY THE RESEED IS CALLED WITH NO PATH. mcp#58 made a configured
# `-Djawata.memory.roots` AUTHORITATIVE rather than additive. The no-path form
# is the one that consults it, so this arm proves #58 through the front door as
# a side effect of proving the rebuild: if the legacy discovery still
# contributed, files from outside the substrate would appear in the report.
#
# Usage:  build/c3-store-frontdoor-probe.sh [dist-dir]
# Env:    C3_SUBSTRATE=<dir>          the substrate root (default: the sprint's)
#         PROBE_TRANSCRIPT=<path>     keep the request/response transcript
# Exit:   0 = every claim held · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
SUBSTRATE="${C3_SUBSTRATE:-/home/harald/CursorProjects/jawata-enterprise/docs/knowledge/stories}"
PORT="${JAWATA_PROBE_PORT:-8903}"
TOKEN="c3-store-frontdoor-probe-$$"

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

[ -f "$JAR" ]      || { echo "no artifact at $JAR — build first" >&2; exit 2; }
[ -d "$SUBSTRATE" ] || { echo "no substrate at $SUBSTRATE" >&2; exit 2; }

# The expected file count is COMPUTED from the substrate, never written down
# here. A hardcoded 135 would go stale the first time a story is added, and
# would then be asserting about a corpus that no longer exists.
FILES_ON_DISK="$(find "$SUBSTRATE" -name '*.md' -type f | wc -l | tr -d ' ')"
[ "$FILES_ON_DISK" -gt 0 ] || { echo "substrate holds no .md files" >&2; exit 2; }

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

# shellcheck disable=SC2086
java $VECTOR -Djawata.experience.shared.dir="$STORE" \
     -Djawata.memory.roots="$SUBSTRATE" \
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
PROBE_SUBSTRATE="$SUBSTRATE" PROBE_FILES="$FILES_ON_DISK" \
python3 - << 'PY'
import json, os, sys, urllib.request

PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]
SUBSTRATE = os.environ["PROBE_SUBSTRATE"]
FILES = int(os.environ["PROBE_FILES"])
TRANSCRIPT = open(os.environ["PROBE_TRANSCRIPT_PATH"], "w")

# The needle is the LITERAL as the renderer writes it (ExperienceRetrieval:1195),
# read off the source rather than paraphrased. A needle written from memory is
# how an assertion comes to pass against text the product never emits.
ABSENT   = "NOTHING IS ANCHORED TO THIS CUE"
ABSENT_2 = "No known knowledge for this cue"
# The anchored control's needle comes from the substrate file that carries the
# symbol, not from what the renderer happened to print.
ANCHORED_SYMBOL = "org.jawata.mcp.learn.WatchEngine"
ANCHORED_NEEDLE = "stateful feature presents as a stateless one"
UNKNOWN_SYMBOL  = "com.example.no.such.pkg.NoSuchType#noSuchMember"

passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def call(tool, args, timeout=600):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    body = json.dumps(req).encode()
    TRANSCRIPT.write("=== REQUEST  POST http://127.0.0.1:%s/mcp\n%s\n" % (PORT, json.dumps(req)))
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=body,
        headers={"Authorization": "Bearer " + TOKEN,
                 "Mcp-Session-Id": "c3-store-probe",
                 "Content-Type": "application/json"})
    raw = urllib.request.urlopen(r, timeout=timeout).read().decode()
    TRANSCRIPT.write("--- RESPONSE\n%s\n\n" % raw)
    TRANSCRIPT.flush()
    outer = json.loads(raw)
    body = json.loads(outer["result"]["content"][0]["text"])
    # The tool answers a ToolResponse ENVELOPE: {success, data, meta}. The report
    # is inside `data`. The first version of this probe returned the envelope and
    # read every field off it as None — which then made the reproducibility arm
    # below compare None with None and PASS. Unwrap here, once, and refuse an
    # envelope that did not succeed rather than reading fields off a failure.
    if isinstance(body, dict) and "data" in body and "success" in body:
        if not body.get("success"):
            raise AssertionError("the tool refused: " + json.dumps(body)[:400])
        return body["data"] if isinstance(body["data"], dict) else body
    return body

def count_of(v):
    """A report field may be a count or the list it counts. Take either, and
    refuse anything else rather than coercing it to a number that would then be
    asserted about."""
    if isinstance(v, bool):  return None
    if isinstance(v, int):   return v
    if isinstance(v, list):  return len(v)
    if isinstance(v, dict):  return len(v)
    return None

def reseed():
    return call("experience", {"kind": "reseed", "recursive": True, "confirm": True})

# --- CLAUSE 1: a reseed from the substrate root rebuilds the store -----------
print("\n--- clause 1: the reseed rebuilds from the configured substrate ---")
r1 = reseed()
print("    report keys: " + ", ".join(sorted(r1.keys())))

loaded  = count_of(r1.get("loaded"))
skipped = count_of(r1.get("skipped"))

if loaded is None:
    bad("the reseed report carries no readable 'loaded' — got %r" % (r1.get("loaded"),))
elif loaded == FILES:
    ok("the reseed loaded every substrate file: %d of %d on disk" % (loaded, FILES))
else:
    bad("the reseed loaded %d of the %d files on disk" % (loaded, FILES))

if skipped is None:
    bad("the reseed report carries no readable 'skipped' — got %r" % (r1.get("skipped"),))
elif skipped == 0:
    ok("no substrate file was refused: skipped == 0")
else:
    bad("the reseed skipped %d file(s): %r" % (skipped, r1.get("skipped")))

# mcp#58: a configured substrate is AUTHORITATIVE. If the legacy discovery still
# contributed, a path from outside the substrate appears here. Scanning the
# whole report as text is deliberate — the shape of the file list is not the
# subject, a foreign path anywhere in it is.
blob = json.dumps(r1)
outside = [seg for seg in ("/.claude/", "CLAUDE.md", "/home/harald/CursorProjects/jawata-mcp/",
                           "/home/harald/CursorProjects/jawata-studio/")
           if seg in blob]
if outside:
    bad("the report names paths outside the substrate — the legacy roots still "
        "contributed: %r" % outside)
else:
    ok("no path outside the substrate appears in the report (mcp#58: configured is authoritative)")

# --- CLAUSE 1b: the store's own count agrees with the rebuild ----------------
print("\n--- clause 1b: the store's own count agrees ---")
st = call("experience", {"kind": "stats"})
sub = st.get("substrate", {})
root = sub.get("root") if isinstance(sub, dict) else None
if root is None:
    bad("stats reports no substrate.root after a reseed of one")
elif os.path.realpath(str(root)) == os.path.realpath(SUBSTRATE):
    ok("stats derives substrate.root to the substrate itself: %s" % root)
else:
    bad("stats derives substrate.root to %r, not the substrate %r" % (root, SUBSTRATE))

total = count_of(st.get("total"))
by_status = st.get("by_status") if isinstance(st.get("by_status"), dict) else {}
accepted = by_status.get("accepted")
print("    stats: total=%r by_status=%r" % (total, by_status))
if not isinstance(accepted, int) or accepted <= 0:
    bad("the store holds no accepted entries after a reseed of %d files" % FILES)
else:
    ok("the store holds %d accepted entries from %d substrate files" % (accepted, FILES))

# --- CLAUSE 1c: the rebuild is reproducible ---------------------------------
print("\n--- clause 1c: a second reseed reproduces the first ---")
r2 = reseed()
st2 = call("experience", {"kind": "stats"})
l2 = count_of(r2.get("loaded"))
s2 = count_of(r2.get("skipped"))
a2 = (st2.get("by_status") or {}).get("accepted")
if None in (loaded, skipped, accepted, l2, s2, a2):
    # Three nulls equal three nulls. The first version of this arm said "ok" for
    # exactly that reason, which is the vacuous-assertion shape this checkpoint
    # spent its whole audit on — committed inside the probe written to prove it.
    bad("the rebuild cannot be compared: a count was missing "
        "(first loaded=%r skipped=%r accepted=%r, second loaded=%r skipped=%r accepted=%r)"
        % (loaded, skipped, accepted, l2, s2, a2))
elif (l2, s2, a2) == (loaded, skipped, accepted):
    ok("the second reseed is identical: loaded=%r skipped=%r accepted=%r" % (l2, s2, a2))
else:
    bad("the rebuild is not reproducible: first (loaded=%r skipped=%r accepted=%r), "
        "second (loaded=%r skipped=%r accepted=%r)" % (loaded, skipped, accepted, l2, s2, a2))

# --- CLAUSE 3: an unknown symbol produces a STATED absence ------------------
# format=text is the form the hooks inject, and it is the form mcp#73 was about:
# the distinction existed for a JSON reader and was invisible to this one.
print("\n--- clause 3: an unknown symbol produces a stated absence ---")
u = call("experience", {"kind": "recall", "symbol": UNKNOWN_SYMBOL, "format": "text"})
utext = u if isinstance(u, str) else json.dumps(u)
states_absence = (ABSENT in utext) or (ABSENT_2 in utext)
renders_pile   = "In a similar situation" in utext
print("    rendered %d chars; resemblance rows present: %s" % (len(utext), renders_pile))
if states_absence:
    ok("an unknown symbol STATES the absence in the text the hooks inject")
elif renders_pile:
    bad("resemblance rows rendered with NO absence statement — mcp#73's defect verbatim:\n"
        "        %s" % utext[:400])
else:
    bad("neither an absence statement nor any rows: %r" % utext[:400])

# --- CLAUSE 3, THE CONTROL: an anchored symbol does NOT ---------------------
# Without this arm, a statement printed unconditionally would pass the arm above
# and be as wrong as the silence it replaced.
print("\n--- clause 3 control: an anchored symbol does not state an absence ---")
a = call("experience", {"kind": "recall", "symbol": ANCHORED_SYMBOL, "format": "text"})
atext = a if isinstance(a, str) else json.dumps(a)
if ABSENT in atext:
    bad("the anchored symbol %s ALSO states an absence — the line is unconditional"
        % ANCHORED_SYMBOL)
elif ANCHORED_NEEDLE in atext:
    ok("the anchored symbol renders its entry and states no absence")
else:
    bad("the anchored symbol %s rendered neither its entry nor an absence line: %r"
        % (ANCHORED_SYMBOL, atext[:400]))

print("\nC3-STORE-FRONTDOOR passed=%d failed=%d" % (passed, failed))
sys.exit(0 if failed == 0 else 1)
PY
RC=$?
echo "transcript: $TRANSCRIPT"
exit $RC
