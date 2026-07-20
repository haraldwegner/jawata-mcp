package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.coverage.MechanicalChangeJournal;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The event tap (Sprint 26; Sprint 26a): sits at the ToolRegistry choke point —
 * the one place every tool outcome converges. It feeds the {@link SessionLedger}
 * the server-side checks read, drives the {@link ToolExperienceRecorder}
 * (the D2 experience loop's selective capture — the real edit-outcome capture),
 * and records {@link LearnerEvent} telemetry (tool errors, undos, gate calls).
 * The edit-switch model that once trained on these events is retired in Sprint
 * 26a (D4); the events remain as cheap telemetry, and the experience loop is the
 * live capture.
 */
public class EventTap {

    /** The gate tools whose outcomes are labels (compile/tests/diagnostics). */
    private static final Set<String> GATE_TOOLS =
        Set.of("compile_workspace", "get_diagnostics", "run_tests");

    private final SessionLedger ledger;
    private final LearnerEventStore events;

    /** Sprint 26a D2: the experience-loop capture — null until the application
     *  wires it (the real edit-outcome capture that replaces the retired edit switch). */
    private ToolExperienceRecorder toolExperience;

    public EventTap(SessionLedger ledger, LearnerEventStore events) {
        this.ledger = ledger;
        this.events = events;
    }

    /** Sprint 26a D2: install the experience-loop recorder (selective capture). */
    public void setToolExperienceRecorder(ToolExperienceRecorder recorder) {
        this.toolExperience = recorder;
    }

    public SessionLedger ledger() {
        return ledger;
    }

    /** Called after every completed tool call (success or structured error). */
    public void onCall(String sessionId, String name, JsonNode arguments, ToolResponse response) {
        int filesModified = filesModified(response);
        ledger.record(sessionId, new SessionLedger.CallRecord(
            name, response.isSuccess(), filesModified, System.currentTimeMillis()));
        // Sprint 26a D2: the experience loop's selective capture — independent of
        // the learner-event store below and of the learner models (retired in D4).
        if (toolExperience != null) {
            toolExperience.onCall(sessionId, name, arguments, response);
        }
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
            // A failing gate while mechanically-touched files are pending marks
            // the touch suspect — retained as telemetry. The edit switch that
            // once trained on this signal is retired (D4); the experience loop
            // captures the real edit→compile outcome into tool_experience.
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
