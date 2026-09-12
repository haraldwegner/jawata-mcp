#!/usr/bin/env bash
# Sprint 28f Stage 9 step 3 — D1–D9 THROUGH THE FRONT DOOR, AGAINST THE BUILT ARTIFACT.
#
# WHY. C9's rule: "every deliverable carries an assertion or the word unproven". A green
# unit suite says the code does what its author expected; it says nothing about what the
# SHIPPED product answers a client. These assertions go over JSON-RPC to a real resident
# running the dist, on a scratch port, store and workspace.
#
# UNPROVEN IS A FIRST-CLASS OUTCOME and is the whole reason this file is trustworthy. A
# deliverable this probe cannot put in a position to answer is reported UNPROVEN with the
# reason, never quietly dropped and never rounded up to a pass. Exit 2 means the probe
# could not run at all, which is also never a pass.
#
# ONE assertion per deliverable, deliberately. This is a wiredness check, not a second
# test suite: the suite owns depth, this owns "the shipped thing answers".
#
# Usage:  build/28f-deliverables.sh [dist-dir]
# Exit:   0 = no FAIL (unproven allowed) · 1 = a deliverable FAILED · 2 = could not run

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_DELIVERABLES_PORT:-8917}"
TOKEN="deliverables-$$"

WS="$(mktemp -d)"
STORE="$(mktemp -d)"
EXPORTS="$(mktemp -d)"
LOG="$WS/resident.log"
RESIDENT_PID=""

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE" "$EXPORTS"
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

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_ROOT="$ROOT" \
PROBE_EXPORTS="$EXPORTS" python3 - << 'PY'
import json, os, urllib.request

PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]
ROOT = os.environ["PROBE_ROOT"]

passed = failed = unproven = 0
def ok(d, msg):
    global passed; passed += 1; print("  %-4s PASS      %s" % (d, msg))
def bad(d, msg):
    global failed; failed += 1; print("  %-4s FAIL      %s" % (d, msg))
def unknown(d, msg):
    global unproven; unproven += 1; print("  %-4s UNPROVEN  %s" % (d, msg))

def call(tool, args):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN,
                 "Mcp-Session-Id": "deliverables",
                 "Content-Type": "application/json"})
    raw = urllib.request.urlopen(r, timeout=900).read().decode()
    return json.loads(json.loads(raw)["result"]["content"][0]["text"])

def exp(**args):
    return call("experience", args)

MARKER = "Oxidise the flange assembly before the gantry inspection begins."

# PROOF OF LIFE — without it every absence below is satisfied by a dead store.
seed = exp(kind="record", type="domain_fact", symbol="com.example.Gantry#oxidise",
           summary=MARKER)
if not seed.get("success"):
    raise SystemExit("the store would not accept a row, so nothing below measures "
                     "anything: %s" % json.dumps(seed)[:300])
print("  ---- proof of life: the store accepts a row ----")

# --- D1: a committed write survives every maintenance verb ------------------------------
# One verb, driven live. The suite owns "every verb"; this owns "the shipped product does
# not lose a row when a maintenance verb runs".
exp(kind="refresh")
after = exp(kind="recall", symbol="com.example.Gantry#oxidise", format="text")
if MARKER[:30] in str(after.get("data", "")):
    ok("D1", "a recorded story survives a maintenance verb and is still recalled")
else:
    bad("D1", "the row did not survive `refresh`: %s" % json.dumps(after)[:200])

# --- D2: a destructive verb leaves a copy and returns its path --------------------------
# `prune` is destructive and safe here: this store is a scratch directory this probe made.
pruned = exp(kind="prune", days=0)
d = pruned.get("data") or {}
path = next((str(d[k]) for k in ("backup", "backupPath", "restorePath", "copy")
             if k in d), "")
if path:
    ok("D2", "a destructive verb returns the copy it left: %s" % path[:70])
elif pruned.get("success"):
    bad("D2", "the verb ran and returned no restore path — the copy is the whole "
              "deliverable: %s" % json.dumps(d)[:200])
else:
    unknown("D2", "prune was refused here, so the copy-path clause is not measured: %s"
            % json.dumps(pruned)[:160])

# --- D3: the story folder ---------------------------------------------------------------
stats = exp(kind="stats")
root = ((stats.get("data") or {}).get("substrate") or {}).get("root")
if root:
    ok("D3", "the store names its substrate root, so an accepted story has a folder to "
             "land in: %s" % str(root)[:70])
else:
    unknown("D3", "this scratch store declares no substrate root, so the export-folder "
                  "clause cannot be put in a position to answer here — it needs a "
                  "configured folder, which is the developer's own and is not touched.")

# --- D4: searchable BY MEANING the moment it is written ---------------------------------
# The cue shares no keyword with the row: no word of it appears in MARKER.
# `nominate` is the product's meaning-by-prose path; `recall` answers a CUE. D4's clause
# is about meaning, so it is asked of the verb that does meaning — an earlier version
# asked `recall(symptom=…)`, which is a cue match and was the wrong question.
meaning = exp(kind="nominate", format="text",
              question="rust on the crane rail before it is checked")
