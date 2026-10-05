package org.jawata.mcp.learn;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.models.CallerIdentity;

/**
 * The server-side enforcement lane (Sprint 26, D4 + D5 + the D3 defect
 * rule): rides the answers every client already reads — the universal lane,
 * mode-agnostic (a deployed command and a rule-block stance look identical
 * here: both are judged by the session's own call ledger).
 *
 * <ul>
 *   <li>D4 — a seat OUTCOME record arriving in a session whose ledger lacks
 *       the seat's named gate calls → flagged (steering + journal); a
 *       session evicted with seat-shaped activity and no outcome → flagged
 *       unjournaled.</li>
 *   <li>D5 — a summary-shaped store record (decision ask / checkpoint
 *       summary / sprint result) with no audit evidence in the session →
 *       flagged + the decision test attached; an ask-shaped CALL pattern
 *       gets the decision test attached preemptively.</li>
 *   <li>D3 — a declared fallback arriving after a jawata tool error in the
 *       same session → a RELAX label AND a filed defect, together, always
 *       (silently learning around own bugs is forbidden).</li>
 * </ul>
 */
public final class ServerChecks {

    private static final Logger log = LoggerFactory.getLogger(ServerChecks.class);

    /** Event kinds this lane journals. */
    public static final String KIND_SEAT_GATE_SKIP = "seat_gate_skip";
    public static final String KIND_SEAT_UNJOURNALED = "seat_unjournaled";
    public static final String KIND_UNAUDITED_ASK = "unaudited_ask";
    public static final String KIND_RELAX_LABEL = "relax_label";
    public static final String KIND_DEFECT_FILED = "defect_filed";
    public static final String KIND_AUDIT_EVIDENCE = "audit_evidence";

    /** The decision test, attached where a summary is forming. */
    public static final String DECISION_TEST =
        "THE DECISION TEST (enforced): a decision ask, checkpoint summary, or "
        + "sprint result must let the reader decide from the text alone — no "
        + "interpretation, no guessing, every term defined, meaning preserved, "
        + "noise (including LENGTH) stripped. Audit before sending; report "
        + "results as WHAT THEY PROVE.";

    private final LearnerEventStore events;
    /** Files a defect into the experience store: (summary, details). */
    private final BiConsumer<String, String> defectFiler;
    /** The per-situation enforcement dial this lane feeds. */
    private final PolicyDial dial;

    /**
     * The registry these checks ask which tools are GATES (Sprint 28g S9a M1).
     *
     * <p>It was {@code SEAT_GATE_TOOLS}, a {@code Set.of("compile_workspace",
     * "get_diagnostics", "run_tests")} right here, with the byte-identical set also in
     * {@code EventTap} and {@code ToolExperienceRecorder} — and the same three names a
     * fourth time, in the steering message {@link #seatOutcomeCheck} returns. The tools
     * declare it now, through {@link org.jawata.mcp.tools.Tool#isGate()}, and the message
     * asks the registry rather than spelling them.</p>
     *
     * <p>Injected, in the words {@code ArchitectGate} in this same package already used for
     * exactly this repair: <em>"a dependency taken from a global is one a test cannot
     * supply … hiding that behind a singleton would leave the gate's own tests passing over
     * an empty registry, which is a differently-shaped version of the same blindness."</em>
     * So these checks fail CLOSED on an unpopulated registry — a seat outcome reads as
     * ungated — and the lane's tests must supply the gates rather than inherit them.</p>
     */
    private final org.jawata.mcp.refactoring.OperationRegistry registry;

    public ServerChecks(LearnerEventStore events, BiConsumer<String, String> defectFiler) {
        this(events, defectFiler, org.jawata.mcp.refactoring.OperationRegistry.theRegistry());
    }

    /** @param registry the registry these checks ask which tools are gates; see {@link #registry}. */
    public ServerChecks(LearnerEventStore events, BiConsumer<String, String> defectFiler,
            org.jawata.mcp.refactoring.OperationRegistry registry) {
        this.events = events;
        this.defectFiler = defectFiler;
        this.dial = new PolicyDial(events);
        this.registry = registry;
    }

