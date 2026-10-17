#!/usr/bin/env bash
# Sprint 28g Stage 21, T5 — HOW LONG A SEARCH OVER THE WHOLE CAPTURED DISCUSSION TAKES.
#
# The bar (spec, S21 Measure): a search over the whole captured history answers within the time the
# hooks already allow a recall, 1.2 seconds. This puts a REAL discussion in the lane — every Claude
# Code transcript on this machine, cut into windows the way the hook cuts them (his words, the
# agent's words to him, one line per tool call, no tool output) — and times searches against it.
#
# The content goes into a private resident's scratch store and is deleted with it. Only counts and
# times are printed or kept.
#
# Usage:  build/c21-transcript-latency.sh [dist-dir] [evidence-dir] [max-windows] [windows-file]
#
# windows-file is the output of the hook crate's `dump_windows` example (one JSON line per window,
# cut by the hook's own reader). Optional environment:
#   C21_JVM_ARGS      extra JVM arguments for the resident, e.g. the debug(action=preset_args)
#                     line, so the profiler seat can attach to it
#   C21_SERVE_SECONDS after the timed groups, keep the resident up and ask it a steady mix of the
#                     three calls for this long, and write $EVIDENCE/ready with the resident's pid
#                     when that starts, so a profile can be taken while it is being asked
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
EVIDENCE="${2:-$ROOT/build/c21-latency-evidence}"
MAXW="${3:-0}"
WINDOWS="${4:-$EVIDENCE/windows.jsonl}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_C21_PORT:-8933}"
TOKEN="c21-lat-$$"
[ -f "$JAR" ] || { echo "no artifact at $JAR" >&2; exit 2; }
mkdir -p "$EVIDENCE"
WS="$(mktemp -d)"; STORE="$(mktemp -d)"; LOG="$WS/resident.log"; PID=""
cleanup() { [ -n "$PID" ] && kill "$PID" 2>/dev/null; [ -n "$PID" ] && wait "$PID" 2>/dev/null
            cp "$LOG" "$EVIDENCE/resident.log" 2>/dev/null; rm -rf "$WS" "$STORE"; }
trap cleanup EXIT INT TERM HUP
VECTOR=""; java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 && VECTOR="--add-modules jdk.incubator.vector"
# shellcheck disable=SC2086
java $VECTOR ${C21_JVM_ARGS:-} -Xmx3g -Djawata.experience.shared.dir="$STORE" -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
PID=$!
for _ in $(seq 1 180); do grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && break; sleep 1; done

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_EVIDENCE="$EVIDENCE" PROBE_STORE="$STORE" PROBE_MAXW="$MAXW" PROBE_WINDOWS="$WINDOWS" PROBE_PID="$PID" PROBE_SERVE="${C21_SERVE_SECONDS:-0}" python3 -I - << 'PY'
import concurrent.futures, glob, json, os, statistics, time, urllib.request

PORT = os.environ["PROBE_PORT"]; TOKEN = os.environ["PROBE_TOKEN"]
STORE = os.environ["PROBE_STORE"]; MAXW = int(os.environ["PROBE_MAXW"])

def call(args, who="lat"):
    req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": "experience", "arguments": args}}
    r = urllib.request.Request("http://127.0.0.1:%s/mcp" % PORT, data=json.dumps(req).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Mcp-Session-Id": who,
                 "Content-Type": "application/json"})
    return json.loads(json.loads(urllib.request.urlopen(r, timeout=900).read().decode())
                      ["result"]["content"][0]["text"])

# THE WINDOWS COME FROM THE HOOK'S OWN READER. `dump_windows` is a cargo example of the hook crate
# that runs every transcript through `stop::read_turn_keeping`, the one parser of that file, so the
# corpus is what the hook would store and not what a script guesses is a window (the first C21
# audit found the script's own cutting gave 14,400 "windows" where the hook sees 1,762).
jobs = []
with open(os.environ["PROBE_WINDOWS"], encoding="utf-8") as fh:
    for line in fh:
        w = json.loads(line)
        jobs.append((w["session"], {"key": w["key"], "ts": w["ts"], "his": w["his"],
                                    "agent": [w["agent"]] if w["agent"] else [],
                                    "tools": [{"line": t, "failed": False} for t in w["tools"]]}))
        if MAXW and len(jobs) >= MAXW:
            break
files = [None] * len(set(s for s, _ in jobs))
print("windows to store: %d from %d transcripts" % (len(jobs), len(files)), flush=True)

def store(job):
    session, w = job
    args = {"kind": "transcript_append", "session": session, "key": w["key"], "client": "claude-code",
            "his": w["his"], "agent": "\n\n".join(w["agent"]),
            "tools": [t["line"] for t in w["tools"]]}
    if w["ts"]:
        args["ts"] = w["ts"]
    return (call(args, "ingest").get("data") or {}).get("appended")

