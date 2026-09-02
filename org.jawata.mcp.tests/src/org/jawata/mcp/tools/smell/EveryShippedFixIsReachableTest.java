package org.jawata.mcp.tools.smell;

import org.jawata.mcp.refactoring.OperationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C0's gate: four fixes that ALREADY SHIPPED, finally offered by the smells whose own
 * prose names them.
 *
 * <p>Before P1 this table could only name a {@code refactor_to_pattern} kind, so a
 * feature-envy finding — 713 of them on this repository — offered nothing runnable
 * while {@code move_method} sat in the product. These assert the route, not the tier:
 * the tier is DERIVED, and a gate demanding PERFORM would be wrong the day a second
 * route is declared.</p>
 */
class EveryShippedFixIsReachableTest {

    /** The four, and the operation each must now name. */
    private static final List<String[]> ROUTES = List.of(
        new String[] {"feature_envy", "move_method"},
        new String[] {"god_class", "extract"},
        new String[] {"temporary_field", "extract"},
        new String[] {"encapsulation", "encapsulate_field"});

    @Test
    @DisplayName("each of the four smells offers its already-shipped fix by name")
    void theFourRoutesExist() {
        for (String[] route : ROUTES) {
            String kind = route[0];
            String operation = route[1];
            assertTrue(CureCatalog.recipesFor(kind).contains(operation),
                kind + " must offer '" + operation + "' — the fix its own prose names."
                    + " Declared: " + CureCatalog.recipesFor(kind));
        }
    }

    @Test
    @DisplayName("a standalone operation can be a cure step at all — the change P1 made")
    void aStandaloneOperationIsAcceptedAsAStep() {
        // Before P1 every one of these would have failed the table's load-time check,
        // because none is a refactor_to_pattern kind.
        for (String[] route : ROUTES) {
            assertFalse(
                org.jawata.mcp.tools.RefactorToPatternTool.publishedKinds().contains(route[1]),
                "PROOF OF LIFE: '" + route[1] + "' is deliberately NOT a pattern kind —"
                    + " if it were, this test would prove nothing about the widening");
        }
    }

    @Test
    @DisplayName("the table validates against the registry, and says which step nothing backs")
    void validationNamesTheUnbackedStep() {
        OperationRegistry empty = new OperationRegistry();
        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> CureCatalog.validateAgainst(empty),
            "an empty registry backs no step, so validation must refuse");
        assertTrue(refused.getMessage().contains("no registered operation backs it"),
            "the refusal must say WHY, not merely fail: " + refused.getMessage());

        OperationRegistry full = new OperationRegistry();
        for (String recipe : allDeclaredRecipes()) {
            full.register(recipe, List.of());
        }
        CureCatalog.validateAgainst(full);  // must not throw
    }

    @Test
    @DisplayName("Change Value to Reference is reachable as advice and absent from the runnable list")
    void adviceOnlyIsReachableAndNotRunnable() {
        String address = CureCatalog.adviceFor("change_value_to_reference");
        assertNotNull(address, "a reader meeting it must be pointed somewhere");
        assertEquals("design:repository", address,
            "the address a reader opens — the pattern this refactoring becomes");

        OperationRegistry registry = new OperationRegistry();
        for (String recipe : allDeclaredRecipes()) {
            registry.register(recipe, List.of());
        }
        assertFalse(registry.has("change_value_to_reference"),
            "advice only means exactly this: nothing publishes it as a runnable operation");
        for (String kind : List.of("feature_envy", "god_class", "temporary_field",
                "encapsulation", "long_method", "switch_statements")) {
            assertFalse(CureCatalog.recipesFor(kind).contains("change_value_to_reference"),
                kind + " must not offer a declined refactoring as runnable");
        }
        assertNull(CureCatalog.adviceFor("move_method"),
            "PROOF OF LIFE: an operation we DO run is not advice-only");
    }

    private static List<String> allDeclaredRecipes() {
        List<String> all = new java.util.ArrayList<>();
        for (String kind : List.of("ocp", "cqs", "coupling", "composition_over_inheritance",
                "encapsulation", "divergent_change", "shotgun_surgery", "switch_statements",
                "type_code", "singleton", "long_method", "feature_envy", "god_class",
                "temporary_field")) {
            all.addAll(CureCatalog.recipesFor(kind));
        }
        return all;
    }
}
