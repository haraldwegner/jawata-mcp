package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3.2.1 (Sprint 26 dogfood finding #1): a store that failed to open must
 * neither lie nor stay stuck. The recovering wrapper serves an in-memory
 * fallback LOUDLY (notice non-null), keeps retrying the real store in the
 * background, and on success REPLAYS everything recorded during the degraded
 * window — the window loses nothing, and the notice clears.
 */
class RecoveringExperienceStoreTest {

    private RecoveringExperienceStore store;
    private H2ExperienceStore real;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
        if (real != null) {
            real.close();
        }
    }

    private static SymbolFact fact(String summary) {
        return SymbolFact.of("lesson", summary, Confidence.HIGH).build();
    }

    @Test
    @DisplayName("degraded from the first moment: serves, and SAYS SO")
    void degraded_store_serves_and_announces() {
        store = new RecoveringExperienceStore("open failed: boom",
            () -> { throw new IllegalStateException("still down"); }, 3600_000);
        assertNotNull(store.notice(), "a degraded store must announce itself");
        assertTrue(store.notice().contains("NON-PERSISTENT"),
            "the notice names the consequence: " + store.notice());
        assertTrue(store.notice().contains("boom"), "the notice carries the reason");
        String id = store.put(fact("recorded while degraded"));
        assertNotNull(id);
        assertEquals(1, store.count(), "the fallback still works");
        assertEquals("in-memory (DEGRADED: open failed: boom)",
            store.stats().get("file"), "stats names the degrade, not a bare in-memory");
    }

    @Test
    @DisplayName("recovery: the real store attaches, the degraded window is REPLAYED, the notice clears")
    void recovery_swaps_and_replays() throws Exception {
        real = H2ExperienceStore.openMemory();
        AtomicInteger attempts = new AtomicInteger();
        store = new RecoveringExperienceStore("flip race",
            () -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new IllegalStateException("still locked");
                }
                return real;
            }, 50);
        store.put(fact("written during the degraded window"));

        long deadline = System.currentTimeMillis() + 10_000;
        while (store.notice() != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertNull(store.notice(), "recovered: the notice must clear");
        assertTrue(attempts.get() >= 2, "the background retry actually retried");
        assertEquals(1, real.count(),
            "the degraded-window entry was replayed into the real store");
        // New writes now land in the real store directly.
        store.put(fact("written after recovery"));
        assertEquals(2, real.count());
        assertEquals(2, store.count(), "the wrapper serves the real store now");
    }

    @Test
    @DisplayName("a permanently failing reopen stays degraded and stays LOUD — never a bare in-memory")
    void permanent_failure_stays_loud() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        store = new RecoveringExperienceStore("schema written by a newer resident",
            () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("still newer");
            }, 30);
        Thread.sleep(300);
        assertNotNull(store.notice(), "still degraded, still announcing");
        assertTrue(attempts.get() >= 2, "keeps trying in the background");
        assertTrue(store.notice().contains("schema written by a newer resident"));
    }
}