t0 = time.time()
counts = {}
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    for r in pool.map(store, jobs):
        counts[r] = counts.get(r, 0) + 1
ingest = time.time() - t0
print("stored in %.0f s: %s" % (ingest, counts), flush=True)

stats = (call({"kind": "stats"}).get("data") or {}).get("transcript", {})
call({"kind": "compact"})
size = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(STORE) for f in fs)
print("lane holds %s turns; store directory %.1f MB after compaction" % (stats.get("turns"), size / 1e6), flush=True)

CUES = [
    "the lane is chosen before the search", "works council switch to turn it off",
    "calibration gate corpus", "autocontinue judge verdict", "release only on his word",
    "duplicate gate declaration jawata-distinct", "sprint plan checkpoint audit", "mutation control revert",
    "stop hook too long", "rename symbol refactoring tool", "compile workspace diagnostics",
    "memory index feedback", "compaction forgets nothing", "latency typed message recall",
    "hook crate lanes table", "findings ledger closed by test", "dist older than source suite fatal",
    "the", "kestrel", "quantefaktweb", "mvn clean package skiptests", "no size cap just compression",
    "who owns the fault", "live session transcript evidence",
]

# Sentences he really typed (taken from the stored corpus, spread over its length) and sentences he
# never typed. The check of a quote is the stop hook's call, so it is timed as the hook makes it.
def sentences():
    real = []
    step = max(1, len(jobs) // 12)
    for _, w in jobs[::step]:
        words = w["his"].split()
        if len(words) >= 8:
            real.append(" ".join(words[:12]))
        if len(real) == 12:
            break
    never = ["we should rewrite the whole engine in a language nobody here has used", "he insisted the hook must never touch the network at all",
             "send every transcript to the vendor so they can train on it"] * 4
    return real, never[:12]
REAL, NEVER = sentences()
SESSIONS = sorted({s for s, _ in jobs})[:: max(1, len({s for s, _ in jobs}) // 12)][:12]

def timed(label, calls):
    ms = []; misses = 0; unchecked = 0
    for c in calls:
        a = time.time(); d = (call(c).get("data") or {}); ms.append((time.time() - a) * 1000)
        if isinstance(d, str):  # the text form is what a hook asks for after a compaction
            d = {"entries": [1] if d.strip() else []}
        if c.get("kind") == "recall" and not (d.get("entries") or []): misses += 1
        if c.get("kind") == "transcript_verify" and d.get("checked") is False: unchecked += 1
    ms.sort()
    pc = lambda p: ms[min(len(ms) - 1, int(len(ms) * p))]
    r = {"calls": len(ms), "median_ms": round(statistics.median(ms)), "p95_ms": round(pc(0.95)),
         "max_ms": round(ms[-1]), "empty_answers": misses, "unchecked": unchecked}
    print("%-8s %s" % (label, r), flush=True)
    return r

search_calls = [{"kind": "recall", "lanes": ["transcript"], "symptom": c} for c in CUES for _ in range(5)]
verify_calls = [{"kind": "transcript_verify", "quote": q} for q in REAL + NEVER for _ in range(5)]
recent_calls = [{"kind": "recall", "lanes": ["transcript"], "recent": 12, "session": s, "format": "text"}
                for s in SESSIONS for _ in range(5)]
results = {"search": timed("search", search_calls), "verify": timed("verify", verify_calls),
           "recent": timed("recent", recent_calls)}
worst = max(r["max_ms"] for r in results.values())
print("bar: 1200 ms  -> %s (worst single call %d ms)" % ("WITHIN" if worst <= 1200 else "OVER", worst))
json.dump({"windows": len(jobs), "turns": stats.get("turns"), "store_mb": round(size / 1e6, 1),
           "ingest_s": round(ingest), "groups": results},
          open(os.path.join(os.environ["PROBE_EVIDENCE"], "latency.json"), "w"))

serve = int(os.environ.get("PROBE_SERVE", "0"))
if serve:
    open(os.path.join(os.environ["PROBE_EVIDENCE"], "ready"), "w").write(os.environ["PROBE_PID"])
    end = time.time() + serve
    # interleaved, so a profile of any one of the three sees its calls from the first second
    a, b, c = search_calls[::5], verify_calls[::5], recent_calls[::5]
    mix = [x for trio in zip(a, b, c) for x in trio] + a[len(c):] + b[len(c):]
    n = 0
    while time.time() < end:
        call(mix[n % len(mix)]); n += 1
    print("served %d calls while a profile could be taken" % n, flush=True)
PY
