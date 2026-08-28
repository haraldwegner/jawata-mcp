#!/usr/bin/env bash
# Sprint 23 (D2) — sharded full-suite runner: partition the in-framework test
# classes across N boot JVMs, balanced by MEASURED per-class times (the C0
# baseline timings file; unknown classes get a default estimate), run the
# shards in parallel, merge the summaries. Exit != 0 when any test fails or
# any shard dies.
#
# Usage:  build/run-suite.sh [shards]        (default 4)
# Env:    TIMINGS=<file>  DEFAULT_SECS=<n>   (default 15)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHARDS="${1:-4}"
TIMINGS="${TIMINGS:-$ROOT/docs/sprints/dossier-23-timings.txt}"
DEFAULT_SECS="${DEFAULT_SECS:-15}"
DIST="$ROOT/build/dist/target/dist"
# Stage 6b: --impacted is OPT-IN and never the gate path (see the safety rule below).
IMPACTED=0
for a in "$@"; do [ "$a" = "--impacted" ] && IMPACTED=1; done
FIXTURES="$ROOT/org.jawata.core.tests/test-resources/sample-projects"
# PER-RUN output dir. Two concurrent runs used to share $DIST/suite-shards and
# both `rm -rf` it, so the loser found no summaries and reported
# "4 shard(s) produced no summary" — which reads as a broken SUITE rather than
# as two runs colliding. Observed 2026-08-07 when a second run was started while
# the first was still going. The pid keeps them apart; the symlink keeps the
# familiar path pointing at the most recent run for anyone reading logs.
OUT="$DIST/suite-shards-$$"

[ -f "$DIST/jawata.jar" ] || { echo "FATAL: dist not built ($DIST/jawata.jar)"; exit 2; }

# issue #1: refuse a contaminated dist BEFORE partitioning — a stale
# prior-version org.jawata jar beside the current one silently shadows fresh
# code (the shards run in classlist mode, which skips the boot's discovery
# guard, so this check is the sharded run's only protection).
for d in "$DIST/bundles" "$DIST/test-bundles"; do
    dupes=$(ls "$d"/org.jawata.*.jar 2>/dev/null | sed 's|.*/||; s|-[0-9][^-]*\.jar$||' | sort | uniq -d)
    if [ -n "$dupes" ]; then
        echo "FATAL (issue #1): two versions of the same org.jawata bundle in $d:"
        ls "$d"/org.jawata.*.jar | sed 's|.*/|  |'
        echo "A stale jar can silently shadow the current one. Rebuild the dist (the purge step removes leftovers)."
        exit 2
    fi
done

# issue #1's other half: refuse a STALE dist. The check above catches TWO
# versions of a bundle shadowing each other; it does not catch ONE bundle that
# is simply OLDER than the code. Discovery below reads these jars, so an
# out-of-date dist means the run tests the previous build and says nothing
# whatever about the working tree.
#
# Measured 2026-08-28, which is why this exists: two consecutive runs reported
# an identical total AND an identical shard split while the tree carried two
# more tests than the jar. Both executed a jar built 70 minutes earlier, the two
# new tests never ran, and the green was a true result about the wrong code —
# indistinguishable, from the summary line, from a green about the right one.
#
# RESOURCES COUNT TOO, and the first version of this guard missed them: it
# matched *.java only, so it stayed silent when a file that SHIPS INSIDE a bundle
# was newer than the dist. catalogue/patterns.json, samples/samples.json and
# catalogue.properties all live inside org.jawata.mcp-*.jar, and patterns.json is
# the file this very sprint rewrote 187 rows of — so the gap sat directly on the
# change surface it was added to protect.
#
# Fixtures are excluded on purpose: sample-project sources under test-resources
# are read from disk at run time and never compiled into a bundle (measured: none
# of them appear as classes in either test bundle), so touching one stales
# nothing.
STALE_SRC=$(find "$ROOT" \( -name '*.java' -o -path '*/resources/*' \) \
                -type f \
                -not -path '*/target/*' \
                -not -path '*/test-resources/*' \
                -not -path '*/.git/*' \
                -newer "$DIST/jawata.jar" -print -quit 2>/dev/null)
if [ -n "$STALE_SRC" ]; then
    echo "FATAL: the dist is OLDER than the source, so this run would test the PREVIOUS build."
    echo "  source newer than the dist: $STALE_SRC"
    echo "  dist built:                 $(date -r "$DIST/jawata.jar" '+%Y-%m-%d %H:%M:%S')"
    echo "Rebuild first:  mvn -f build/pom.xml clean package -DskipTests"
    exit 2
fi

