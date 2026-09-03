package org.jawata.mcp.tools.smell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — a fix that commonly declines must not be reported as an instruction.
 *
 * <p>{@link CureTier} derives PERFORM from route COUNT alone: one runnable route, run it.
 * An architect review found the consequence. The {@code loops} smell has exactly one
 * route, so the product told users to RUN a rewrite that, measured over the whole fork,
 * accepts 2 of the 21 files its own finder names. Following an instruction and getting an
 * honest no-op teaches a user to distrust the next instruction too.</p>
 *
 * <p>The derivation now consults whether the route acts on what the finding names. The
 * control below is what makes this a test rather than a restatement: a kind whose single
 * route is NOT partial still derives PERFORM, so the change narrows one case rather than
 * flattening every tier to ADVISE.</p>
 */
class PartialRouteAdvisesTest {

    /** The registry the derivation is asked against — every operation named below exists. */
    private static final List<String> REGISTRY = List.of(
        "apply_cleanup kind=loop_to_pipeline",
        "apply_cleanup kind=remove_dead_code");

    @Test
    @DisplayName("a route that declines most of what its finding names ADVISES, and says why")
    void aPartialRouteAdvises() {
        CureTier.Derivation d = CureTier.derive("loops", REGISTRY);

        assertEquals(CureTier.Tier.ADVISE, d.tier(),
            "one runnable route, but it declines most candidates: " + d);
        assertNull(d.recipe(),
            "ADVISE carries no recipe — offering one is the instruction this removes: " + d);
        assertTrue(d.reason().contains("declines most of what this finding names"),
            "the reason must say what changed the tier: " + d.reason());
        assertTrue(d.reason().contains("33 candidates") && d.reason().contains("changes 2"),
            "and carry the MEASUREMENT, so a reader can judge rather than take it: "
                + d.reason());
    }

    @Test
    @DisplayName("CONTROL: a single route that is not partial still PERFORMs")
    void anOrdinarySingleRouteStillPerforms() {
        CureTier.Derivation d = CureTier.derive("unused", REGISTRY);

        // Without this, the case above would pass just as well if the change had made
        // every derivation ADVISE — which is a different product, not a repair.
        assertEquals(CureTier.Tier.PERFORM, d.tier(),
            "remove_dead_code acts on exactly what the unused finding names: " + d);
        assertEquals("apply_cleanup kind=remove_dead_code", d.recipe(),
            "and it is still handed to the caller by name: " + d);
        assertNotEquals(CureTier.Tier.ADVISE, d.tier(), "the control must differ from the case");
    }

    @Test
    @DisplayName("the partial reason is absent for a route nobody measured as partial")
    void unmeasuredRoutesAreNotPartial() {
        assertNull(CureCatalog.partialReason("apply_cleanup kind=remove_dead_code"),
            "only a MEASURED route belongs in that table; silence is the default");
        assertNull(CureCatalog.partialReason(null), "and a null operation is not a lookup");
    }
}
