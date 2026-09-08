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
#
# THE REFERENCE IS THE OLDEST ARTIFACT, NOT jawata.jar, and the difference is a
# defect this guard shipped with. jawata.jar is the boot LAUNCHER — the module
# that changes least often — so an incremental build that correctly rebuilds the
# changed bundle leaves it untouched and the guard called the whole dist stale.
# Measured 2026-09-01: bundles/org.jawata.core-4.0.0.jar at 14:52:03 against
# jawata.jar at 12:46:25, after a build that was entirely correct, and the run
# was refused.
#
# The proxy was unsound in BOTH directions, which is the part that matters. It
# false-REFUSES as above; it would also have PASSED a dist whose launcher was
# rebuilt while a bundle was not, which is precisely the silent-wrong-code case
# the guard exists to catch.
#
# The sound question is "is any source newer than the artifact that contains
# it", and computing that exactly means mapping every file to its bundle. The
# OLDEST artifact is the conservative answer to the same question: nothing can be
# stale if no source is newer than the oldest thing in the dist. It can refuse a
# dist that is actually fine (a source newer than the oldest jar but older than
# its own), and that direction is the safe one — a needless rebuild costs
# minutes, a green about the previous build costs a sprint.
# OUR OWN jars only. Third-party jars are COPIED into the dist and keep their
# upstream timestamps, so the oldest jar in the tree is apiguardian-api-1.1.2.jar
# dated 2021 — which says nothing whatever about when we last built. This
# guard's own control caught it: with every jar considered, the repaired guard
# REFUSED a correctly built tree, which is worse than the defect it replaced.
OLDEST_ARTIFACT=$(find "$DIST" -name 'org.jawata.*.jar' -type f -printf '%T@ %p\n' 2>/dev/null \
                    | sort -n | head -1 | cut -d' ' -f2-)
[ -n "$OLDEST_ARTIFACT" ] || OLDEST_ARTIFACT="$DIST/jawata.jar"
STALE_SRC=$(find "$ROOT" \( -name '*.java' -o -path '*/resources/*' \) \
                -type f \
                -not -path '*/target/*' \
                -not -path '*/test-resources/*' \
                -not -path '*/.git/*' \
                -newer "$OLDEST_ARTIFACT" -print -quit 2>/dev/null)
if [ -n "$STALE_SRC" ]; then
    echo "FATAL: the dist is OLDER than the source, so this run would test the PREVIOUS build."
    echo "  source newer than the dist: $STALE_SRC"
    echo "  oldest dist artifact:       $OLDEST_ARTIFACT"
    echo "  built:                      $(date -r "$OLDEST_ARTIFACT" '+%Y-%m-%d %H:%M:%S')"
    echo "Rebuild first:  mvn -f build/pom.xml clean package -DskipTests"
    exit 2
fi

rm -rf "$OUT"; mkdir -p "$OUT"
ln -sfn "$OUT" "$DIST/suite-shards"

# mcp#44 — WHICH /tmp DIRECTORIES EXISTED BEFORE THIS RUN. A SET, not a timestamp.
#
# The first version compared mtimes (`find -newer <marker>`) and was WRONG in exactly the
# state this machine is normally in: a directory's mtime updates when its CONTENTS change, so
# a directory created long before the marker matches `-newer` the moment anything writes into
# it. Measured: three jawata residents were live during this checkpoint with jawata-test-ws
# directories modified inside the hour, and a concurrent suite run would have swept a LIVE
# resident's working directory. The claim that pre-existing debris was "untouched by
# construction" was false.
#
# A recorded set cannot have that failure: a directory is this run's if and only if its name
# was absent when the run began. It is taken HERE, before the two gates below launch their own
# JVMs, so what those gates leave behind is swept with everything else rather than being
# permanently exempt.
TMP_BEFORE="$OUT/.tmp-before"
"$ROOT/build/tmp-sweep.sh" snapshot /tmp "$TMP_BEFORE"

