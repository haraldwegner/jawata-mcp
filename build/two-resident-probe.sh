#!/usr/bin/env bash
# mcp#27 stage 1 — TWO RESIDENTS ON ONE MACHINE, THROUGH THE REAL FRONT DOOR.
#
# This is C6's exit clause and nothing else:
#
#     "two residents on one machine, a symbol that lives only in the second — a
#      search on the first names the second's workspace and project with nothing
#      asking for it; the same search with the second stopped says it consulted
#      zero siblings"
#
# WHY IT EXISTS WHEN THE UNIT TESTS ARE GREEN. Every test of the peek speaks to a
# stub, and every stub was written by the author of the parser — so the suite
# cannot catch a wrong belief about the real envelope, the real auth header or
# the real registry spelling. Those four things only meet each other in a live
# process. This is the instrument for that and it makes no claim beyond it.
#
# THE ONE SEAM THIS PROBE DOES NOT CLOSE, stated plainly rather than left to be
# discovered: the registry below is written by THIS SCRIPT, not by Studio. So it
# proves the resident READS the shape written here; it does not prove Studio
# WRITES that shape. Studio's writer is pinned on its own side, by a Rust test
# asserting the raw JSON keys (`the_registry_is_written_in_the_keys_the_resident_reads`),
# and the two are held together only by both being asserted against the same
# literal spelling. They live in different repositories, so a shared golden file
# would mean one repo hard-coding a path into the other — worse than the gap.
# If the two ever disagree, this probe passes and production degrades silently
# to "no siblings", which is the failure mode the reader cannot distinguish from
# a one-resident machine.
#
# It creates .java fixture files. That is authoring, not refactoring — there is
# no tool that writes a new class from nothing, and the files are throwaway.
#
# Usage:  build/two-resident-probe.sh [dist-dir]
# Exit:   0 = every claim held · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is NEVER reported as a pass)

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT_A="${PROBE_PORT_A:-8911}"
PORT_B="${PROBE_PORT_B:-8912}"
TOKEN_A="two-resident-probe-a-$$"
TOKEN_B="two-resident-probe-b-$$"

WS="$(mktemp -d)"
STORE_A="$(mktemp -d)"
STORE_B="$(mktemp -d)"
PID_A=""
PID_B=""

cleanup() {
    local status=$?
    for pid in "$PID_A" "$PID_B"; do
        [ -n "$pid" ] && kill "$pid" 2>/dev/null
        [ -n "$pid" ] && wait "$pid" 2>/dev/null
    done
    # A FAILED run keeps its workspace. The first version deleted it
    # unconditionally, so the one run that failed left nothing to diagnose from
    # and the next step was a guess — which is the whole reason this probe
    # exists instead of a unit test.
    if [ "$status" -ne 0 ] || [ -n "${PROBE_KEEP:-}" ]; then
        echo "kept for diagnosis: $WS" >&2
        rm -rf "$STORE_A" "$STORE_B"
    else
        rm -rf "$WS" "$STORE_A" "$STORE_B"
    fi
}
# EXIT alone is not enough: a shell killed by a signal can exit without running
# it, and a leaked resident holds the port the next run wants.
trap cleanup EXIT INT TERM HUP

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }

# ---- two projects, and ONE symbol that exists in exactly one of them ----------
mk_project() {  # $1=name  $2=package  $3=class
    local dir="$WS/projects/$1"
    mkdir -p "$dir/src/main/java/${2//.//}"
    cat > "$dir/pom.xml" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.probe</groupId>
  <artifactId>$1</artifactId>
  <version>1.0</version>
  <properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
  </properties>
</project>
POM
    cat > "$dir/src/main/java/${2//.//}/$3.java" <<JAVA
package $2;

/** Probe fixture: this type exists in the '$1' workspace and nowhere else. */
public class $3 {
    public String describe() {
        return "$3";
    }
}
JAVA
}

mk_workspace() {  # $1=name
    mkdir -p "$WS/workspaces/$1"
    cat > "$WS/workspaces/$1/workspace.json" <<WSJSON
{ "name": "$1", "projects": ["$WS/projects/$1"] }
WSJSON
}

