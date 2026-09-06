package org.jawata.mcp.tools.smell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.jawata.mcp.tools.RefactorToPatternTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28d Stage 11a — THE TIER IS DERIVED, NOT ASSIGNED.
 *
 * <p>The cure model (ARCHITECTURE-28d.md, "What the catalogue is", ruled built
 * 2026-08-31): a cure is a route of steps naming operations that already exist,
 * and whether a finding's answer is PERFORM (run this) or ADVISE (a design
 * decision) is <b>derived</b> from route count and step existence — never
 * written into a table where it can drift from the operations that exist.</p>
 *
 * <p>The derivation's registry is the front door's own published kind list,
 * reached through an accessor — a copy of that list here would be a second
 * home for one fact, and the copy is the one that goes stale.</p>
 */
class CureTierTest {

    // ------------------------------------------------------------ derivation

    /**
     * The four advise causes and the one perform cause, each on the real table.
     *
     * <p>Per kind rather than by counting tiers: a count would still pass after
     * two kinds swapped their tiers.</p>
     */
    @Test
    @DisplayName("the tier is derived per kind: one runnable route performs, everything else advises")
    void theTierIsDerivedPerKind() {
        // PERFORM — exactly one runnable route, its step published.
        for (String kind : List.of("switch_statements", "type_code", "singleton", "long_method")) {
            CureTier.Derivation d = CureTier.derive(kind);
            assertEquals(CureTier.Tier.RUN, d.tier(),
                () -> kind + " declares exactly one runnable route whose step is a"
                    + " published kind — anything but PERFORM means the derivation"
                    + " cannot see the route the table declares: " + d);
            assertEquals(CureCatalog.recipesFor(kind).get(0), d.recipe(),
                "the performing recipe is the route's own step, read off the table");
        }

        // ADVISE — several runnable routes, nothing mechanical chooses.
        for (String kind : List.of("ocp", "divergent_change", "shotgun_surgery")) {
            CureTier.Derivation d = CureTier.derive(kind);
            assertEquals(CureTier.Tier.CONSIDER, d.tier(),
                () -> kind + " declares THREE runnable routes; choosing one is a design"
                    + " decision, and a derivation that picks one anyway has invented"
                    + " a preference no table declares: " + d);
            assertNull(d.recipe(), "an advisory answer carries no single recipe to run");
        }

        // ADVISE — cures declared, none runnable (design-only).
        //
        // `cqs` IS NOT ONE OF THESE, and used to be listed here under this comment. It
        // declares a RUNNABLE recipe (apply_cleanup kind=return_modified_value), so the
        // "none runnable" rule cannot be what answers for it: in this JVM it derives ADVISE
        // by the STEP-NOT-REGISTERED rule instead, because no registry is wired in a unit
        // test. Two true statements about two different registry states, filed under one
        // false rationale — found by a C4 audit reading the comment against the table.
        for (String kind : List.of("coupling", "composition_over_inheritance",
                "encapsulation")) {
            CureTier.Derivation d = CureTier.derive(kind);
            assertEquals(CureTier.Tier.CONSIDER, d.tier(),
                () -> kind + " is cured by a design decision — nothing automates it,"
                    + " so nothing can be PERFORMED: " + d);
        }

        // `cqs` WITH ITS STEP REGISTERED — PERFORM, and this is the claim the shipped product
        // makes about it. CureCatalog's unrouted entry for row 61 tells a future route merge
        // that wiring a SECOND cure here would cost this smell its runnable instruction, and
        // that sentence is only worth reading if the first half is true. It was asserted
        // nowhere until a C4 audit pointed out that the only test naming cqs asserted ADVISE,
        // for a reason that does not apply to it.
        CureTier.Derivation performed =
            CureTier.derive("cqs", List.of("apply_cleanup kind=return_modified_value"));
        assertEquals(CureTier.Tier.RUN, performed.tier(),
            () -> "cqs declares ONE runnable route, so with that step registered the answer"
                + " is run it: " + performed);
        assertEquals("apply_cleanup kind=return_modified_value", performed.recipe(),
            () -> "and PERFORM must name the single step to run: " + performed);

        // THE OTHER HALF OF ROW 61's RECORDED TRADE — that a SECOND runnable route would turn
        // this instruction back into a suggestion — is NOT asserted here, and the reason is
        // worth writing down because the first attempt to assert it was wrong.
        //
        // It passed a two-element list to the overload above, expecting ADVISE. The overload's
        // second argument is the REGISTRY of registered steps, not the routes: routes come
        // from the catalog, which declares ONE cure for cqs whatever registry it is asked
        // about. So that call still derived PERFORM, and the run said so — "one runnable
        // route, every step registered".
        //
        // Constructing the two-route case for cqs means adding the second cure to the
        // catalogue, which is exactly the change the route merge has not made. The RULE it
        // would hit is already exercised, three assertions above, by the kinds that really do
        // declare several runnable routes. So the trade is real, its two halves are covered
        // by different tests, and neither is a claim nobody checks.

        // ADVISE — zero cures is a NORMAL state, not a defect.
        CureTier.Derivation none = CureTier.derive("no_such_smell");
        assertEquals(CureTier.Tier.CONSIDER, none.tier(),
            "a kind with no declared cure advises; it does not throw and does not invent");
        CureTier.Derivation nul = CureTier.derive(null);
        assertEquals(CureTier.Tier.CONSIDER, nul.tier(),
            "every caller passes whatever the finding carried, so null must be an answer");
    }

