#!/usr/bin/env bash
# C16 (Sprint 28g S16) — the hire path, driven through the BUILT ARTIFACT's own front door.
#
# What this proves that a unit test cannot: that `hire` is PUBLISHED and WIRED. The clause is
# C16's first — "the PM seat hires the cataloguer, named hirer, named hired, not a script, and
# that run goes end to end: brief issued, gates run, result journalled" — and until S16 the
# capability had seven callers, every one a test (S16-5). A test calling a constructor proves the
# code works; only the wire proves a client can reach it.
#
# WHAT IT DELIBERATELY DOES NOT DO: spend a real vendor brain. The `binary` input points at a
# stand-in that speaks CLAUDE'S OWN measured event shape, so claude's dialect and claude's reader
# are the ones under test — the seat's frontmatter chose them — while no token is bought and no
# vendor auth is needed. The four real-brain runs M12 counts are a SEPARATE deliverable and this
# script is not them; it says so rather than letting a reader conflate the two.
#
# Reports PASS / FAIL and exits 2 for "could not run at all", which is never a pass.

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_PROBE_PORT:-8907}"
TOKEN="c16-hire-probe-$$"
FIXTURE_PROJECT="$ROOT/org.jawata.core.tests/test-resources/sample-projects/simple-maven"

WS="$(mktemp -d)"       # throwaway workspace AND store: the probe must never
STORE="$(mktemp -d)"    # read or write the developer's real one
LOG="$WS/resident.log"
RESIDENT_PID=""

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
# EXIT alone is not enough: a shell killed by a signal can exit without running it, and the
# resident it started outlives the run. The sibling probe records one leaking for two days.
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }
[ -d "$FIXTURE_PROJECT" ] || { echo "no fixture project at $FIXTURE_PROJECT" >&2; exit 2; }

# THE HIRED SEAT'S FILE — the cataloguer's real frontmatter. The clause is that a seat's own file
# IS the hire, so this is what the product reads to pick the brain, the model and the ceilings.
cat > "$WS/cataloguer.md" <<'SEAT'
---
name: cataloguer
model: claude-sonnet-5
kind: code
effort: medium
tools: [inspect, search_symbols, analyze, experience]
gates: []
ttl_secs: 900
max_iterations: 1
cost_budget_usd: 2.0
max_tool_steps: 80
---
You are the cataloguer seat.
SEAT

# The same seat on the brain that CANNOT be told which tools to attach.
sed 's/^name: cataloguer$/name: cataloguer\nbrain: cursor/' "$WS/cataloguer.md" > "$WS/cursor-seat.md"

# THE STAND-IN BRAINS, SUBSTITUTED ON **PATH** AND NOT THROUGH ANY PRODUCT INPUT.
#
# This is the correction after the first version of this probe spent real money. It passed a
# `binary` argument to `hire`, which was INERT — `HiredBrain.hire` discards the base's argv and the
# dialect owns the command line — so the resident spawned the REAL claude in the developer's own
# repository: 54 tool steps, $1.28, exit 1, and job rows written to the live store. The input is
# gone from the product now, because honouring it would have made `hire` run an arbitrary
# executable named in a tool argument.
#
# So the substitution happens where "which claude" is decided for every program on this machine:
# a directory first on PATH, holding executables of the brains' own names. The dialect is untouched
# and under test; nothing about the product had to be loosened to make this measurable.
BIN="$WS/bin"
mkdir -p "$BIN"
for brain in claude cursor-agent copilot codex; do
    cat > "$BIN/$brain" <<'BRAIN'
#!/bin/sh
# IT WRITES ONE FILE into whatever directory it was run in. That is what makes C16's
# "every changed file attributed to the run" measurable: a brain edits through its own
# tools, so the only witness is the directory, and a stand-in that changed nothing could
# never tell an empty attribution from a working one.
echo 'written by the stand-in brain' > brain-wrote-this.txt
echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"I catalogued the execution package."},{"type":"tool_use"},{"type":"tool_use"}]}}'
echo '{"type":"result","result":"done","total_cost_usd":0.07}'
BRAIN
    chmod +x "$BIN/$brain"
done

# A GIT WORKING TREE for the attribution case. The first hire runs in a plain temp directory,
# where the honest answer is a stated absence; this one is a repository, where the census runs.
REPO="$WS/repo"
mkdir -p "$REPO"
git -C "$REPO" init -q 2>/dev/null \
    || { echo "could not git init the attribution fixture — refusing to report a false absence" >&2
         exit 2; }
