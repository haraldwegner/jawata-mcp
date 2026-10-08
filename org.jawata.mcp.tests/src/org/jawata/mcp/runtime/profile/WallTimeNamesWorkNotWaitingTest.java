package org.jawata.mcp.runtime.profile;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S15 (D34 / M13) — <b>a thread WAITING FOR WORK is not a method that is slow.</b>
 *
 * <p>The wall dimension attributed every blocking event's full duration to its TOP frame, which
 * for a parked thread is {@code Unsafe#park}. Every parked thread in the process therefore
 * collapsed into one row, and that row accumulated roughly N x T over a recording of length T
 * with N idle threads — so idle rows outranked work rows <b>arithmetically</b>, not by accident,
 * and harder the bigger the pool.</p>
 *
 * <p><b>Measured before the repair, and the number is what makes it a defect rather than a
 * preference:</b> over a 12-second sample of an essentially idle resident, {@code Unsafe#park}
 * was attributed <b>210002 ms against 12000 ms of recording — 17.5x the recording's own
 * length</b>. No single method can occupy more wall time than the recording lasted, so that
 * figure is reachable only by summing many threads' parks into one frame. Both ranked rows were
 * park/wait and no real method appeared at all.</p>
 *
 * <p>And the method's own catalogued job states the intent it was failing: a method that mostly
 * sits parked or in I/O "shows up ALONGSIDE one that mostly computes". Alongside. Parked time is
 * meant to be visible, not dominant.</p>
 *
 * <h2>Why this test makes its own recording</h2>
 *
 * <p>A {@code RecordedEvent} cannot reasonably be constructed, and a committed {@code .jfr}
 * fixture would be a binary whose contents depend on the JVM that produced it. So the recording
 * is produced here, in this JVM, from two waits chosen to have the two stack SHAPES the rule
 * turns on — and nothing about either depends on thread-pool timing.</p>
 */
class WallTimeNamesWorkNotWaitingTest {

    /**
     * THE RULE, as a pure function — innermost frame first, which is JFR's own order.
     *
     * <p>Every case here is a real stack shape rather than an invented one: the idle case is
     * what a ForkJoinPool worker waiting for work actually looks like, and the blocked case is
     * what a program method waiting on a lock actually looks like.</p>
     */
    @Test
    @DisplayName("M13: a stack with no program frame is idle; otherwise the blocked method is named")
    void the_rule_reads_the_stack_rather_than_a_list_of_pool_method_names() {
        List<String> idlePoolWorker = List.of(
            "jdk.internal.misc.Unsafe#park",
            "java.util.concurrent.locks.LockSupport#park",
            "java.util.concurrent.ForkJoinPool#awaitWork",
            "java.util.concurrent.ForkJoinPool#runWorker",
            "java.util.concurrent.ForkJoinWorkerThread#run");

        List<String> blockedInWork = List.of(
            "jdk.internal.misc.Unsafe#park",
            "java.util.concurrent.locks.LockSupport#park",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer#acquire",
            "java.util.concurrent.locks.ReentrantLock#lock",
            "org.jawata.mcp.knowledge.H2ExperienceStore#put",
            "org.jawata.mcp.tools.ExperienceTool#execute",
            "java.lang.Thread#run");

        List<String> idleTimerThread = List.of(
            "java.lang.Object#wait0",
            "java.lang.Object#wait",
            "java.util.TimerThread#mainLoop",
            "java.util.TimerThread#run");

        assertAll(
            () -> assertNull(JfrParser.innermostProgramFrame(idlePoolWorker),
                "a pool worker waiting FOR work has no work on its stack, which is the whole"
                    + " definition of idle — every frame is the JVM's own"),
            () -> assertEquals("org.jawata.mcp.knowledge.H2ExperienceStore#put",
                JfrParser.innermostProgramFrame(blockedInWork),
                "a thread blocked IN work carries the blocked method under the park, and the"
                    + " INNERMOST program frame is that method rather than its caller"),
            () -> assertNull(JfrParser.innermostProgramFrame(idleTimerThread),
                "the same holds for a monitor wait, not only a park"),
            () -> assertNull(JfrParser.innermostProgramFrame(List.of()),
                "and a stack with no frames at all is not attributable to anything"));
    }

    /**
     * THE CONTROL FOR THE RULE'S SHAPE. Without it, "the innermost program frame" would be
     * equally satisfied by a function returning the LAST program frame, or the first frame of
     * any kind — and the blocked case above happens to have its program frames adjacent.
     */
    @Test
    @DisplayName("the rule returns the INNERMOST program frame, not the outermost and not frame 0")
    void the_rule_is_not_satisfied_by_the_outermost_frame_or_by_the_top_of_stack() {
        List<String> twoProgramFrames = List.of(
            "jdk.internal.misc.Unsafe#park",
            "org.jawata.Inner#blockedHere",
            "java.util.concurrent.ThreadPoolExecutor#runWorker",
            "org.jawata.Outer#submittedIt");

        String answer = JfrParser.innermostProgramFrame(twoProgramFrames);

        assertAll(
            () -> assertEquals("org.jawata.Inner#blockedHere", answer,
                "the method that was BLOCKED is the inner one; the outer one merely started it"),
            () -> assertFalse("jdk.internal.misc.Unsafe#park".equals(answer),
                "and it is not frame 0, which is the defect being repaired"));
    }

    /**
     * END TO END, over a recording this test produces — because the two halves of M13 are a
     * property of the RESPONSE and the response is what a caller reads.
     *
     * <p>Two waits with the two stack shapes, neither depending on pool timing:</p>
     * <ul>
     *   <li>a park called from a METHOD OF THIS CLASS — a program frame sits under it, so it
     *       must be named and must carry a share;</li>
     *   <li>a {@link Timer}, whose thread waits inside {@code java.util.TimerThread#mainLoop}
     *       with an all-JDK stack <b>by construction</b> — no application frame exists on it,
     *       so it is idle and must be reported BESIDE the ranking rather than in it.</li>
     * </ul>
     *
     * <p>The Timer is deliberate rather than convenient: a thread pool would also produce an
     * all-JDK park, but only after its workers happen to go idle, and a test whose outcome
     * depends on that is a test that reports machine load as a defect. A Timer's thread waits
     * the moment it exists.</p>
     */
    @Test
    @DisplayName("M13 both halves, on a real recording: work is named with its share, waiting is beside it")
    void the_response_names_the_blocked_method_with_a_share_and_reports_waiting_separately()
            throws Exception {
        Path jfr = Files.createTempFile("s15-wall-", ".jfr");
        Timer idleByConstruction = new Timer("s15-idle", true);
        try {
            Recording recording = new Recording();
            recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1));
            recording.enable("jdk.JavaMonitorWait").withThreshold(Duration.ofMillis(1));
            recording.start();

            // THE TIMER'S WAIT MUST END INSIDE THE WINDOW OR IT IS NEVER EMITTED, and this is
            // the version of the fixture that works. JFR records a wait when it COMPLETES,
            // because that is when the duration is known — so a Timer with nothing scheduled
            // waits indefinitely, the wait is still in progress when the recording stops, and
            // no event exists at all. The first version of this case relied on that and its
            // idle clauses were `assertNotNull`, which pass at zero; a mutation showed the
            // clauses reddening for a reason unrelated to idleness, which is what exposed it.
            // Scheduling a task makes the thread wake, which ENDS the preceding wait — and the
            // wait's own stack is still TimerThread#mainLoop, all JDK, which is the shape under
            // test. The task body is irrelevant and runs after the wait it closes.
            idleByConstruction.schedule(new java.util.TimerTask() {
                @Override public void run() {
                    // nothing: its only job is to end the wait that preceded it
                }
            }, 60);

            parkInsideAMethodOfThisClass();

            recording.stop();
            recording.dump(jfr);
            recording.close();

            Map<String, Object> result = JfrParser.wallHotspots(jfr, 0, 50);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");

            String thisTest = WallTimeNamesWorkNotWaitingTest.class.getName();
            Map<String, Object> mine = rows.stream()
                .filter(r -> String.valueOf(r.get("symbol")).startsWith(thisTest))
                .findFirst().orElse(null);

            assertAll(
                // PROOF OF LIFE. Without it every clause below could be satisfied by a parser
                // that returned nothing at all.
                () -> assertNotNull(mine,
                    () -> "the park taken inside this class's own method must be NAMED, since a"
                        + " program frame sits directly under it. Rows: " + rows),
                // HALF 1. The symbol every parked thread used to collapse into.
                () -> assertTrue(rows.stream().noneMatch(
                        r -> String.valueOf(r.get("symbol")).startsWith("jdk.internal.misc.Unsafe")),
                    () -> "no row may be the park PRIMITIVE — that is the frame every blocked"
                        + " thread in the process shares, and ranking it is the defect. Rows: "
                        + rows),
                () -> assertTrue(rows.stream().noneMatch(
                        r -> String.valueOf(r.get("symbol")).startsWith("java.util.TimerThread")),
                    () -> "nor may a thread that only ever waited for work be ranked at all,"
                        + " whichever of its frames you pick. Rows: " + rows),
                // HALF 2. The measure asks for the method reported AT ITS SHARE.
                () -> assertNotNull(mine == null ? null : mine.get("sharePercent"),
                    () -> "every row carries its share of the ranked total: " + mine),
                // THE COUNTS SIT BESIDE THE TIME, IN THEIR OWN UNITS. Without any count, a row
                // built from ONE long block and a row built from four hundred short ones rank
                // identically while being different facts about the program. And the C15 audit
                // caught the first version publishing ONE field named `samples` that counted
                // blocking EVENTS on one branch and execution SAMPLES on the other — one name
                // over two units, which is the same relabeling shape as a defect this product
                // already shipped once. Neither is an invocation count; those need a live trace
                // and stay with action=call_counts.
                () -> assertNotNull(mine == null ? null : mine.get("blockingEvents"),
                    () -> "the row says how many BLOCKING EVENTS it summed: " + mine),
                () -> assertNotNull(mine == null ? null : mine.get("cpuSamples"),
                    () -> "and how many on-CPU SAMPLES, separately: " + mine),
                () -> assertTrue(mine != null
                        && ((Number) mine.get("samples")).longValue()
                            == ((Number) mine.get("blockingEvents")).longValue()
                                + ((Number) mine.get("cpuSamples")).longValue(),
                    () -> "and `samples` is exactly their sum, so the one field the rankability"
                        + " guard reads cannot drift from the two that explain it: " + mine),
                // AND THE SHARE IS A SHARE — a figure that cannot exceed the whole.
                () -> assertTrue(mine == null
                        || ((Number) mine.get("sharePercent")).doubleValue() <= 100.0,
                    () -> "a share above 100% would mean the total it divides by is not the"
                        + " total: " + mine),
                // WAITING IS REPORTED, NEVER DROPPED. Excluding it silently would replace one
                // dishonest answer with a quieter one.
                //
                // A COUNT RATHER THAN assertNotNull, and the difference is the whole point: a
                // non-null check passes at ZERO, so it would have been satisfied by a parser
                // that classified nothing as idle — including by the pre-repair one, which had
                // no idle path at all. This clause requires that the Timer's all-JDK wait was
                // actually SEEN and actually counted there.
                () -> assertTrue(result.get("idleEvents") != null
                        && ((Number) result.get("idleEvents")).longValue() > 0,
                    () -> "the Timer's thread waited with an all-JDK stack and its wait ended"
                        + " inside the window, so at least one wait must be counted as idle —"
                        + " at zero this case proves only the work half: " + result),
                () -> assertTrue(result.get("idleWallMillis") != null
                        && ((Number) result.get("idleWallMillis")).longValue() > 0,
                    () -> "and the waiting carries real milliseconds beside the ranking rather"
                        + " than merely a key: " + result),

                // C15 AUDIT BLOCKER 3 — THE TWO LOAD-BEARING PROPERTIES WERE ASSERTED AND
                // GATED BY NOTHING. The clauses were `sharePercent != null` and `<= 100`, and
                // the audit named two mutations that stay fully green under them: restoring
                // idle into `totalWallMillis` (the pre-repair meaning), and dividing the share
                // by twice the total. Both leave every share non-null and under 100. So "the
                // share is OF THE RANKED TOTAL" — the sentence the code makes twice — was
                // prose. These two clauses are what hold it.
                () -> {
                    double sum = rows.stream()
                        .map(r -> r.get("sharePercent"))
                        .filter(Number.class::isInstance)
                        .mapToDouble(v -> ((Number) v).doubleValue())
                        .sum();
                    assertTrue(sum > 99.0 && sum < 101.0,
                        () -> "every row's share is of ONE total, so over the whole unpaged"
                            + " answer they sum to 100 (+-rounding); " + sum + " means the"
                            + " denominator is not the total these rows came from: " + rows);
                },
                () -> {
                    long ranked = ((Number) result.get("totalWallMillis")).longValue();
                    long idle = ((Number) result.get("idleWallMillis")).longValue();
                    long rowSum = rows.stream()
                        .map(r -> r.get("wallMillis"))
                        .filter(Number.class::isInstance)
                        .mapToLong(v -> ((Number) v).longValue())
                        .sum();
                    assertTrue(Math.abs(ranked - rowSum) <= rows.size(),
                        () -> "totalWallMillis is the RANKED total — the rows' own sum, within"
                            + " per-row truncation — and NOT the old meaning that included"
                            + " idle. ranked=" + ranked + " rowSum=" + rowSum + " idle=" + idle);
                    assertTrue(idle > 0 && ranked > 0,
                        () -> "and the two are genuinely separate quantities here, so the clause"
                            + " above is not passing because one of them is empty: " + result);
                });
        } finally {
            idleByConstruction.cancel();
            Files.deleteIfExists(jfr);
        }
    }

    /**
     * A park whose stack carries a frame of this class, which is what makes it WORK rather than
     * waiting. Its own method so the frame has a name an assertion can look for.
     */
    private static void parkInsideAMethodOfThisClass() {
        LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
    }

    /**
     * C15 AUDIT BLOCKER 4 — <b>a truncated path must say it was truncated</b>, and this was the
     * sharper half of that finding.
     *
     * <p>The tree's {@code render} already declared how many children it cut. {@code hotPath} —
     * named in its own commit as "the deliverable, not the tree", and the thing the profiler
     * seat is instructed to report — ran out at the depth limit and emitted nothing. A path that
     * stops without saying it stopped reads as the end of the chain, which is exactly the lie
     * the marker exists to prevent, on the output that matters most.</p>
     *
     * <p><b>Why it is tested HERE and not through the tool, which is a measurement rather than a
     * preference.</b> A first version asserted it against {@code HotLoopTarget} through
     * {@code profile(action=call_tree)} and FAILED: that fixture's sampled tree is exactly two
     * deep ({@code main -> burnCpu}, burnCpu being the innermost frame of every sample) while
     * the door clamps {@code maxDepth} to at least 1 — so the shallowest accepted request
     * already reaches the end of the chain and nothing is cut. Loosening the clamp to make the
     * assertion pass would have been changing the product to suit the test. A chain of this
     * class's own methods is deep by construction, and the parser applies no clamp.</p>
     */
    @Test
    @DisplayName("a hot path cut at the depth limit says so, and names what is below it")
    void a_truncated_hot_path_says_it_was_truncated() throws Exception {
        Path jfr = Files.createTempFile("s15-tree-", ".jfr");
        try {
            Recording recording = new Recording();
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5));
            recording.start();
            burnThroughAChainOfFourOfOurOwnFrames();
            recording.stop();
            recording.dump(jfr);
            recording.close();

            String entry = WallTimeNamesWorkNotWaitingTest.class.getName()
                + "#burnThroughAChainOfFourOfOurOwnFrames";
            Map<String, Object> deep = JfrParser.callTree(jfr, entry, 10);
            Map<String, Object> cut = JfrParser.callTree(jfr, entry, 1);

            @SuppressWarnings("unchecked")
            List<String> fullPath = (List<String>) deep.get("hotPath");
            @SuppressWarnings("unchecked")
            List<String> shortPath = (List<String>) cut.get("hotPath");

            // THE SAMPLER CAN MISS, AND THAT IS NOT THIS CLAUSE'S SUBJECT — so it ABORTS here
            // rather than failing, which is the convention this suite already uses for its
            // profiling tests (HotspotTest#assumeRankable, and the standing
            // "CPU profile too thin to rank" skip the plan records for a loaded machine).
            //
            // It is in the file because it HAPPENED: the first run of this case, immediately
            // after a clean build with the machine still loaded, returned an EMPTY path from
            // both calls and reported it as "the path was not truncated" — a sampler miss
            // wearing a truncation defect's message. An empty tree means no execution sample
            // named this chain at all, which says nothing whatever about the marker. Three
            // later runs of this class alone were green, and the identical configuration did
            // not reproduce it, so the discriminator is machine load and not configuration.
            Assumptions.assumeFalse(fullPath.isEmpty(),
                "no execution sample named this chain — the sampler missed a 1200ms burn, which"
                    + " happens on a loaded machine. Nothing is asserted about truncation here;"
                    + " this is a skip rather than a pass.");

            assertAll(
                // PROOF OF LIFE, and it is also the precondition that makes the clause below
                // mean anything: the chain really is deeper than the limit being imposed.
                // Reached only once samples exist, per the assumption above.
                () -> assertTrue(fullPath.size() > 2,
                    () -> "the chain must be deeper than the cut, or there is nothing to"
                        + " truncate and this case measures nothing. Path: " + fullPath),
                () -> assertTrue(shortPath.stream().anyMatch(s -> s.contains("cut at maxDepth")),
                    () -> "a path cut at the depth limit SAYS it was cut: " + shortPath),
                () -> assertTrue(shortPath.stream().anyMatch(s -> s.contains("callee(s) below")),
                    () -> "and names how many callees are below the cut, so the reader knows"
                        + " there is more rather than guessing: " + shortPath),
                () -> assertTrue(shortPath.size() < fullPath.size(),
                    () -> "and the cut answer really is shorter than the uncut one, so the"
                        + " marker is not being asserted on a complete path. short="
                        + shortPath + " full=" + fullPath));
        } finally {
            Files.deleteIfExists(jfr);
        }
    }

    // A chain four of our own frames deep, so a depth limit has something to cut. Each level is
    // its own method rather than a loop, because the tree is built from frames and a loop
    // produces one frame however many times it runs.
    private static void burnThroughAChainOfFourOfOurOwnFrames() {
        chainLevelTwo();
    }

    private static void chainLevelTwo() {
        chainLevelThree();
    }

    private static void chainLevelThree() {
        chainLevelFourBurnsTheCpu();
    }

    // 1200ms rather than a few hundred: at a 5ms sampling period a short burn is thin enough
    // that a loaded machine misses it entirely, which is the miss the assumption above names.
    // Raising the burn makes the miss rarer; it does not make it impossible, which is why the
    // assumption stays rather than being replaced by a longer sleep.
    private static void chainLevelFourBurnsTheCpu() {
        long until = System.nanoTime() + Duration.ofMillis(1200).toNanos();
        long spin = 0;
        while (System.nanoTime() < until) {
            spin += 1;
        }
        if (spin == Long.MIN_VALUE) {
            throw new IllegalStateException("unreachable; keeps the loop from being elided");
        }
    }
}
