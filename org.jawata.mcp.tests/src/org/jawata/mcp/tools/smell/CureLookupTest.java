package org.jawata.mcp.tools.smell;

import org.jawata.mcp.knowledge.CatalogueAddresses;
import org.jawata.mcp.knowledge.CatalogueOrigin;
import org.jawata.mcp.knowledge.CatalogueSeeder;
import org.jawata.mcp.knowledge.CatalogueSources;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CURE LOOKUP — Sprint 28d Stage 5 (arch step 4, spec D6).
 *
 * <p>Three properties, one test each, and each able to fail for exactly one
 * reason:</p>
 *
 * <ul>
 *   <li><b>W1 resolve, never compose</b> — every address is read off a row that
 *       exists; a key nothing carries yields nothing.</li>
 *   <li><b>W2 an absent namespace is a STATED degradation</b> — a namespace
 *       holding zero rows is NAMED, and a namespace holding rows is not.</li>
 *   <li><b>W3 cure addresses re-resolve</b> — a deliberately broken declaration
 *       produces a named NON-ZERO count, which returns to zero when repaired.
 *       Asserted as a pair, in that order.</li>
 * </ul>
 *
 * <p>A fourth test carries C5's own exit clause: EACH of the five kinds this
 * sprint adds resolves, per kind rather than one example.</p>
 */
class CureLookupTest {

    /**
     * The distinct cure keys the table declares, counted BY HAND from
     * {@link CureCatalog}: ocp 3 (state, command, template-method) + cqs 1 +
     * coupling 2 + composition_over_inheritance 2 + encapsulation 1 = 9; the
     * churn traces and switch_statements re-use ocp's three and add none;
     * type_code, singleton and long_method add one each = 12.
     *
     * <p>Hand-derived rather than read from the table, because a number taken
     * from the thing under test agrees with it by construction and would still
     * agree after a cure was accidentally dropped.</p>
     */
    private static final int DECLARED_CURE_KEYS = 12;

    /** The five principle kinds Sprint 28d adds — C5's per-kind clause. */
    private static final List<String> NEW_KINDS = List.of(
        "cqs", "coupling", "composition_over_inheritance", "ocp", "encapsulation");

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void seedEverySource() {
        for (CatalogueOrigin o : CatalogueSources.all()) {
            CatalogueSeeder.seed(store, o);
        }
    }

    // ------------------------------------------------------------------ W1

