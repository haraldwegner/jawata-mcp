package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f D4, amended 2026-09-13 — a load ANSWERS FIRST, and its rows are embedded behind
 * the answer.
 *
 * <p><b>What this replaced, and why.</b> This class was {@code DrainBeforeReturnTest}, which
 * asserted the opposite: that a load does not answer until every row it wrote has a meaning
 * vector. That rule was E5's cure for imported rows sitting outside the meaning index with
 * nothing to say so. Its cost was measured twice — a load that re-read a story folder after a
 * loader change stalled 327 s and 364 s, and studio's reload gave up on it after ten seconds.
 * Harald, 2026-09-13: <i>"we should not block anything and run in background"</i>.</p>
 *
 * <p><b>What E5 protected is still asserted, from the other side.</b> The answer must SAY how
 * many rows cannot yet answer by meaning, and the worker the load asked must bring that number
 * to zero with nobody calling again.</p>
 *
 * <p><b>The worker is HELD.</b> The test installs an executor that records the background
 * task instead of running it, so the load's answer is read before any embedding can have
 * happened. Put the wait back into {@code load} and the pending count in that answer is zero
 * instead of twenty — the first claim goes red. Without holding the worker, a fast machine
 * could embed twenty rows before the count was read, and the test would pass or fail on
 * timing.</p>
 */
class LoadEmbedsInBackgroundTest {

    /** Records the background task instead of running it, so the answer can be read first. */
    private static final class HeldWorker implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable task) {
            tasks.add(task);
        }

        synchronized int queued() {
            return tasks.size();
        }

        void runAll() {
            List<Runnable> now;
            synchronized (this) {
                now = new ArrayList<>(tasks);
                tasks.clear();
            }
            now.forEach(Runnable::run);
        }
    }

    /** A story as a human writes one: frontmatter, a claim, and a body in prose. */
    private static void story(Path dir, String name, String summary, String body)
            throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: " + summary
                + "\ntype: domain_fact\n---\n\n" + body + "\n");
    }

    private static long unembedded(Map<String, Object> report) {
        Object v = report.get("unembedded");
        assertTrue(v instanceof Number,
            () -> "the load report must CARRY its pending count, or a caller has no way to"
                + " know its rows are not yet findable by meaning. Report was: " + report);
        return ((Number) v).longValue();
    }

    @Test
    void a_load_answers_before_its_rows_are_embedded_and_they_are_embedded_behind_it(
            @TempDir Path dir, @TempDir Path roots) throws Exception {
        Assumptions.assumeTrue(EmbeddingService.shared().available(),
            "no embedder available — there is nothing to embed in the background");
        HeldWorker held = new HeldWorker();
        Executor previous = BackgroundEmbedding.useForTests(held);
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = new ExperienceMaintenance(store, fqn -> null);
            for (int i = 0; i < 20; i++) {
                story(roots, "story-" + i,
                    "a settled observation about batch number " + i,
                    "The body, in prose, saying what happened and what to do about it.");
            }

            Map<String, Object> report = maintenance.load(roots, true);

            assertEquals(20, report.get("loaded"),
                () -> "the control: twenty files must actually have loaded, or a pending count"
                    + " of twenty proves nothing about who embeds them — " + report);
            assertEquals(20L, unembedded(report),
                () -> "THE LOAD MUST NOT WAIT FOR VECTORS. The worker has not run, so every row"
                    + " this load wrote is still pending when it answers; a count of zero here"
                    + " means the load embedded them itself before answering: " + report);
            assertEquals("background", report.get("embedding"),
                () -> "and the answer must say the vectors are being computed behind it: "
                    + report);
            assertEquals(1, held.queued(),
                () -> "the load must have asked the background worker, exactly once");

            held.runAll();

            assertEquals(0L, EmbeddingIndex.forStore(store).remainingUnembedded(),
                "the pass the load asked for must bring every row it wrote to a vector — a"
                    + " row left here is stored and never findable by meaning, which is the"
                    + " defect E5 was opened for");
        } finally {
            BackgroundEmbedding.useForTests(previous);
        }
    }

    @Test
    void requests_coalesce_while_a_pass_is_queued_and_queue_again_once_it_has_run(
            @TempDir Path dir) {
        Assumptions.assumeTrue(EmbeddingService.shared().available(),
            "no embedder available — there is no index to ask for a pass");
        HeldWorker held = new HeldWorker();
        Executor previous = BackgroundEmbedding.useForTests(held);
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            EmbeddingIndex index = EmbeddingIndex.forStore(store);

            BackgroundEmbedding.request(index);
            BackgroundEmbedding.request(index);
            assertEquals(1, held.queued(),
                "a second request while the first pass is still queued must add nothing: that"
                    + " pass has not started, so it will see the second write's rows too");

            held.runAll();
            BackgroundEmbedding.request(index);
            assertEquals(1, held.queued(),
                "once a pass has run, the next write must queue another — otherwise its rows"
                    + " wait for the next restart with nothing to say so");
            held.runAll();
        } finally {
            BackgroundEmbedding.useForTests(previous);
        }
    }
}
