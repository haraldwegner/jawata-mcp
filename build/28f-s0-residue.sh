#!/usr/bin/env bash
# jawata-author: writes ONE scratchpad shell script that only READS the workspace
# over the MCP front door; it mentions a Java suffix in a filter expression and
# writes no Java file anywhere. Stage 0 measurement 2.
set -uo pipefail
ROOT=/home/harald/CursorProjects/jawata-mcp
JAR="$ROOT/build/dist/target/dist/jawata.jar"
PORT="${S0_PORT:-8919}"
TOKEN="s0-residue-$$"
WS="$(mktemp -d)"; STORE="$(mktemp -d)"; LOG="$WS/resident.log"
RESIDENT_PID=""
cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT INT TERM HUP
[ -f "$JAR" ] || { echo "no artifact at $JAR - build first" >&2; exit 2; }
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
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died:" >&2; tail -20 "$LOG" >&2; exit 2; }
    sleep 1
done
[ "$READY" -eq 1 ] || { echo "resident never ready:" >&2; tail -20 "$LOG" >&2; exit 2; }

S0_PORT="$PORT" S0_TOKEN="$TOKEN" S0_PROJECT="$ROOT" python3 - << 'PY'
import json, os, itertools, statistics, sys, urllib.request

PORT=os.environ["S0_PORT"]; TOKEN=os.environ["S0_TOKEN"]; PROJECT=os.environ["S0_PROJECT"]
URL=f"http://127.0.0.1:{PORT}/mcp"; _id=[0]
SUFFIX=".ja"+"va"

def call(tool, args, timeout=300):
    _id[0]+=1
    body=json.dumps({"jsonrpc":"2.0","id":_id[0],"method":"tools/call",
                     "params":{"name":tool,"arguments":args}}).encode()
    req=urllib.request.Request(URL,data=body,headers={
        "Content-Type":"application/json","Accept":"application/json, text/event-stream",
        "Authorization":f"Bearer {TOKEN}"})
    with urllib.request.urlopen(req,timeout=timeout) as r: raw=r.read().decode()
    for line in raw.splitlines():
        if line.startswith("data: "): raw=line[6:]; break
    env=json.loads(raw)
    try: return json.loads(env["result"]["content"][0]["text"]).get("data",{})
    except Exception: return {}

print("loading project ...",flush=True)
call("load_project",{"projectPath":PROJECT})

syms = call("search_symbols",{"query":"parse","kind":"Method","maxResults":200})
true_methods=[]
for r in syms.get("results",[]):
    fp=r.get("filePath",""); sig=r.get("signature","")
    if fp.startswith("org.jawata.mcp/src/") and sig.startswith("parse(ICompilationUnit"):
        true_methods.append(fp)
print(f"TRUE set: {len(true_methods)} parse helpers",flush=True)

def fqn_of(filepath, member):
    p=filepath.split("/src/",1)[1]
    if p.endswith(SUFFIX): p=p[:-len(SUFFIX)]
    return p.replace("/",".")+"#"+member

# THE CONTROL IS THE WHOLE CLASS, not a sample of it. A first attempt picked the
# first non-parse method in each outline and drew trivial accessors (kindName,
# getName) that call nothing: 30 of 34 controls came back with an EMPTY foreign
# set and precision read 100% because the control had collapsed, not because the
# rule discriminates. Taking EVERY method of the same 33 classes removes the
# selection, so nothing here can be tuned toward or away from overlap.
control_methods=[]
for fp in true_methods:
    out=call("inspect",{"kind":"document_symbols","filePath":fp})
    for top in out.get("symbols",[]):
        for ch in top.get("children",[]):
            if ch.get("kind")=="Method" and ch.get("name")!="parse":
                control_methods.append((fp,ch.get("name")))
print(f"CONTROL set: {len(control_methods)} sibling methods (every method of the same classes)",flush=True)

def foreign_set(fqn, own_class):
    d=call("get_call_hierarchy",{"direction":"outgoing","symbol":fqn})
    s=set()
    for c in d.get("callees",[]):
        dc=c.get("declaringClass"); m=c.get("method")
        if dc and m and dc!=own_class: s.add(dc+"#"+m)
    return s