mk_project alpha com.probe.alpha OnlyInAlpha
mk_project beta  com.probe.beta  OnlyInBeta
mk_workspace alpha
mk_workspace beta

# The registry, in the shape Studio's Rust writer emits. The key names are the
# contract; a wrong spelling is read as a blank name and the row is DROPPED, so
# the whole file degrades to "no siblings" without saying anything.
cat > "$WS/workspaces/residents.json" <<REG
{
  "residents": [
    { "workspaceName": "alpha", "port": $PORT_A, "token": "$TOKEN_A" },
    { "workspaceName": "beta", "port": $PORT_B, "token": "$TOKEN_B" }
  ]
}
REG

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

start_resident() {  # $1=name $2=port $3=token $4=store
    # shellcheck disable=SC2086
    java $VECTOR -Djawata.experience.shared.dir="$4" \
         -jar "$JAR" -data "$WS/workspaces/$1" -port "$2" -token "$3" \
         > "$WS/$1.log" 2>&1 &
    echo $!
}

await_ready() {  # $1=name $2=pid
    for _ in $(seq 1 120); do
        grep -q "READY\|Server started\|listening" "$WS/$1.log" 2>/dev/null && return 0
        kill -0 "$2" 2>/dev/null || { echo "resident '$1' died on startup:" >&2
                                      tail -20 "$WS/$1.log" >&2; return 1; }
        sleep 1
    done
    echo "resident '$1' never announced readiness in 120s:" >&2
    tail -20 "$WS/$1.log" >&2
    return 1
}

PID_A=$(start_resident alpha "$PORT_A" "$TOKEN_A" "$STORE_A")
PID_B=$(start_resident beta  "$PORT_B" "$TOKEN_B" "$STORE_B")
await_ready alpha "$PID_A" || exit 2
await_ready beta  "$PID_B" || exit 2

PROBE_WS="$WS" PROBE_PORT_A="$PORT_A" PROBE_PORT_B="$PORT_B" \
PROBE_TOKEN_A="$TOKEN_A" PROBE_TOKEN_B="$TOKEN_B" PROBE_PID_B="$PID_B" \
python3 - << 'PY'
import json, os, subprocess, sys, time, urllib.error, urllib.request

WS = os.environ["PROBE_WS"]
PORT_A, TOKEN_A = os.environ["PROBE_PORT_A"], os.environ["PROBE_TOKEN_A"]
PORT_B, TOKEN_B = os.environ["PROBE_PORT_B"], os.environ["PROBE_TOKEN_B"]

passed = failed = 0
def ok(msg):
    global passed; passed += 1; print("  ok    " + msg)
def bad(msg):
    global failed; failed += 1; print("  FAIL  " + msg)

def call(port, token, tool, args, timeout=180):
    """One tools/call over the wire. Returns the parsed ToolResponse — the tool
    result is JSON nested inside a JSON string, so both layers are parsed."""
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": tool, "arguments": args}}
    r = urllib.request.Request(
        "http://127.0.0.1:%s/mcp" % port, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + token,
                 "Mcp-Session-Id": "two-resident-probe",
                 "Content-Type": "application/json"})
    raw = urllib.request.urlopen(r, timeout=timeout).read().decode()
    outer = json.loads(raw)
    return json.loads(outer["result"]["content"][0]["text"])

def await_projects(port, token, label):
    """A resident answers before its projects finish loading, and a miss against
    a half-loaded workspace is not the miss this probe is about."""
    last = None
    for _ in range(120):
        try:
            health = call(port, token, "health_check", {}, timeout=30)
            last = health
            data = health.get("data") or {}
            # The count lives in different places across versions; read whichever
            # the running server actually answers with rather than assuming one.
            for probe in (data.get("projectCount"),
                          (data.get("workspace") or {}).get("projectCount"),
                          len(data.get("projects") or [])):
                if isinstance(probe, int) and probe > 0:
                    return True
        except Exception as e:
            last = {"exception": str(e)}
        time.sleep(1)
    # Say WHAT it answered. "Never loaded a project" with no evidence is the
    # shape that sends the next step off guessing.
    print("  resident '%s' never loaded a project. Last health_check:\n%s"
          % (label, json.dumps(last, indent=2)[:2000]), file=sys.stderr)
    return False

