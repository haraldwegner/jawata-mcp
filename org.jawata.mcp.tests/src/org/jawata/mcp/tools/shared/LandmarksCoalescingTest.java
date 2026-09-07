package org.jawata.mcp.tools.shared;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jawata-mcp#41 — <b>one ranking per workspace, however many callers ask.</b>
 *
 * <p>Ranking is O(source types) full-index reference searches: measured at ~7 minutes and
 * 225 CPU-seconds on a 29-project, 2,646-source workspace, past every client's timeout — and
 * the work did not stop when the client gave up. Two timed-out calls therefore left TWO
 * seven-minute computations running, so the cost grew with the number of people who had
 * already stopped waiting.</p>
 *
 * <p>Coalescing is invisible from the outside: five callers sharing one ranking and five each
 * starting their own return identical landmarks, differing only in what the machine spent.
 * The start counter is the one fact that separates them, which is why it exists.</p>
 */
class LandmarksCoalescingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("mcp#41: concurrent callers share ONE ranking")
    void concurrentCallersDoNotEachStartARanking() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Landmarks.invalidate();
        int before = Landmarks.RANKINGS_STARTED.get();

        // Released together, so they genuinely overlap: staggered calls would be served from
        // the cache and would prove nothing about coalescing.
        int callers = 6;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        List<Landmarks.Ranking> answers = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    Landmarks.Ranking r = Landmarks.of(service, 5);
                    synchronized (answers) {
                        answers.add(r);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
            threads.add(t);
        }
        go.countDown();
        assertTrue(done.await(120, java.util.concurrent.TimeUnit.SECONDS),
            "every caller must be answered — none may hang");
        for (Thread t : threads) {
            t.join();
        }

        assertAll(
            () -> assertEquals(1, Landmarks.RANKINGS_STARTED.get() - before,
                "six concurrent callers must cost ONE ranking, not six"),
            // THE CONTROL. Without it, an implementation that started one ranking and
            // answered nobody would satisfy the clause above — which is the failure mode
            // this issue is actually about.
            () -> assertEquals(callers, answers.size(),
                "every caller got an answer: " + answers.size()),
            // EVERY READY ANSWER IS THE SAME ANSWER. That is what "they shared one ranking"
            // means from the caller's side, and it is the half the start counter cannot see:
            // a counter of 1 beside six DIFFERENT answers would mean the sharing was
            // accidental rather than real.
            //
            // (The first version of this clause read `r.ready() || !r.ready()` — a tautology
            // that could not fail, written into the very test that proves the fix. It is
            // recorded here rather than quietly replaced, because that is the defect class
            // this file's own subject keeps producing.)
            () -> assertEquals(1,
                answers.stream().filter(Landmarks.Ranking::ready)
                    .map(r -> r.landmarks().toString()).distinct().count(),
                "every caller that got a ranking got the SAME ranking: " + answers.size()
                    + " answers"));
    }

    @Test
    @DisplayName("mcp#41 THE CONTROL — a warm ranking starts nothing at all")
    void asecondCallCostsNothing() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Landmarks.invalidate();

        // Drive it to ready, the way a client does: ask again, which joins rather than starts.
        Landmarks.Ranking ready = null;
        for (int attempt = 0; attempt < 60 && (ready == null || !ready.ready()); attempt++) {
            ready = Landmarks.of(service, 5);
            if (!ready.ready()) {
                Thread.sleep(500);
            }
        }
        assertTrue(ready != null && ready.ready(), "the ranking must finish on this fixture");

        int afterWarm = Landmarks.RANKINGS_STARTED.get();
        Landmarks.of(service, 5);
        Landmarks.of(service, 5);
        assertEquals(afterWarm, Landmarks.RANKINGS_STARTED.get(),
            "a cached ranking must not start another — this is what makes asking again free,"
                + " which is what the tool tells callers to do");
    }
}
