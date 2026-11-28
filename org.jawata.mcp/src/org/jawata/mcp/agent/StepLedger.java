package org.jawata.mcp.agent;

import java.util.List;

/**
 * Where the agent asks which steps are still waiting for their gate (Sprint 28h, S8, D18). It holds no state of its own: the answer
 * comes from the one class that does, so the turn boundary and the hook that came before it cannot disagree about a step.
 */
@FunctionalInterface
public interface StepLedger {

    /**
     * A step that has not passed its gate.
     *
     * @param planId   the plan it belongs to
     * @param index    its position in the plan
     * @param gateTool the tool whose passing call would move the plan past it
     */
    record Pending(String planId, int index, String gateTool, boolean inFlight) {

        public Pending(String planId, int index, String gateTool) {
            this(planId, index, gateTool, false);
        }
    }

    /** The steps still waiting, oldest plan first; empty when none is, and when the ledger cannot be asked. */
    List<Pending> pending(PrincipalSession session);

    /**
     * Whether any plan is known to this ledger for {@code session}, so that an empty {@link #pending} can be told apart: no step is open in a
     * plan that is known, or there is no plan to open a step in. A ledger that cannot say answers yes, and an empty list then means no step is open.
     */
    default boolean planKnown(PrincipalSession session) {
        return true;
    }

    /**
     * Where the sprint plan {@code session} entered stands, as the engine answered it (28h Stage 28 (i), M1, M2).
     *
     * @param goOn         whether nothing of the plan's own holds the lead: a step is open, no checkpoint was just reached and no step of the
     *                     person stands next
     * @param planStanding the plan's standing as the engine answered it: passed, total, next, nextCheckpoint, forPerson, open,
     *                     checkpointReached, personNext, reachedCheckpoint and personStep
     */
    record OwnPlan(boolean goOn, com.fasterxml.jackson.databind.JsonNode planStanding) {
    }

    /** The caller's own sprint plan with its standing; empty when it has none or the ledger cannot say, which is every ledger that knows no marks. */
    default java.util.Optional<OwnPlan> ownPlan(PrincipalSession session) {
        return java.util.Optional.empty();
    }
}
