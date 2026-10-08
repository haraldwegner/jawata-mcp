package org.jawata.mcp.learn;

import org.jawata.mcp.coverage.MechanicalChangeJournal;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The event tap (Sprint 26; Sprint 26a): sits at the ToolRegistry choke point —
 * the one place every tool outcome converges. It feeds the {@link SessionLedger}
 * the server-side checks read, drives the {@link ToolExperienceRecorder}
 * (the D2 experience loop's selective capture — the real edit-outcome capture),
 * and records {@link LearnerEvent} telemetry (tool errors, undos, gate calls).
 * The edit-switch model that once trained on these events is retired in Sprint
 * 26a (D4); the events remain as cheap telemetry, and the experience loop is the
 * live capture. Sprint 28b adds the sanitized field recording — a small
 * synchronous file append per call (the tap's first disk consumer; the cost is
 * owned in ARCHITECTURE-field-recordings-28b.md).
 */
public class EventTap {

    private final SessionLedger ledger;
    private final LearnerEventStore events;

    /**
     * The registry this tap asks which tools are GATES (Sprint 28g S9a M1).
     *
     * <p>It was a {@code Set.of("compile_workspace", "get_diagnostics", "run_tests")} right
     * here — and the byte-identical set existed in two more classes of this package at the
     * same time, with a fourth hardcoding of one of the names inline. Four places to keep in
     * step and nothing able to notice, because no reference-updating refactoring touches a
     * string literal; {@code ArchitectGate} in this same package records what that cost it.
     * The tools declare it now, through {@link org.jawata.mcp.tools.Tool#isGate()}.</p>
     *
     * <p>Injected, for the reason {@code ArchitectGate}'s constructor gives in the same
     * words: a dependency taken from a global is one a test cannot supply, and a singleton
     * reach here would leave this tap's own tests passing over an empty registry — a
     * differently-shaped version of the same blindness.</p>
     */
    private final org.jawata.mcp.refactoring.OperationRegistry registry;

    /** Sprint 26a D2: the experience-loop capture — null until the application
     *  wires it (the real edit-outcome capture that replaces the retired edit switch). */
    private ToolExperienceRecorder toolExperience;

    /** Sprint 28b D1: the sanitized field recording — null until the
     *  application wires it. Shapes, never content. */
    private org.jawata.mcp.field.FieldRecorder fieldRecorder;

    /** Sprint 28c D14 (v13): stamps which client recorded an entry — null until
     *  the application wires it. {@code (sessionId, entryId)}. */
    private java.util.function.BiConsumer<String, String> originStamper;

    public EventTap(SessionLedger ledger, LearnerEventStore events) {
        this(ledger, events, org.jawata.mcp.refactoring.OperationRegistry.theRegistry());
    }

    /** @param registry the registry this tap asks which tools are gates; see {@link #registry}. */
    public EventTap(SessionLedger ledger, LearnerEventStore events,
            org.jawata.mcp.refactoring.OperationRegistry registry) {
        this.ledger = ledger;
        this.events = events;
        this.registry = registry;
    }

    /** Sprint 26a D2: install the experience-loop recorder (selective capture). */
    public void setToolExperienceRecorder(ToolExperienceRecorder recorder) {
        this.toolExperience = recorder;
    }

    /** Sprint 28b D1: install the sanitized field recorder (application wiring). */
    public void setFieldRecorder(org.jawata.mcp.field.FieldRecorder recorder) {
        this.fieldRecorder = recorder;
    }

    /**
     * Sprint 28c D14 (v13): install the origin stamper — {@code (sessionId,
     * entryId)} → stamp which client recorded the entry.
     *
     * <p>It lives HERE because this is the one place that holds both halves: a
     * tool sees only its arguments, and the session — the thing that knows the
     * client — arrives with every completed call at this tap. The stamper
     * itself is a closure built at application wiring over
     * {@code ClientDirectory} and the store, so this class stays ignorant of
     * both, the same rule as the two collaborators above.</p>
     */
    public void setOriginStamper(java.util.function.BiConsumer<String, String> stamper) {
        this.originStamper = stamper;
    }

    public SessionLedger ledger() {
        return ledger;
    }

    /** Called after every completed tool call (success or structured error).
     * @param durationMs wall-clock duration of the call, for the field
     *        recording's latency bucket (Sprint 28b) */
    public void onCall(CallerIdentity caller, String name, JsonNode arguments,
            ToolResponse response, long durationMs) {
        // The collaborators below are keyed by the identity's ID — a stored
        // column, a map key, an event row. The IDENTITY is what this method
        // takes, so none of them can be handed an absence to invent one from.
        String sessionId = caller.id();
        int filesModified = filesModified(response);
        ledger.record(caller, new SessionLedger.CallRecord(
            name, response.isSuccess(), filesModified, System.currentTimeMillis(), arguments));
        // Sprint 28b D1: the sanitized field recording — every outcome, success
        // and error, as a shape. Its own try: recording never fails the call.
        if (fieldRecorder != null) {
            try {
                fieldRecorder.onCall(sessionId, name, arguments, response, durationMs);
            } catch (Exception e) {
                // FieldPile counts its own drops; this guards recorder bugs.
            }
        }
        // Sprint 28c D14 (v13): stamp WHICH CLIENT recorded a new entry. Only
        // the `record` verb — an import re-attributing rows to the importing
        // session, or a set_form claiming authorship of a correction's ORIGIN,
        // would both overwrite a true fact with a plausible one. Its own try:
        // attribution must never fail the call it attributes.
        if (originStamper != null && "experience".equals(name) && arguments != null
                && "record".equals(arguments.path("kind").asText(null))
                && response.isSuccess()
                && response.getData() instanceof java.util.Map<?, ?> data
                && data.get("id") instanceof String entryId) {
            try {
                originStamper.accept(sessionId, entryId);
            } catch (Exception e) {
                // The stamp is bookkeeping; the record already succeeded.
            }
        }
        // Sprint 26a D2: the experience loop's selective capture — independent of
        // the learner-event store below and of the learner models (retired in D4).
        if (toolExperience != null) {
            toolExperience.onCall(caller, name, arguments, response);
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
        if (filesModified > 0 && MechanicalChangeJournal.isMechanicalTool(name)) {
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_MECHANICAL_TOUCH, name,
                "{\"filesModified\":" + filesModified + "}"));
        }
        if (registry.isGate(name)) {
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
        return ResponseFacts.filesModified(response);
    }

    /**
     * ZERO when the response carried no count in a shape the shared reader knows, which is this
     * class's own behaviour unchanged — telemetry about a tool that reported nothing is zero.
     *
     * <p>A GATE must not make that choice: see {@link ResponseFacts#errorCount}. The decision lives
     * here, at the caller, precisely because the two kinds of caller want opposite answers to it.</p>
     */
    private static long errorCount(ToolResponse response) {
        return ResponseFacts.errorCount(response).orElse(0L);
    }
}
