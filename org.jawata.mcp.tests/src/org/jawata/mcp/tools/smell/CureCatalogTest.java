package org.jawata.mcp.tools.smell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28d Stage 6 / S7 — THE CURE TABLES FOLD TO ONE.
 *
 * <p>There were three things answering one question. {@code CureCatalog} held
 * {@code (recipe, operation)} per smell; {@code RecipeCatalog} held the recipe
 * half again in its own switch; {@code OcpCure} held two of that switch's cases
 * a third time, and the recipe table delegated to it. Two of the three are
 * deleted at S7 — not because they disagreed, but because nothing prevented it.
 * A javadoc sentence claiming they answered "different questions" is not a
 * mechanism, and it was already false when written.</p>
 *
 * <p><b>What this test is actually for.</b> The fold changes USER-VISIBLE text:
 * three churn detectors append the OCP hint verbatim to their findings. A count
 * of cures cannot see a wording change, so the hint is compared as a STRING.</p>
 */
class CureCatalogTest {

    /**
     * The hint exactly as it read before the fold, as a golden master.
     *
     * <p>Written out rather than derived, on purpose: this is the one place a
     * literal copy earns its keep. {@link CureCatalog#ocpHint()} builds the
     * sentence FROM the table so it can never describe a different set of
     * designs than the table holds — and this constant is the tripwire that
     * makes a change to what the user reads a deliberate act. Adding a fourth
     * design to {@code OPEN_THE_AXIS} SHOULD turn this red; the author then
     * updates it having seen the new sentence, instead of shipping it unread.</p>
     */
    private static final String HINT_BEFORE_THE_FOLD =
        " OCP cure: introduce an abstraction at the modification axis — refactor_to_pattern "
            + "kind=refactor_to_state / refactor_to_command_dispatcher / form_template_method "
            + "(or refactoring(action=plan, kind=<same>) then apply_plan for a parity-gated run).";

    @Test
    @DisplayName("the derived hint is byte-identical to the constant it replaced")
    void theHintSurvivesTheFoldUnchanged() {
        assertEquals(HINT_BEFORE_THE_FOLD, CureCatalog.ocpHint(),
            "USER-VISIBLE TEXT. Three churn detectors append this verbatim, so a wording"
                + " change here is a change to what every reader of a divergent_change or"
                + " shotgun_surgery finding sees. Deriving it from the table must not have"
                + " altered a character");
    }

    /**
     * Every mapping the deleted recipe table held, asserted against the survivor.
     *
     * <p>Listed one per kind rather than as a set comparison: a set that matched
     * by accident would pass, and the point is that each specific smell still
     * reaches the specific plan kind it reached before.</p>
     */
    @Test
    @DisplayName("every recipe the deleted table held survives, per kind")
    void theRecipeMappingsSurvive() {
        assertEquals(List.of("inline_singleton"), CureCatalog.recipesFor("singleton"));
        assertEquals(List.of("replace_type_code_with_class"),
            CureCatalog.recipesFor("type_code"));
        // CHANGED DELIBERATELY, v4.0.2, and this guard is why it is deliberate.
        // It pins what the deleted table held, so a migration cannot lose a
        // mapping by accident — and it correctly refused this edit until the
        // reason was written down.
        //
        // The reason: the detector's own sentence recommends Replace Conditional
        // with Polymorphism, Sprint 28d built that operation for this smell, and
        // the table still pointed at State — so one finding named two different
        // refactorings. Found by dogfooding v4.0.1 with one call against real
        // code. State did not "survive" here because it was replaced on purpose,
        // and it remains reachable through OPEN_THE_AXIS.
        assertEquals(List.of("replace_conditional_with_polymorphism"),
            CureCatalog.recipesFor("switch_statements"));
        assertEquals(List.of("compose_method"), CureCatalog.recipesFor("long_method"));

        List<String> axis = List.of(
            "refactor_to_state", "refactor_to_command_dispatcher", "form_template_method");
        assertEquals(axis, CureCatalog.recipesFor("divergent_change"),
            "the churn traces kept their three designs, in order — the hint is built from"
                + " this list, so a reordering would silently reword what the user reads");
        assertEquals(axis, CureCatalog.recipesFor("shotgun_surgery"));
    }

    /**
     * A cure with no runnable recipe is still a cure — the property that made two
     * tables look necessary in the first place.
     */
    @Test
    @DisplayName("a design-only cure yields no recipe but is still declared")
    void aDesignOnlyCureIsNotARecipe() {
        assertFalse(CureCatalog.curesFor("cqs").isEmpty(),
            "cqs is cured by a design decision and has an address a reader can open —"
                + " leaving it out would read as 'no cure known'");
        assertEquals(List.of(), CureCatalog.recipesFor("cqs"),
            "but nothing automates it, so there is no plan kind to run");

        // Was `assertTrue(CureCatalog.hasRecipe("long_method"))`. hasRecipe was deleted
        // 2026-08-28: the unwired gate showed all three of its callers were this test,
        // so it was API added in S7 that no production path ever asked for. The check it
        // expressed is unchanged — it was only ever recipesFor(...).isEmpty() inverted.
        assertFalse(CureCatalog.recipesFor("long_method").isEmpty(),
            "whereas a definitional cure — the method ends composed — does run");
    }

    @Test
    @DisplayName("an unknown kind yields nothing, and null is an answer")
    void anUnknownKindYieldsNothing() {
        assertEquals(List.of(), CureCatalog.recipesFor("no_such_smell"));
        assertEquals(List.of(), CureCatalog.recipesFor(null),
            "every caller passes whatever the finding carried, so null must be an answer"
                + " rather than an exception");
    }
}
