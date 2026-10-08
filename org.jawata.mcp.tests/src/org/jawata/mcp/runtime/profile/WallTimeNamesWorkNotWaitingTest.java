package org.jawata.mcp.runtime.profile;

import jdk.jfr.Recording;
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
                // THE COUNT SITS BESIDE THE TIME. Without it a row built from ONE long block and
                // a row built from four hundred short ones rank identically while being
                // different facts about the program — and every other dimension already carried
                // this field, including for the suite's own rankability guard to read. It is the
                // number of EVENTS the milliseconds came from, NOT an invocation count; true
                // per-method counts need a live trace and stay with action=call_counts.
                () -> assertTrue(mine != null && mine.get("samples") != null
                        && ((Number) mine.get("samples")).longValue() > 0,
                    () -> "the row says how many events its milliseconds were summed from: "
                        + mine),
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
                        + " than merely a key: " + result));
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
}
