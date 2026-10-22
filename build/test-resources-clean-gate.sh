#!/usr/bin/env bash
# jawata-gate: test-resources-clean
# AFTER A RUN, THE TEST RESOURCES HOLD NOTHING GIT DOES NOT TRACK.
#
# WHY. Tests that import a sample project IN PLACE let the build tools write into the
# repository: Gradle leaves `.gradle/`, the compiler `bin/`, Maven `target/`. Found 2026-10-22
# in his editor's explorer, five samples carrying such leftovers, the oldest from July. They are
# git-ignored, so nothing ever reported them; a sample that carries yesterday's build output is
# also not the sample the test was written against.
#
# The check is strict rather than before/after: a leftover that was already there is the same
# defect as a new one, and a before/after comparison would let a test re-write it unnoticed.
#
# Usage: build/test-resources-clean-gate.sh
# Exit:  0 = clean · 1 = leftovers, listed · 2 = could not run (never a pass)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "COULD NOT RUN: $ROOT is not a git checkout"; exit 2; }

LEFT="$(git status --porcelain --ignored --untracked-files=all -- \
        org.jawata.core.tests/test-resources org.jawata.mcp.tests/test-resources \
        | grep -E '^(\?\?|!!) ' | cut -c4-)"
if [ -n "$LEFT" ]; then
    echo "FAILED: the test resources hold $(printf '%s\n' "$LEFT" | wc -l) path(s) git does not track —"
    echo "a test wrote into a sample instead of into a copy of it:"
    printf '%s\n' "$LEFT" | head -40 | sed 's/^/  /'
    exit 1
fi
echo "test-resources gate: the samples hold only what git tracks."
