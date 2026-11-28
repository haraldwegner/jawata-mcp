package org.jawata.mcp.execution;

import java.util.Optional;

import org.jawata.mcp.learn.PlanLedger;

/**
 * When the engine sends a pm back to its sprint plan at the end of a turn (28h Stage 28 (i), M3): the plan it entered has an open step and
 * nothing holds the pm from doing it. A checkpoint just reached, a step of the person standing next, a hire of the pm's own still running and a
 * pm whose context reached its limit each hold it, and then the pm is not sent back.
 */
public final class PlanStandingSendBack {

    /** What a pm that is sent back is told under its plan's standing. */
    static final String DO_IT = "The plan has an open step and nothing blocks you: do it, or say what blocks you.";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private PlanStandingSendBack() {
    }

    /**
     * What a lead is told about the plan it entered when its turn ends with a step it can do now.
     *
     * @param plans      the ledger that holds the plan
     * @param hires      the registry of the running hires
     * @param leadPlanId the plan id of the lead's own hire, which owns the plan it entered
     * @return the plan's standing as an open check of the plan, or empty when the lead has no such plan or something holds it
     */
    public static Optional<AcpBrainSession.OpenCheck> of(PlanLedger plans, HireRegistry hires, String leadPlanId) {
        return plans.standingOf(leadPlanId)
            .filter(org.jawata.mcp.learn.Standing::canGoOn)
            .filter(standing -> hires.runningNamesFor(leadPlanId).isEmpty())
            .filter(standing -> hires.replacementNotice(leadPlanId).isEmpty())
            .map(standing -> new AcpBrainSession.OpenCheck(standing.passed(),
                HireReport.standingLine(JSON.valueToTree(standing.asMap())) + "\n" + DO_IT, true));
    }
}