# A LOUD GUARD, because the failure this replaces was silent and billable. If a stand-in is not
# what resolves, the run must not proceed to spawn whatever does.
export PATH="$BIN:$PATH"
RESOLVED="$(command -v claude)"
[ "$RESOLVED" = "$BIN/claude" ] || { echo "PATH substitution failed: claude resolves to"       \
                                          "$RESOLVED, not the stand-in — refusing to run, since" \
                                          "the next step would spawn a real brain" >&2; exit 2; }

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

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_WS="$WS" PROBE_PROJECT="$FIXTURE_PROJECT" \
PROBE_REPO="$REPO" \
python3 -I - << 'PY'
import json, os, urllib.request

PORT = os.environ["PROBE_PORT"]
TOKEN = os.environ["PROBE_TOKEN"]
WS = os.environ["PROBE_WS"]
PROJECT = os.environ["PROBE_PROJECT"]
REPO = os.environ["PROBE_REPO"]

passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def rpc(method, params):
    req = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
    body = json.dumps(req).encode()
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % PORT, data=body,
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(r, timeout=900) as resp:
        return json.loads(resp.read().decode())

def call(tool, args):
    """One tools/call, with the tool's own JSON payload parsed out of the text content."""
    answer = rpc("tools/call", {"name": tool, "arguments": args})
    content = answer.get("result", {}).get("content", [])
    for part in content:
        if part.get("type") == "text":
            try:
                return json.loads(part["text"])
            except json.JSONDecodeError:
                return {"_text": part["text"]}
    return {"_raw": answer}

# ---------------------------------------------------------------- PROOF OF LIFE
# Without this, every absence check below could pass having looked at nothing.
listed = rpc("tools/list", {}).get("result", {}).get("tools", [])
names = sorted(t["name"] for t in listed)
if len(names) > 20:
    ok("PROOF OF LIFE: the front door publishes %d tools" % len(names))
else:
    bad("the front door published only %d tools — the checks below mean nothing" % len(names))

# --------------------------------------------------- 1. `hire` IS PUBLISHED
if "hire" in names:
    ok("hire is PUBLISHED on the wire")
else:
    bad("hire is NOT published — it is registered in code and unreachable by a client")

# TWO COUNTS, NAMED SEPARATELY, because the first version of this probe reported one under the
# other's name. It compared the LENGTH of tools/list against the `toolCount` health_check
# publishes — the figure C9 and every release record quote — and announced a jump of two where the
# tree had added one tool. Whatever the two measures differ by, a claim about one of them has to be
# taken from it.
health = call("health_check", {})
reported = (health.get("data") or {}).get("toolCount")
print("  ----  tools/list returns %d names; health_check reports toolCount=%r"
      % (len(names), reported))
if reported == len(names):
    ok("the two published counts agree, so either may be quoted")
else:
    ok("the two published counts DIFFER by %d — quote health_check's, which is what a release"
       " record means by toolCount" % abs(len(names) - (reported or 0)))

hire_schema = next((t for t in listed if t["name"] == "hire"), {}).get("inputSchema", {})
required = set(hire_schema.get("required", []))
want = {"hirer", "seat", "seatFile", "brief", "steps"}
if want <= required:
    ok("its five required inputs are declared: %s" % ", ".join(sorted(want)))
else:
    bad("required inputs missing from the published schema: %s" % ", ".join(sorted(want - required)))

# ------------------------------- 2. A PROJECT, so the gates can genuinely ANSWER
loaded = call("load_project", {"projectPath": PROJECT})
if loaded.get("success"):
    ok("a fixture project is loaded, so a compile gate has something to answer about")
else:
    bad("the fixture project did not load: %s" % json.dumps(loaded)[:200])

# ------------------- 3. THE PM SEAT HIRES THE CATALOGUER, through the front door
hired = call("hire", {
    "hirer": "pm",
    "seat": "cataloguer",
    "seatFile": WS + "/cataloguer.md",
    "brief": "Describe every member of org.jawata.mcp.execution.",
    # REQUIRED, and pointed at the throwaway workspace. The default used to be the resident's own
    # directory, which is half of why a real brain ran in the developer's repository.
    "workingDirectory": WS,
    "steps": [{"tool": "compile_workspace", "arguments": {}}],
})
d = hired.get("data", {})
if hired.get("success"):
    ok("the hire ran end to end over the wire")
