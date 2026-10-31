package org.jawata.mcp.agent;

import java.util.ArrayList;
import java.util.List;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The agent's reading of the engine's step state (Sprint 28h, S8): it asks the {@code plan_steps} tool, which is all the face contract
 * allows and all it needs. A tool that is not there, a refusal, or an answer it cannot read all mean that no step can be shown to be
 * pending, so the turn is forwarded as before instead of being held on a guess.
 */
public final class ToolStepLedger implements StepLedger {

    static final String TOOL = "plan_steps";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCalls tools;

    public ToolStepLedger(ToolCalls tools) {
        this.tools = tools;
    }

    @Override
    public List<Pending> pending(PrincipalSession session) {
        ToolResponse response;
        try {
            response = tools.callTool(TOOL, JSON.createObjectNode(), session.identity());
        } catch (Exception e) {
            return List.of();
        }
        if (response == null || !response.isSuccess()) {
            return List.of();
        }
        JsonNode rows = JSON.valueToTree(response.getData()).path("pending");
        List<Pending> pending = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode step = row.path("step");
            if (row.hasNonNull("planId") && step.hasNonNull("tool")) {
                pending.add(new Pending(row.get("planId").asText(), step.path("index").asInt(), step.get("tool").asText()));
            }
        }
        return pending;
    }
}
