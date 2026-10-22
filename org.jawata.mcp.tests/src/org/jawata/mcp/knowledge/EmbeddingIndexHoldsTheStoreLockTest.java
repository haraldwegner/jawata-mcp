package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

/**
 * S19-12 — the embedding index runs its statements on the shared connection UNDER the store's
 * monitor, so {@code close()} (which takes that monitor) cannot land between the index taking the
 * connection and using it.
 *
 * <p><b>The defect this ends.</b> The index took {@code store.sharedConnection()} — which checks
 * "not closed" and hands the connection back — and prepared its statement without the monitor. A
 * close between the two shut the session under the statement, and H2 recorded the failure by
 * writing {@code experience.trace.db} into the store's folder: in the S19-11 checkpoint suite, one
 * test whose store closed during the background pass could not delete its temp folder.</p>
 *
 * <p>The race itself needs suite load to show (0 in 20 runs of that class alone), so this pins the
 * MECHANISM instead: while this thread holds the store monitor, a count must wait for it.</p>
 */
class EmbeddingIndexHoldsTheStoreLockTest {

    @Test
    void a_count_waits_while_the_store_monitor_is_held() throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            EmbeddingIndex index = new EmbeddingIndex(store, EmbeddingService.shared());
            CompletableFuture<Long> count;
            synchronized (store) {
                count = CompletableFuture.supplyAsync(() -> index.embeddedCount("experience_entry"));
                assertThrows(TimeoutException.class, () -> count.get(500, TimeUnit.MILLISECONDS),
                    "the count ran while another thread held the store monitor, so a close() in"
                        + " that window would shut the connection under its statement");
            }
            assertTrue(count.get(10, TimeUnit.SECONDS) >= 0,
                "once the monitor is released the count answers");
        }
    }

    /** The drain's other count, held to the same rule (S19-R3 audit: one probe per locked site). */
    @Test
    void a_total_count_waits_while_the_store_monitor_is_held() throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            EmbeddingIndex index = new EmbeddingIndex(store, EmbeddingService.shared());
            CompletableFuture<Long> count;
            synchronized (store) {
                count = CompletableFuture.supplyAsync(() -> index.totalCount("experience_entry"));
                assertThrows(TimeoutException.class, () -> count.get(500, TimeUnit.MILLISECONDS),
                    "the total count ran while another thread held the store monitor");
            }
            assertTrue(count.get(10, TimeUnit.SECONDS) >= 0,
                "once the monitor is released the total count answers");
        }
    }
}