else:
    bad("the hire was refused: %s" % json.dumps(hired)[:400])

if d.get("hirer") == "pm" and d.get("hired") == "cataloguer":
    ok("NAMED HIRER and NAMED HIRED: pm hired cataloguer")
else:
    bad("the record does not name both parties: hirer=%r hired=%r"
        % (d.get("hirer"), d.get("hired")))

# NOT A SCRIPT: the stand-in spoke claude's shape and claude's reader counted it, which happens
# only if the dialect came from the seat's own frontmatter rather than from a default.
if d.get("toolSteps") == 2:
    ok("NOT A SCRIPT: claude's dialect read 2 tool_use blocks from the seat's chosen brain")
else:
    bad("the seat's own dialect did not read the brain's events: toolSteps=%r" % d.get("toolSteps"))

if d.get("costUsd") == 0.07:
    ok("the brain's published dollar cost reached the record")
else:
    bad("cost not carried: %r" % d.get("costUsd"))

# A PROBE THAT FAILS AND SHOWS NOTHING sends the reader back to run it again. When either claim
# about what the brain said comes out wrong, print the whole answer and the record the run wrote —
# the two artifacts that say why — rather than the one field that disagreed.
if d.get("toolSteps") != 2 or d.get("costUsd") != 0.07:
    print("  ---- the whole answer, because a claim about the brain's output failed ----")
    print(json.dumps({k: v for k, v in d.items() if k != "answer"}, indent=2)[:2500])
    print("  ---- answer text (first 600 chars) ----")
    print(str(d.get("answer"))[:600])
    rec = d.get("record")
    if rec and os.path.isfile(rec):
        print("  ---- the record it wrote (first 1500 chars) ----")
        with open(rec) as fh:
            print(fh.read()[:1500])

# GATES RUN — and each row says whether it ANSWERED as well as what it counted.
gates = d.get("gates") or []
if len(gates) == 1 and gates[0].get("tool") == "compile_workspace":
    ok("GATES RUN: the declared gate was called through the real registry")
else:
    bad("the gate did not run: %r" % gates)

if gates and gates[0].get("answered") is True and gates[0].get("errorCount") == 0:
    ok("the gate ANSWERED and found nothing — reported as two facts, not one zero")
elif gates:
    bad("the gate did not answer: %r" % gates[0])

if d.get("complete") is True and d.get("stepsPassed") == 1:
    ok("the LEDGER advanced the plan past its gate")
else:
    bad("the plan did not advance: complete=%r stepsPassed=%r"
        % (d.get("complete"), d.get("stepsPassed")))

# RESULT JOURNALLED — the record is a file a human opens, at the path the answer gives.
record = d.get("record")
if record and os.path.isfile(record):
    # THE ARTIFACT, AND THIS LINE USED TO CLAIM THE JOURNAL. It said "RESULT JOURNALLED" while
    # checking proposal.md — a file on disk, which is a different thing from a row in the event
    # store. An audit named it. The journal row is asserted in HireToolTest against the store's
    # own counters, because LearnerEventStore publishes no row-level read for a probe to use.
    ok("THE RECORD IS ON DISK (the artifact, not the journal) at %s" % os.path.basename(record))
else:
    bad("no record at the path the response gave: %r" % record)

if d.get("artifactId") and d.get("plan"):
    ok("the artifact id and the plan id are both on the answer")
else:
    bad("artifactId=%r plan=%r" % (d.get("artifactId"), d.get("plan")))

# EVERY CHANGED FILE ATTRIBUTED TO THE RUN — C16's clause. The throwaway working directory is not
# a git repository, so the honest answer here is an ABSENCE with its reason, and `changedFiles`
# must be missing rather than empty. That distinction is the clause's whole content: a caller shown
# [] would be told the brain touched nothing when nobody looked.
basis = d.get("changedFilesBasis")
if basis:
    ok("the run states the BASIS of its file attribution, always: %s" % str(basis)[:90])
else:
    bad("no changedFilesBasis on the answer — a list with no basis invites assumed exactness")

