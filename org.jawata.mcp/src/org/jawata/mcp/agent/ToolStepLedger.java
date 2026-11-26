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
    private final java.util.Optional<String> askAs;

    public ToolStepLedger(ToolCalls tools) {
        this(tools, java.util.Optional.empty());
    }

    /**
     * Asks as the session key the brain's own calls carry, so the plans its hires opened are the ones it hears about (S10): a hire another
     * caller is running does not hold this turn.
     */
    public ToolStepLedger(ToolCalls tools, java.util.Optional<String> askAs) {
        this.tools = tools;
        this.askAs = askAs;
    }

    /** What {@code plan_steps} answered to this session, or empty when it could not be asked or refused. */
    private java.util.Optional<JsonNode> ask(PrincipalSession session) {
        ToolResponse response;
        try {
            response = tools.callTool(TOOL, JSON.createObjectNode(),
                askAs.map(org.jawata.mcp.models.CallerIdentity::supplied).orElseGet(session::identity));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
        if (response == null || !response.isSuccess()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(JSON.valueToTree(response.getData()));
    }

    /** No plan is known only when {@code plan_steps} answered and said the ledger holds none; every other case is yes, as the interface says. */
    @Override
    public boolean planKnown(PrincipalSession session) {
        return ask(session).map(data -> data.path("planCount")).filter(JsonNode::isNumber).map(count -> count.asInt() != 0).orElse(true);
    }

    @Override
    public List<Pending> pending(PrincipalSession session) {
        java.util.Optional<JsonNode> data = ask(session);
        if (data.isEmpty()) {
            return List.of();
        }
        JsonNode rows = data.get().path("pending");
        List<Pending> pending = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode step = row.path("step");
            if (row.hasNonNull("planId") && step.hasNonNull("tool")) {
                pending.add(new Pending(row.get("planId").asText(), step.path("index").asInt(), step.get("tool").asText(), row.path("inFlight").asBoolean(false)));
            }
        }
        return pending;
    }
}