    public PolicyDial dial() {
        return dial;
    }

    /**
     * BEFORE the act (Sprint 28g S9a M3): the ACCEPTANCE path. Returns a refusal
     * for the caller to receive instead of the tool's answer, or null to proceed.
     *
     * <h2>Why this is a separate member from {@link #onCompleted}</h2>
     *
     * <p>The two are split by TIMING, not by subject, and the split is the step.
     * What used to be one method ran entirely AFTER {@code tool.execute} and
     * returned a string the registry appended to {@code meta.steering} — so by
     * construction it could only comment on an act already completed and already
     * recorded. Reporting a seat outcome over a gate that FAILED was observed,
     * commented on, and kept.</p>
     *
     * <p>It also could not have refused through that channel even if it tried:
     * {@code ToolResponse#appendSteering} early-returns on any failure response,
     * so a refusal riding it vanishes with no log, no counter and no trace. This
     * repository already records that as a shipped defect in the registry's own
     * comment. The precedent that works is {@code precedentCharge}, which RETURNS
     * a refusal as the response — and which returns it from a point BEFORE the
     * in-flight ticket is taken, which is why this check is consumed there too: a
     * refusal returned after the ticket and before its {@code try} would leak it,
     * and that {@code finally}'s own comment calls a leaked ticket a false
     * hang.</p>
     *
     * <p>Only the seat-gate question moves here. The advisory checks STAY after
     * the act and the reason is measured rather than stylistic: they read the
     * ledger for the CURRENT call — {@code unauditedAskCheck} asks for more than
     * one experience call — and pre-act the current call is not in the ledger yet,
     * because the tap records it on the response. Moving them forward would
     * silently stop them firing.</p>
     *
     * <p>Fails OPEN on an exception, following {@code precedentCharge}: a bug in a
     * check must not refuse every tool call in the resident. It is logged.</p>
     */
    public ToolResponse acceptance(CallerIdentity caller, String name, JsonNode arguments,
            SessionLedger ledger) {
        try {
            if (!"experience".equals(name) || arguments == null) {
                return null;
            }
            if (!"record".equals(arguments.path("kind").asText(""))) {
                return null;
            }
            String operation = arguments.path("operation").asText("");
            if (!operation.startsWith("seat:")) {
                return null;
            }
            return seatOutcomeCheck(caller, operation, ledger);
        } catch (Exception e) {
            log.error("Acceptance failed before {} — this call went unchecked, deliberately:"
                + " a bug in a check must not refuse every call", name, e);
            return null;
        }
    }

    /**
     * AFTER the act: the advisory half. Returns a steering block to append, or
     * null. Never throws.
     *
     * <p>The {@code response} parameter this method used to take is GONE. It was
     * passed at every call site and read nowhere in the body — so moving
     * acceptance ahead of the act cost nothing that was being used.</p>
     *
     * <p>The seat-outcome branch is gone too: it is {@link #acceptance}'s, and it
     * runs before the tool does. A seat outcome reaching HERE has already been
     * accepted.</p>
     */
    public String onCompleted(CallerIdentity caller, String name, JsonNode arguments,
            SessionLedger ledger) {
        try {
            if (!"experience".equals(name) || arguments == null) {
                return askShapedPattern(caller, ledger);
            }
            String kind = arguments.path("kind").asText("");
            if (!"record".equals(kind)) {
                return null;
            }
            String operation = arguments.path("operation").asText("");
            String summary = arguments.path("summary").asText("");
            if (operation.startsWith("seat:")) {
                return null;
            }
            if ("communication-audit".equals(operation) || operation.startsWith("audit")) {
                journal(caller.id(), KIND_AUDIT_EVIDENCE, name, "{}");
                return null;
            }
            if ("jawata-fallback-slip".equals(operation)) {
                return fallbackAfterError(caller, summary, ledger);
            }
            if (isSummaryShaped(summary)) {
                return unauditedAskCheck(caller, ledger);
            }
            return null;
        } catch (Exception e) {
            log.error("Server checks failed after {} — this call went unchecked", name, e);
            return null;
        }
    }

