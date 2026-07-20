package org.jawata.mcp.knowledge;

import org.jawata.mcp.learn.LearnerEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3.2.1 (dogfood finding #3): learner-event writes on the SHARED store were
 * dropping ~40% under live fleet conditions — the connection self-heals only
 * at acquisition, so a statement that fails mid-write (auto-server handoff,
 * lock timeout against a concurrent resident) lost its event on the single
 * attempt. Writes now retry on a forced-fresh connection; an event is
 * counted dropped only after the retries are exhausted.
 */
class LearnerEventResilienceTest {

    private H2ExperienceStore store;
    private LearnerEventStore events;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    @DisplayName("a connection killed under the store does not lose the event")
    void append_survives_a_dead_connection() throws Exception {
        events.append(new LearnerEvent("s", "gate_call", "compile_workspace", "{}"));
        // Kill the live connection BEHIND the store's back — the next write's
        // first acquisition may hand back a stale handle mid-statement.
        store.sharedConnection().close();
        events.append(new LearnerEvent("s", "gate_call", "compile_workspace", "{}"));
        assertEquals(2, events.totalEvents(), "both events persisted");
        assertEquals(0, events.failedWrites(), "a healed write is not a failed write");
    }

    @Test
    @DisplayName("the retry helper: transient failures are absorbed, persistent ones surface")
    void retry_helper_contract() {
        AtomicInteger calls = new AtomicInteger();
        // Fails twice, succeeds on the third — absorbed.
        assertDoesNotThrow(() -> LearnerEventStore.withRetry(3, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new java.sql.SQLException("transient");
            }
        }, () -> { }));
        assertEquals(3, calls.get());
        // Fails always — surfaces after the budget.
        AtomicInteger always = new AtomicInteger();
        assertThrows(Exception.class, () -> LearnerEventStore.withRetry(3, () -> {
            always.incrementAndGet();
            throw new java.sql.SQLException("persistent");
        }, () -> { }));
        assertEquals(3, always.get(), "the full budget was spent before giving up");
    }

    @Test
    @DisplayName("saveState heals the same way")
    void save_state_survives_a_dead_connection() throws Exception {
        events.saveState("edit-switch", "state-1");
        store.sharedConnection().close();
        events.saveState("edit-switch", "state-2");
        assertEquals("state-2", events.loadState("edit-switch").orElseThrow());
        assertEquals(0, events.failedWrites());
    }

    /**
     * F1 (Sprint-26 audit): the LIVE condition the v3.2.1 fix never exercised —
     * TWO residents (separate store instances, separate locks) writing the
     * shared AUTO_SERVER file concurrently, contending on the same
     * {@code learner_state} key. Connection-reopen retry alone does not survive
     * lock contention; this reproduces the drop and gates the real cure.
     */
    @Test
    @DisplayName("F1: two concurrent residents on the shared file drop no writes")
    void concurrent_residents_do_not_drop_writes() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("jawata-f1-");
        H2ExperienceStore r1 = H2ExperienceStore.openAt(dir);
        H2ExperienceStore r2 = H2ExperienceStore.openAt(dir);
        LearnerEventStore e1 = new LearnerEventStore(r1);
        LearnerEventStore e2 = new LearnerEventStore(r2);
        final int n = 150;
        Runnable w1 = () -> {
            for (int i = 0; i < n; i++) {
                e1.append(new LearnerEvent("r1", "gate_call", "compile_workspace", "{}"));
                e1.saveState("edit-switch", "r1-" + i);
            }
        };
        Runnable w2 = () -> {
            for (int i = 0; i < n; i++) {
                e2.append(new LearnerEvent("r2", "gate_call", "compile_workspace", "{}"));
                e2.saveState("edit-switch", "r2-" + i);
            }
        };
        Thread t1 = new Thread(w1, "resident-1");
        Thread t2 = new Thread(w2, "resident-2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        long dropped = e1.failedWrites() + e2.failedWrites();
        long total = e1.totalEvents();
        r1.close();
        r2.close();
        assertEquals(0, dropped, "concurrent residents dropped " + dropped + " writes");
        assertEquals(2 * n, total, "every appended event is on the shared file");
    }

    /**
     * F1 (Sprint-26 audit): the drops are cross-PROCESS — a write issued while a
     * peer resident restarts fails until the shared auto-server socket is back
     * (seconds). This reproduces the mechanism (a write that stays unavailable
     * for ~1s): the OLD 3-attempt ~0.3s budget gives up inside the window; the
     * shipped {@link LearnerEventStore#WRITE_ATTEMPTS}=8 budget outlasts it.
     */
    @Test
    @DisplayName("F1: the retry budget outlasts a multi-second store outage")
    void retry_budget_outlasts_a_restart_window() {
        java.util.function.Supplier<LearnerEventStore.SqlOp> failsFor1s = () -> {
            long start = System.currentTimeMillis();
            return () -> {
                if (System.currentTimeMillis() - start < 1000L) {
                    throw new java.sql.SQLException("store unavailable — peer restarting");
                }
            };
        };
        // The old budget (3 attempts, ~0.3s) drops the event inside the window.
        assertThrows(Exception.class,
            () -> LearnerEventStore.withRetry(3, failsFor1s.get(), () -> { }));
        // The shipped budget waits the restart out.
        assertDoesNotThrow(
            () -> LearnerEventStore.withRetry(LearnerEventStore.WRITE_ATTEMPTS,
                failsFor1s.get(), () -> { }));
    }
}
