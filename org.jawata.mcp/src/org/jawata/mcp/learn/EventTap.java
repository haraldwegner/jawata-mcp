package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.coverage.MechanicalChangeJournal;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The event tap (Sprint 26, D7's wiring): sits at the ToolRegistry choke point
 * — the one place every tool outcome converges — and turns outcomes into
 * {@link LearnerEvent}s AS A SIDE EFFECT of normal work. No manual step
 * anywhere: an agent that compiles, reverts, or breaks something has already
 * fed the learners. Also feeds the {@link SessionLedger} the server-side
 * checks read.
 */
public class EventTap {

    /** The gate tools whose outcomes are labels (compile/tests/diagnostics). */
    private static final Set<String> GATE_TOOLS =
        Set.of("compile_workspace", "get_diagnostics", "run_tests");

    private final SessionLedger ledger;
    private final LearnerEventStore events;

    public EventTap(SessionLedger ledger, LearnerEventStore events) {
        this.ledger = ledger;
        this.events = events;
    }

    public SessionLedger ledger() {
        return ledger;
    }

    /** Called after every completed tool call (success or structured error). */
    public void onCall(String sessionId, String name, JsonNode arguments, ToolResponse response) {
        int filesModified = filesModified(response);
        ledger.record(sessionId, new SessionLedger.CallRecord(
            name, response.isSuccess(), filesModified, System.currentTimeMillis()));
        if (events == null) {
            return;
        }
        if (!response.isSuccess()) {
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_TOOL_ERROR, name,
                "{\"error\":true}"));
            return;
        }
        if ("refactoring".equals(name) && arguments != null
                && arguments.path("action").asText("").startsWith("undo")) {
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_UNDO, name,
                "{\"action\":\"" + arguments.path("action").asText() + "\"}"));
        }
        if (filesModified > 0 && MechanicalChangeJournal.EXEMPT_TOOLS.contains(name)) {
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_MECHANICAL_TOUCH, name,
                "{\"filesModified\":" + filesModified + "}"));
        }
        if (GATE_TOOLS.contains(name)) {
            long errorCount = errorCount(response);
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_GATE_CALL, name,
                "{\"errors\":" + errorCount + "}"));
            // The compile-after-touch label: a failing gate while mechanically
            // touched files are pending marks the touch suspect — the
            // immediate revert-class signal the learners train on.
            if (errorCount > 0 && MechanicalChangeJournal.hasEntries()) {
                events.append(new LearnerEvent(sessionId,
                    LearnerEvent.KIND_COMPILE_AFTER_TOUCH_FAIL, name,
                    "{\"errors\":" + errorCount + "}"));
            }
        }
    }

    private static int filesModified(ToolResponse response) {
        if (response.getData() instanceof Map<?, ?> map
                && map.get("filesModified") instanceof List<?> files) {
            return files.size();
        }
        return 0;
    }

    private static long errorCount(ToolResponse response) {
        if (response.getData() instanceof Map<?, ?> map) {
            Object v = map.get("errorCount");
            if (v instanceof Number n) {
                return n.longValue();
            }
            Object failed = map.get("failed");
            if (failed instanceof Number n) {
                return n.longValue();
            }
        }
        return 0;
    }
}
