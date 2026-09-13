package org.jawata.mcp.knowledge;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 28f D4, amended 2026-09-13 — a bulk write hands its rows to this worker and
 * answers at once; the meaning vectors are computed here, behind the caller.
 *
 * <p><b>Why a write no longer waits.</b> {@code load}, {@code import} and
 * {@code wipe_and_import} used to index what they wrote before answering. Each row costs
 * roughly a second across its lanes, so a load that re-read a story folder after a loader
 * change stalled 327 s in one measurement and 364 s in another, and whoever asked — studio's
 * reload, an agent — sat blocked for all of it. Harald, 2026-09-13: <i>"we should not block
 * anything and run in background"</i>.</p>
 *
 * <p><b>What the wait used to buy is kept another way, and none of it is new.</b> The write's
 * response carries {@code unembedded}, so the caller is told how many rows cannot yet answer
 * by meaning; a recall that meets such a row marks it rather than scoring it zero; and
 * {@code stats} publishes the remainder until it reaches zero.</p>
 *
 * <p><b>One worker, and requests coalesce per store.</b> A request that arrives while a drain
 * for the same store is still QUEUED adds nothing — that drain has not started, so it will see
 * the new rows. A request that arrives once the drain has STARTED queues exactly one more, so a
 * row written during a pass is never left for the next restart. The flag is cleared as the task
 * begins, not when it ends, which is what makes that second case safe.</p>
 *
 * <p>The drain itself holds a per-store lock ({@link EmbeddingIndex#drain(int,
 * java.util.function.BooleanSupplier)}), so this worker and the startup backfill never embed
 * the same rows twice when a write lands while the resident is still converging.</p>
 */
public final class BackgroundEmbedding {

    private static final Logger log = LoggerFactory.getLogger(BackgroundEmbedding.class);

    private static final Executor DEFAULT = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "jawata-embedding-after-write");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static volatile Executor executor = DEFAULT;

    /** Per store: a drain has been queued and has not started yet. */
    private static final Map<H2ExperienceStore, AtomicBoolean> QUEUED =
        Collections.synchronizedMap(new WeakHashMap<>());

    private BackgroundEmbedding() {
    }

    /**
     * Ask for this index's store to be brought to zero unembedded rows, in the background.
     * Returns immediately. A {@code null} index — no H2 store, or no embedder — is a no-op,
     * because there is nothing that could embed.
     */
    public static void request(EmbeddingIndex index) {
        if (index == null) {
            return;
        }
        AtomicBoolean queued = QUEUED.computeIfAbsent(index.store(), s -> new AtomicBoolean());
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            queued.set(false);
            // A store its owner closed while this pass waited in the queue is finished
            // business, not a failure: the owner chose to stop it, and there is nothing left
            // to make searchable. Reporting it as a failed pass would put a warning in front of
            // every shutdown and every test that writes and then closes its store.
            if (index.store().isClosed()) {
                return;
            }
            try {
                int embedded = index.drain();
                if (embedded > 0) {
                    log.info("embedding after write: {} row(s) embedded; {} still unembedded",
                        embedded, index.remainingUnembedded());
                }
            } catch (RuntimeException e) {
                if (index.store().isClosed()) {
                    log.debug("embedding after write stopped: the store was closed during the"
                        + " pass");
                    return;
                }
                // Never fatal: the rows stay keyword-reachable, stats keeps reporting them,
                // and the next write or restart asks again.
                log.warn("embedding after write FAILED ({}); the rows stay unembedded and"
                    + " are retried by the next write or restart", e.toString());
            }
        });
    }

    /**
     * Replace the worker for a test, so a test can hold the drain and observe what a write
     * answers before any embedding has happened. {@code null} restores the real worker.
     *
     * @return the worker that was in place
     */
    static Executor useForTests(Executor replacement) {
        Executor previous = executor;
        executor = replacement == null ? DEFAULT : replacement;
        return previous;
    }
}
