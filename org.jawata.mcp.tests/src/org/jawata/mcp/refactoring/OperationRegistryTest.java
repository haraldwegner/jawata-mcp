package org.jawata.mcp.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1's seam: what may be named as a cure step.
 *
 * <p>The registry replaced a read of one front door's published kinds, which silently
 * forbade every standalone operation. These assert the two halves that matters: a
 * tool's own NAME is an operation, and so is every KIND a parametric front door
 * publishes — and the kinds are READ off the tool rather than listed here, so this test
 * cannot pass while the registry holds a stale copy.</p>
 */
class OperationRegistryTest {

    /** A tool with a kind enum, so the schema-reading path is exercised. */
    private static final class Parametric implements Tool {
        @Override public String getName() {
            return "pretend_front_door";
        }
        @Override public String getDescription() {
            return "kind alpha, kind beta";
        }
        @Override public Map<String, Object> getInputSchema() {
            return Map.of("properties", Map.of(
                "kind", Map.of("type", "string", "enum", List.of("alpha", "beta"))));
        }
        @Override public ToolResponse execute(JsonNode arguments) {
            return ToolResponse.success(Map.of());
        }
    }

    /** A tool with no kinds at all — the ordinary case. */
    private static final class Standalone implements Tool {
        @Override public String getName() {
            return "pretend_standalone";
        }
        @Override public String getDescription() {
            return "does one thing";
        }
        @Override public Map<String, Object> getInputSchema() {
            return Map.of("properties", Map.of("filePath", Map.of("type", "string")));
        }
        @Override public ToolResponse execute(JsonNode arguments) {
            return ToolResponse.success(Map.of());
        }
    }

    @Test
    @DisplayName("registering a tool publishes its name AND every kind it declares")
    void registrationPublishesNameAndKinds() {
        OperationRegistry registry = new OperationRegistry();
        registry.register("pretend_front_door", List.of("alpha", "beta"));

        assertTrue(registry.has("pretend_front_door"), "the tool's own name is an operation");
        assertTrue(registry.has("alpha"), "a published kind is an operation");
        assertTrue(registry.has("beta"), "a published kind is an operation");
        assertFalse(registry.has("gamma"), "PROOF OF LIFE: an unpublished name is absent");
        assertEquals("pretend_front_door", registry.toolFor("alpha"),
            "the refusal message must be able to name who publishes a step");
    }

    /**
     * The singleton is global, and this test empties it. Without restoring it, every
     * later test in the same JVM that derives a cure tier would see a registry holding
     * two fabricated tools — an order dependency between test classes, invisible until
     * it bites.
     */
    @org.junit.jupiter.api.AfterEach
    void restoreTheSingleton() {
        OperationRegistry.theRegistry().clear();
    }

    @Test
    @DisplayName("the tool registry feeds the operation registry, so the two cannot drift")
    void theToolRegistryFeedsIt() {
        OperationRegistry live = OperationRegistry.theRegistry();
        live.clear();
        ToolRegistry tools = new ToolRegistry();

        assertFalse(live.has("pretend_front_door"),
            "PROOF OF LIFE: nothing is published before registration");

        tools.register(new Parametric());
        tools.register(new Standalone());

        assertTrue(live.has("pretend_front_door"), "the front door's name arrived by registering it");
        assertTrue(live.has("alpha"), "its kinds were READ off its own schema, not listed here");
        assertTrue(live.has("beta"), "its kinds were READ off its own schema, not listed here");
        assertTrue(live.has("pretend_standalone"),
            "a tool with no kind enum still publishes its own name — the ordinary case");
    }
}
