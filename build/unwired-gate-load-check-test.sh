#!/usr/bin/env bash
# Self-test for build/unwired-gate-load-check.py (REL44-45). Drives the REAL checker over crafted
# load_project replies — no copy of its rule lives here.
#
# It exists because the 4.4.9 release lost about 25 minutes to this gate answering "load_project
# refused ... Maven dependency resolution failed" and stopping. The engine's sentence was right and
# printed; what it did not say is the step that cures it — a version bump needs its artifacts
# installed in the local Maven repository first.
#
# Usage: unwired-gate-load-check-test.sh [--quiet]
set -uo pipefail
CHECK="$(cd "$(dirname "$0")" && pwd)/unwired-gate-load-check.py"
QUIET=0; [ "${1:-}" = "--quiet" ] && QUIET=1
FAILURES=0
say() { [ "$QUIET" -eq 1 ] || echo "$@"; }
check() {   # check <name> <condition-result>
    if [ "$2" = "0" ]; then say "  ok   $1"
    else echo "  FAIL $1"; FAILURES=$((FAILURES + 1)); fi
}
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT

reply() {   # reply <file> <payload-json> — the shape the gate's call() writes
    python3 -c 'import json,sys; print(json.dumps({"result":{"content":[{"type":"text","text":sys.argv[1]}]}}))' "$2" > "$1"
}

say "unwired-gate load check self-test:"

reply "$W/ok.json" '{"success":true,"data":{}}'
python3 "$CHECK" "$W/ok.json" > "$W/ok.out" 2>&1
check "a load that succeeded passes" "$?"

reply "$W/maven.json" '{"success":false,"error":{"code":"LOAD_FAILED","message":"Maven dependency resolution failed for org.jawata.core:4.5.0"}}'
python3 "$CHECK" "$W/maven.json" > "$W/maven.out" 2>&1
check "a refused load exits 2, not 0" "$([ $? -eq 2 ]; echo $?)"
grep -q "Maven dependency resolution failed" "$W/maven.out"
check "the engine's own sentence is still printed" "$?"
grep -q "mvn -B -f build/pom.xml install" "$W/maven.out"
check "a dependency-resolution refusal names the install step that cures it" "$?"

reply "$W/other.json" '{"success":false,"error":{"code":"LOAD_FAILED","message":"the project path does not exist"}}'
python3 "$CHECK" "$W/other.json" > "$W/other.out" 2>&1
grep -q "mvn -B -f build/pom.xml install" "$W/other.out"
check "CONTROL: an unrelated refusal does NOT send the reader to the install step" "$([ $? -ne 0 ]; echo $?)"

echo '{not json' > "$W/bad.json"
python3 "$CHECK" "$W/bad.json" > "$W/bad.out" 2>&1
check "an unreadable reply exits 2" "$([ $? -eq 2 ]; echo $?)"

if [ "$FAILURES" -ne 0 ]; then echo "unwired-gate load check self-test: $FAILURES FAILED"; exit 1; fi
say "unwired-gate load check self-test: all passed"