if "changedFiles" not in d and "NOT ATTRIBUTABLE" in str(basis):
    ok("an un-censusable directory reports an ABSENCE, not an empty list")
elif "changedFiles" in d:
    ok("the census ran and attributed %d file(s)" % len(d["changedFiles"]))
else:
    bad("neither a list nor a stated absence: basis=%r" % basis)

# ------------- 3b. THE CLAUSE'S REAL CASE: a brain that WRITES, in a repository
# The stand-in writes one file into whatever directory it is run in. Hired above in a plain
# temp directory that answer was an honest absence; hired here, in a git working tree, the run
# must NAME the file. The contrast between the two is the discriminator — either alone would
# pass against a product that always reported an absence, or always reported an empty list.
wrote = call("hire", {
    "hirer": "pm",
    "seat": "cataloguer",
    "seatFile": WS + "/cataloguer.md",
    "brief": "Describe every member of org.jawata.mcp.execution.",
    "workingDirectory": REPO,
    "steps": [{"tool": "compile_workspace", "arguments": {}}],
})
w = wrote.get("data", {})
if wrote.get("success"):
    ok("a second hire ran in a git working tree")
else:
    bad("the hire in the repository was refused: %s" % json.dumps(wrote)[:300])

attributed = w.get("changedFiles")
if attributed is not None and "brain-wrote-this.txt" in attributed:
    ok("EVERY CHANGED FILE ATTRIBUTED: the run names the file its brain wrote (%s)"
       % ", ".join(attributed))
else:
    bad("the file the brain wrote was not attributed: changedFiles=%r basis=%r"
        % (attributed, w.get("changedFilesBasis")))

# AND IT IS IN THE FILE A HUMAN READS, not only in the tool's answer.
rec2 = w.get("record")
if rec2 and os.path.isfile(rec2):
    with open(rec2) as fh:
        body = fh.read()
    if "## What it changed" in body and "brain-wrote-this.txt" in body:
        ok("and the proposal record carries it under its own heading")
    else:
        bad("the record does not name what changed:\n%s" % body[-500:])
else:
    bad("no record for the second hire: %r" % rec2)


# --------- 4. A BRAIN THAT CANNOT BE TOLD WHICH TOOLS IS REPORTED, NOT DROPPED
cursor = call("hire", {
    "hirer": "pm",
    "seat": "cataloguer",
    "seatFile": WS + "/cursor-seat.md",
    "brief": "Describe every member of org.jawata.mcp.execution.",
    "workingDirectory": WS,
    "steps": [{"tool": "compile_workspace", "arguments": {}}],
})

# AND A REFUSAL, which is the fix's own control: a hire that does not say where the brain works is
# refused rather than run in whatever directory the resident happens to sit in.
nowhere = call("hire", {
    "hirer": "pm",
    "seat": "cataloguer",
    "seatFile": WS + "/cataloguer.md",
    "brief": "Describe every member of org.jawata.mcp.execution.",
    "steps": [{"tool": "compile_workspace", "arguments": {}}],
})
if not nowhere.get("success") and "workingDirectory" in json.dumps(nowhere):
    ok("a hire that names no working directory is REFUSED, not defaulted")
else:
    bad("a hire with no working directory was accepted: %s" % json.dumps(nowhere)[:300])
c = cursor.get("data", {})
if cursor.get("success"):
    ok("the uncontainable brain still RAN — it is reported, not dropped from the count")
else:
    bad("the cursor seat was refused rather than reported: %s" % json.dumps(cursor)[:300])

if c.get("toolsSettableByTheHirer") is False and "cannot" in str(c.get("toolAttachment", "")).lower():
    ok("and the answer says it cannot be told which tools: %s" % c.get("toolAttachment"))
else:
    bad("the limitation is not reported: settable=%r report=%r"
        % (c.get("toolsSettableByTheHirer"), c.get("toolAttachment")))

# The honest report is NARROWER than "cannot be contained": cursor takes --mode plan.
if "plan" in str(c.get("toolAttachment", "")):
    ok("the containment that WAS applied is named, so the report does not overstate")
else:
    bad("the report does not say what containment was applied: %r" % c.get("toolAttachment"))

print("")
print("C16-HIRE-FRONTDOOR passed=%d failed=%d" % (passed, failed))
raise SystemExit(0 if failed == 0 else 1)
PY
PROBE_STATUS=$?
exit $PROBE_STATUS
