package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.coverage.MechanicalChangeJournal;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 26 Stage 1: the event tap turns tool outcomes into learner labels as
 * a side effect — the D7 wiring contract at unit level. Each event class is
 * asserted as a persisted row; the ledger records every call.
 */
class EventTapTest {

    private H2ExperienceStore store;
    private LearnerEventStore events;
    private SessionLedger ledger;
    private EventTap tap;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MechanicalChangeJournal.clear();
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
        ledger = new SessionLedger();
        tap = new EventTap(ledger, events);
    }

    @AfterEach
    void tearDown() throws Exception {
        MechanicalChangeJournal.clear();
        store.close();
    }

    @Test
    void a_tool_error_becomes_a_tool_error_event_and_a_ledger_row() throws Exception {
        tap.onCall("s1", "rename_symbol", mapper.readTree("{}"),
            ToolResponse.internalError("boom"));
        assertEquals(Map.of(LearnerEvent.KIND_TOOL_ERROR, 1L), events.countByKind());
        List<SessionLedger.CallRecord> calls = ledger.calls("s1");
        assertEquals(1, calls.size());
        assertFalse(calls.get(0).ok());
    }

    @Test
    void an_undo_call_becomes_an_undo_event() throws Exception {
        tap.onCall("s1", "refactoring", mapper.readTree("{\"action\":\"undo\"}"),
            ToolResponse.success(Map.of("undone", true)));
        assertTrue(events.countByKind().containsKey(LearnerEvent.KIND_UNDO));
    }

    @Test
    void a_mechanical_touch_becomes_a_touch_event() throws Exception {
        tap.onCall("s1", "rename_symbol", mapper.readTree("{}"),
            ToolResponse.success(Map.of("filesModified", List.of("A.java", "B.java"))));
        assertTrue(events.countByKind().containsKey(LearnerEvent.KIND_MECHANICAL_TOUCH));
        assertEquals(2, ledger.calls("s1").get(0).filesModified());
    }

    @Test
    void a_failing_gate_after_a_touch_labels_compile_after_touch_fail() throws Exception {
        MechanicalChangeJournal.recordMechanical("src/A.java");
        tap.onCall("s1", "compile_workspace", mapper.readTree("{}"),
            ToolResponse.success(Map.of("errorCount", 3)));
        Map<String, Long> counts = events.countByKind();
        assertTrue(counts.containsKey(LearnerEvent.KIND_GATE_CALL));
        assertTrue(counts.containsKey(LearnerEvent.KIND_COMPILE_AFTER_TOUCH_FAIL),
            "errors while touched files pending = the revert-class label");
    }

    @Test
    void a_clean_gate_without_touches_is_only_a_gate_call() throws Exception {
        tap.onCall("s1", "compile_workspace", mapper.readTree("{}"),
            ToolResponse.success(Map.of("errorCount", 0)));
        assertEquals(Map.of(LearnerEvent.KIND_GATE_CALL, 1L), events.countByKind());
    }

    @Test
    void ledger_is_bounded_per_session_and_across_sessions() {
        for (int s = 0; s < SessionLedger.MAX_SESSIONS + 10; s++) {
            ledger.record("session-" + s,
                new SessionLedger.CallRecord("t", true, 0, s));
        }
        assertTrue(ledger.sessionCount() <= SessionLedger.MAX_SESSIONS, "LRU-bounded sessions");
        for (int i = 0; i < SessionLedger.MAX_CALLS + 50; i++) {
            ledger.record("busy", new SessionLedger.CallRecord("t", true, 0, i));
        }
        assertEquals(SessionLedger.MAX_CALLS, ledger.calls("busy").size(), "bounded calls");
    }
}
