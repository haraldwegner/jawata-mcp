#!/usr/bin/env bash
# 28h S16e (e): the window run with three planted breaks, one for each of formatting, code actions and file operations.
#
# Not a gate and not part of any suite. For each break it changes ONE line of the engine, rebuilds the distribution, runs the VS Code window
# (build/s16-window-run.sh, which needs the extension built and xvfb) and requires the window run to FAIL on that feature's own assertion;
# the source is restored after each and the distribution is rebuilt and run once more, which must pass.
#
# Usage:  build/s16-window-breaks.sh [maven|plugins]
# Exit:   0 = every break turned the window red and the final control passed · 1 = a break did not turn it red, or the control failed
# Time:   about 3 minutes on the Maven tree (four rebuilds and four window runs: three breaks and the control).
set -uo pipefail
TREE="${1:-maven}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$(mktemp -d)"
CUR=""
# an interrupted run must not leave a planted break in the engine's source: the file being broken is restored from its backup
trap '[ -n "$CUR" ] && [ -f "$OUT/backup" ] && cp "$OUT/backup" "$CUR"; rm -rf "$OUT"' EXIT
L=org.jawata.mcp/src/org/jawata/mcp/java/lsp
FAILURES=0

# break <label> <file> <old text> <new text> <the assertion the window run must fail on>
brk() {
    echo "=== BREAK $1"
    cp "$2" "$OUT/backup"
    CUR="$2"
    python3 -I - "$2" "$3" "$4" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path).read()
assert text.count(old) == 1, "the line to break is not there exactly once: " + old
open(path, "w").write(text.replace(old, new))
PY
    mvn -f build/pom.xml clean package -DskipTests -q > "$OUT/build-$1.log" 2>&1 || { echo "  REBUILD FAILED"; FAILURES=$((FAILURES + 1)); }
    build/s16-window-run.sh "$TREE" > "$OUT/window-$1.log" 2>&1
    code=$?
    cp "$OUT/backup" "$2"
    CUR=""
    if [ "$code" -ne 0 ] && sed 's/\x1b\[[0-9;]*m//g' "$OUT/window-$1.log" | grep -q "$5"; then
        echo "  ok    the window run failed on: $5"
    else
        echo "  FAIL  exit=$code and the assertion '$5' was not the failure"
        FAILURES=$((FAILURES + 1))
    fi
}

brk formatting "$L/LspFormatting.java" '        if (unit == null) {
            return List.of();' '        if (true) {
            return List.of();' 'the range is laid out'
brk codeactions "$L/LspCodeActions.java" '        if (unit == null || unsaved.test(uri)) {' '        if (true) {' 'the selection can be extracted to a variable'
brk fileoperations "$L/LspEditing.java" '        return changes.isEmpty() ? null : Map.of("changes", changes);' '        return null;' 'the type and its constructor follow the file'

echo "=== CONTROL (the source restored, the distribution rebuilt)"
mvn -f build/pom.xml clean package -DskipTests -q > "$OUT/build-control.log" 2>&1 || { echo "  REBUILD FAILED"; FAILURES=$((FAILURES + 1)); }
if build/s16-window-run.sh "$TREE" > "$OUT/window-control.log" 2>&1; then
    echo "  ok    the window run passes"
else
    echo "  FAIL  the control window run failed"
    FAILURES=$((FAILURES + 1))
fi
git status --short -- "$L" | head -5
[ "$FAILURES" -eq 0 ] && { echo "s16 window breaks: all three turned the window red and the control passed"; exit 0; }
echo "s16 window breaks: $FAILURES problem(s)"
exit 1