# The verdict gate proves its own arithmetic before it is trusted to judge a
# run. It costs milliseconds and runs FIRST so a broken gate costs a re-run
# rather than a whole suite. A gate that certifies a run is worth no more than
# the last time anything checked it, and this one shipped a unit error twice.
"$ROOT/build/verdict-gate-test.sh" --quiet \
    || { echo "FATAL: the suite's verdict gate fails its own self-test — refusing to certify a run with it."; exit 2; }

# mcp#44 — and the sweep proves its own rule before it is trusted to DELETE anything. Its
# first version lived inline here, which meant nothing short of a whole suite run could
# exercise it: it shipped with no control at all, and carried a defect that would have removed
# a live process's working directory. A rule that deletes has to be the best-tested thing on
# this path, not the least.
"$ROOT/build/tmp-sweep-test.sh" --quiet \
    || { echo "FATAL: the /tmp sweep fails its own self-test — refusing to run it over /tmp."; exit 2; }

# mcp#52 — and the same argument one level out: the verdict gate's arithmetic is proved
# above against INVENTED counters, which says nothing about whether the runner ever
# PRODUCES them. mcp#54 and mcp#51 each shipped a container-level marker and counter that
# no class in the suite could exercise, and each recorded the gap rather than implying
# coverage. This drives classes that really abort and really fail a container, through the
# real runner, and costs one short JVM. It runs HERE, on the gate path, for the reason a
# diligence mechanism offered as an opt-in is chosen by nobody.
"$ROOT/build/container-marker-gate.sh" --quiet \
    || { echo "FATAL: the runner does not report container aborts/failures correctly — refusing to certify a run with it."; exit 2; }

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
# ---------------------------------------------------------------- load guard
#
# REFUSE to start on a machine that is already busy, because the failures this
# produces are indistinguishable from regressions.
#
# Measured 2026-08-31/09-01: a whole-corpus analysis run was started beside this
# suite. Twice it went red, and each time the failing tests were DIFFERENT and
# each looked like a real defect — a CPU-profiler test whose own hot loop lost
# the top sample to an unrelated JDK method, and family sweeps timing out at
# their 120s ceiling. Load average across the window was 7.76; on a quiet
# machine the identical tree was 0 failed, twice.
#
# The knowledge form of this rule ("do not run heavy analysis beside the suite")
# cannot fire: it is needed exactly when the person is busy doing the other
# thing. So the gate reads the machine instead.
#
# NOT A LOAD THRESHOLD, and the first version of this guard was one — measured
# and discarded. It refused above half the cores; this host has 20, so it
# refused above 10, and the load across the incident window was 7.76. The guard
# would have gone GREEN on the very run that motivated it. A number chosen for
# feeling conservative is a guess wearing a rule, and that is the same defect
# the release gate was repaired for earlier in this sprint.
#
# The real condition is not "the machine is busy" in the abstract — it is
# "another heavy job of ours is running": a corpus analysis, a Maven build, a
# linter over a whole tree. That is a fact you can look up, with no threshold to
# tune and nothing to be wrong about.
#
# JAWATA_SUITE_IGNORE_LOAD=1 proceeds anyway, and says so rather than passing
# silently.
# THE PATTERNS ARE DELIBERATELY SPECIFIC, and the loose version was caught by
# this guard's own control run. `maven` alone matched the editor's sandbox proxy
# processes, because their command line carries a network allow-list containing
# the string "maven.org" — so the guard refused on a COMPLETELY IDLE machine,
# which is the failure that makes a control useless (a guard that always
# refuses is turned off within a day).
#
# Each token below is a launcher class, a distribution directory or a compiler
# flag: strings that appear when the tool is actually RUNNING and cannot appear
# inside a configuration blob.
COMPETITORS=$(pgrep -a -f \
    'classworlds\.launcher\.Launcher|pmd-bin-|net\.sourceforge\.pmd|Xplugin:ErrorProne|build/calibration' \
    2>/dev/null | grep -v 'run-suite\|cursorsandbox\|--policy-json' | head -4)
