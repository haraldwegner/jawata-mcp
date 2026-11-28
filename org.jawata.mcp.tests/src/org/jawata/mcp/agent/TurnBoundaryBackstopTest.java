package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28h Stage 28 (i), slice 3 (M1, M2): main's backstop. The engine's turn loop reads the standing of the plan main entered, through the one
 * {@code plan_steps} answer: main is sent back only while the plan can go on and none of its own hires is running, and is told where the plan
 * stands, with no try spent, when a checkpoint was reached or a step of the person stands next.
 */
class TurnBoundaryBackstopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static PrincipalSession session(boolean autonomy) {
        PrincipalSession session = new PrincipalSession("s1", "/w", false);
        session.setAutonomy(Channel.HOST, autonomy);
        return session;
    }

    private static ObjectNode standing(String reachedCheckpoint, String personStep) {
        ObjectNode standing = JSON.createObjectNode().put("passed", 1).put("total", 3);
        standing.putObject("next").put("index", 1).put("title", "Second").put("tool", "run_tests");
        standing.putNull("nextCheckpoint");
        standing.putArray("forPerson");
        standing.put("open", true).put("checkpointReached", reachedCheckpoint != null).put("personNext", personStep != null);
        standing.put("reachedCheckpoint", reachedCheckpoint);
        standing.put("personStep", personStep);
        return standing;
    }

    /** A ledger with one step waiting for its gate, and the own plan the test names (none by default, as every ledger that knows no marks). */
    private static final class Waiting implements StepLedger {
        volatile Optional<OwnPlan> own = Optional.empty();

        @Override
        public List<Pending> pending(PrincipalSession session) {
            return List.of(new Pending("plan-1", 1, "run_tests"));
        }

        @Override
        public Optional<OwnPlan> ownPlan(PrincipalSession session) {
            return own;
        }
    }

    private static Waiting waitingWith(boolean goOn, String reachedCheckpoint, String personStep) {
        Waiting ledger = new Waiting();
        ledger.own = Optional.of(new StepLedger.OwnPlan(goOn, standing(reachedCheckpoint, personStep)));
        return ledger;
    }

    private static ToolCalls answering(ToolResponse response) {
        return new ToolCalls() {
            @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                return response;
            }
            @Override public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
    }

    /** What plan_steps answers with nothing of the caller's own plan in it. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> plainAnswer() {
        ObjectNode answer = JSON.createObjectNode();
        answer.putArray("pending");
        answer.put("planCount", 1);
        return JSON.convertValue(answer, Map.class);
    }

    // ---- instruction 3: ToolStepLedger#ownPlan -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("28h Stage 28 (i) M1: ownPlan of the tool ledger is present with goOn false and the same planStanding when plan_steps answered both keys")
    void theToolLedgerReadsTheOwnPlanFromTheAnswer() {
        Map<String, Object> answer = new java.util.LinkedHashMap<>(plainAnswer());
        answer.put("planStanding", JSON.convertValue(standing("Checkpoint A", null), Map.class));
        answer.put("goOn", false);

        Optional<StepLedger.OwnPlan> own = new ToolStepLedger(answering(ToolResponse.success(answer))).ownPlan(session(true));

        assertAll(
            () -> assertTrue(own.isPresent(), "no own plan was read"),
            () -> assertEquals(false, own.orElseThrow().goOn()),
            () -> assertEquals(standing("Checkpoint A", null), own.orElseThrow().planStanding()));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: ownPlan of the tool ledger is empty when the answer has no planStanding key")
    void noPlanStandingMeansNoOwnPlan() {
        Map<String, Object> answer = new java.util.LinkedHashMap<>(plainAnswer());
        answer.put("goOn", true);

        assertEquals(Optional.empty(), new ToolStepLedger(answering(ToolResponse.success(answer))).ownPlan(session(true)));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: ownPlan of the tool ledger is empty when plan_steps refuses")
    void aRefusingToolMeansNoOwnPlan() {
        ToolCalls refusing = answering(ToolResponse.error("NO_SUCH_TOOL", "plan_steps", "none"));

        assertEquals(Optional.empty(), new ToolStepLedger(refusing).ownPlan(session(true)));
    }

    // ---- instruction 4: the boundary ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("28h Stage 28 (i) M1: with autonomy on, a step open, a plan that can go on and no own hire running, the turn end resumes and counts one try")
    void aPlanThatCanGoOnResumesAndCountsOneTry() {
        TurnBoundary boundary = new TurnBoundary(waitingWith(true, null, null), new AttemptLedger(), s -> false);

        TurnBoundary.Verdict first = boundary.atTurnEnd(session(true));
        TurnBoundary.Verdict second = boundary.atTurnEnd(session(true));

        assertAll(
            () -> assertEquals(TurnBoundary.Kind.RESUME, first.kind()),
            () -> assertTrue(first.text().contains("resumption 1 of this step"), first.text()),
            () -> assertTrue(second.text().contains("resumption 2 of this step"), second.text()));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: the same state with an own hire running forwards the turn and counts no try")
    void aRunningOwnHireForwardsAndCountsNoTry() {
        AtomicBoolean running = new AtomicBoolean(true);
        TurnBoundary boundary = new TurnBoundary(waitingWith(true, null, null), new AttemptLedger(), s -> running.get());

        TurnBoundary.Verdict whileRunning = boundary.atTurnEnd(session(true));
        running.set(false);
        TurnBoundary.Verdict afterwards = boundary.atTurnEnd(session(true));

        assertAll(
            () -> assertEquals(TurnBoundary.Kind.FORWARD, whileRunning.kind()),
            () -> assertEquals("", whileRunning.text()),
            () -> assertEquals(TurnBoundary.Kind.RESUME, afterwards.kind(), "CONTROL: without the hire the turn is resumed"),
            () -> assertTrue(afterwards.text().contains("resumption 1 of this step"), "a try was spent while the hire ran: " + afterwards.text()));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: with autonomy off and the same state the verdict is the existing report, whatever the plan's standing says")
    void withAutonomyOffTheExistingReportStays() {
        TurnBoundary boundary = new TurnBoundary(waitingWith(true, null, null), new AttemptLedger(), s -> true);

        TurnBoundary.Verdict verdict = boundary.atTurnEnd(session(false));

        assertAll(
            () -> assertEquals(TurnBoundary.Kind.REPORT, verdict.kind()),
            () -> assertTrue(verdict.text().contains("still waiting"), verdict.text()),
            () -> assertTrue(!verdict.text().contains("Plan standing:"), verdict.text()));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: a ledger with no own plan is resumed as before, and a running own hire does not change that")
    void aLedgerWithNoOwnPlanBehavesAsBefore() {
        TurnBoundary boundary = new TurnBoundary(new Waiting(), new AttemptLedger(), s -> true);

        TurnBoundary.Verdict verdict = boundary.atTurnEnd(session(true));

        assertEquals(TurnBoundary.Kind.RESUME, verdict.kind());
        assertTrue(verdict.text().contains("resumption 1 of this step"), verdict.text());
    }

    @Test
    @DisplayName("28h Stage 28 (i) M2: with autonomy on and a checkpoint reached the turn is reported with the plan's standing and the checkpoint's name, and no try is counted")
    void aReachedCheckpointIsReportedAndCountsNoTry() {
        Waiting ledger = waitingWith(false, "Checkpoint A", null);
        TurnBoundary boundary = new TurnBoundary(ledger, new AttemptLedger(), s -> false);

        TurnBoundary.Verdict verdict = boundary.atTurnEnd(session(true));
        ledger.own = Optional.empty();
        TurnBoundary.Verdict afterwards = boundary.atTurnEnd(session(true));

        assertAll(
            () -> assertEquals(TurnBoundary.Kind.REPORT, verdict.kind()),
            () -> assertTrue(verdict.text().contains("Plan standing:"), verdict.text()),
            () -> assertTrue(verdict.text().contains("Checkpoint reached: Checkpoint A."), verdict.text()),
            () -> assertTrue(afterwards.text().contains("resumption 1 of this step"), "a try was spent by the report: " + afterwards.text()));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M2: with autonomy on and a step of the person next the turn is reported with the plan's standing and the person's step")
    void aStepOfThePersonIsReported() {
        TurnBoundary boundary = new TurnBoundary(waitingWith(false, null, "Sign the release"), new AttemptLedger(), s -> false);

        TurnBoundary.Verdict verdict = boundary.atTurnEnd(session(true));

        assertAll(
            () -> assertEquals(TurnBoundary.Kind.REPORT, verdict.kind()),
            () -> assertTrue(verdict.text().contains("Plan standing:"), verdict.text()),
            () -> assertTrue(verdict.text().contains("Your step: Sign the release."), verdict.text()));
    }
}
