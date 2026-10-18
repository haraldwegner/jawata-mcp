#!/usr/bin/env bash
# jawata-gate: release
# THE WHOLE LOCAL RELEASE GATE, in the order in which a cheap check fails first.
#
# WHY IT IS ONE SCRIPT. 4.4.9 went red on CI after a green local run, twice: the suite with the
# embedder switched off failed a test no local run had ever run that way, and the Windows abort
# budget refused a skip worded differently from the budget file. Both were test-side, both are
# things a push cannot afford to discover. This runs, before the push, what CI runs that a plain
# `run-suite.sh --full` does not:
#
#   1. the skip-wording gate and its own test        seconds   (CI: static-gates)
#   2. a clean install of the version being released minutes   (a version bump needs it: the
#                                                              hollow-wiring gate loads the project
#                                                              through Maven and fails otherwise)
#   3. the whole suite, embedder ON, with the        ~16 min   (CI: build, ubuntu)
#      hollow-wiring gate and the end-to-end gate
#   4. the whole suite, embedder OFF, judged against ~16 min   (CI: embedder-off)
#      the linux AND embedder-off budgets
#
# WHAT IT CANNOT REPLACE: a real Windows or macOS run. A skip that fires only there, in wording
# some budget file accepts, passes here and is decided by CI.
#
# Run it through the bracket, from anywhere:
#   jawata-hook-guard bracket --cd <repo> --out /tmp/release-gate.txt -- build/release-gate.sh
# Exit 0 = every step passed; otherwise the name of the step that stopped it is the last line.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
DIST="$ROOT/build/dist/target/dist"

step() {
    local name="$1"; shift
    echo
    echo "=== release gate: $name"
    "$@"
    local rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "RELEASE GATE FAILED at: $name (exit $rc)"
        exit "$rc"
    fi
    echo "--- passed: $name"
}

step "skip wording" python3 build/skip-wording-gate.py
step "skip wording, its own test" bash build/skip-wording-gate-test.sh
step "clean install of this version" mvn -B -q -f build/pom.xml clean install -DskipTests
step "whole suite, embedder ON (with the hollow-wiring and end-to-end gates)" build/run-suite.sh 4 --full

# The same suite with the embedder switched off, as the CI job runs it. The end-to-end gate ran
# above with the embedder on and is skipped here: it drives the product's front door and is not
# about the degrade path. JAVA_TOOL_OPTIONS reaches every shard JVM without replacing the
# runner's own JVM options.
step "whole suite, embedder OFF" env JAVA_TOOL_OPTIONS=-Djawata.embed.disabled=true \
    JAWATA_SKIP_E2E=1 ABORT_BUDGET=linux,embedder-off build/run-suite.sh 4 --full

# PROOF THE SWITCH TOOK, as the CI job does: a run in which nothing skipped for want of an embedder
# did not run with it off, and would have proved nothing while reporting green.
echo
echo "=== release gate: the embedder switch took"
if ! grep -lE '~~ ABORTED .*(no embedder|embedder unavailable)' "$DIST"/suite-shards/shard-*.log >/dev/null 2>&1; then
    echo "RELEASE GATE FAILED at: the embedder switch took (no test skipped for want of an embedder)"
    exit 1
fi
echo "--- passed: the embedder switch took"

echo
echo "RELEASE GATE PASSED: skip wording, clean install, suite with the embedder on, suite with it off"
