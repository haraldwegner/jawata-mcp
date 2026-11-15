package org.jawata.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Which steps are still waiting for their gate (Sprint 28h, S8, D18). It reads the plan ledger that the hire path opens plans in and
 * that only a passing gate moves, and changes nothing: the agent asks it at the end of a brain's turn whether a step is pending, so the
 * answer comes from the one class that holds step state and not from a second copy kept by the asker.
 */
public final class PlanStepsTool implements Tool {

    private final PlanLedger plans;

    public PlanStepsTool(PlanLedger plans) {
        this.plans = plans;
    }

    @Override
    public String getName() {
        return "plan_steps";
    }

    @Override
    public String getDescription() {
        return """
            The steps still waiting for their gate, read from the plan ledger and changing nothing.

            USAGE: plan_steps()

            Answers {pending: [{planId, passedStep, inFlight, step: {index, tool, arguments}}]}, oldest plan first. inFlight is true
            when a hire of that plan is running right now, so the step is waiting because someone is working on it. A plan whose every step has
            passed is not listed. A plan advances only when its next step's gate call answered and reported no errors, so an empty
            list means no step is waiting, and a listed plan names the gate tool that would move it.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        List<Map<String, Object>> pending = new ArrayList<>();
        // A caller answers for the plans it opened and for those nobody was recorded for: a hire another caller is running does not hold this
        // caller's turn (S10). A call made outside the registry has no caller to answer for and sees every plan.
        List<PlanLedger.Pending> mine = org.jawata.mcp.models.CallerIdentity.serving()
            .map(caller -> plans.pendingFor(caller.id()))
            .orElseGet(plans::pending);
        for (PlanLedger.Pending open : mine) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("index", open.next().index());
            step.put("tool", open.next().tool());
            step.put("arguments", open.next().gateArguments());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("planId", open.id().toString());
            row.put("passedStep", open.passedStep());
            row.put("step", step);
            // A step of a plan whose hire is running is waiting because the hire is working on it (Stage 24, M11), not because it was left behind.
            row.put("inFlight", org.jawata.mcp.execution.HireRegistry.shared().isRunningPlan(open.id().toString()));
            pending.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pending", pending);
        return ToolResponse.success(data);
    }
}