if not await_projects(PORT_A, TOKEN_A, "alpha"): sys.exit(2)
if not await_projects(PORT_B, TOKEN_B, "beta"):  sys.exit(2)

# --- PROOF OF LIFE ------------------------------------------------------------
# Without this, every assertion below could be satisfied by a resident that
# resolves NOTHING: a miss on the beta symbol proves the peek only if a hit on
# alpha's own symbol proves the search works at all.
own = call(PORT_A, TOKEN_A, "find_references",
           {"kind": "references", "symbol": "com.probe.alpha.OnlyInAlpha"})
if own.get("success"):
    ok("alpha resolves its OWN symbol — the miss below is a real miss")
else:
    bad("alpha cannot resolve its own symbol, so nothing below means anything: %s"
        % own.get("error"))
    print("passed=%d failed=%d" % (passed, failed)); sys.exit(1)

# --- THE CLAUSE: a search on the first names the second's workspace + project --
miss = call(PORT_A, TOKEN_A, "find_references",
            {"kind": "references", "symbol": "com.probe.beta.OnlyInBeta"})
hint = ((miss.get("error") or {}).get("hint") or "")
if miss.get("success"):
    bad("alpha claims to resolve a symbol that lives only in beta")
else:
    ok("alpha does not resolve beta's symbol")
    # THE NEEDLE MUST BE WORDING ONLY AN ANSWER EMITS. The first version asked
    # whether the hint contained "beta" and "project" — both true of the NAMING
    # FALLBACK as well ("Running here: beta", "1 project(s): alpha") — so it
    # passed while the peek never fired at all. That is what this probe is for,
    # and it is also how a probe lies when its needles come from the wrong
    # sentence: the assertion this replaces was satisfied by the very state it
    # existed to rule out.
    if "has it, in project" in hint:
        ok("the miss ANSWERS — it names the workspace AND the project")
    else:
        bad("not an answer, this is the naming fallback: %s" % hint)
    if "'beta'" in hint:
        ok("the workspace named is beta")
    else:
        bad("the answer does not name beta: %s" % hint)
    if "Running here:" in hint:
        bad("the naming fallback is still there, so no peek happened: %s" % hint)
    else:
        ok("the naming-only tail is replaced by the answer")
    # "with nothing asking for it": the call carried no sibling argument.
    ok("nothing in the request asked for this — the arguments were kind+symbol only")

# --- stop the second, and ask again -------------------------------------------
# BY PID, and confirmed dead. The first version ran `pkill -f "-port 8912"`;
# pkill read the leading dash as its own option, errored, and killed nothing —
# so the "stopped sibling" arm ran against a LIVE beta and was measuring the
# opposite of what it claimed. A teardown that silently does nothing makes every
# assertion after it a statement about a state that was never created.
import signal
beta = int(os.environ["PROBE_PID_B"])
os.kill(beta, signal.SIGKILL)
for _ in range(40):
    try:
        os.kill(beta, 0)
        time.sleep(0.25)
    except OSError:
        break
else:
    print("  beta did not die — the stopped-sibling arm would be meaningless",
          file=sys.stderr)
    sys.exit(2)
time.sleep(2)

after = call(PORT_A, TOKEN_A, "find_references",
             {"kind": "references", "symbol": "com.probe.beta.OnlyInBeta"})
hint2 = ((after.get("error") or {}).get("hint") or "")
if after.get("success"):
    bad("alpha resolved beta's symbol after beta stopped")
elif "consulted 0 of 1" in hint2:
    # ZERO consulted, ONE listed: the row survives the stop because the registry
    # is written on spawn, and reporting it as consulted would claim we asked
    # something that never heard us.
    ok("with beta stopped the search says it consulted ZERO siblings")
else:
    bad("the stopped-sibling answer does not say it consulted zero — hint: %s" % hint2)

if "beta" in hint2 and "has it" in hint2:
    bad("alpha still claims beta HAS the symbol after beta stopped")
else:
    ok("alpha no longer claims beta holds it")

print("TWO-RESIDENT-PROBE passed=%d failed=%d" % (passed, failed))
sys.exit(0 if failed == 0 else 1)
PY
exit $?