    /**
     * THE CONTROL, broken first: a route whose step is not in the registry must
     * never derive PERFORM — and it must not be silently narrowed away either.
     *
     * <p>A table declaring a step that does not exist is a defect to SURFACE.
     * Narrowing to the remaining routes would hide it exactly the way an absent
     * field reading as empty hid three fields at Stage 10.</p>
     */
    @Test
    @DisplayName("a step missing from the registry derives ADVISE and NAMES the step — then PERFORM with the real registry")
    void aFabricatedStepDerivesAdviseNeverPerform() {
        // The step this kind needs CHANGED in v4.0.2 — switch_statements now
        // routes to replace_conditional_with_polymorphism, the operation its own
        // prose recommends and which Sprint 28d built for it. The test's intent is
        // untouched: remove the step this kind depends on, and the tier must fall
        // to ADVISE naming it. Only the literal moved.
        //
        // Worth noting how this surfaced: the first attempt at that change ADDED
        // the new route beside State instead of replacing it, and this test caught
        // the cost — two routes derive ADVISE, so a runnable instruction would
        // have become advice. The tier model priced the second route and the
        // answer was no.

        // (1) BROKEN FIRST — the registry loses the one step switch_statements needs.
        List<String> without = new ArrayList<>(RefactorToPatternTool.patternKinds());
        assertTrue(without.remove("replace_conditional_with_polymorphism"),
            "the registry must actually contain the step this test removes, or the"
                + " 'broken' run is broken for a different reason than intended");

        CureTier.Derivation broken = CureTier.derive("switch_statements", without);
        assertEquals(CureTier.Tier.CONSIDER, broken.tier(),
            "a route whose step no registered operation backs cannot be performed");
        assertNull(broken.recipe());
        assertTrue(broken.reason().contains("replace_conditional_with_polymorphism"),
            () -> "the missing step is NAMED — a bare ADVISE cannot tell a design"
                + " decision from a mis-spelled table row: " + broken.reason());

        // (2) REPAIRED SECOND — the real registry, same kind.
        CureTier.Derivation repaired = CureTier.derive("switch_statements");
        assertEquals(CureTier.Tier.RUN, repaired.tier());
        assertEquals("replace_conditional_with_polymorphism", repaired.recipe());
    }

    // ---------------------------------------------------- standing invariants
    //
    // The two table invariants — the pair (kind, operation) is the entry
    // identity, and every declared step names a published operation kind — are
    // NOT tests here. They are enforced in CureCatalog's own builder, which
    // throws on a violating table, so a bad row is UNCONSTRUCTIBLE rather than
    // merely detectable (the same move C6 made for namespace collision). Test
    // sweeps here would have needed a kind-enumeration accessor whose only
    // callers were these sweeps — the unwired gate refused exactly that, and it
    // was right. Each builder guard was proven by planting a violating row and
    // watching the matching throw; the proof is recorded at C11a.

    // ---------------------------------------------------------------- wiring

    /**
     * THE TIER REACHES THE FINDING — appended to the cure sentence detectors
     * carry. Asserted on a hand-built answer so the test needs no store: the
     * record is public and the tier is derived from the kind, not the fields.
     */
    @Test
    @DisplayName("the hint carries the derived tier, and a perform answer names what to run")
    void theHintCarriesTheDerivedTier() {
        // v4.0.2: switch_statements routes to replace_conditional_with_polymorphism.
        // The fixture had to follow, and the reason is in this javadoc already —
        // the tier is derived from the KIND, not from the fields handed in, so a
        // stale fixture produces a hint that contradicts itself: one recipe in the
        // resolved half, another in the tier. That is the very defect this release
        // fixes, reproduced here by a test fixture rather than by the table.
        CureLookup.Cures perform = new CureLookup.Cures("switch_statements",
            List.of(new CureLookup.ResolvedCure("replace_conditional_with_polymorphism",
                "design:strategy", "java-design-patterns",
                "catalogue:java-design-patterns/strategy/README.md")),
            List.of(), null, List.of());
        assertTrue(perform.hint().contains(
                "TIER: RUN — run refactor_to_pattern kind=replace_conditional_with_polymorphism"),
            () -> "a one-route kind's finding must say RUN and name the invocation: "
                + perform.hint());

        CureLookup.Cures advise = new CureLookup.Cures("ocp",
            List.of(new CureLookup.ResolvedCure("refactor_to_state", "design:state",
                "java-design-patterns", "catalogue:java-design-patterns/state/README.md")),
            List.of(), null, List.of());
        assertTrue(advise.hint().contains("TIER: CONSIDER"),
            () -> "ocp declares three routes; today that still yields CONSIDER: "
                + advise.hint());
    }

