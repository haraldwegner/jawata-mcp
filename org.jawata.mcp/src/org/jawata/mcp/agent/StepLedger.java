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
}
