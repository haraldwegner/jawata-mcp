package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3 (jawata-mcp#37, the STRUCTURAL half) — the store stops being a
 * global chokepoint.
 *
 * <p>The measured incident: a reader parked in a socket read inside
 * {@code all()} held the store's instance monitor and 66 threads queued behind
 * it. Re-measured live on 2026-08-19 against the 3.11.1 fleet, the same shape
 * had a resident answering {@code health_check} instantly while every real tool
 * call timed out — because the tool tap writes its usage row through that same
 * monitor.</p>
 *
 * <p>The discriminator used here is the property's clean side: a thread holds
 * the instance monitor exactly as {@code LearnerEventStore} does, and the read
 * path must still answer. Put {@code synchronized} back on the read methods and
 * every read below blocks until its latch expires, so each assertion fails.
 * Latches throughout, never timing.</p>
 */
class StoreChokepointTest {

    /**
     * THE DEFECT, as an assertion. A monitor holder must not stop reads — and
     * by the same fact, a read cannot stop a writer, because a read no longer
     * takes the monitor at all.
     */
    @Test
    @DisplayName("reads answer while a writer holds the store monitor")
    void readsAnswerWhileTheMonitorIsHeld() throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            store.put(entry("the first row"));

            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                // EXACTLY what LearnerEventStore, ToolExperienceStore and the
                // store's own writers do: take the instance monitor and use
                // the shared connection under it.
                synchronized (store) {
                    assertNotNull(store.sharedConnection());
                    holding.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "monitor-holder");
            holder.setDaemon(true);
            holder.start();
            assertTrue(holding.await(10, TimeUnit.SECONDS), "the holder took the monitor");

            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Integer> rows = new AtomicReference<>();
            CountDownLatch read = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try {
                    rows.set(store.all().size());
                    store.count();
                    store.stats();
                    read.countDown();
                } catch (Throwable t) {
                    failure.set(t);
                    read.countDown();
                }
            }, "reader");
            reader.setDaemon(true);
            reader.start();

            assertTrue(read.await(15, TimeUnit.SECONDS),
                "a read must answer while a writer holds the monitor — #37 is that it "
                    + "could not: one holder stopped every reader, and one parked reader "
                    + "stopped every writer and every tool call on the resident");
            release.countDown();
            holder.join(10_000);
            assertNull(failure.get(), "the read itself did not fail");
            assertEquals(1, rows.get(), "and it answered the real row");
        }
    }

    /**
     * The invariant Stage 3 must NOT silently break, from
     * {@code LearnerEventStore}'s own javadoc: the learner shares the connection
     * AND this instance as its lock, so the two writers never interleave. Reads
     * moved off the monitor; writers did not.
     */
    @Test
    @DisplayName("writers still serialise on the store monitor — the learner invariant holds")
    void writersStillDoNotInterleave() throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            AtomicBoolean overlapped = new AtomicBoolean();
            AtomicBoolean inWrite = new AtomicBoolean();
            CountDownLatch done = new CountDownLatch(2);

            Runnable writer = () -> {
                for (int i = 0; i < 50; i++) {
                    synchronized (store) {
                        if (!inWrite.compareAndSet(false, true)) {
                            overlapped.set(true);
                        }
                        assertNotNull(store.sharedConnection());
                        inWrite.set(false);
                    }
                }
                done.countDown();
            };
            Thread t1 = new Thread(writer, "writer-1");
            Thread t2 = new Thread(writer, "writer-2");
            t1.setDaemon(true);
            t2.setDaemon(true);
            t1.start();
            t2.start();

            assertTrue(done.await(20, TimeUnit.SECONDS), "both writers finished");
            assertFalse(overlapped.get(),
                "two writers were inside the monitor at once — the invariant "
                    + "LearnerEventStore's javadoc states was dropped");
        }
    }

    /**
     * No convoy, asserted STRUCTURALLY rather than by racing threads: the store
     * hands out {@code READ_POOL_SIZE} DISTINCT read connections at the same
     * time, so that many reads can be in flight at once.
     *
     * <p>The first version of this test raced three reader threads and was a
     * FALSE GREEN — verified by reverting the fix, which left it passing:
     * readers that merely complete one after another satisfy a latch just as
     * well as readers running together. This version fails the moment the pool
     * becomes one shared connection again.</p>
     */
    @Test
    @DisplayName("the store hands out several read connections at once")
    void severalReadsCanBeInFlightAtOnce() throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            store.put(entry("row"));
            List<java.sql.Connection> held = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < H2ExperienceStore.READ_POOL_SIZE; i++) {
                    java.sql.Connection c = store.borrowRead();
                    assertNotNull(c, "read slot " + i + " must be available");
                    held.add(c);
                }
                assertEquals(H2ExperienceStore.READ_POOL_SIZE,
                    new java.util.HashSet<>(held).size(),
                    "every in-flight read must have its OWN connection — one shared "
                        + "connection is the convoy this stage removes: " + held);
                // And the shared WRITE connection is none of them: a reader can
                // never be sitting on the connection the writers serialise on.
                assertFalse(held.contains(store.sharedConnection()),
                    "a pooled read must never hand back the writers' connection");
            } finally {
                for (java.sql.Connection c : held) {
                    store.releaseRead(c, true);
                }
            }
        }
    }

    /** The read path answers the same rows the monitor-held path did. */
    @Test
    @DisplayName("the read path returns what it always returned")
    void readsStillAnswerTheSameRows() throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            store.put(entry("alpha row"));
            store.put(entry("beta row"));

            List<StoredEntry> all = store.all();
            assertEquals(2, all.size(), "both rows come back: " + all);
            assertEquals(2L, store.count(), "and count agrees with them");
            assertTrue(store.get(all.get(0).id()).isPresent(), "get() answers by id");
            assertEquals(2, store.byIds(List.of(all.get(0).id(), all.get(1).id())).size(),
                "byIds answers both");
            Map<String, Object> stats = store.stats();
            assertEquals(2L, stats.get("total"), "stats agree: " + stats);
            assertEquals(2, store.exportEntries(null, null).size(), "export agrees");
            assertEquals(2, store.listEntries(null, null, null, null, 10).size(),
                "listEntries agrees");
        }
    }

    private static ExperienceEntry entry(String summary) {
        return ExperienceEntry.candidate(
            SymbolFact.of("lesson", summary, Confidence.MEDIUM)
                .symbol("com.example.Foo#bar")
                .build());
    }
}
