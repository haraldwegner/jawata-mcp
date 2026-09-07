package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.learn.ToolExperience;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.3.1: the D2 justification-cost, ENFORCED. The signed Sprint-26a D2 body
 * says the injector surfaces the precedent "as a steer with a
 * justification-cost to defect — not an optional hint the agent may ignore …
 * enforced-by-default; doing otherwise costs a written justification".
 *
 * <p>v3.3.0 shipped that cost as WORDS ONLY — the steer described a charge that
 * nothing levied, so the push was advisory and the clause was narrowed. An
 * external code review against the signed spec caught it. These tests pin the
 * charge itself: refused BEFORE dispatch, payable with a written reason, and
 * scoped so plain calls are untouched.</p>
 */
class PrecedentEnforcementTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static Tool mock(String name, Function<JsonNode, ToolResponse> exec) {
        return new Tool() {
            @Override public String getName() {
                return name;
            }
            @Override public String getDescription() {
                return name;
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return exec.apply(arguments);
            }
        };
    }

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A registry whose retriever reports {@code tool} as having reverted here before. */
    private static ToolRegistry registryWarningAbout(String tool, boolean[] ran) {
        ToolRegistry reg = new ToolRegistry();
        reg.setPrecedentRetriever((query, limit) -> List.of(
            new ToolExperience("s1", tool + " com.foo.Bar", tool,
                ToolExperience.OUTCOME_REVERTED, "{}")));
        reg.register(mock(tool, a -> {
            ran[0] = true;
            return ToolResponse.success(Map.of("ok", true));
        }));
        reg.register(mock("analyze", a -> ToolResponse.success(Map.of("ok", true))));
        return reg;
    }

    /**
     * mcp#31 — <b>the choke's own meta-argument is DECLARED, not merely accepted.</b>
     *
     * <p>{@code precedentOverride} is read by the registry and stripped before any tool sees
     * it, so no tool's own schema ever mentioned it. A client reading {@code tools/list} could
     * not discover it at all: the only place it appeared was inside the refusal text that
     * tells an agent to use it — which is the one moment the agent has already been blocked.
     * That is the parameter documented by the failure it causes.</p>
     *
     * <p>It is declared HERE rather than in each tool because the choke owns it. It applies to
     * every call, it is consumed by the registry, and it is removed before dispatch — so
     * putting it in forty-odd hand-written schemas would be forty copies of one fact, which is
     * the shape this sprint spent a stage deriving away.</p>
     */
    @Test
    @DisplayName("mcp#31: every published schema declares precedentOverride, with a description")
    void theChokesOwnMetaArgumentIsDeclared() {
        ToolRegistry reg = registryWarningAbout("move", new boolean[1]);
        // A tool whose schema ALREADY has properties. Without it, every published schema here
        // is empty, the "no properties" assertion answers first, and the containsKey below is
        // never reached — so removing the declaration would fail on the wrong line and prove
        // less than it appears to. It also pins the half an empty schema cannot: the tool's
        // own parameters must SURVIVE, not be replaced by the one being added.
        reg.register(new Tool() {
            @Override public String getName() {
                return "already_has_properties";
            }
            @Override public String getDescription() {
                return "a tool with a schema of its own";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object",
                    "properties", Map.of("filePath", Map.of("type", "string")));
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of("ok", true));
            }
        });

        List<Map<String, Object>> definitions = reg.getToolDefinitions();

        @SuppressWarnings("unchecked")
        Map<String, Object> ownProperties = (Map<String, Object>)
            ((Map<String, Object>) definitions.stream()
                .filter(d -> "already_has_properties".equals(d.get("name")))
                .findFirst().orElseThrow()
                .get("inputSchema")).get("properties");
        assertTrue(ownProperties.containsKey("filePath"),
            "the tool's own parameters must survive the declaration: " + ownProperties.keySet());
        // Asserted HERE, before the loop, and deliberately. The loop meets an empty-schema
        // mock first, so its own "no properties" assertion answers before it ever reaches a
        // populated schema — meaning a mutation that removed the declaration would fail on
        // the empty case and never exercise this one. This is the assertion the loop cannot
        // reach, on the only tool here that has a schema worth adding to.
        assertTrue(ownProperties.containsKey("precedentOverride"),
            "a tool with a schema of its own must gain the declaration too: "
                + ownProperties.keySet());

        // Proof of life: without it the loop below asserts nothing and reads as a pass.
        assertFalse(definitions.isEmpty(), "no tools published — the loop would prove nothing");

        for (Map<String, Object> def : definitions) {
            String name = String.valueOf(def.get("name"));
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) def.get("inputSchema");
            assertNotNull(schema, name + " publishes no inputSchema at all");

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertNotNull(properties,
                name + " publishes a schema with no properties, so it can declare nothing");
            assertTrue(properties.containsKey("precedentOverride"),
                name + " accepts precedentOverride and does not declare it: " + properties.keySet());

            @SuppressWarnings("unchecked")
            Map<String, Object> declared = (Map<String, Object>) properties.get("precedentOverride");
            String description = String.valueOf(declared.get("description"));
            // A declared name with no description is discoverable and still unusable: the
            // caller learns a key exists without learning what to put in it.
            assertFalse(description.isBlank(), name + " declares it with no description");
        }
    }

    @Test
    void defecting_from_a_surfaced_precedent_without_a_reason_is_refused() throws Exception {
        boolean[] ran = {false};
        ToolRegistry reg = registryWarningAbout("extract", ran);

        ToolResponse warned = reg.callTool("analyze", json("{\"typeName\":\"com.foo.Bar\"}"), "s1");
        assertNotNull(warned.getMeta(), "precondition: the push produced steering");
        assertTrue(warned.getMeta().getSteering().contains("⚠ PRECEDENT"),
            "precondition: the negative precedent was SURFACED");

        ToolResponse refused = reg.callTool("extract", json("{\"symbol\":\"com.foo.Bar\"}"), "s1");
        assertFalse(ran[0], "the tool must NOT run — the cost is charged BEFORE dispatch");
        assertFalse(refused.isSuccess(), "an unjustified defection is REFUSED, not merely nudged");
        assertNotNull(refused.getError(), "a refusal carries a structured error");
        String said = refused.getError().getMessage() + " " + refused.getError().getHint();
        assertTrue(said.contains("precedentOverride"),
            "the refusal names exactly how to pay the cost: " + said);
        assertTrue(said.contains("extract"), "and which tool it is charging for: " + said);
    }

    @Test
    void a_written_justification_pays_the_cost_and_the_call_proceeds() throws Exception {
        boolean[] ran = {false};
        ToolRegistry reg = registryWarningAbout("extract", ran);
        reg.callTool("analyze", json("{\"typeName\":\"com.foo.Bar\"}"), "s1");

        ToolResponse ok = reg.callTool("extract", json("{\"symbol\":\"com.foo.Bar\","
            + "\"precedentOverride\":\"the earlier revert was a bad range; this is a clean block\"}"),
            "s1");
        assertTrue(ran[0], "a written reason pays the cost — the tool runs");
        assertTrue(ok.isSuccess(), "and the call succeeds");
    }

    @Test
    void a_call_with_no_surfaced_precedent_is_untouched() throws Exception {
        boolean[] ran = {false};
        ToolRegistry reg = new ToolRegistry();
        reg.register(mock("extract", a -> {
            ran[0] = true;
            return ToolResponse.success(Map.of("ok", true));
        }));

        ToolResponse ok = reg.callTool("extract", json("{\"symbol\":\"com.foo.Bar\"}"), "s1");
        assertTrue(ran[0], "no surfaced precedent, no charge — the tool runs");
        assertTrue(ok.isSuccess(), "plain calls are untouched by the enforcement");
    }

    @Test
    void the_charge_is_scoped_to_the_warned_target() throws Exception {
        boolean[] ran = {false};
        ToolRegistry reg = registryWarningAbout("extract", ran);
        reg.callTool("analyze", json("{\"typeName\":\"com.foo.Bar\"}"), "s1");

        // The warning was surfaced for com.foo.Bar only — a different target owes nothing.
        ToolResponse other = reg.callTool("extract", json("{\"symbol\":\"com.other.Thing\"}"), "s1");
        assertTrue(other.isSuccess(), "an unwarned target is not charged");
        assertTrue(ran[0], "and the tool actually ran for it");
    }
}
