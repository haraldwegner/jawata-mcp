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
     * The singleton is global and this test writes to it, so it is RETURNED EXACTLY.
     *
     * <p>Three attempts preceded this one and each was a hand-written copy of what the
     * application registers. The first put the KEYS back and destroyed the attribution.
     * The second registered three real front doors — out of the thirty-nine
     * {@code JawataApplication} registers — under a comment saying the singleton was left
     * as found, so every other tool's operations were gone for the rest of the JVM and
     * {@code CureLookup}, {@code CureTier} and {@code ArchitectGate} read the remains.
     * A C6 audit found each in turn.</p>
     *
     * <p>A borrow is only safe when the thing borrowed can be returned exactly, so the
     * registry now hands out its own state ({@link OperationRegistry#snapshot()}) and takes
     * it back. There is no copy left to drift.</p>
     */
    private OperationRegistry.Snapshot borrowed;

    @Test
    @DisplayName("a borrowed registry comes back EXACTLY — the claim three restores made falsely")
    void theBorrowIsExact() {
        OperationRegistry registry = new OperationRegistry();
        registry.register("alpha_door", java.util.List.of("one", "two"), true, false,
            java.util.Set.of("one"));
        registry.register("beta_door", java.util.List.of("three"), false, true,
            java.util.Set.of());
        OperationRegistry.Snapshot taken = registry.snapshot();

        // PROOF OF LIFE, and then some damage worth undoing.
        assertTrue(registry.has("alpha_door"), "the fixture must be registered to begin with");
        registry.clear();
        registry.register("gamma_door", java.util.List.of("four"), false, false,
            java.util.Set.of("four"));
        assertFalse(registry.has("alpha_door"), "and the damage must be real");

        registry.restore(taken);

        assertEquals(taken.publishedBy().keySet(), registry.all(),
            "every operation is back, and no extra: this is what 'restored' has to mean, and"
                + " what three hand-written restores in this file claimed while putting back"
                + " the keys only, then three front doors of the thirty-nine the application"
                + " registers");
        assertEquals(java.util.Set.of("alpha_door"), registry.toolsFor("one"),
            "AND THE ATTRIBUTION, which is the half a key-only restore silently destroyed —"
                + " an operation has to remember which tool publishes it or the ambiguity"
                + " check reads a different registry than the one it was written for");
        assertTrue(registry.isMechanical("alpha_door"), "mechanical classification is back");
        assertTrue(registry.isStructural(OperationRegistry.qualify("alpha_door", "one")),
            "and so is the structural set the architect gate reads");
        assertFalse(registry.has("gamma_door"),
            "and what was written while the registry was borrowed is gone");
    }

    @org.junit.jupiter.api.BeforeEach
    void captureTheSingleton() {
        borrowed = OperationRegistry.theRegistry().snapshot();
    }

    @org.junit.jupiter.api.AfterEach
    void restoreTheSingleton() {
        OperationRegistry.theRegistry().restore(borrowed);
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


}
