package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.agent.PrincipalSession;
import org.jawata.mcp.agent.StepLedger;
import org.jawata.mcp.agent.ToolStepLedger;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S10 (the audit's finding F7): a hire another caller is running must not hold a principal's turn. {@code plan_steps} answers
 * for the plans its caller opened and for the plans nobody was recorded for, so two callers on one resident each hear about their own steps
 * and the principal's turn is held only by its own hires.
 *
 * <p>Each case comes with its control: a filter that hid everything, or hid nothing, would pass one half and fail the other.</p>
 */
class PlanStepsAnswersForItsCallerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanLedger plans = new PlanLedger();
    private final PlanStepsTool tool = new PlanStepsTool(plans);

    private PlanId openedFor(String owner) throws Exception {
        PlanId plan = plans.open(List.of(new GatedStep(0, "compile_workspace", JSON.readTree("{}"))));
        if (owner != null) {
            plans.ownedBy(plan, owner);
        }
        return plan;
    }

    @SuppressWarnings("unchecked")
    private static List<String> idsIn(ToolResponse response) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) response.getData()).get("pending");
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add((String) row.get("planId")));
        return ids;
    }

    private List<String> listedFor(CallerIdentity caller) {
        return idsIn(caller.speaking(() -> tool.execute(JSON.createObjectNode())));
    }

    @Test
    @DisplayName("a caller hears about the plans it opened and not about another caller's, and a plan nobody was recorded for is heard by every caller")
    void aCallerHearsOnlyItsOwnPlans() throws Exception {
        PlanId mine = openedFor("principal-1");
        PlanId theirs = openedFor("someone-else");
        PlanId nobodys = openedFor(null);
        CallerIdentity principal = CallerIdentity.supplied("principal-1");
        CallerIdentity other = CallerIdentity.supplied("someone-else");

        assertAll(
            () -> assertEquals(List.of(mine.toString(), nobodys.toString()), listedFor(principal),
                "the principal hears its own plan and the ownerless one, not the other caller's"),
            () -> assertEquals(List.of(theirs.toString(), nobodys.toString()), listedFor(other),
                "the control: the other caller hears its own plan, so the filter is not hiding everything"),
            () -> assertTrue(listedFor(CallerIdentity.supplied("a-third-caller")).contains(nobodys.toString()),
                "a caller that opened nothing still hears the plan nobody owns"));
    }

    @Test
    @DisplayName("a call made outside the registry has no caller to answer for and sees every plan")
    void aCallWithNoCallerSeesEveryPlan() throws Exception {
        PlanId mine = openedFor("principal-1");
        PlanId theirs = openedFor("someone-else");

        assertEquals(List.of(mine.toString(), theirs.toString()), idsIn(tool.execute(JSON.createObjectNode())));
    }

    @Test
    @DisplayName("a face opened over a brain asks plan_steps under that brain's own tool-session key at the end of a turn, and under the host session when the brain has none")
    void theFaceAsksAsTheBrainsKey() throws Exception {
        List<CallerIdentity> askedAs = new ArrayList<>();
        ToolCalls recording = new ToolCalls() {
            @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                // Only the question about waiting steps: the face's recorder also calls tools after a turn, as the session.
                if ("plan_steps".equals(name)) {
                    askedAs.add(caller);
                }
                return ToolResponse.success(Map.of("pending", List.of()));
            }
            @Override public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
        org.jawata.mcp.engine.EngineApi engine = (org.jawata.mcp.engine.EngineApi) java.lang.reflect.Proxy.newProxyInstance(
            org.jawata.mcp.engine.EngineApi.class.getClassLoader(), new Class<?>[] {org.jawata.mcp.engine.EngineApi.class},
            (proxy, method, args) -> "tools".equals(method.getName()) ? recording : null);

        org.jawata.mcp.agent.AgentBrain keyed = new org.jawata.mcp.agent.AgentBrain() {
            @Override public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                chunks.accept("ok");
            }
            @Override public java.util.Optional<String> toolSessionKey() {
                return java.util.Optional.of("principal-face-key");
            }
        };
        org.jawata.mcp.agent.AgentBrain keyless = (prompt, chunks) -> chunks.accept("ok");

        promptOnce(engine, keyed);
        CallerIdentity afterKeyed = askedAs.get(askedAs.size() - 1);
        askedAs.clear();
        promptOnce(engine, keyless);

        assertAll(
            () -> assertEquals(CallerIdentity.supplied("principal-face-key"), afterKeyed,
                "the face did not ask as the brain's key, so the principal's turn would be held by every caller's hires"),
            () -> assertTrue(!askedAs.isEmpty() && !CallerIdentity.supplied("principal-face-key").equals(askedAs.get(0)),
                "the control: a brain with no key is asked about as the host session"));
    }

    private static void promptOnce(org.jawata.mcp.engine.EngineApi engine, org.jawata.mcp.agent.AgentBrain brain) {
        List<JsonNode> said = new ArrayList<>();
        org.jawata.mcp.agent.AgentFace face = new org.jawata.mcp.agent.AgentFace(() -> List.of());
        face.attach(engine);
        org.jawata.mcp.agent.AcpServer server = face.open(brain, message -> said.add(JSON.valueToTree(message)), null);
        server.receive(org.jawata.mcp.protocol.JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow()
            .path("result").path("sessionId").asText();
        com.fasterxml.jackson.databind.node.ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "go");
        server.receive(org.jawata.mcp.protocol.JsonRpcMessage.request(2, "session/prompt", params));
    }

    @Test
    @DisplayName("the turn boundary asks as the session key the brain's own calls carry, and as the host session when the brain has none")
    void theLedgerAsksAsTheBrainsKey() {
        List<CallerIdentity> askedAs = new ArrayList<>();
        ToolCalls recording = new ToolCalls() {
            @Override public ToolResponse callTool(String name, com.fasterxml.jackson.databind.JsonNode arguments, CallerIdentity caller) {
                askedAs.add(caller);
                return ToolResponse.success(Map.of("pending", List.of()));
            }
            @Override public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
        PrincipalSession session = new PrincipalSession("host-session", "/w", false);
        StepLedger keyed = new ToolStepLedger(recording, java.util.Optional.of("principal-xyz"));
        StepLedger keyless = new ToolStepLedger(recording);

        keyed.pending(session);
        keyless.pending(session);

        assertAll(
            () -> assertEquals(CallerIdentity.supplied("principal-xyz"), askedAs.get(0),
                "asked as the brain's key, which is the owner its hires' plans were recorded under"),
            () -> assertEquals(session.identity(), askedAs.get(1),
                "the control: with no key the ledger asks as the host session, as it did before"));
    }
}
