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
            assertEquals(CureTier.Tier.PERFORM, d.tier(),
                () -> kind + " declares exactly one runnable route whose step is a"
                    + " published kind — anything but PERFORM means the derivation"
                    + " cannot see the route the table declares: " + d);
            assertEquals(CureCatalog.recipesFor(kind).get(0), d.recipe(),
                "the performing recipe is the route's own step, read off the table");
        }

        // ADVISE — several runnable routes, nothing mechanical chooses.
        for (String kind : List.of("ocp", "divergent_change", "shotgun_surgery")) {
            CureTier.Derivation d = CureTier.derive(kind);
            assertEquals(CureTier.Tier.ADVISE, d.tier(),
                () -> kind + " declares THREE runnable routes; choosing one is a design"
                    + " decision, and a derivation that picks one anyway has invented"
                    + " a preference no table declares: " + d);
            assertNull(d.recipe(), "an advisory answer carries no single recipe to run");
        }

        // ADVISE — cures declared, none runnable (design-only).
        for (String kind : List.of("cqs", "coupling", "composition_over_inheritance",
                "encapsulation")) {
            CureTier.Derivation d = CureTier.derive(kind);
            assertEquals(CureTier.Tier.ADVISE, d.tier(),
                () -> kind + " is cured by a design decision — nothing automates it,"
                    + " so nothing can be PERFORMED: " + d);
        }

        // ADVISE — zero cures is a NORMAL state, not a defect.
        CureTier.Derivation none = CureTier.derive("no_such_smell");
        assertEquals(CureTier.Tier.ADVISE, none.tier(),
            "a kind with no declared cure advises; it does not throw and does not invent");
        CureTier.Derivation nul = CureTier.derive(null);
        assertEquals(CureTier.Tier.ADVISE, nul.tier(),
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
        List<String> without = new ArrayList<>(RefactorToPatternTool.publishedKinds());
        assertTrue(without.remove("replace_conditional_with_polymorphism"),
            "the registry must actually contain the step this test removes, or the"
                + " 'broken' run is broken for a different reason than intended");

        CureTier.Derivation broken = CureTier.derive("switch_statements", without);
        assertEquals(CureTier.Tier.ADVISE, broken.tier(),
            "a route whose step no registered operation backs cannot be performed");
        assertNull(broken.recipe());
        assertTrue(broken.reason().contains("replace_conditional_with_polymorphism"),
            () -> "the missing step is NAMED — a bare ADVISE cannot tell a design"
                + " decision from a mis-spelled table row: " + broken.reason());

        // (2) REPAIRED SECOND — the real registry, same kind.
        CureTier.Derivation repaired = CureTier.derive("switch_statements");
        assertEquals(CureTier.Tier.PERFORM, repaired.tier());
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
                "TIER: PERFORM — run refactor_to_pattern kind=replace_conditional_with_polymorphism"),
            () -> "a one-route kind's finding must say PERFORM and name the run: "
                + perform.hint());

        CureLookup.Cures advise = new CureLookup.Cures("ocp",
            List.of(new CureLookup.ResolvedCure("refactor_to_state", "design:state",
                "java-design-patterns", "catalogue:java-design-patterns/state/README.md")),
            List.of(), null, List.of());
        assertTrue(advise.hint().contains("TIER: ADVISE"),
            () -> "ocp declares three routes; its finding advises: " + advise.hint());
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
     * THE DEFERRED WIDENING, asserted absent with its delivery condition.
     *
     * <p>The model says a cure is ORDERED STEPS; every route the table holds
     * today has at most one, so a steps list would be a field nothing reads.
     * This pins the current shape so the first multi-step cure turns it red and
     * the widening is a conscious act instead of a drive-by.</p>
     */
    @Test
    @DisplayName("a route is one step until a cure needs two")
    void aRouteIsOneStepUntilACureNeedsTwo() {
        assertEquals(2, CureCatalog.Cure.class.getRecordComponents().length,
            "Cure is (recipe, operation) — one step per route. DELIVERY CONDITION:"
                + " the first cure needing an ORDERED second step replaces `recipe`"
                + " with a steps list and updates the tier derivation with it. Until"
                + " then a list field would be a field nothing reads");
    }
}