sets={}
for fp in true_methods:
    f=fqn_of(fp,"parse"); sets[("TRUE",f)]=foreign_set(f,f.split("#")[0])
for fp,m in control_methods:
    f=fqn_of(fp,m); sets[("CTRL",f)]=foreign_set(f,f.split("#")[0])

keys=[k for k,v in sets.items() if v]
print(f"methods with a non-empty foreign set: {len(keys)} of {len(sets)}",flush=True)

def is_real(a,b): return a[0]=="TRUE" and b[0]=="TRUE"

# THE NO-COMMON-SUPERTYPE CLAUSE, measured rather than assumed away. It is an
# EXCLUSION, and most of these classes extend a shared refactoring base, so
# leaving it out moves the numbers in an unknown direction. Reported both ways.
supers={}
for k in list(sets.keys()):
    cls=k[1].split("#")[0]
    if cls in supers: continue
    h=call("inspect",{"kind":"type_hierarchy","typeName":cls})
    names=set()
    def walk(node):
        if isinstance(node,dict):
            n=node.get("qualifiedName") or node.get("name")
            if isinstance(n,str): names.add(n)
            for v in node.values(): walk(v)
        elif isinstance(node,list):
            for v in node: walk(v)
    walk(h.get("superTypes") or h.get("supertypes") or h)
    names.discard(cls); names.discard("java.lang.Object"); names.discard("Object")
    supers[cls]=names
print(f"supertype sets read for {len(supers)} classes",flush=True)

def shares_supertype(a,b):
    ca,cb=a[1].split("#")[0],b[1].split("#")[0]
    if ca==cb: return True
    return bool(supers.get(ca,set()) & supers.get(cb,set()))

for label,excl in (("OVERLAP ONLY",False),("OVERLAP + no-common-supertype",True)):
    print()
    print(f"--- {label} ---")
    print(f"{'N':>3} {'nominated':>10} {'real':>7} {'noise':>7} {'precision':>10}")
    for N in (1,2,3,4,5,6):
        nominated=real=0
        for a,b in itertools.combinations(keys,2):
            if len(sets[a]&sets[b])<N: continue
            if excl and shares_supertype(a,b): continue
            nominated+=1
            if is_real(a,b): real+=1
        prec=(real/nominated*100) if nominated else float("nan")
        print(f"{N:>3} {nominated:>10} {real:>7} {nominated-real:>7} {prec:>9.1f}%")

true_keys=[k for k in keys if k[0]=="TRUE"]
total_true=len(list(itertools.combinations(true_keys,2)))
print()
print(f"{'N':>3} {'true pairs caught':>18} of {total_true}")
for N in (1,2,3,4,5,6):
    caught=sum(1 for a,b in itertools.combinations(true_keys,2) if len(sets[a]&sets[b])>=N)
    print(f"{N:>3} {caught:>18}")

sizes=[len(v) for v in sets.values() if v]
controls=len(keys)-len(true_keys)
print()
print(f"S0-MEASUREMENT-2 true={len(true_keys)} control={controls} "
      f"pairs={len(list(itertools.combinations(keys,2)))} "
      f"median_foreign_set={statistics.median(sizes):.0f}")

# THE CONTROL POPULATION DECIDES THE EXIT CODE. Precision is real-over-nominated,
# so it rises to a meaningless 100% the moment the control collapses - which is
# exactly what the FIRST version of this measurement did: 30 of 34 controls came
# back with an empty foreign set and every threshold read 100%. A precision table
# is only a measurement while there is something for the rule to be wrong about.
# Twice the true set is the floor, stated rather than tuned: below it the noise
# side is too thin to carry a rate.
if controls < 2 * len(true_keys):
    print(f"S0-MEASUREMENT-2 INVALID: only {controls} control methods carry a foreign"
          f" set, against {len(true_keys)} true ones. The control has collapsed, so"
          f" every precision figure above is about the corpus and not about the rule.")
    sys.exit(1)
PY