    /**
     * W1 — AN ADDRESS IS READ OFF A ROW, NEVER BUILT FROM A SLUG.
     *
     * <p><b>What makes this falsifiable — REBUILT at Stage 6/S4.</b> It used to
     * rest on the two registered sources spelling their {@code source_ref}s
     * incompatibly: the fork's with a {@code /README.md} tail, the samples' with
     * no tail at all under a {@code sample:} namespace. No single composition
     * rule produced both, so a lookup that BUILT addresses got one assertion
     * wrong whichever convention it picked.
     *
     * <p><b>S4 unified the scheme on purpose, and that destroyed the property.</b>
     * Both origins now emit {@code catalogue:<namespace>/<slug>/README.md}, so one
     * composition rule satisfies both — the test would have kept passing while
     * proving nothing. The divergence is therefore introduced DELIBERATELY now, by
     * a probe row carrying a {@code source_ref} no composition rule would generate.
     * That does not depend on the origins differing, so it survives any further
     * unification. And a key nothing holds must still produce NOTHING — a composer
     * would hand back a perfectly plausible address for a pattern that does not
     * exist.</p>
     */
    @Test
    @DisplayName("W1: a cure address is the row's own source_ref, and an unheld key resolves to nothing")
    void anAddressIsReadOffARowAndNeverComposed() {
        seedEverySource();
        CatalogueAddresses addresses = CatalogueAddresses.of(store);

        // A key NO row carries. A composer answers; a resolver does not.
        assertNull(addresses.address("design:this-pattern-does-not-exist"),
            "a cure key nothing holds must resolve to NOTHING. A non-null answer here"
                + " means the address was built from the key rather than read off a row,"
                + " which succeeds for every slug anyone ever mistypes");

        CatalogueAddresses.Address fork = addresses.address("design:state");
        assertNotNull(fork, "the seeded fork holds `state`; if this is null the seeding,"
            + " not the composition rule, is what broke");
        assertEquals("catalogue:java-design-patterns/state/README.md", fork.sourceRef());

        CatalogueAddresses.Address sample = addresses.address("design:compose-method");
        assertNotNull(sample, "the seeded samples hold `compose-method`");
        assertEquals("catalogue:jawata-samples/compose-method/README.md", sample.sourceRef());

        assertNotEquals(fork.namespace(), sample.namespace(),
            "the two addresses must come from different namespaces, or this test is"
                + " comparing one convention against itself");

        // THE DISCRIMINATOR, REBUILT AT S4 — and the rebuild is the point.
        //
        // It used to rest on the two sources spelling their refs INCOMPATIBLY:
        // the fork with a /README.md tail, the samples with none, so no single
        // composition rule could satisfy both assertions. S4 unified the scheme
        // deliberately, and that DESTROYED the property this test was leaning on:
        // both origins now emit catalogue:<namespace>/<slug>/README.md, and a
        // composer following that one rule would satisfy every assertion above.
        // Updating the expected string alone would have left a guard that cannot
        // fail for the reason it names.
        //
        // So the divergence is now introduced ON PURPOSE: one row whose ref no
        // convention would ever produce. A composer cannot reach it; only a reader
        // can. This does not depend on the two origins differing, so it survives
        // any further unification.
        String unComposable = "catalogue:jawata-samples/deliberately/not/the/composed/shape";
        store.putWithSource(probeEntry(), unComposable, "hash-ref-divergence-probe");

        CatalogueAddresses.Address probe =
            CatalogueAddresses.of(store).address("design:ref-divergence-probe");
        assertNotNull(probe, "the probe row was seeded and carries its operation key; a null"
            + " here means the lookup missed a row that exists, not that it composed one");
        assertEquals(unComposable, probe.sourceRef(),
            "the address MUST be the ref the row actually carries. Any value derived from"
                + " the slug — including the now-shared catalogue:<ns>/<slug>/README.md"
                + " convention — proves the lookup composed rather than read");
    }

    /**
     * A row for the composition probe: an ordinary catalogue-shaped entry whose
     * only job is to carry an operation key at a {@code source_ref} that no
     * composition rule in the codebase would generate.
     */
    private static org.jawata.mcp.knowledge.ExperienceEntry probeEntry() {
        String situation = "when a lookup must return the address a row carries rather than"
            + " one derived from its key";
        return org.jawata.mcp.knowledge.ExperienceEntry.of(
                org.jawata.mcp.knowledge.SymbolFact.of("reference",
                    "A probe row whose source_ref no composition rule produces.",
                    org.jawata.mcp.knowledge.Confidence.MEDIUM)
                    .details("Seeded by CureLookupTest to keep W1 falsifiable after S4"
                        + " unified the two address schemes.")
                    .build())
            .status(org.jawata.mcp.knowledge.ExperienceEntry.CANDIDATE)
            .situation(situation)
            .cause("a composed address answers for every key, including ones nothing holds")
            .form(org.jawata.mcp.knowledge.EntryForm.formOf(situation))
            .provenanceKind("catalog")
            .operation("design:ref-divergence-probe")
            .build();
    }

    // ------------------------------------------------------------------ W2

