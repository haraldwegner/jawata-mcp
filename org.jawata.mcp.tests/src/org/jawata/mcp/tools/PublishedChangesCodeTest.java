package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.fixtures.InertShippedTools;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.OperationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A CLIENT THAT MUST DECIDE "DID THIS CALL CHANGE CODE?" READS IT FROM {@code tools/list}.
 *
 * <p>The studio's hook kept three hand-written lists of the engine's code-changing tools and they
 * disagreed (REL44-37). The annotations the engine already published cannot answer the question:
 * {@code readOnlyHint} is false for {@code compile_workspace} and {@code experience}, which change no
 * source, and {@code destructiveHint} is absent for {@code format}, {@code quick_fix},
 * {@code refactoring} and {@code dependency}, which do. The engine has the answer per tool
 * ({@link Tool#changesCode()}, the flag a hired run is classified by); this publishes it as
 * {@code _meta."jawata/changesCode"}.</p>
 */
class PublishedChangesCodeTest {

    private static final String KEY = "jawata/changesCode";

    private static Tool stub(String name, boolean changes) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "stub"; }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }
            @Override public boolean changesCode() { return changes; }
        };
    }

    @SuppressWarnings("unchecked")
    private static Object flagOf(Map<String, Object> definition) {
        Map<String, Object> meta = (Map<String, Object>) definition.get("_meta");
        assertNotNull(meta, definition.get("name") + " must publish _meta: a client cannot ask the tool");
        return meta.get(KEY);
    }

    @Test
    @DisplayName("a tool's flag is published as it answers it, both ways")
    void theFlagIsPublishedAsTheToolAnswersIt() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub("stub_writer", true));
        registry.register(stub("stub_reader", false));

        Map<String, Object> byName = new LinkedHashMap<>();
        for (Map<String, Object> def : registry.getToolDefinitions()) {
            byName.put((String) def.get("name"), flagOf(def));
        }
        assertEquals(Boolean.TRUE, byName.get("stub_writer"));
        assertEquals(Boolean.FALSE, byName.get("stub_reader"));
    }

    @Test
    @DisplayName("every shipped tool publishes its own flag, and it separates what the annotations could not")
    void everyShippedToolPublishesItsOwnFlag() {
        // BORROW AND RETURN: ToolRegistry.register publishes into the process-wide operation registry
        // (see ReadOnlyHintAnnotationTest), so a test that registers the real tools restores it.
        OperationRegistry ops = OperationRegistry.theRegistry();
        OperationRegistry.Snapshot borrowed = ops.snapshot();
        try {
            ToolRegistry registry = new ToolRegistry();
            List<Tool> shipped = InertShippedTools.tools();
            Map<String, Boolean> expected = new LinkedHashMap<>();
            for (Tool tool : shipped) {
                registry.register(tool);
                expected.put(tool.getName(), tool.changesCode());
            }
            assertFalse(expected.isEmpty(), "the product ships tools, or this proves nothing");

            Map<String, Object> published = new LinkedHashMap<>();
            for (Map<String, Object> def : registry.getToolDefinitions()) {
                published.put((String) def.get("name"), flagOf(def));
            }
            assertEquals(expected.keySet(), published.keySet());
            expected.forEach((name, flag) ->
                assertEquals(flag, published.get(name), name + " publishes a flag different from its own answer"));

            // The five the annotations get wrong or leave silent, named so the field cannot be
            // replaced by them without this failing.
            for (String writes : List.of("format", "quick_fix", "refactoring", "dependency", "rename_symbol")) {
                assertEquals(Boolean.TRUE, published.get(writes), writes + " changes code");
            }
            for (String reads : List.of("compile_workspace", "run_tests", "experience", "hire", "search_symbols")) {
                assertEquals(Boolean.FALSE, published.get(reads), reads + " changes no source");
            }
        } finally {
            ops.restore(borrowed);
        }
    }
}