    /**
     * THE BLANK IS A CONTRACT: a kind with nothing declared yields an EMPTY
     * hint — OcpDetector's fallback branch keys on it, and a tier sentence on
     * an empty answer would silently flip that branch.
     */
    @Test
    @DisplayName("a kind with nothing declared keeps its blank hint")
    void aKindWithNothingDeclaredKeepsItsBlankHint() {
        CureLookup.Cures empty = new CureLookup.Cures("no_such_smell",
            List.of(), List.of(), null, List.of());
        assertEquals("", empty.hint(),
            "the blank is what the detector's fallback branch keys on");
    }

    /**
     * THE DEFERRED WIDENING, still deferred — and it is NOT what moved this number.
     *
     * <p>This pinned TWO components until 2026-09-06, with a delivery condition naming
     * "the first cure needing an ORDERED second step". A third component arrived and it
     * is a DISCRIMINATOR, not a step, so the assertion went red for a reason its own
     * text did not name. Bumping the number and leaving the sentence would have retired
     * a deferral nobody has met, which is why the condition is restated rather than
     * quietly dropped: a cure is still ONE step, and the day one needs two ordered steps
     * {@code recipe} becomes a list and the tier derivation is updated with it.</p>
     */
    @Test
    @DisplayName("a route is still one step; the third component is a discriminator")
    void aRouteIsOneStepUntilACureNeedsTwo() {
        assertEquals(3, CureCatalog.Cure.class.getRecordComponents().length,
            "Cure is (recipe, operation, discriminator) — still ONE step per route."
                + " DELIVERY CONDITION, unchanged and unmet: the first cure needing an"
                + " ORDERED second step replaces `recipe` with a steps list and updates"
                + " the tier derivation with it");
        assertEquals("discriminator",
            CureCatalog.Cure.class.getRecordComponents()[2].getName(),
            "and the third component is the sentence that tells a cure from its"
                + " neighbours — if it ever becomes a steps list, the condition above"
                + " has been met and this test is the place that says so");
    }

    /**
     * INVARIANT 3, driven through a PLANTED table — the only way it can fail.
     *
     * <p>The shipped table satisfies every invariant, so a check run only against it is
     * unfalsifiable: one that had rotted would look exactly like one that held. These
     * plant a table that breaks it, and a table that does not.</p>
     */
    @Test
    @DisplayName("two runnable cures with nothing to tell them apart do not load")
    void twoUndiscriminatedRunnableCuresRefuseToLoad() {
        java.util.Map<String, java.util.List<CureCatalog.Cure>> planted = java.util.Map.of(
            "planted_smell", java.util.List.of(
                new CureCatalog.Cure("extract kind=method", null),
                new CureCatalog.Cure("inline kind=method", null)));

        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> CureCatalog.validate(planted),
            "a smell offering two runnable cures and no way to choose between them leaves"
                + " an agent guessing, which is what the ranked list exists to prevent");
        assertTrue(refused.getMessage().contains("planted_smell"),
            "the refusal must name the KIND, or a table with four bad rows is a scavenger"
                + " hunt: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("extract kind=method")
                && refused.getMessage().contains("inline kind=method"),
            "and BOTH offending cures, because every offender is collected before the"
                + " throw — a first-wins check makes four bad rows take four builds and"
                + " each one look like a new defect: " + refused.getMessage());
    }

    @Test
    @DisplayName("the control: two runnable cures that CAN be told apart load fine")
    void twoDiscriminatedRunnableCuresAreAccepted() {
        // Without this the case above would pass just as well against a check that
        // refused every multi-cure kind — which is a different product, not an invariant.
        java.util.Map<String, java.util.List<CureCatalog.Cure>> planted = java.util.Map.of(
            "planted_smell", java.util.List.of(
                new CureCatalog.Cure("extract kind=method", null, "when the run of"
                    + " statements has a name you can give it"),
                new CureCatalog.Cure("inline kind=method", null, "when the method's body"
                    + " is as clear as its name")));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> CureCatalog.validate(planted),
            "a discriminator on each is exactly what the invariant asks for");

        // AND A SINGLE runnable cure needs none: there is nothing to tell it apart FROM,
        // and demanding a sentence there would be demanding prose for its own sake. This
        // is what keeps the two-argument convenience constructor honest rather than lax.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> CureCatalog.validate(java.util.Map.of("solo",
                java.util.List.of(new CureCatalog.Cure("extract kind=method", null)))),
            "one runnable cure has no neighbour to be distinguished from");
    }
}
