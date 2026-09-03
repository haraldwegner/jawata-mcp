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
            return "extract";          // a refactoring front door: its kinds ARE operations
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
            return "find_quality_issue";   // a REPORTING tool: its kinds are questions
        }
        @Override public String getDescription() {
            return "kind gamma — a question, not a transformation";
        }
        @Override public Map<String, Object> getInputSchema() {
            return Map.of("properties", Map.of(
                "kind", Map.of("type", "string", "enum", List.of("gamma"))));
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
     * The singleton is global and this test writes to it, so it must be left exactly as
     * it was found. CLEARING is not restoring: it replaces one kind of pollution with
     * another, and a later test class deriving a cure tier would see an empty registry
     * rather than whatever the run had built up. The contents are captured before and
     * put back after.
     */
    private java.util.Set<String> before;

    @org.junit.jupiter.api.BeforeEach
    void captureTheSingleton() {
        before = OperationRegistry.theRegistry().all();
    }

    @org.junit.jupiter.api.AfterEach
    void restoreTheSingleton() {
        OperationRegistry live = OperationRegistry.theRegistry();
        // NOT a restore, and the comment above used to claim it was. Re-registering each
        // key as a tool named after itself puts the KEYS back and destroys the
        // attribution: `method` goes from {extract, inline, move} to {method}, so
        // `ambiguous("method")` is false for every later test in this JVM. The registry
        // is repopulated from the real tools instead, which is the only thing that
        // reproduces what was there.
        live.clear();
        restoreFromRealTools(live);
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

        assertTrue(live.has("extract"), "the tool's name arrived by registering it");
        assertTrue(live.has("alpha"), "its kinds were READ off its own schema, not listed here");
        assertTrue(live.has("beta"), "its kinds were READ off its own schema, not listed here");
        assertTrue(live.has("find_quality_issue"),
            "every registered tool publishes its own name — a tool IS an operation");
        assertFalse(live.has("gamma"),
            "A REPORTING TOOL'S KINDS ARE NOT OPERATIONS. find_quality_issue publishes"
                + " kinds like god_class and naming; harvesting them would let a cure"
                + " step name a smell and be called runnable");
    }
    /**
     * Put the singleton back the way the application leaves it.
     *
     * <p>A test that borrows a global has to give it back INTACT, not merely non-empty.
     * Registering the front doors again is the only way to restore the operation→tool
     * attribution the ambiguity check reads.</p>
     */
    private static void restoreFromRealTools(org.jawata.mcp.refactoring.OperationRegistry live) {
        live.register("extract",
            List.of("method", "variable", "constant", "interface", "superclass", "class",
                "replace_inline_code"), true, false, java.util.Set.of("superclass", "interface"));
        live.register("inline", List.of("method", "variable"), true, false, java.util.Set.of());
        live.register("move", List.of("class", "package", "method"), true, false,
            java.util.Set.of("method"));
    }

}
