#!/usr/bin/env bash
# Self-test for the error-stub guard in build/pom.xml (S16-1). Drives the pom's OWN rule — the
# antrun execution refuse-error-stub-classes — over scratch folders, so no copy of the rule
# lives here, or this would test the copy and not the guard.
#
# It exists because the guard shipped with a hand run as its only evidence: a guard that
# silently stopped matching (a renamed execution, a moved fileset, a marker spelled another
# way) would pass every build after it, which is the blindness the guard was written to end.
#
# Usage: stub-class-guard-test.sh [--quiet]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QUIET=0; [ "${1:-}" = "--quiet" ] && QUIET=1
FAILURES=0
say() { [ "$QUIET" -eq 1 ] || echo "$@"; }
check() {   # check <name> <condition-result>
    if [ "$2" = "0" ]; then say "  ok   $1"
    else echo "  FAIL $1"; FAILURES=$((FAILURES + 1)); fi
}

R="$(mktemp -d)"; trap 'rm -rf "$R"' EXIT
guard() {   # guard <dir> — the pom's own execution, aimed at <dir>
    mvn -o -q -N -f "$ROOT/build/pom.xml" antrun:run@refuse-error-stub-classes \
        -Dstub.scan.dir="$1" > "$R/out.txt" 2>&1
}
say "stub-class guard self-test:"

# What the Eclipse compiler writes for code that does not compile: a class file whose constant
# pool carries this sentence. The guard reads for the sentence, so these bytes are enough.
mkdir -p "$R/stub/classes/a" "$R/clean/classes/a" "$R/stub-test/test-classes/a"
printf '\xca\xfe\xba\xbe\x00\x00\x00\x41Unresolved compilation problem: \n\tx cannot be resolved\n' \
    > "$R/stub/classes/a/Broken.class"
printf '\xca\xfe\xba\xbe\x00\x00\x00\x41an ordinary class\n' > "$R/clean/classes/a/Fine.class"
printf '\xca\xfe\xba\xbe\x00\x00\x00\x41Unresolved compilation problem: \n' \
    > "$R/stub-test/test-classes/a/BrokenTest.class"

guard "$R/stub";      [ "$?" -ne 0 ]; check "an error-stub class among the classes fails the build" "$?"
grep -q "Broken.class" "$R/out.txt"; check "and the refusal names the class" "$?"
guard "$R/stub-test"; [ "$?" -ne 0 ]; check "an error-stub class among the TEST classes fails it too" "$?"
guard "$R/clean";     [ "$?" -eq 0 ]; check "a folder of ordinary classes passes" "$?"
guard "$R/missing";   [ "$?" -eq 0 ]; check "a module with no output yet passes, as the pom says" "$?"

if [ "$FAILURES" -ne 0 ]; then
    echo "stub-class guard self-test: $FAILURES case(s) FAILED — the build could package code that does not compile."
    [ "$QUIET" -eq 1 ] && sed 's/^/    /' "$R/out.txt"
    exit 1
fi
say "stub-class guard self-test: all cases pass"
exit 0