    /**
     * Session eviction hook (D4, outcome-less): seat-shaped activity —
     * detector calls plus mutations — that ends without a seat outcome is an
     * unjournaled run.
     */
    public void onSessionEvicted(String sessionId, List<SessionLedger.CallRecord> calls) {
        // THE INLINE "run_tests" STAYS, and S9a M1 left it on purpose rather than by
        // oversight. It is not asking "is this a gate" — it is asking "did this session do
        // DETECTOR-SHAPED work", and `run_tests` qualifies there because it produces
        // findings, not because its answer is evidence. Swapping it for registry.isGate
        // would silently admit compile_workspace and get_diagnostics to that population,
        // which is a sensitivity change rather than a declaration move. And it cannot be
        // measured yet: M0 established this check NEVER FIRES today, because it needs a
        // detector call, a mutation and no outcome in ONE session, and the live transport
        // mints a fresh id per request so the ledger holds one row at a time.
        //
        // IT WAS HOMED TO M4 AND M4 DOES NOT KEEP THE PROMISE — corrected here rather
        // than left standing. M4 does make the ledger accumulate, which is the half that
        // was right. But it accumulates every header-less caller into ONE identity, and
        // eviction needs MAX_SESSIONS distinct ones — so collapsing them makes eviction
        // LESS likely in production, not more. This check is as inert after M4 as before
        // it, for a different reason, and saying so is cheaper than a reader re-deriving
        // it from a comment that promises otherwise.
        boolean detectorCalled = calls.stream().anyMatch(c ->
            c.tool().startsWith("find_") || "run_tests".equals(c.tool()));
        boolean mutated = calls.stream().anyMatch(c -> c.filesModified() > 0);
        boolean outcomeRecorded = calls.stream().anyMatch(c ->
            "experience".equals(c.tool()));
        if (detectorCalled && mutated && !outcomeRecorded) {
            journal(sessionId, KIND_SEAT_UNJOURNALED, "-",
                "{\"calls\":" + calls.size() + "}");
            log.warn("Session {} evicted seat-shaped with NO outcome record — unjournaled run",
                sessionId);
        }
    }

    /**
     * Returns a REFUSAL (Sprint 28g S9a M3) rather than a sentence to append. The
     * acceptance path returns it as the caller's response, so the outcome is not
     * recorded at all — where before the record was stored and then commented on.
     */
    private ToolResponse seatOutcomeCheck(CallerIdentity caller, String operation,
            SessionLedger ledger) {
        // A GATE CALL THAT ITSELF FAILED IS NOT A GATE THAT RAN. Reading the tool name
        // alone accepted a seat outcome recorded over a gate that had blown up: the tool
        // never answered, so its silence says nothing about the tree. What this still
        // cannot see is a gate that ANSWERED and reported errors — `CallRecord` carries
        // `ok` but no error count, and adding one is a record-component change across its
        // writers. That half is declared in the plan rather than implied here.
        boolean gateCalled = ledger.calls(caller).stream()
            .anyMatch(c -> registry.isGate(c.tool()) && c.ok());
        if (gateCalled) {
            return null;
        }
        journal(caller.id(), KIND_SEAT_GATE_SKIP, operation, "{}");
        // THE MESSAGE NAMES THE GATES BY ASKING, not by spelling them. This sentence held
        // the same three names a fourth time, where `isGate` alone would have left them
        // standing and reading as fixed; a gate that is retired now leaves the text with it.
        Set<String> gates = registry.gates();
        String named = gates.isEmpty() ? "" : " (" + String.join(" / ", gates) + ")";
        return ToolResponse.error("SEAT_GATE_NOT_RUN",
            "SEAT DISCIPLINE: this session is recording a " + operation + " outcome"
                + " WITHOUT any gate call that ANSWERED" + named + ". A gate you did not"
                + " run — or ran and that failed — has NOT passed, so this outcome is"
                + " REFUSED rather than recorded and flagged."
                + sharedHistoryCaveat(caller),
            "Run the seat's gates" + named + " and re-record the outcome.");
    }

