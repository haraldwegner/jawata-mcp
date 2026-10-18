#!/usr/bin/env bash
# The skip-wording gate's own test: every behaviour it promises, on a small tree built here.
# A gate that is never shown to refuse proves nothing, so the refusing cases come first.
set -uo pipefail
GATE="$(cd "$(dirname "$0")" && pwd)/skip-wording-gate.py"
FAILS=0

# case <name> <expected-exit> <expected-text-or-empty> — runs the gate on $ROOT.
check() {
    local name="$1" want="$2" text="$3" out rc
    out="$(python3 "$GATE" --root "$ROOT" 2>&1)"; rc=$?
    if [ "$rc" -ne "$want" ]; then
        echo "FAIL  $name: exit $rc, wanted $want"; echo "$out" | sed 's/^/        /'; FAILS=$((FAILS + 1)); return
    fi
    if [ -n "$text" ] && ! printf '%s' "$out" | grep -qF -- "$text"; then
        echo "FAIL  $name: output lacks '$text'"; echo "$out" | sed 's/^/        /'; FAILS=$((FAILS + 1)); return
    fi
    echo "ok    $name"
}

fresh() {
    ROOT="$(mktemp -d)"
    mkdir -p "$ROOT/build" "$ROOT/mod/src"
    printf '%s\n' '# a comment' 'the brain fixture is a POSIX shell script' 'no embedder' > "$ROOT/build/expected-aborts.windows"
}
source_file() { cat > "$ROOT/mod/src/FooTest.java"; }

fresh
source_file <<'EOF'
class FooTest { void a() { assumeTrue(ok(), "the fixture is a POSIX shell script"); } }
EOF
check "a literal no pattern matches is refused (the 4.4.9 Windows case)" 1 "VIOLATION"

fresh
source_file <<'EOF'
class FooTest { void a() { assumeTrue(ok(), "the brain fixture is a POSIX shell script"); } }
EOF
check "a literal a pattern matches passes" 0 "0 NEW violation"

fresh
source_file <<'EOF'
class FooTest { void a() { assumeTrue(ok(), "no embedder - " + "and the rest " + why()); } }
EOF
check "a message joined from literals and a call is matched on its literal part" 0 "1 skip message(s) match"

fresh
source_file <<'EOF'
class FooTest { void a() { assumeTrue(ok(), "something nobody budgeted " + why()); } }
EOF
check "an unmatched message with a part built at run time is listed, never failed" 0 "1 unchecked"

fresh
source_file <<'EOF'
class FooTest { void a() { assumeTrue(ok()); } }
EOF
check "a skip with no message is refused" 1 "no message"

fresh
source_file <<'EOF'
class FooTest { void a() { Assumptions.abort("nobody budgeted this"); } }
EOF
check "Assumptions.abort is read like assumeTrue" 1 "nobody budgeted this"

fresh
source_file <<'EOF'
class FooTest {
    // assumeTrue(false) in a comment is not a skip
    /* neither is assumeTrue(cond) in this one */
    void a() { String s = "assumeTrue(x) inside a string is not a call either"; }
}
EOF
check "comments and strings that mention a call are not calls" 0 "0 NEW violation"

fresh
mkdir -p "$ROOT/mod/test-resources/p"
cat > "$ROOT/mod/test-resources/p/Fixture.java" <<'EOF'
class Fixture { void a() { assumeTrue(ok()); } }
EOF
check "a fixture project under test-resources is not scanned" 0 "0 NEW violation"

fresh
source_file <<'EOF'
class FooTest { void a() { assumeTrue(ok(), "never fires in CI, nobody budgeted it"); } }
EOF
check "before a baseline the unbudgeted skip is refused" 1 "VIOLATION"
python3 "$GATE" --root "$ROOT" --update-baseline >/dev/null
check "recorded in the baseline it passes" 0 "1 known unbudgeted"
source_file <<'EOF'
class FooTest { void a() { assumeTrue(ok(), "never fires in CI, nobody budgeted it, reworded"); } }
EOF
check "a baselined skip whose wording changed is refused again" 1 "VIOLATION"

if [ "$FAILS" -ne 0 ]; then
    echo "skip-wording-gate-test: $FAILS case(s) failed"
    exit 1
fi
echo "skip-wording-gate-test: all cases passed"
