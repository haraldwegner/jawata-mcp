package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.jawata.mcp.learn.LearnerEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S16-27: what a run journalled can be read back, row by row, by the identity it was written under.
 *
 * <p>The journal published {@code append} and three counters and no row-level read, so a hired run's
 * own payload — the brain, the artifact, how far the plan got — could be written and never read
 * again by anybody. The three cases are the three answers a read can give, and the third is the one
 * that matters most: a journal that could not be read is NOT a journal with no rows in it.</p>
 */
class ARunsJournalRowsCanBeReadBackTest {

    private H2ExperienceStore store;
    private LearnerEventStore events;

    @BeforeEach
    void setUp() throws Exception {
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("a session's rows come back whole, in the order they were written, and no other session's")
    void aSessionsRowsComeBackWholeAndInOrder() {
        LearnerEvent run = new LearnerEvent("plan-A", LearnerEvent.KIND_HIRED_RUN, "claude-code",
            "{\"artifactId\":\"proposal-1\",\"stepsPassed\":2,\"ofSteps\":3}");
        LearnerEvent otherRun = new LearnerEvent("plan-B", LearnerEvent.KIND_HIRED_RUN, "codex",
            "{\"artifactId\":\"proposal-2\",\"stepsPassed\":0,\"ofSteps\":1}");
        LearnerEvent later = new LearnerEvent("plan-A", LearnerEvent.KIND_GATE_CALL,
            "compile_workspace", "{\"errors\":0}");
        events.append(run);
        events.append(otherRun);
        events.append(later);

        assertEquals(Optional.of(List.of(run, later)), events.eventsOfSession("plan-A"),
            "every field as written, the plan's own rows only, oldest first");
    }

    @Test
    @DisplayName("a session nothing was written under answers an EMPTY list, which is a reading")
    void aSessionWithNoRowsIsAnEmptyList() {
        events.append(new LearnerEvent("plan-A", LearnerEvent.KIND_HIRED_RUN, "claude-code", "{}"));

        assertEquals(Optional.of(List.of()), events.eventsOfSession("plan-nobody"),
            "the journal was read and holds nothing for this session");
    }

    @Test
    @DisplayName("a journal that cannot be read answers ABSENT, never an empty list")
    void aJournalThatCannotBeReadSaysSo() throws Exception {
        events.append(new LearnerEvent("plan-A", LearnerEvent.KIND_HIRED_RUN, "claude-code", "{}"));
        store.close();
        store = null;

        assertEquals(Optional.empty(), events.eventsOfSession("plan-A"),
            "the row exists and the store is closed: an empty list here would say the run"
                + " journalled nothing, which nobody established");
    }
}