if [ -n "$COMPETITORS" ]; then
    if [ "${JAWATA_SUITE_IGNORE_LOAD:-0}" = "1" ]; then
        echo "note: heavy jobs are running and JAWATA_SUITE_IGNORE_LOAD=1 was set." >&2
        echo "      Timing-sensitive failures in this run are NOT evidence about the tree." >&2
    else
        echo "REFUSED: another heavy job is running, and this suite has" >&2
        echo "timing-sensitive cells (CPU sampling; sweeps with a 120s ceiling)." >&2
        echo "Beside a competing job they fail in ways that read exactly like" >&2
        echo "regressions — measured twice, different tests each time, clean on a" >&2
        echo "quiet machine both times. The diagnosis costs more than the wait." >&2
        echo "" >&2
        printf '  %s\n' "$COMPETITORS" >&2
        echo "" >&2
        echo "Wait for them, or set JAWATA_SUITE_IGNORE_LOAD=1 to proceed knowing" >&2
        echo "a red result may be about the contention and not the code." >&2
        exit 2
    fi
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
TOT=0; PASS=0; FAIL=0; ABORT=0; SKIP=0; UNLOAD=0; CABORT=0; CFAIL=0; SUMMARIES=0
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
    # mcp#51 — A MISSING FIELD MUST NOT READ AS ZERO, and without this it does. If a
    # summary line predates either counter (an old jawata.jar left in the dist), the
    # GREEDY sed below finds no match and returns THE WHOLE LINE; the arithmetic then
    # fails on it, and because this script sets no `-e` the assignment is simply skipped
    # and the counter keeps its initial 0. A gate would then report the healthy value for
    # a run it could not measure — which is the shape of the hole this issue closes, so
    # it is refused here rather than defaulted.
    for field in containersAborted containersFailed; do
        case "$line" in
            *"$field="*) ;;
            *) echo "FATAL: shard $s's summary carries no $field= — the dist is older than" \
                    "this script. Rebuild it (mvn install). Read as 0, a missing counter" \
                    "is indistinguishable from a clean run."; exit 2 ;;
        esac
    done
    # mcp#54. THE CASE OF ONE LETTER IS LOAD-BEARING HERE, and it is worth saying so
    # because nothing else in this file would tell you. Every pattern above is GREEDY,
    # so `.*aborted=` matches the RIGHTMOST occurrence on the line — and the only
    # reason it does not swallow `containersAborted=` is that JUnit's name capitalises
    # the A. Rename the field to `containers_aborted=` and ABORT silently starts
    # reading the container count instead.
    CABORT=$((CABORT + $(sed 's/.*containersAborted=\([0-9]*\).*/\1/' <<< "$line")))
    # mcp#51 — and the SAME accident is now load-bearing twice. `.*failed=` above is
    # greedy too, and `containersFailed=` is safe from it only because JUnit capitalises
    # the F. Two fields, two capitals, one convention nobody here controls: if either
    # name is ever spelled lower-case, the counter ABOVE silently starts reading the
    # container count and the suite reports a failure total it did not measure.
    CFAIL=$((CFAIL + $(sed 's/.*containersFailed=\([0-9]*\).*/\1/' <<< "$line")))
done

echo "SHARDED-SUITE shards=$SHARDS wall=${WALL}s total=$TOT succeeded=$PASS failed=$FAIL aborted=$ABORT skipped=$SKIP unloadable=$UNLOAD containersAborted=$CABORT containersFailed=$CFAIL"
[ "$SUMMARIES" -eq "$SHARDS" ] || { echo "FAILED: $((SHARDS - SUMMARIES)) shard(s) produced no summary"; exit 3; }

# Every PLANNED test must have produced a verdict — the runner's blind spot is a
# container-level throw, which belongs to no test and so shows up in no bucket.
# The gate lives in its own script so it can be exercised with counters that a
# real run almost never produces (see build/verdict-gate-test.sh); inline, its
# unloadable-vs-total unit error was unreachable by any test and shipped.
"$ROOT/build/verdict-gate.sh" "$TOT" "$PASS" "$FAIL" "$ABORT" "$SKIP" "$OUT/shard-*.log" "$CABORT" "$CFAIL" || exit $?