    private String fallbackAfterError(CallerIdentity caller, String summary,
            SessionLedger ledger) {
        boolean errorBefore = ledger.calls(caller).stream().anyMatch(c -> !c.ok());
        if (!errorBefore) {
            dial.record("fallback", PolicyDial.Signal.SLIP);
            return null;
        }
        dial.record("fallback", PolicyDial.Signal.RELAX);
        // BOTH, always: the relax label AND the filed defect — never one alone.
        journal(caller.id(), KIND_RELAX_LABEL, "-", "{}");
        journal(caller.id(), KIND_DEFECT_FILED, "-", "{}");
        if (defectFiler != null) {
            defectFiler.accept(
                "jawata defect (auto-filed): a tool error preceded a declared fallback",
                "Session " + caller.id() + ": " + summary
                    + " — the policy relaxes here AND this defect is filed; "
                    + "learning around our own bugs silently is forbidden.");
        }
        return null;
    }

    private String unauditedAskCheck(CallerIdentity caller, SessionLedger ledger) {
        boolean audited = ledger.calls(caller).stream()
            .anyMatch(c -> "experience".equals(c.tool()));
        // The record being stored now IS an experience call; audit evidence
        // must have arrived EARLIER (a communication-audit record).
        long experienceCalls = ledger.calls(caller).stream()
            .filter(c -> "experience".equals(c.tool())).count();
        if (audited && experienceCalls > 1) {
            return null;
        }
        journal(caller.id(), KIND_UNAUDITED_ASK, "-", "{}");
        return DECISION_TEST + sharedHistoryCaveat(caller);
    }

    private String askShapedPattern(CallerIdentity caller, SessionLedger ledger) {
        List<SessionLedger.CallRecord> calls = ledger.calls(caller);
        if (calls.size() < 4) {
            return null;
        }
        List<SessionLedger.CallRecord> tail = calls.subList(calls.size() - 4, calls.size());
        long gatherers = tail.stream().filter(c ->
            c.tool().startsWith("find_") || registry.isGate(c.tool())).count();
        boolean anyMutation = tail.stream().anyMatch(c -> c.filesModified() > 0);
        if (gatherers >= 4 && !anyMutation
                && tail.stream().map(SessionLedger.CallRecord::tool).distinct().count() >= 2) {
            return DECISION_TEST + sharedHistoryCaveat(caller);
        }
        return null;
    }

    /**
     * Sprint 28g S9a M4 — the one place that READS an identity's origin.
     *
     * <p>Every check above concludes something about an AGENT from a caller's
     * history. That is sound for an identity the client supplied, which is one
     * agent's. It is not sound for one the RESIDENT minted: that is shared by
     * every header-less caller of a transport, so the history may be several
     * agents' work interleaved and a conclusion drawn from it can land on the
     * wrong one.</p>
     *
     * <p>The check still FIRES — silencing it would trade a false positive for a
     * false negative on every anonymous caller, which is most of them. What it
     * adds is the one fact the reader needs to judge the conclusion, which is
     * the whole reason {@code origin} exists rather than a bare id.</p>
     */
    private static String sharedHistoryCaveat(CallerIdentity caller) {
        if (caller.origin() != CallerIdentity.Origin.RESIDENT_MINTED) {
            return "";
        }
        return " (NOTE: this caller sent no session id, so the history above is"
            + " the transport's shared one and may interleave several agents'"
            + " calls — weigh the conclusion accordingly.)";
    }

    private static boolean isSummaryShaped(String summary) {
        if (summary == null) {
            return false;
        }
        String s = summary.toUpperCase();
        return s.startsWith("DECISION:")
            || s.contains("AWAITING \"CONTINUE\"") || s.contains("⏸")
            || s.contains("CHECKPOINT") && s.contains("SHIPPED")
            || s.contains("SPRINT") && (s.contains("CLOSED") || s.contains("RESULT"));
    }

    private void journal(String sessionId, String kind, String tool, String detail) {
        if (events != null) {
            events.append(new LearnerEvent(sessionId, kind, tool, detail));
        }
    }
}
