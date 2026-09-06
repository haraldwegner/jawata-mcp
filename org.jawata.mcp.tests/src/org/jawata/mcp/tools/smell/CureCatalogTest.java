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
        // SURVIVES AND IS STILL FIRST, which is this test's actual subject. It pins that a
        // migration cannot LOSE a mapping; it never claimed a kind may have only one. S8b
        // step 9 gave type_code two more — subclasses when behaviour branches on the code, a
        // value object when the code has rules of its own — so the assertion moved from the
        // whole list to the mapping it exists to protect, plus its ORDER, because the first
        // cure is what a reader meets.
        assertEquals("replace_type_code_with_class",
            CureCatalog.recipesFor("type_code").get(0),
            "the mapping the deleted table held must survive, and stay the one offered first");
        org.junit.jupiter.api.Assertions.assertTrue(
            CureCatalog.recipesFor("type_code").size() > 1,
            "PROOF OF LIFE for the assertion above: with a single recipe it would pass"
                + " whether or not step 9's routes had landed");
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
        // The same move as type_code above, and the bigger one: long_method went from ONE
        // recipe to FIVE at S8b step 9. What this test protects is that the mapping the
        // deleted table held is still here and still first.
        assertEquals("compose_method", CureCatalog.recipesFor("long_method").get(0),
            "the mapping the deleted table held must survive, and stay the one offered first");
        org.junit.jupiter.api.Assertions.assertEquals(5,
            CureCatalog.recipesFor("long_method").size(),
            "PROOF OF LIFE: the four routes the old count rule kept out are what step 9"
                + " added, and a first-element check alone would pass without them");

        List<String> axis = List.of(
            "refactor_to_state", "refactor_to_command_dispatcher", "form_template_method");
        // THE HINT'S SOURCE MOVED, and this assertion moved with it. It used to read
        // `divergent_change`, because all three churn rows shared one constant and any of
        // them rendered the same sentence. At S8b step 9 `divergent_change` gained row 64
        // (`extract kind=split_phase`) — the only refactoring the spec's "Finds it" column
        // gives it — which is not a design on this axis and not a `refactor_to_pattern` kind,
        // so rendering from that row would have printed a call naming the wrong tool.
        // `ocp` is the row whose members ARE the OCP designs.
        assertEquals(axis, CureCatalog.recipesFor("ocp"),
            "the OCP designs, in order — ocpHint() is built from THIS list, so a reordering"
                + " would silently reword what the user reads");
        assertEquals(axis, CureCatalog.recipesFor("shotgun_surgery"));
        // The churn traces still keep the three, and divergent_change keeps them FIRST — the
        // three reshape a class, row 64 reshapes a method, so it is offered last.
        assertEquals(axis, CureCatalog.recipesFor("divergent_change").subList(0, 3),
            "divergent_change still leads with the same three designs, in the same order");
        assertEquals(List.of("extract kind=split_phase"),
            CureCatalog.recipesFor("divergent_change").subList(3, 4),
            "PROOF OF LIFE for the sublist above: without row 64 the two assertions would be"
                + " the single assertion this replaced, and the move of ocpHint()'s source"
                + " would be unmeasured");
    }

    /**
     * A cure with no runnable recipe is still a cure — the property that made two
     * tables look necessary in the first place.
     */
    @Test
    @DisplayName("a design-only cure yields no recipe but is still declared")
    void aDesignOnlyCureIsNotARecipe() {
        // THE EXAMPLE MOVED, and the move is the point. This pinned `cqs`, which
        // stopped being design-only when row 60 shipped its local cure — so the
        // test went red for the right reason, and retargeting it is how the
        // property survives its own example being fixed. `coupling` is
        // design-only today for the same reason cqs was: the decision is which
        // dependency to invert, and no tool makes it.
        assertFalse(CureCatalog.curesFor("coupling").isEmpty(),
            "coupling is cured by a design decision and has an address a reader can open —"
                + " leaving it out would read as 'no cure known'");
        assertEquals(List.of(), CureCatalog.recipesFor("coupling"),
            "but nothing automates it, so there is no plan kind to run");
        // ...and cqs is now the other half of the same contract: a smell whose
        // cure IS automated names the operation that runs it.
        // BOTH HALVES AT S8b STEP 9, and the sentence this replaces had gone false twice
        // over. It read "row 61 is the cross-file half and is not shipped" — row 61 SHIPPED
        // at C4 (`change_method_signature kind=separate_query_from_modifier`), so the claim
        // was stale from the day that stage closed; and the reason it was not wired anyway
        // was the count rule, which step 3 reversed. A reader of `cqs` now gets the local
        // fix and the cross-file one, each saying which case it is for.
        assertEquals(List.of("apply_cleanup kind=return_modified_value",
                "change_method_signature kind=separate_query_from_modifier"),
            CureCatalog.recipesFor("cqs"),
            "row 60 is the runnable LOCAL cure for cqs — the answer is built in a mutable"
                + " local — and row 61 is the cross-file half, where the answer is a field"
                + " the method wrote and callers read");

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
