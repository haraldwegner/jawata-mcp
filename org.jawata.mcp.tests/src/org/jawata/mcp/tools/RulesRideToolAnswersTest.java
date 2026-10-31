package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S14 (D23) — the dispatch reads the rules lane: an accepted rule labelled {@code tool:<name>}
 * rides every answer of that tool, a changed rule changes the next answer, and no code changes.
 *
 * <p>The cases drive the published verbs ({@code promote_rule}, {@code amend_rule}, {@code retire_rule}) and the
 * registry's own {@code callTool}, the way an agent and the studio reach them. The controls differ from the
 * positive case in exactly one thing each: a rule with no label, a rule labelled for another tool, a registry
 * never given the store.</p>
 */
class RulesRideToolAnswersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LEAD = "Rules that bind compile_workspace";

    private H2ExperienceStore store;
    private ExperienceTool experience;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        experience = new ExperienceTool(() -> null, store);
        registry = registryWith(true);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ToolRegistry registryWith(boolean withStore) {
        ToolRegistry reg = new ToolRegistry();
        if (withStore) {
            reg.setRuleStore(() -> this.store);
        }
        reg.register(tool("compile_workspace", a -> ToolResponse.success(Map.of("ok", true))));
        reg.register(tool("get_diagnostics", a -> ToolResponse.success(Map.of("ok", true))));
        reg.register(tool("refuses", a -> ToolResponse.error("NOPE", "refused on purpose", "none")));
        return reg;
    }

    private static Tool tool(String name, Function<JsonNode, ToolResponse> exec) {
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

    private ToolResponse exec(String kind, java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode a = MAPPER.createObjectNode();
        a.put("kind", kind);
        fill.accept(a);
        return experience.execute(a);
    }

    @SuppressWarnings("unchecked")
    private String idOf(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (String) ((Map<String, Object>) r.getData()).get("id");
    }

    /** A rule, accepted at once, bound by the label given. */
    private String rule(String summary, String label) {
        String source = idOf(exec("record", a -> {
            a.put("type", "lesson");
            a.put("summary", "a source lesson for: " + summary);
            a.put("situation", "when a stage is about to be called done");
            a.put("verdict", "failed_avoid");
        }));
        return idOf(exec("promote_rule", a -> {
            a.put("summary", summary);
            a.put("situation", "when compile_workspace has just answered");
            a.put("status", "accepted");
            if (label != null) {
                a.put("operation", label);
            }
            a.putArray("ids").add(source);
        }));
    }

    private ToolResponse call(ToolRegistry reg, String tool) throws Exception {
        return reg.callTool(tool, MAPPER.readTree("{}"), CallerIdentity.supplied("s1"));
    }

    private static String steeringOf(ToolResponse r) {
        return r.getMeta() == null ? "" : String.valueOf(r.getMeta().getSteering());
    }

    @Test
    void aRuleLabelledForAToolRidesThatToolsAnswer() throws Exception {
        rule("read the error count and say it in the same sentence as the verdict", "tool:compile_workspace");

        String steering = steeringOf(call(registry, "compile_workspace"));

        assertTrue(steering.contains(LEAD), steering);
        assertTrue(steering.contains("read the error count and say it in the same sentence as the verdict"), steering);
    }

    @Test
    void aRuleWithNoLabelOrForAnotherToolDoesNotRideIt() throws Exception {
        rule("an unlabelled rule binds seats and no tool", null);
        rule("a rule for another tool", "tool:get_diagnostics");

        String steering = steeringOf(call(registry, "compile_workspace"));

        assertFalse(steering.contains(LEAD), "THE CONTROL: the same call, with only rules that name no such tool: " + steering);
        assertFalse(steering.contains("an unlabelled rule"), steering);
        assertTrue(steeringOf(call(registry, "get_diagnostics")).contains("a rule for another tool"),
            "the other tool's rule rides the other tool's answer");
    }

    @Test
    void changingTheRuleChangesTheNextAnswerWithNoCodeChange() throws Exception {
        String id = rule("first wording of the rule", "tool:compile_workspace");
        String before = steeringOf(call(registry, "compile_workspace"));
        assertTrue(before.contains("first wording of the rule"), "before the amendment: " + before);

        ToolResponse amendment = exec("amend_rule", a -> {
            a.put("id", id);
            a.put("summary", "second wording of the rule");
        });
        String second = idOf(amendment);
        String amended = steeringOf(call(registry, "compile_workspace"));
        assertTrue(amended.contains("second wording of the rule"), "after the amendment: " + amended + " / " + amendment.getData());
        assertFalse(amended.contains("first wording of the rule"), "the superseded version no longer rides: " + amended);

        ToolResponse retired = exec("retire_rule", a -> a.put("id", second));
        assertTrue(retired.isSuccess(), "retire: " + retired.getError());
        assertFalse(steeringOf(call(registry, "compile_workspace")).contains(LEAD),
            "a retired rule stops riding the answer");
    }

    @Test
    void aRefusalCarriesTheRulesOfItsToolToo() throws Exception {
        rule("when this tool refuses, say what it refused before you try another", "tool:refuses");

        ToolResponse refused = call(registry, "refuses");

        assertFalse(refused.isSuccess());
        assertTrue(steeringOf(refused).contains("Rules that bind refuses"), steeringOf(refused));
    }

    @Test
    void aRegistryNeverGivenTheStoreCarriesNoRules() throws Exception {
        rule("a rule that exists in the store", "tool:compile_workspace");

        assertFalse(steeringOf(call(registryWith(false), "compile_workspace")).contains(LEAD),
            "THE CONTROL: the store holds the rule and the registry was never given it");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aToolLabelIsNotDeadWhenTheToolExistsAndIsListedWhenItDoesNot() throws Exception {
        java.nio.file.Path seats = java.nio.file.Files.createTempDirectory("jawata-seats-");
        try {
            java.nio.file.Files.writeString(seats.resolve("worker.md"),
                "---\nname: worker\nbrain: claude-code\nttl_secs: 30\n---\n\nYou are the worker.\n");
            rule("rides compile_workspace", "tool:compile_workspace");
            rule("names a tool that does not exist", "tool:compile_workspce");
            experience.setToolNames(registry::getToolNames);

            Map<String, Object> asked = (Map<String, Object>) ((Map<String, Object>) exec("review_sweep",
                a -> a.put("seats", seats.toString())).getData()).get("rulesNobodyAsks");

            assertEquals(Set.of("tool:compile_workspce"), ((Map<String, Object>) asked.get("unasked")).keySet(),
                "the real tool's label is asked for; the typo is the only dead label: " + asked);
        } finally {
            try (var files = java.nio.file.Files.list(seats)) {
                files.forEach(p -> p.toFile().delete());
            }
            seats.toFile().delete();
        }
    }

    @Test
    void theRulesLaneIsReadAgainOnlyWhenTheStoreSaysItChanged() {
        int[] reads = {0};
        org.jawata.mcp.knowledge.ExperienceStore counting = (org.jawata.mcp.knowledge.ExperienceStore) java.lang.reflect.Proxy.newProxyInstance(
            org.jawata.mcp.knowledge.ExperienceStore.class.getClassLoader(), new Class<?>[] {org.jawata.mcp.knowledge.ExperienceStore.class},
            (proxy, method, args) -> {
                if (method.getName().equals("all")) {
                    reads[0]++;
                }
                try {
                    return method.invoke(store, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        assertNotNull(store.changeStamp(), "the store this case runs on says when it changes, or there is no cache to show");
        rule("first rule for the cache case", "tool:compile_workspace");

        org.jawata.mcp.knowledge.SeatRules.bindingOnTool(counting, "compile_workspace");
        org.jawata.mcp.knowledge.SeatRules.bindingOnTool(counting, "compile_workspace");
        org.jawata.mcp.knowledge.SeatRules.bindingOnTool(counting, "get_diagnostics");
        assertEquals(1, reads[0], "three asks of an unchanged store read the lane once: the others are cache hits");

        rule("second rule for the cache case", "tool:compile_workspace");
        java.util.List<String> after = org.jawata.mcp.knowledge.SeatRules.bindingOnTool(counting, "compile_workspace");
        assertEquals(2, reads[0], "a store that changed is read again: the cached lane was stale");
        assertTrue(after.stream().anyMatch(t -> t.contains("second rule for the cache case")), after.toString());
    }

    @Test
    void aStoreWhoseRulesCannotBeReadDoesNotFailTheCall() throws Exception {
        ToolRegistry broken = new ToolRegistry();
        broken.setRuleStore(() -> {
            throw new IllegalStateException("the store is gone");
        });
        broken.register(tool("compile_workspace", a -> ToolResponse.success(Map.of("ok", true))));

        ToolResponse answer = call(broken, "compile_workspace");

        assertNotNull(answer);
        assertTrue(answer.isSuccess(), "a rule is guidance; the call it rides is not a gate on it");
    }
}