    /**
     * W2 — "THE CATALOGUE HAD NOTHING FOR YOU" AND "THERE IS NO CATALOGUE" ARE
     * DIFFERENT ANSWERS, AND ONLY THE SECOND IS A FAULT.
     *
     * <p>Seeds the fork and NOT the samples, so exactly one registered
     * namespace holds zero rows. The lookup must NAME that namespace — the
     * shape the store already uses for an unembedded corpus and for substrate
     * drift — and must not name the one that is fine. The hardcoded recipes
     * arrive labelled as the degradation they are, never as the answer.</p>
     */
    @Test
    @DisplayName("W2: a namespace holding zero rows is NAMED, and the fallback says it is a fallback")
    void anAbsentNamespaceIsNamedRatherThanReadAsAnEmptyAnswer() {
        // The fork ONLY — the samples namespace is deliberately left empty, which is
        // the whole point of this test. Selected BY NAME rather than by position:
        // registry order is a real contract for merge determinism, but a test that
        // says get(0) breaks silently and confusingly the day the order changes.
        CatalogueOrigin fork = CatalogueSources.all().stream()
            .filter(o -> "java-design-patterns".equals(o.namespace()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the fork origin is not registered — this test asserts what happens when"
                    + " one of two namespaces is empty, and cannot run with neither"));
        CatalogueSeeder.seed(store, fork);

        CatalogueAddresses addresses = CatalogueAddresses.of(store);
        assertEquals(List.of("jawata-samples"), addresses.absentNamespaces(),
            "exactly the un-seeded namespace must be reported absent — the fork was"
                + " seeded and the samples were not");

        // long_method's cure lives in the EMPTY namespace.
        CureLookup.Cures degraded = CureLookup.forKind(addresses, "long_method");
        assertTrue(degraded.resolved().isEmpty(),
            "nothing can resolve out of a namespace holding no rows");
        assertEquals(List.of("design:compose-method"), degraded.unresolved());
        assertNotNull(degraded.degradation(),
            "an unresolvable cure whose namespace is EMPTY is a fault, not an answer");
        assertTrue(degraded.degradation().contains("jawata-samples"),
            () -> "the degradation must NAME the absent namespace — a caller cannot fix"
                + " what it is not told: " + degraded.degradation());
        assertFalse(degraded.degradation().contains("java-design-patterns"),
            () -> "the seeded namespace is not absent and naming it would send a reader"
                + " to repair a catalogue that is fine: " + degraded.degradation());
        assertEquals(List.of("compose_method"), degraded.fallbackRecipes(),
            "the hardcoded map is handed over — as the fallback, which is why it only"
                + " appears beside a non-null degradation");
        assertTrue(degraded.hint().contains("DEGRADED"),
            () -> "the finding's own sentence must say the cure did not come from the"
                + " store, or the degradation is invisible where it is read: "
                + degraded.hint());

        // The other half of the same property: a kind whose cures DID resolve
        // is not dragged into the degradation just because some namespace is
        // empty. Absence is reported; it is not contagious.
        CureLookup.Cures fine = CureLookup.forKind(addresses, "ocp");
        assertEquals(3, fine.resolved().size(),
            "ocp declares state / command / template-method, all three in the seeded fork");
        assertNull(fine.degradation(),
            "every declared cure resolved, so nothing here is a fallback — an empty"
                + " namespace elsewhere does not make THIS kind's answer a fallback,"
                + " which is the half that says absence is reported and not contagious");
    }

    // ------------------------------------------------------------------ W3