if "flange" in str(meaning.get("data", "")):
    ok("D4", "a row written moments ago is reachable BY MEANING from prose sharing none "
             "of its words")
else:
    # WHICH OF THE TWO IT IS, decided from the product's own `embedding` block rather
    # than shrugged at. An earlier version guessed two key names, found neither, and
    # reported "unreported" — which reads as the product being silent when it was the
    # probe looking in the wrong place. With no vectors, a meaning miss is an honest
    # absence; WITH vectors it is a defect, and saying so is the whole point of D4.
    emb = (stats.get("data") or {}).get("embedding") or {}
    # The count is NESTED per table. A flat read returned 0 and reported "no vectors"
    # over a store that had just embedded the row on write — the probe's own guess
    # producing the absence it then reported.
    vectors = sum(v.get("embedded", 0) for v in emb.values() if isinstance(v, dict))
    if vectors:
        bad("D4", "the store holds %s vector(s) and a meaning cue still missed a row it "
                  "should reach — this is the deliverable failing, not an absence: %s"
            % (vectors, json.dumps(emb)[:160]))
    else:
        unknown("D4", "this scratch resident carries NO vectors (%s), so meaning search "
                      "has nothing to search — an honest absence, and the clause needs a "
                      "store with embeddings to answer at all" % json.dumps(emb)[:160])

# --- D5: imported markdown is not stamped `java` ----------------------------------------
by_lang = (stats.get("data") or {}).get("by_language")
if by_lang is None:
    unknown("D5", "`stats` reports no by_language breakdown on this build, so the "
                  "not-stamped-java clause has nothing to read here.")
elif isinstance(by_lang, dict) and set(by_lang) - {"java"}:
    ok("D5", "rows are counted outside `java`: %s" % json.dumps(by_lang)[:90])
else:
    unknown("D5", "this scratch store holds only rows this probe recorded, so a "
                  "non-java population cannot be distinguished from an absent one: %s"
            % json.dumps(by_lang)[:90])

# --- D6: four lanes, and the sweep reports PER lane rather than an aggregate ------------
sweep = exp(kind="review_sweep")
sd = sweep.get("data") or {}
# D6's measure, verbatim: "reports per lane and per trigger, and the aggregate number is
# gone". The first version of this looked for the FLAT `deletionList` and called the
# per-lane shape a failure — asserting the shape this sprint REPLACED, which made the
# product's own deliverable read as the defect.
by_lane = "deletionListByLane" in sd
by_trigger = "writingBacklogByTrigger" in sd
aggregate = any(k in sd for k in ("shown", "chosen", "shownTotal", "chosenTotal"))
if by_lane and by_trigger and not aggregate:
    ok("D6", "the sweep reports per LANE and per TRIGGER, and publishes no aggregate")
elif aggregate:
    bad("D6", "an aggregate shown/chosen is still published: %s" % json.dumps(sd)[:200])
else:
    bad("D6", "per-lane=%s per-trigger=%s — keys: %s"
        % (by_lane, by_trigger, sorted(sd)))

# --- D7: the code lane answers WHERE a job lives, resolved at that moment ----------------
loaded = call("load_project", {"projectPath": ROOT})
if not loaded.get("success"):
    unknown("D7", "the project would not load, so a live file+line cannot be resolved.")
else:
    exp(kind="record", type="job",
        symbol="org.jawata.mcp.tools.shared.DraftSource#methodsIn",
        summary="Read the methods a draft declares, whether a whole file or a fragment.")
    job = exp(kind="recall", symbol="org.jawata.mcp.tools.shared.DraftSource#methodsIn")
    entries = ((job.get("data") or {}).get("entries") or [])
    ptr = entries[0].get("resolved_pointer") if entries else None
    if ptr and ptr.get("resolved") and ptr.get("file"):
        ok("D7", "a job answers with a pointer resolved AT THIS MOMENT: %s"
           % str(ptr.get("file")).rsplit("/", 1)[-1])
    else:
        bad("D7", "the job came back without a live location: %s" % json.dumps(entries)[:200])

# --- D8: the moments fire unprompted ----------------------------------------------------
# Owned by its own probe, which drives the HOOK BINARY — the only thing that can answer
# "unprompted". Claiming it here from an engine call would be the overstatement that
# probe exists to avoid.
unknown("D8", "owned by build/28f-live-moments.sh, which drives the hook binary itself; "
              "this probe reaches only the engine and cannot speak to unpromptedness.")

# --- D9: the two issues carry closing comments ------------------------------------------
unknown("D9", "the closing comments live on GitHub and the manifest clause needs an "
              "extraction run; neither is reachable from a resident, and asserting "
              "either from here would be a claim rather than a measurement.")

print("\n28F-DELIVERABLES passed=%d failed=%d unproven=%d" % (passed, failed, unproven))
raise SystemExit(1 if failed else 0)
PY
