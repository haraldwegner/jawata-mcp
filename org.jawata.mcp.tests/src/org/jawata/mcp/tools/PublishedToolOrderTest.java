package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jawata-mcp#22 — <b>a client that truncates the tool list by position must not be handed the
 * workspace-destroying tools first.</b>
 *
 * <p>Registration order used to put {@code load_project} (which REPLACES the workspace) and
 * {@code project} (add/remove) in slots 2 and 3, ahead of every analysis tool. Observed live
 * on 2026-08-15: an agent that could not find a class reached for the workspace-replacing
 * tool, because that is what it had been shown. A safety property, not a discoverability
 * nicety.</p>
 *
 * <p>The registry is populated with STUBS NAMED AS THE REAL TOOLS ARE, which is sound here
 * for one measured reason and no more: read-only-ness is decided from the tool's NAME
 * ({@code ToolRegistry.isReadOnly}), so a stub called {@code load_project} is classified
 * exactly as the real one is. It exercises the published ordering; it says nothing about
 * which tools production registers, which is asserted elsewhere.</p>
 */
class PublishedToolOrderTest {

    private static Tool stub(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "stub"; }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }
        };
    }

    /**
     * The order the issue MEASURED, reproduced deliberately: a status tool, then the two
     * workspace-mutating ones, then the analysis. Registering them already-sorted would make
     * every assertion below pass against no sort at all.
     */
    private static ToolRegistry registryInTheOrderTheIssueFound() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of(
                "health_check", "load_project", "project",
                "search_symbols", "go_to_definition", "find_references",
                "rename_symbol", "inspect", "analyze", "get_diagnostics")) {
            registry.register(stub(name));
        }
        return registry;
    }

    @SuppressWarnings("unchecked")
    private static boolean readOnly(Map<String, Object> definition) {
        Object annotations = definition.get("annotations");
        return annotations instanceof Map<?, ?> map
            && Boolean.TRUE.equals(((Map<String, Object>) map).get("readOnlyHint"));
    }

    private static String name(Map<String, Object> definition) {
        return String.valueOf(definition.get("name"));
    }

    @Test
    @DisplayName("mcp#22: no mutating tool is published ahead of a read-only one")
    void readOnlyToolsComeFirst() {
        List<Map<String, Object>> definitions =
            registryInTheOrderTheIssueFound().getToolDefinitions();
        assertTrue(definitions.size() >= 10, "proof of life: got " + definitions.size());

        List<String> misplaced = new ArrayList<>();
        boolean seenMutating = false;
        String firstMutating = null;
        for (Map<String, Object> definition : definitions) {
            if (readOnly(definition)) {
                if (seenMutating) {
                    misplaced.add(name(definition) + " (read-only, published after "
                        + firstMutating + ")");
                }
            } else if (!seenMutating) {
                seenMutating = true;
                firstMutating = name(definition);
            }
        }
        assertEquals(List.of(), misplaced,
            "the read-only tools must form an unbroken prefix, or a client that keeps only the"
                + " first N is handed mutation instead of analysis. Published: "
                + definitions.stream().map(PublishedToolOrderTest::name).toList());
    }

    @Test
    @DisplayName("mcp#22: the first slots a truncating client keeps carry no mutating tool")
    void theFirstSlotsAreAnalysis() {
        // The issue's own measure: it counted the first FIVE slots and found TWO
        // workspace-mutating tools among them.
        List<Map<String, Object>> published =
            registryInTheOrderTheIssueFound().getToolDefinitions();
        List<String> mutatingInFirstFive = published.subList(0, 5).stream()
            .filter(d -> !readOnly(d)).map(PublishedToolOrderTest::name).toList();
        assertEquals(List.of(), mutatingInFirstFive,
            "the first five slots still carry mutating tools: "
                + published.subList(0, 5).stream().map(PublishedToolOrderTest::name).toList());
    }

    @Test
    @DisplayName("THE CONTROL — ordering publishes the same tools, no more and no fewer")
    void theSortChangesOrderAndNothingElse() {
        // Without this, a sort that dropped or duplicated an entry would satisfy both
        // assertions above — the shortest list that passes them is one read-only tool.
        ToolRegistry registry = registryInTheOrderTheIssueFound();
        List<String> names = registry.getToolDefinitions().stream()
            .map(PublishedToolOrderTest::name).toList();
        assertAll(
            () -> assertEquals(registry.getToolCount(), names.size(),
                "every registered tool is published: " + names),
            () -> assertEquals(names.size(), names.stream().distinct().count(),
                "no tool is published twice: " + names),
            // And the mutating ones are still THERE, merely later. An "ordering" that
            // published only the read-only tools would pass everything above.
            () -> assertTrue(names.containsAll(List.of("load_project", "project",
                "rename_symbol")), "the mutating tools are still published: " + names));
    }
}
