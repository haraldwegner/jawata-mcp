#!/usr/bin/env bash
# Sprint 28 (D-IMPORTER): LOAD a real public Bazel Java repository through the
# product's own front door.
#
# The committed simple-bazel fixture proves the code path against a tree we
# wrote. That is worth little on its own — a fixture is shaped by the same
# assumptions as the code. So we also load somebody else's Bazel repo, one we
# have never seen, and report what the importer derived from it.
#
# NOTHING from the clone may enter this repository. The clone lives under /tmp,
# is removed on exit, and the working tree's `git status` LISTING is compared
# before and after — not a count of dirty files, which stays equal when one file
# is swapped for another. Limit, stated rather than glossed: comparing the
# listing sees files appearing, disappearing or changing status, but NOT a
# content edit to a file that was already modified.
#
# A failure that belongs to THAT project is recorded as a diagnosis, never
# converted into a fixture.
#
# Usage: build/probe-public-bazel.sh [git-url]
set -uo pipefail

REPO="${1:-https://github.com/bazelbuild/examples.git}"
WORK="$(mktemp -d /tmp/jawata-bazel-probe-XXXXXX)"
PORT="${JAWATA_PROBE_PORT:-8907}"
TOKEN="bazel-probe-$$"
RESIDENT_PID=""

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/build/dist/target/dist/jawata.jar"

# The tree state as CONTENT. `wc -l` on this is what the first version compared,
# and it cannot see a file appearing while another disappears.
BEFORE="$(git -C "$ROOT" status --porcelain)"
BEFORE_CLEAN=$([ -z "$BEFORE" ] && echo yes || echo no)

check_tree_unchanged() {
    local after; after="$(git -C "$ROOT" status --porcelain)"
    if [ "$BEFORE" != "$after" ]; then
        echo "probe: FAILED — the working tree CHANGED during the probe."
        echo "probe: nothing from a cloned repo may enter this repository. Difference:"
        diff <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$after") | sed 's/^/probe:   /'
        return 1
    fi
    if [ "$BEFORE_CLEAN" = yes ] && [ -n "$after" ]; then
        echo "probe: FAILED — the tree was clean before the probe and is dirty after."
        return 1
    fi
    echo "probe: tree unchanged by content ($(printf '%s' "$after" | grep -c . ) dirty entries," \
         "identical to before; clean-before=$BEFORE_CLEAN)"
    return 0
}

echo "probe: cloning $REPO into $WORK (shallow)"
if ! git clone --depth 1 --quiet "$REPO" "$WORK/repo" 2>/dev/null; then
    echo "probe: RESULT=unreachable — could not clone $REPO (offline or repo moved)."
    echo "probe: this is not a jawata finding; it is a network/availability fact."
    check_tree_unchanged || exit 1
    exit 0
fi

TARGET="$WORK/repo"
for candidate in "$WORK/repo/java-tutorial" "$WORK/repo"; do
    if [ -f "$candidate/WORKSPACE" ] || [ -f "$candidate/MODULE.bazel" ] || [ -f "$candidate/WORKSPACE.bazel" ]; then
        TARGET="$candidate"; break
    fi
done
JAVA_FILES=$(find "$TARGET" -name '*.java' | wc -l)
BUILD_FILES=$(find "$TARGET" -name 'BUILD*' | wc -l)
echo "probe: target=$TARGET"
echo "probe: java files=$JAVA_FILES, BUILD files=$BUILD_FILES"

if [ "$JAVA_FILES" -eq 0 ]; then
    echo "probe: RESULT=no-java — $REPO has no Java sources under $TARGET; nothing to load."
    check_tree_unchanged || exit 1
    exit 0
fi

if [ ! -f "$JAR" ]; then
    echo "probe: RESULT=not-built — no artifact at $JAR. Build the dist first;"
    echo "probe: a probe that cannot load is unproven, not passing."
    check_tree_unchanged || exit 1
    exit 2
fi

# ---- the actual load, through the product's front door -------------------
# A throwaway workspace and store: the probe must never touch the developer's.
PWS="$WORK/ws"; PSTORE="$WORK/store"; mkdir -p "$PWS" "$PSTORE"
java -Djawata.experience.shared.dir="$PSTORE" \
     -jar "$JAR" -data "$PWS" -port "$PORT" -token "$TOKEN" > "$WORK/resident.log" 2>&1 &
RESIDENT_PID=$!
for _ in $(seq 1 120); do
    grep -q "READY\|Server started\|listening" "$WORK/resident.log" 2>/dev/null && break
    kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "probe: RESULT=resident-died on startup"
                                             tail -20 "$WORK/resident.log"; exit 2; }
    sleep 1
done

call() {   # call <tool> <json-args>
    curl -s --max-time 300 -X POST "http://127.0.0.1:$PORT/mcp" \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: bazel-probe-$$" \
        -H 'Content-Type: application/json' \
        -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",
             \"params\":{\"name\":\"$1\",\"arguments\":$2}}" | sed 's/\\"/"/g'
}

echo "probe: loading $TARGET through load_project"
LOAD="$(call load_project "{\"projectPath\":\"$TARGET\"}")"
printf 'probe: load_project answered: %s\n' "$(printf '%s' "$LOAD" | head -c 400)"

STRUCT="$(call inspect '{"kind":"project_structure"}')"
printf 'probe: project_structure: %s\n' "$(printf '%s' "$STRUCT" | head -c 600)"

# Was anything actually mounted? An empty load reporting success is the exact
# failure this sprint exists to end, so it is named rather than counted as a pass
# — AND it leaves through a non-zero exit. A caller reads the exit status; a
# finding printed on stdout above `exit 0` is a pass to everything but a human.
VERDICT=0
if printf '%s' "$LOAD" | grep -q '"success":true'; then
    if printf '%s' "$LOAD" "$STRUCT" | grep -qE '"sourceFileCount":[1-9]|"packageCount":[1-9]'; then
        echo "probe: RESULT=loaded — a real public Bazel repo mounted sources."
    else
        echo "probe: RESULT=loaded-EMPTY — load_project reported success and mounted NOTHING."
        echo "probe: that is a jawata finding, not a property of $REPO."
        VERDICT=3
    fi
else
    echo "probe: RESULT=load-failed — see the answer above."
    echo "probe: diagnose before deciding whether it is ours or theirs."
    VERDICT=3
fi

echo "probe: declared javacopts levels found in the clone: $(grep -rhoE '"(--release|-source)"\s*,\s*"[0-9.]+"' "$TARGET" --include='BUILD*' 2>/dev/null | grep -oE '[0-9.]+' | sort -u | tr '\n' ' ')"

# Nothing about someone else's HEAD is asserted — a gate that depends on another
# project's repository is not a gate — but a load that FAILED or came back empty
# is about us, and leaves through exit 3.
check_tree_unchanged || exit 1
exit "$VERDICT"
