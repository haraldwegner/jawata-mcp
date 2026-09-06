package org.jawata.mcp.tools.smell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        //
        // `type_code` and `long_method` LEFT THIS GROUP AT S8b STEP 9, and where they went is
        // the point. Both gained routes the table had withheld — type_code three, long_method
        // five — and under the old rule that would have DEMOTED them from an instruction to a
        // suggestion. They are asserted below with the other multi-route kinds instead, so
        // this loop keeps meaning "exactly one" rather than quietly becoming "at least one".
        for (String kind : List.of("switch_statements", "singleton")) {
            CureTier.Derivation d = CureTier.derive(kind);
            assertEquals(CureTier.Tier.RUN, d.tier(),
                () -> kind + " declares exactly one runnable route whose step is a"
                    + " published kind — anything but PERFORM means the derivation"
                    + " cannot see the route the table declares: " + d);
            assertEquals(CureCatalog.recipesFor(kind).get(0), d.recipe(),
                "the performing recipe is the route's own step, read off the table");
        }

        // RUN WITH THREE — the reversal, and these three assertions are the whole of it.
        // They asserted CONSIDER until 2026-09-06, on the rule that several routes mean
        // nothing mechanical chooses. Nothing mechanical does choose; the AGENT does, and
        // withholding the alternatives was never what made that safe.
        // `divergent_change` LEFT THIS LOOP AT STEP 9's ROUTING TABLE, and the reason is the
        // trap this file records twice below rather than a change of verdict: it gained row 64
        // (`extract kind=split_phase`), which is not a pattern kind, and in a unit-test JVM no
        // tool has registered — so the one-argument derive() answers by the
        // STEP-NOT-REGISTERED rule and says nothing about the count rule these assertions
        // exist to prove is gone. Its own assertion, with a registry, is below.
        for (String kind : List.of("ocp", "shotgun_surgery")) {
            CureTier.Derivation d = CureTier.derive(kind);
            assertEquals(CureTier.Tier.RUN, d.tier(),
                () -> kind + " declares THREE runnable routes, and three good answers are"
                    + " coverage rather than doubt — the old rule demoted a smell for"
                    + " knowing more: " + d);
            assertEquals(3, d.runnable().size(),
                () -> kind + " must hand over ALL of them, ranked: " + d);
            for (CureCatalog.Cure c : d.runnable()) {
                assertNotNull(c.discriminator(),
                    () -> kind + " offers several cures, so each must say what tells it"
                        + " from the others — a ranked list of identical descriptions is"
                        + " the guess this replaced: " + c);
            }
        }

        // RUN WITH TWO — lazy_class, the kind whose two cures the table used to withhold
        // with a written reason that the finding cannot tell them apart. It still cannot;
        // the cures say which case each is for, and the agent reads the type.
        // WITH ITS STEPS REGISTERED, and the registry argument is not decoration — it is
        // the trap this file already records for cqs. In a unit-test JVM no tool has
        // registered, so `inline kind=class` is in no registry and the derivation answers
        // by the STEP-NOT-REGISTERED rule, which is a true statement about plumbing and
        // says nothing about the count rule this assertion is here to prove is gone.
        CureTier.Derivation lazy = CureTier.derive("lazy_class",
            List.of("inline kind=class", "inline kind=subclass"));
        assertEquals(CureTier.Tier.RUN, lazy.tier(),
            () -> "two shipped fixes for one smell must both be offered: " + lazy);
        assertEquals(2, lazy.runnable().size(), () -> "both of them: " + lazy);

        // THE TWO KINDS STEP 9 MOVED HERE, and they are the reversal's largest payoff.
        //
        // `long_method` reports 276 findings on this repository and offered ONE answer. Four
        // more were built and withheld — and the unrouted table said why, in as many words:
        // "long_method already has one route, which the tier model turns to ADVISE the moment
        // a second is added, so bolting this on would cost that kind its runnable
        // instruction." That was a true reading of the old rule. Step 3 reversed the rule and
        // the sentence became false, so the routes went in.
        CureTier.Derivation longMethod = CureTier.derive("long_method",
            List.of("compose_method", "apply_cleanup kind=guard_clauses",
                "refactor_to_pattern kind=decompose_conditional",
                "extract kind=temp_to_query", "extract kind=function_to_command"));
        assertEquals(CureTier.Tier.RUN, longMethod.tier(),
            () -> "five shipped fixes for one smell is coverage, not doubt: " + longMethod);
        assertEquals(5, longMethod.runnable().size(),
            () -> "and every one is handed over, ranked: " + longMethod);

        // RUN WITH FOUR — divergent_change, the churn trace that also has a SEQUENTIAL cure.
        // Three OCP designs plus row 64 Split Phase, which is the only refactoring the spec's
        // "Finds it" column gives this smell and the reason ocpHint() now renders from `ocp`.
        CureTier.Derivation divergent = CureTier.derive("divergent_change",
            List.of("refactor_to_state", "refactor_to_command_dispatcher",
                "form_template_method", "extract kind=split_phase"));
        assertEquals(CureTier.Tier.RUN, divergent.tier(),
            () -> "four shipped fixes for one smell is coverage, not doubt: " + divergent);
        assertEquals(4, divergent.runnable().size(),
            () -> "and every one is handed over, ranked: " + divergent);
        assertEquals("extract kind=split_phase",
            divergent.runnable().get(3).recipe(),
            () -> "the three designs reshape a class and Split Phase reshapes a method, so it"
                + " is offered LAST — a ranking a reader acts on top-down: " + divergent);

        CureTier.Derivation typeCode = CureTier.derive("type_code",
            List.of("replace_type_code_with_class",
                "hierarchy kind=replace_type_code_with_subclasses",
                "data kind=replace_primitive"));
        assertEquals(CureTier.Tier.RUN, typeCode.tier(),
            () -> "a type code has three different right answers depending on what the code"
                + " is FOR, and the agent reads which: " + typeCode);
        assertEquals(3, typeCode.runnable().size(), () -> "all three: " + typeCode);

        // EVERY multi-route kind must discriminate, or a ranked list is a guess. Asserted
        // over both new arrivals rather than trusting INVARIANT 3 to have caught it — that
        // invariant runs at LOAD time on the shipped table, and this says the same thing
        // about the derivation a reader actually receives.
        for (CureTier.Derivation multi : List.of(longMethod, typeCode)) {
            for (CureCatalog.Cure c : multi.runnable()) {
                assertNotNull(c.discriminator(),
                    () -> "each cure must say what tells it from its neighbours: " + c);
            }
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

        // `cqs` WITH BOTH STEPS REGISTERED — RUN, with two, and this row is where the whole
        // reversal can be read in one place.
        //
        // The paragraph that stood here said the two-route case "means adding the second cure
        // to the catalogue, which is exactly the change the route merge has not made", and
        // explained that the merge would not make it because a second runnable route would
        // turn this smell's instruction back into a suggestion. Every word was a true reading
        // of the old rule. S8b step 3 reversed the rule and step 9 made the change: row 61
        // (`change_method_signature kind=separate_query_from_modifier`) shipped at C4 and is
        // now declared beside row 60.
        //
        // So the "trade" this file recorded across three checkpoints was never a trade
        // between two goods. It was the cost of a rule, and the rule is gone.
        CureTier.Derivation performed = CureTier.derive("cqs",
            List.of("apply_cleanup kind=return_modified_value",
                "change_method_signature kind=separate_query_from_modifier"));
        assertEquals(CureTier.Tier.RUN, performed.tier(),
            () -> "cqs declares TWO runnable routes and both are shipped fixes — under the"
                + " old count rule that was a demotion: " + performed);
        assertEquals(2, performed.runnable().size(),
            () -> "and both are handed over, ranked: " + performed);
        assertEquals("apply_cleanup kind=return_modified_value", performed.recipe(),
            () -> "the first is the LOCAL fix, which is the commoner case: " + performed);

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
        assertTrue(broken.runnable().isEmpty(),
            "and it hands over nothing to run — the reversal widened WHEN we instruct,"
                + " not what we are willing to name as runnable when it is not there");
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
        // THIS ASSERTED "CONSIDER" UNTIL STEP 3, and the sentence beside it said "today
        // that still yields CONSIDER" — true when written, and the tier reversal is
        // exactly what made it false. ocp declares three runnable routes, so the reader
        // now gets all three, ranked, each with what tells it from the others.
        assertTrue(advise.hint().contains("TIER: RUN — 3 alternatives"),
            () -> "three routes are three answers, not doubt: " + advise.hint());
        assertTrue(advise.hint().contains("(1) ") && advise.hint().contains("(2) ")
                && advise.hint().contains("(3) "),
            () -> "and they are RANKED, so a reader has an order to read them in: "
                + advise.hint());
        assertTrue(advise.hint().contains("the axis is the object's own lifecycle"),
            () -> "each carrying the sentence that tells it from its neighbours — without"
                + " which a ranked list is a list and the choice is a guess: "
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
        assertEquals(4, CureCatalog.Cure.class.getRecordComponents().length,
            "Cure is (recipe, operation, discriminator, needs) — still ONE step per route."
                + " DELIVERY CONDITION, unchanged and STILL UNMET: the first cure needing an"
                + " ORDERED second step replaces `recipe` with a steps list and updates"
                + " the tier derivation with it");
        assertEquals("discriminator",
            CureCatalog.Cure.class.getRecordComponents()[2].getName(),
            "the third component is the sentence that tells a cure from its"
                + " neighbours — if it ever becomes a steps list, the condition above"
                + " has been met and this test is the place that says so");
        // THE COUNT MOVED 3 -> 4 AT S8b STEP 9, and the delivery condition above did NOT.
        // This is the same correction step 2 made when the discriminator arrived: a
        // component is not a STEP. `needs` names the inputs a door takes that a finding
        // cannot carry — a new class's name, a boundary line — so the agent supplies exactly
        // those and nothing else. Bumping the number and leaving the sentence unexamined
        // would retire a deferral nobody has met, which is why the fourth component is
        // pinned BY NAME here too.
        assertEquals("needs",
            CureCatalog.Cure.class.getRecordComponents()[3].getName(),
            "and the fourth is what the AGENT must supply, not an ordered step this"
                + " product performs");
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