# The verdict gate proves its own arithmetic before it is trusted to judge a
# run. It costs milliseconds and runs FIRST so a broken gate costs a re-run
# rather than a whole suite. A gate that certifies a run is worth no more than
# the last time anything checked it, and this one shipped a unit error twice.
"$ROOT/build/verdict-gate-test.sh" --quiet \
    || { echo "FATAL: the suite's verdict gate fails its own self-test — refusing to certify a run with it."; exit 2; }

rm -rf "$OUT"; mkdir -p "$OUT"
ln -sfn "$OUT" "$DIST/suite-shards"

# 1. Discover test classes exactly like the boot does (org.jawata.* test
#    bundles, top-level *Test.class).
ALL_CLASSES="$OUT/all-classes.txt"
for jar in "$DIST"/test-bundles/org.jawata.*.jar; do
    unzip -Z1 "$jar" | grep 'Test\.class$' | grep -v '\$' | sed 's|/|.|g; s|\.class$||'
done | sort -u > "$ALL_CLASSES"
TOTAL_CLASSES=$(wc -l < "$ALL_CLASSES")
echo "Discovered $TOTAL_CLASSES test classes across $SHARDS shards"

# 1b. IMPACTED-TEST SELECTION (Stage 6b, G4) — OPT-IN, INNER LOOP ONLY.
#
# THE BINDING SAFETY RULE: selection narrows the INNER loop and nothing else.
# Every checkpoint gate calls this script WITHOUT --impacted and therefore runs
# the full suite. We are not building a false-green machine.
#
# It narrows only when the evidence can carry the claim, and SAYS WHY whenever
# it does not:
#   * no --impacted flag                  -> full (the default, and the gate path)
#   * no resident to ask                  -> full, reason printed
#   * the tool reports no attribution     -> full, reason printed
#   * the tool names zero impacted tests  -> full, reason printed (a diff that
#                                            touches code nothing covers is the
#                                            LAST thing to run narrowly)
#   * the artifact is PARTIAL             -> full, reason printed. jawata's own
#                                            suite splits between plain-JVM tests
#                                            and tests that need the Eclipse
#                                            workspace; the forked runner cannot
#                                            run the latter, so their coverage is
#                                            absent from any artifact it produced.
#                                            Narrowing on evidence that structurally
#                                            cannot see half the suite is exactly
#                                            the false green this rule forbids.
if [ "$IMPACTED" = "1" ]; then
    reason=""
    if [ -z "${JAWATA_URL:-}" ] || [ -z "${JAWATA_TOKEN:-}" ]; then
        reason="no JAWATA_URL/JAWATA_TOKEN — nothing to ask for attribution"
    else
        SEL="$OUT/impacted.txt"
        if ! "$ROOT/build/impacted-tests.sh" > "$SEL" 2> "$OUT/impacted.err"; then
            reason="$(cat "$OUT/impacted.err" | tail -1)"
        elif [ ! -s "$SEL" ]; then
            reason="the tool named no impacted test classes"
        else
            # Intersect with what actually exists in this dist: a stale
            # attribution row naming a deleted class must not shrink the run.
            comm -12 "$SEL" "$ALL_CLASSES" > "$OUT/impacted-live.txt"
            SEL_COUNT=$(wc -l < "$OUT/impacted-live.txt")
            if [ "$SEL_COUNT" -eq 0 ]; then
                reason="none of the impacted classes exist in this dist"
            else
                cp "$OUT/impacted-live.txt" "$ALL_CLASSES"
                TOTAL_CLASSES="$SEL_COUNT"
                echo "IMPACTED SELECTION: running $SEL_COUNT of the discovered classes"
            fi
        fi
    fi
    if [ -n "$reason" ]; then
        echo "IMPACTED SELECTION UNAVAILABLE -> running the FULL suite. Reason: $reason"
    fi
fi

# 2. Greedy balance by measured time (longest-first onto the lightest shard).
awk -v shards="$SHARDS" -v deflt="$DEFAULT_SECS" -v timings="$TIMINGS" -v out="$OUT" '
BEGIN {
    while ((getline line < timings) > 0) {
        n = split(line, f, " ");
        if (n >= 2) { t = f[1]; sub(/s$/, "", t); byName[f[2]] = t + 0; }
    }
}
{
    simple = $0; sub(/.*\./, "", simple);
    secs = (simple in byName) ? byName[simple] : deflt;
    names[NR] = $0; times[NR] = secs; total++;
}
END {
    # selection sort desc (small N)
    for (i = 1; i <= total; i++) idx[i] = i;
    for (i = 1; i <= total; i++)
        for (j = i + 1; j <= total; j++)
            if (times[idx[j]] > times[idx[i]]) { tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp; }
    for (s = 0; s < shards; s++) load[s] = 0;
    for (i = 1; i <= total; i++) {
        best = 0;
        for (s = 1; s < shards; s++) if (load[s] < load[best]) best = s;
        load[best] += times[idx[i]];
        print names[idx[i]] >> (out "/shard-" best ".txt");
    }
    for (s = 0; s < shards; s++)
        printf "shard %d: %ds planned\n", s, load[s] > "/dev/stderr";
}' "$ALL_CLASSES"

