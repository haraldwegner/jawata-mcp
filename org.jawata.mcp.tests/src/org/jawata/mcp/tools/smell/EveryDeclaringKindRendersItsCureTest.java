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
     * THE UNIVERSAL THIS CLASS IS NAMED FOR — added at C8, because until then the class
     * promised one and its only loop was over eight names.
     *
     * <p><b>An architect watch measured the gap and it is bigger than the audit reported.</b>
     * {@link CureCatalog} declares TWENTY-NINE kinds (measured at C8b round 6, against
     * "twenty-two" written here when the table was smaller) and {@code WERE_SILENT} covers
     * eight, so
     * <b>fourteen declaring kinds had their rendering asserted by nothing at all</b>,
     * including every kind Stage 8 added. The class name says "every declaring kind"; this
     * method is what makes that true.</p>
     *
     * <p><b>THE FIRST VERSION OF THIS LOOP SKIPPED {@code ocp}, AND THAT SKIP WAS INERT —
     * a mutation is what found it.</b> It was written believing that a detector answering
     * {@code rendersOwnCure()} must render nothing HERE, so including it would assert the
     * opposite of what it promises. Measured: with the skip disabled, {@code ocp} entered the
     * loop and the test stayed GREEN, because {@code rendersOwnCure()} governs whether
     * {@link AbstractAstDetector} appends a cure sentence to its own FINDINGS — it has no
     * bearing on {@link CureLookup}, which renders for {@code ocp} like any other declaring
     * kind. So the guard excluded nothing, and the sentence explaining it was false. It is
     * gone, the loop covers all twenty-two, and this paragraph is here because a dead branch
     * that reads as load-bearing is worse than no branch: the next reader would have believed
     * it.</p>
     *
     * <p><b>{@code WERE_SILENT} IS NOT REPLACED, and that is the point of adding rather than
     * rewriting.</b> Its javadoc argues it must stay hand-written — <i>"a set read off
     * CureCatalog would agree with it by construction and would still agree after a kind
     * quietly lost its cure"</i> — and the architect tested that defence and found it HOLDS:
     * those eight are a measurement taken by the as-built pass on a date, which no live
     * object owns. It is a regression lock across TIME. This method is the complementary
     * shape, a universal across the CURRENT table, and the two fail on different things: the
     * lock catches a kind losing its cure, the universal catches a kind arriving without one.
     * Deriving the lock would have destroyed the only guard against the first.</p>
     */
    @Test
    @DisplayName("EVERY kind that declares a cure renders it — derived from the table, not listed")
    void everyDeclaringKindRendersACure() {
        CatalogueAddresses addresses = CatalogueAddresses.of(store);

        java.util.List<String> silent = new java.util.ArrayList<>();
        java.util.List<String> checked = new java.util.ArrayList<>();
        for (String kind : CureCatalog.declaredKinds()) {
            String hint = CureLookup.forKind(addresses, kind).hint();
            if (hint.isBlank() || !hint.contains("TIER:")) {
                silent.add(kind + " -> " + (hint.isBlank() ? "(nothing)" : hint));
            }
            checked.add(kind);
        }

        // PROOF OF LIFE. An empty or tiny population would make the emptiness assertion below
        // pass over nothing — the shape this sprint has now found six times. The floor is
        // deliberately well under the current twenty-two so that ADDING a kind never fails
        // this line; what it catches is the loop running over a table that failed to load.
        assertTrue(checked.size() >= 15,
            "the cure table must yield a real population here, or the check below passes"
                + " over nothing. Checked: " + checked);
        assertTrue(silent.isEmpty(),
            "these kinds DECLARE a cure and render it without an address or without its"
                + " derived tier, so a reader is told a fix exists and not which one or"
                + " whether to run it: " + silent);
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
