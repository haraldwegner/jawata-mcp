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
# Usage:  build/c21-transcript-latency.sh [dist-dir] [evidence-dir] [max-windows]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/build/dist/target/dist}"
EVIDENCE="${2:-$ROOT/build/c21-latency-evidence}"
MAXW="${3:-0}"
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
java $VECTOR -Xmx3g -Djawata.experience.shared.dir="$STORE" -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
PID=$!
for _ in $(seq 1 180); do grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && break; sleep 1; done

PROBE_PORT="$PORT" PROBE_TOKEN="$TOKEN" PROBE_EVIDENCE="$EVIDENCE" PROBE_STORE="$STORE" PROBE_MAXW="$MAXW" python3 -I - << 'PY'
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

def key_arg(inp):
    if not isinstance(inp, dict):
        return ""
    for k in ("file_path", "command", "pattern", "query", "path", "url", "description", "prompt"):
        v = inp.get(k)
        if isinstance(v, str):
            return " ".join(v.split())[:120]
    return ""

def windows(path):
    """The windows of one transcript, the way the hook cuts them."""
    out = []; cur = None; pending = {}
    def close():
        nonlocal cur
        if cur and (cur["his"].strip() or cur["agent"] or cur["tools"]) and cur["key"]:
            out.append(cur)
        cur = None
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            t = e.get("type"); msg = e.get("message") if isinstance(e.get("message"), dict) else {}
            content = msg.get("content")
            if t == "user":
                text = None
                if isinstance(content, str) and content.strip():
                    text = content
                elif isinstance(content, list):
                    texts = [b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text"]
                    if texts and not e.get("isMeta"):
                        text = "\n".join(texts)
                    for b in content:
                        if isinstance(b, dict) and b.get("type") == "tool_result" and cur is not None:
                            tid = b.get("tool_use_id")
                            if b.get("is_error") and tid in pending:
                                pending[tid]["failed"] = True
                if text is not None:
                    close()
                    cur = {"key": e.get("uuid"), "ts": e.get("timestamp"), "his": text, "agent": [], "tools": []}
            elif t == "assistant" and isinstance(content, list) and cur is not None:
                for b in content:
                    if not isinstance(b, dict):
                        continue
                    if b.get("type") == "text" and b.get("text", "").strip():
                        cur["agent"].append(b["text"])
                    elif b.get("type") == "tool_use":
                        rec = {"line": b.get("name", "") + " " + key_arg(b.get("input")), "failed": False}
                        pending[b.get("id")] = rec; cur["tools"].append(rec)
    close()
    return out

files = sorted(glob.glob(os.path.expanduser("~/.claude/projects/**/*.jsonl"), recursive=True))
jobs = []
for f in files:
    session = os.path.basename(f)[:-6]
    for w in windows(f):
        jobs.append((session, w))
        if MAXW and len(jobs) >= MAXW:
            break
    if MAXW and len(jobs) >= MAXW:
        break
print("windows to store: %d from %d transcripts" % (len(jobs), len(files)), flush=True)

def store(job):
    session, w = job
    args = {"kind": "transcript_append", "session": session, "key": w["key"], "client": "claude-code",
            "his": w["his"], "agent": "\n\n".join(w["agent"]),
            "tools": [t["line"] + (" failed" if t["failed"] else " ok") for t in w["tools"]]}
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
times = []; empty = 0
for cue in CUES:
    for _ in range(5):
        a = time.time()
        d = call({"kind": "recall", "lanes": ["transcript"], "symptom": cue}).get("data") or {}
        times.append((time.time() - a) * 1000)
        if not (d.get("entries") or []):
            empty += 1
times.sort()
def pct(p): return times[min(len(times) - 1, int(len(times) * p))]
print("searches: %d (%d cues x 5), %d answered empty" % (len(times), len(CUES), empty))
print("latency ms  median=%.0f  p95=%.0f  max=%.0f" % (statistics.median(times), pct(0.95), times[-1]))
print("bar: 1200 ms  -> %s" % ("WITHIN" if times[-1] <= 1200 else ("p95 within, max over" if pct(0.95) <= 1200 else "OVER")))
json.dump({"windows": len(jobs), "turns": stats.get("turns"), "store_mb": round(size / 1e6, 1),
           "ingest_s": round(ingest), "median_ms": round(statistics.median(times)),
           "p95_ms": round(pct(0.95)), "max_ms": round(times[-1]), "empty_answers": empty},
          open(os.path.join(os.environ["PROBE_EVIDENCE"], "latency.json"), "w"))
PY