# 3. Launch the shards in parallel. Each JVM is told its FAIR SHARE of the
#    cores — otherwise every shard sizes GC/JIT/pool threads for the whole
#    machine and they thrash each other (measured: 6 unpinned shards were
#    SLOWER than 4).
CORES=$(nproc)
SLICE=$(( CORES / SHARDS )); [ "$SLICE" -lt 2 ] && SLICE=2
JVM_OPTS="${JVM_OPTS:--XX:ActiveProcessorCount=$SLICE -Xmx3g}"
# Sprint 27 D1: the embedder's Vector API backend, GUARDED — a JVM given
# --add-modules for a module it lacks refuses to start (exit 1), so probing
# beats assuming. Without it the tests still pass on the scalar backend; the
# suite is simply slower where it embeds.
#
# JAWATA_VECTOR=0 forces the scalar path. That exists so the flagless run is
# actually runnable: it is the configuration every user without the flag gets,
# and a suite that has only ever run one way has not tested the other.
if [ "${JAWATA_VECTOR:-1}" = "0" ]; then
    echo "note: JAWATA_VECTOR=0 — running the SCALAR backend deliberately" >&2
elif java --add-modules jdk.incubator.vector -version >/dev/null 2>&1; then
    JVM_OPTS="--add-modules jdk.incubator.vector $JVM_OPTS"
else
    echo "note: jdk.incubator.vector is not available in this JVM — the suite" \
         "runs on the scalar backend (correct, slower)" >&2
fi
START=$(date +%s)
PIDS=()
for s in $(seq 0 $((SHARDS - 1))); do
    java $JVM_OPTS \
         -Djawata.test.fixtures="$FIXTURES" \
         -Djawata.bundle.pools.machine=off \
         -Djawata.test.classlist="$OUT/shard-$s.txt" \
         -jar "$DIST/jawata.jar" -runTests > "$OUT/shard-$s.log" 2>&1 &
    PIDS+=($!)
done

FAILED_SHARDS=0
for i in "${!PIDS[@]}"; do
    wait "${PIDS[$i]}" || FAILED_SHARDS=$((FAILED_SHARDS + 1))
done
WALL=$(( $(date +%s) - START ))

# 4. Merge the summaries.
TOT=0; PASS=0; FAIL=0; ABORT=0; SKIP=0; UNLOAD=0; SUMMARIES=0
for s in $(seq 0 $((SHARDS - 1))); do
    line=$(grep 'SPIKE-TESTS' "$OUT/shard-$s.log" | tail -1)
    if [ -z "$line" ]; then
        echo "shard $s: NO SUMMARY (crashed?) — tail:"; tail -5 "$OUT/shard-$s.log"
        continue
    fi
    SUMMARIES=$((SUMMARIES + 1))
    echo "shard $s: $line"
    TOT=$((TOT + $(sed 's/.*total=\([0-9]*\).*/\1/' <<< "$line")))
    PASS=$((PASS + $(sed 's/.*succeeded=\([0-9]*\).*/\1/' <<< "$line")))
    FAIL=$((FAIL + $(sed 's/.*failed=\([0-9]*\).*/\1/' <<< "$line")))
    ABORT=$((ABORT + $(sed 's/.*aborted=\([0-9]*\).*/\1/' <<< "$line")))
    SKIP=$((SKIP + $(sed 's/.*skipped=\([0-9]*\).*/\1/' <<< "$line")))
    UNLOAD=$((UNLOAD + $(sed 's/.*unloadable=\([0-9]*\).*/\1/' <<< "$line")))
done

echo "SHARDED-SUITE shards=$SHARDS wall=${WALL}s total=$TOT succeeded=$PASS failed=$FAIL aborted=$ABORT skipped=$SKIP unloadable=$UNLOAD"
[ "$SUMMARIES" -eq "$SHARDS" ] || { echo "FAILED: $((SHARDS - SUMMARIES)) shard(s) produced no summary"; exit 3; }

# Every PLANNED test must have produced a verdict — the runner's blind spot is a
# container-level throw, which belongs to no test and so shows up in no bucket.
# The gate lives in its own script so it can be exercised with counters that a
# real run almost never produces (see build/verdict-gate-test.sh); inline, its
# unloadable-vs-total unit error was unreachable by any test and shipped.
"$ROOT/build/verdict-gate.sh" "$TOT" "$PASS" "$FAIL" "$ABORT" "$SKIP" "$OUT/shard-*.log" || exit $?

[ "$FAIL" -eq 0 ] && [ "$UNLOAD" -eq 0 ] || exit 1
exit 0
