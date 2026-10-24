package org.jawata.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.change.OperationRegistry;
import org.jawata.mcp.fixtures.InertShippedTools;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * THE 44 TOOLS THE PRODUCT PUBLISHES ARE THE 44 IT PUBLISHED BEFORE THE PRODUCER WAS SPLIT (Sprint 28k, E12).
 *
 * <p>E12 divides {@code ShippedTools.build} into the tools that name no language and the contribution of the Java provider, and a
 * client must not be able to tell: the same names, in the same order, with the same input schemas. The record is
 * {@code build/28k-golden-tools.json}, taken from the running engine before the split began. This is the unit-level half of the
 * check; the built dist is held to the same file by {@code build/28k-e2e.sh}.</p>
 *
 * <p>What a client sees is the registry's own published list, not the producer's list: the registry puts the read-only tools first
 * and keeps registration order within each tier, so a producer that reorders its list changes what a truncating client is shown.
 * The comparison therefore runs on the registry's output.</p>
 *
 * <p>The last three tests are the control: the comparison is handed a list with two names swapped, with one schema replaced and with
 * one tool dropped, and must say so each time. A comparison that cannot fail would pass the real list for any reason.</p>
 */
class TheFortyFourPublishedToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode golden() {
        try {
            return JSON.readTree(Files.readString(
                EveryCrossBundlePackageIsImportedTest.repoRoot().resolve("build").resolve("28k-golden-tools.json"),
                StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("cannot read build/28k-golden-tools.json: " + e + " - this check did NOT run", e);
        }
    }

    /**
     * What a client sees, for the shipped tools built with nothing behind the wiring. BORROW AND RETURN: registering publishes
     * into the process-wide operation registry, which later tests in this JVM would otherwise see (the lesson of
     * {@code ReadOnlyHintAnnotationTest}).
     */
    private static List<Map<String, Object>> published() {
        OperationRegistry operations = OperationRegistry.theRegistry();
        OperationRegistry.Snapshot borrowed = operations.snapshot();
        try {
            ToolRegistry registry = new ToolRegistry();
            InertShippedTools.tools().forEach(registry::register);
            return registry.getToolDefinitions();
        } finally {
            operations.restore(borrowed);
        }
    }

    /** Empty when the list equals the golden in names, order and input schemas; otherwise what differs. */
    private static String difference(List<Map<String, Object>> published, JsonNode golden) {
        List<String> names = new ArrayList<>();
        published.forEach(definition -> names.add(String.valueOf(definition.get("name"))));
        List<String> expected = new ArrayList<>();
        golden.forEach(tool -> expected.add(tool.get("name").asText()));
        if (!names.equals(expected)) {
            return "names or order differ: published " + names + ", golden " + expected;
        }
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            JsonNode schema = JSON.valueToTree(published.get(i).get("inputSchema"));
            if (!schema.equals(golden.get(i).get("inputSchema"))) {
                changed.add(names.get(i));
            }
        }
        return changed.isEmpty() ? "" : "input schemas differ for " + changed;
    }

    @Test
    @DisplayName("the shipped tools publish the golden: 44 names, in order, with the same input schemas")
    void thePublishedListIsTheGolden() {
        List<Map<String, Object>> published = published();

        assertEquals(44, published.size(), "the product ships 44 tools");
        assertEquals("", difference(published, golden()));
    }

    @Test
    @DisplayName("control: two names swapped is seen")
    void aReorderedListIsSeen() {
        List<Map<String, Object>> reordered = new ArrayList<>(published());
        Collections.swap(reordered, 1, 2);

        assertTrue(difference(reordered, golden()).startsWith("names or order differ"),
            "a comparison that cannot see a swap would pass any producer");
    }

    @Test
    @DisplayName("control: a changed input schema is seen, and named")
    void aChangedSchemaIsSeen() {
        List<Map<String, Object>> changed = new ArrayList<>(published());
        Map<String, Object> definition = new LinkedHashMap<>(changed.get(0));
        definition.put("inputSchema", Map.of("type", "object"));
        changed.set(0, definition);

        String difference = difference(changed, golden());
        assertTrue(difference.startsWith("input schemas differ") && difference.contains("health_check"), difference);
    }

    @Test
    @DisplayName("control: a dropped tool is seen")
    void aDroppedToolIsSeen() {
        List<Map<String, Object>> dropped = new ArrayList<>(published());
        dropped.remove(dropped.size() - 1);

        assertFalse(difference(dropped, golden()).isEmpty(), "a tool lost from the list must not pass");
    }
}
