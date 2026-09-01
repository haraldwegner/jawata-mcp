package org.jawata.mcp.tools.smell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jawata.mcp.knowledge.CatalogueAddresses;
import org.jawata.mcp.knowledge.CatalogueOrigin;
import org.jawata.mcp.knowledge.CatalogueSeeder;
import org.jawata.mcp.knowledge.CatalogueSources;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28d Stage 12 — EVERY declaring kind renders its resolved cure, not
 * just {@code ocp}.
 *
 * <p><b>The defect this pins.</b> Only one registry took the store and it gave
 * it to one detector, so exactly one kind's findings carried a cure with an
 * address behind it. Twelve kinds declare a cure in {@link CureCatalog}: one
 * rendered it fully, two rendered a stripped form built from the SAME rows, and
 * eight rendered nothing. Nothing was missing but the wiring — and it was
 * per-detector wiring, so every future detector would have had to remember it.</p>
 *
 * <p><b>Why this test works on the SENTENCE and not on a detector run.</b> A
 * detector run needs a loaded workspace; the rendering is a property of the
 * lookup and the injected store, which is what changed. The reachability of the
 * rendering from a real scan is the e2e's job (`cure-tier-derived`), and the
 * as-built pass proved the chain. This asserts the half a unit test can own:
 * given the store, a declaring kind produces a cure sentence with an address
 * and a tier — and a non-declaring kind still produces nothing.</p>
 */
class EveryDeclaringKindRendersItsCureTest {

    /**
     * The eight kinds that rendered NOTHING before Stage 12, named one by one.
     *
     * <p>Listed rather than derived from the table: a set read off
     * {@code CureCatalog} would agree with it by construction and would still
     * agree after a kind quietly lost its cure. These are the eight the as-built
     * pass measured as rendering nothing.</p>
     */
    private static final List<String> WERE_SILENT = List.of(
        "switch_statements", "type_code", "singleton", "long_method",
        "cqs", "coupling", "composition_over_inheritance", "encapsulation");

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        for (CatalogueOrigin o : CatalogueSources.all()) {
            CatalogueSeeder.seed(store, o);
        }
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    @DisplayName("each of the eight formerly-silent kinds now renders a cure with an address")
    void everyFormerlySilentKindRendersACure() {
        CatalogueAddresses addresses = CatalogueAddresses.of(store);
        int checked = 0;
        for (String kind : WERE_SILENT) {
            String hint = CureLookup.forKind(addresses, kind).hint();
            assertFalse(hint.isBlank(),
                () -> "kind '" + kind + "' declares a cure in CureCatalog and rendered"
                    + " NOTHING — which is the state Stage 12 exists to end");
            assertTrue(hint.contains("TIER:"),
                () -> "kind '" + kind + "': a rendered cure carries its DERIVED tier,"
                    + " so a reader knows whether to run something or decide something: "
                    + hint);
            checked++;
        }
        assertEquals(8, checked,
            "eight kinds were measured silent by the as-built pass; a lower number"
                + " means this list lost one and the loop checked less than it claims");
    }

    /**
     * THE CONTROL, and the reason this test is not vacuous: a kind that declares
     * NO cure must still render nothing. Without it, an implementation that
     * appended a sentence to every finding would pass the assertion above while
     * being obviously wrong.
     */
    @Test
    @DisplayName("a kind that declares no cure still renders nothing")
    void aNonDeclaringKindStillRendersNothing() {
        CatalogueAddresses addresses = CatalogueAddresses.of(store);
        assertEquals("", CureLookup.forKind(addresses, "naming").hint(),
            "`naming` declares no cure; the blank is a contract other branches read");
        assertEquals("", CureLookup.forKind(addresses, "no_such_kind").hint());
    }

    /**
     * The opt-out is EXPLICIT, not a string check on the message.
     *
     * <p>`ocp` frames its cure with the principle's own lead sentence, so the
     * base must not append a second one. Keying that on a substring of the
     * message would rest a behavioural contract on user-visible wording that is
     * free to change — the seam the architect flagged one layer down.</p>
     */
    @Test
    @DisplayName("the detector that composes its own cure opts out explicitly")
    void theSelfRenderingDetectorOptsOutByOverride() {
        assertTrue(new OcpDetector(() -> null).rendersOwnCure(),
            "OcpDetector composes its own cure and must say so by override");
        assertFalse(new CqsDetector().rendersOwnCure(),
            "every other detector takes the base's rendering");
    }
}
