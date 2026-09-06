package org.jawata.mcp.tools.smell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — a fix that commonly declines is still an instruction, with the number
 * beside it.
 *
 * <h2>This class asserted the OPPOSITE until 2026-09-06, and the reversal is the point</h2>
 *
 * <p>The {@code loops} smell has one runnable route, and that route accepts 2 of the 18 files
 * its own finder names. The old answer was to withhold the step: the tier fell to ADVISE and
 * the recipe was nulled, on the reasoning that following an instruction and getting an honest
 * no-op teaches a user to distrust the next one.</p>
 *
 * <p><b>That reasoning was right about the hazard and wrong about the remedy.</b> Withholding
 * the step tells a reader LESS — they lose the instruction and still have to find out why. The
 * measurement is now the route's DISCRIMINATOR, so the same reader gets the step AND the
 * number in the same sentence, and decides. Nothing was lost: the count that used to buy a
 * demotion now buys an informed choice, which is the whole shape of the tier reversal.</p>
 *
 * <p>The old {@code PARTIAL_ROUTES} table and {@code CureCatalog.partialReason} are gone with
 * it — a second table answering "which routes decline", consulted by the derivation, when the
 * cure itself is where that belongs.</p>
 */
class PartialRouteAdvisesTest {

    /** The registry the derivation is asked against — every operation named below exists. */
    private static final List<String> REGISTRY = List.of(
        "apply_cleanup kind=loop_to_pipeline",
        "apply_cleanup kind=remove_dead_code");

    @Test
    @DisplayName("a route that declines most of what its finding names still RUNS, and says so")
    void aPartialRouteStillRunsAndCarriesTheMeasurement() {
        CureTier.Derivation d = CureTier.derive("loops", REGISTRY);

        assertEquals(CureTier.Tier.RUN, d.tier(),
            () -> "one runnable route is an instruction even when it often declines — the"
                + " caveat belongs beside the step, not instead of it: " + d);
        assertEquals("apply_cleanup kind=loop_to_pipeline", d.recipe(),
            () -> "and the step is handed over by name: " + d);

        // THE MEASUREMENT MOVED, IT DID NOT GO. It used to arrive as the tier's reason;
        // it now arrives as the cure's discriminator, which is what a reader reads.
        String discriminator = d.runnable().get(0).discriminator();
        assertNotNull(discriminator,
            () -> "the route that declines must say so where the reader meets it: " + d);
        assertTrue(discriminator.contains("29 candidates")
                && discriminator.contains("changes 2"),
            () -> "and carry the MEASUREMENT, so a reader can judge rather than take it: "
                + discriminator);
    }

    @Test
    @DisplayName("CONTROL: an ordinary single route carries no such caveat")
    void anOrdinarySingleRouteHasNoDiscriminator() {
        CureTier.Derivation d = CureTier.derive("unused", REGISTRY);

        // Without this, the case above would pass just as well if EVERY cure had been given
        // a discriminator — which would make the caveat noise rather than information.
        assertEquals(CureTier.Tier.RUN, d.tier(),
            () -> "remove_dead_code acts on exactly what the unused finding names: " + d);
        assertEquals("apply_cleanup kind=remove_dead_code", d.recipe(),
            () -> "and it is handed to the caller by name: " + d);
        assertEquals(null, d.runnable().get(0).discriminator(),
            () -> "a route with one alternative and nothing to warn about says nothing —"
                + " silence is the default, and only a MEASURED caveat earns a sentence: "
                + d);
    }

    /**
     * THE RENDER OF A SINGLE CURE, and an honest note about which half of it is reachable.
     *
     * <p>{@code CureLookup.forKind} derives against the PROCESS registry, which in a
     * unit-test JVM is empty because no tool has registered. So a rendered hint can only be
     * asserted for a kind whose step is a pattern kind — those are published as a constant
     * and are visible without registration.</p>
     *
     * <p><b>Which means the branch this class is about — a single cure WITH a discriminator —
     * cannot be rendered here.</b> {@code loops} is the only kind of that shape and its step
     * is an {@code apply_cleanup} kind, so it needs a registry. The two assertions above pin
     * it in the MODEL; the multi-cure render is pinned in {@code CureTierTest}; and the
     * single-cure render is pinned below with the shape that IS reachable. The remaining
     * combination is verified at S9 through the built artifact, where the registry is real —
     * recorded as owed rather than asserted from a JVM that cannot see it.</p>
     */
    @Test
    @DisplayName("the rendered finding names the step; the with-caveat render is owed at S9")
    void theRenderedFindingNamesTheStep() {
        String hint = CureLookup.forKind(
            org.jawata.mcp.knowledge.CatalogueAddresses.of(null), "switch_statements").hint();

        assertTrue(hint.contains("TIER: RUN — run"),
            () -> "a single runnable route renders as an instruction: " + hint);
        assertTrue(hint.contains("replace_conditional_with_polymorphism"),
            () -> "and names the step: " + hint);
        // PROOF OF LIFE for the discriminator branch being CONDITIONAL rather than always
        // appended: this kind declares one cure and no discriminator, so nothing follows
        // the step. Without this the renderer could append an empty separator forever.
        assertTrue(hint.contains("polymorphism."),
            () -> "with nothing to warn about, the sentence ends at the step: " + hint);
    }
}