    /**
     * W3 — THE RE-RESOLUTION CHECK, PROVED IN BOTH DIRECTIONS.
     *
     * <p>{@code java-design-patterns} is a FOREIGN authority pinned to somebody
     * else's commit, so moving the pin can rename or drop a pattern under us.
     * Nothing would fail: the cure would simply stop carrying an address. This
     * check is what makes that visible.</p>
     *
     * <p><b>The pair, in that order.</b> A check that never fires and a corpus
     * with nothing to find produce identical output, so the clean run is only
     * evidence AFTER the same check has been seen to report a non-zero count on
     * a declaration that is deliberately wrong. The broken run comes first for
     * that reason and not for tidiness.</p>
     */
    @Test
    @DisplayName("W3: a broken cure mapping reports a NAMED non-zero count, then zero once repaired")
    void aBrokenCureMappingIsCountedAndNamedThenReturnsToZero() {
        seedEverySource();

        // (1) BROKEN FIRST — one declared key renamed the way an upstream pin
        //     move would rename it. The check must SEE it.
        String moved = "design:state-RENAMED-UPSTREAM";
        List<String> broken = new ArrayList<>(CureCatalog.declaredOperations());
        assertTrue(broken.remove("design:state"),
            "the declaration must actually contain the key this test breaks, or the"
                + " 'broken' run is broken for a different reason than intended");
        broken.add(moved);

        CureLookup.Audit afterMove = CureLookup.audit(store, broken);
        assertEquals(1, afterMove.unresolved(),
            () -> "exactly the one renamed cure must fail to re-resolve: " + afterMove);
        assertEquals(List.of(moved), afterMove.unresolvedOperations(),
            "and it must be NAMED — a bare count cannot tell an upstream rename from a"
                + " mistyped table row");
        assertFalse(afterMove.clean());
        assertEquals(DECLARED_CURE_KEYS, afterMove.declared());
        assertEquals(DECLARED_CURE_KEYS - 1, afterMove.resolved());

        // (2) REPAIRED SECOND — the real declaration, against the same store.
        CureLookup.Audit repaired = CureLookup.audit(store);
        assertEquals(0, repaired.unresolved(),
            () -> "every declared cure must point at a live catalogue row: " + repaired);
        assertTrue(repaired.clean());
        assertEquals(DECLARED_CURE_KEYS, repaired.declared(),
            "the hand-derived count of distinct declared cure keys");
        assertEquals(DECLARED_CURE_KEYS, repaired.resolved());
        assertTrue(repaired.absentNamespaces().isEmpty(),
            "both sources were seeded, so no namespace is absent");
    }

    // ------------------------------------------- C5's per-kind exit clause

    /**
     * C5 — EACH of the five kinds this sprint adds has a cure that resolves to
     * an entry the catalogue actually holds. Per kind, not one example: a
     * single-example assertion passes while four of five kinds point nowhere.
     */
    @Test
    @DisplayName("each of the five new kinds' cure ids resolves to an entry the catalogue holds")
    void everyNewKindsCureResolves() {
        seedEverySource();
        CatalogueAddresses addresses = CatalogueAddresses.of(store);

        int checked = 0;
        for (String kind : NEW_KINDS) {
            List<CureCatalog.Cure> declared = CureCatalog.curesFor(kind);
            assertFalse(declared.isEmpty(),
                () -> "kind '" + kind + "' is on this sprint's roster and declares NO cure —"
                    + " a detector that names a problem and no answer to it");
            for (CureCatalog.Cure c : declared) {
                assertTrue(addresses.resolves(c.operation()),
                    () -> "kind '" + kind + "': cure key " + c.operation() + " resolves to"
                        + " nothing. Either the catalogue does not hold that design or the"
                        + " key is mis-spelled — and both look identical from the finding");
                checked++;
            }
            CureLookup.Cures cures = CureLookup.forKind(addresses, kind);
            assertTrue(cures.unresolved().isEmpty(),
                () -> "kind '" + kind + "' has unresolved cures: " + cures.unresolved());
            assertNull(cures.degradation(),
                () -> "kind '" + kind + "' fell back to the hardcoded map on a fully"
                    + " seeded store, which means the lookup did not consult it");
        }
        // 3 (ocp) + 1 (cqs) + 2 (coupling) + 2 (composition_over_inheritance)
        // + 1 (encapsulation), counted by hand from CureCatalog.
        assertEquals(9, checked,
            "the five kinds declare nine cure keys between them; a lower number means a"
                + " kind quietly lost its cures and the loop above checked nothing for it");
    }
}