# mcp#45 — AND EVERY ABORT MUST BE A DECISION SOMEBODY MADE. The gate above proves
# every planned test produced a verdict; it says nothing about whether the aborts
# among them are ones we agreed to. build/abort-budget.sh has existed and worked
# since Sprint 28a and NOTHING CALLED IT on this path, so the count was printed and
# never checked — a skip could join the run and no one would hear about it.
#
# That is not hypothetical, and wiring it is what showed so: run against the shard
# logs of the suite that had just passed, it named an UNBUDGETED abort
# ("[mcp#26 ATTRIBUTION] NOT RUN") which had been firing in every local run
# unremarked. Its reason is now committed in build/expected-aborts.<os>, which is
# the decision this gate exists to force.
"$ROOT/build/abort-budget.sh" "$OUT" || exit $?

# mcp#51 — A FAILED CONTAINER IS A FAILURE, AND IT IS THE ONE THAT BALANCES.
# An @AfterAll that throws blows up after every test in the class has already run
# and reported, so PASS+FAIL+ABORT+SKIP == TOT still holds, the verdict gate above is
# satisfied, and testsFailed is zero. Every gate on this path was therefore green over
# a class whose teardown died — the only trace a `^^ FAILED` line in a shard log
# nobody greps. It is NOT folded into the verdict identity for the same units reason
# containersAborted is not: one failed CONTAINER is not one lost TEST. It is an
# independent failure condition, and it belongs here beside the other two.
if [ "$CFAIL" -gt 0 ]; then
    echo "FAILED: $CFAIL container(s) FAILED — a class-level throw (@BeforeAll," \
         "@AfterAll, or a class initializer). The tests themselves may all have" \
         "passed; the class still blew up. Find it in the shard logs:"
    echo "    grep -n '\\^\\^ FAILED' $OUT/shard-*.log"
fi
[ "$FAIL" -eq 0 ] && [ "$UNLOAD" -eq 0 ] && [ "$CFAIL" -eq 0 ] || {
    # mcp#44: a FAILED run keeps its debris. Those directories are frequently the only
    # evidence of what the failure did, and destroying evidence to reclaim disk is the
    # wrong trade every time.
    echo "note: this run's /tmp working directories were KEPT for diagnosis (the run failed)."
    exit 1
}

# mcp#44 — SWEEP THIS RUN'S OWN /tmp DEBRIS. Measured 2026-09-08: 8886 jawata-* directories,
# 51 GB, still accumulating; the issue measured 120 GB over four days. On a distro where
# /tmp is a tmpfs this is RAM, and the suite starts failing with no-space errors that read
# like product defects.
#
# THIS IS A BACKSTOP, NOT THE CURE, and the difference is worth stating: the creators are
# spread across the debug/profile tests that launch target JVMs (each launch makes one to
# three directories), and each of those still owns its own delete. What a backstop buys that
# per-test cleanup cannot is the run that CRASHES half way, which leaks whatever it had made
# — the issue says so in as many words. What it does NOT cover is a developer running one
# test from an IDE; that path never comes through here.
#
# It is scoped three ways so it can only remove what this run made:
#   - ABSENT from the name set recorded before the run started;
#   - directly in /tmp, never recursively;
#   - never jawata-runtime, which is the persistent artifact store rather than run debris.
#
# THE FIRST VERSION USED `find -newer` AND WAS WRONG, in exactly the state this machine is
# normally in. A directory's mtime moves when its CONTENTS change, so a directory created long
# before the run matched `-newer` as soon as anything wrote into it — and with jawata residents
# live (they keep jawata-test-ws and jawata-boot-config directories under /tmp), a concurrent
# run would have deleted a LIVE resident's working directory. Comparing NAMES against a
# recorded set cannot fail that way: a directory is this run's if and only if its name was not
# there when the run began.
if [ "${JAWATA_KEEP_TMP:-0}" = "1" ]; then
    echo "note: JAWATA_KEEP_TMP=1 — leaving this run's /tmp working directories in place"
else
    "$ROOT/build/tmp-sweep.sh" sweep /tmp "$TMP_BEFORE"
fi
exit 0
