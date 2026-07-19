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
}
