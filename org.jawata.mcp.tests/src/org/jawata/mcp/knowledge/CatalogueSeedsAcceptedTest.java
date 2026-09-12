package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f E5 — the catalogue is ACCEPTED, so the review queue it used to fill is empty.
 *
 * <p><b>What this is about, and it is a queue rather than a label.</b> {@code candidate} is
 * not a note on a row saying where the knowledge came from — it is a WORK QUEUE.
 * {@code stats.catalogue.awaitingReview} counts it and the review seat works from it. While
 * the seeder wrote every pattern as a candidate that queue held the whole catalogue,
 * measured at 189 of 189 on the author's own store: a queue nobody could ever empty, which
 * costs the handful of entries genuinely waiting on a human the one surface built to show
 * them. Borrowed-versus-earned is carried by {@code provenanceKind} and the
 * {@code catalogue:} source ref, which is where a reader and the store actually look.</p>
 *
 * <p><b>THE ZERO BELOW IS TRIVIALLY TRUE OF AN EMPTY STORE, so most of this test exists to
 * make it able to fail.</b> {@code awaitingReview} counts catalogue rows whose status is
 * candidate; with no catalogue rows at all it is 0, so a bare assertion on it would pass
 * over a seeder that wrote nothing, a manifest that yielded nothing, and a counter wired to
 * nothing. Three things close those in turn: the population is PINNED (the seeder wrote
 * every row the manifests yield), the production counter is made to AGREE with the seeder's
 * own independent count, and a planted candidate proves the counter can still reach one.</p>
 *
 * <p><b>Nothing here writes 189.</b> The expected population is summed from the registered
 * origins' own manifests, so adding an origin moves the number rather than breaking the
 * test — and a hand-written total beside a list nothing holds it to is the defect this
 * sprint has recorded at nearly every checkpoint.</p>
 *
 * <p><b>And it reads the PRODUCTION counter rather than re-counting.</b> The claim is about
 * what {@code stats} reports, because that is the surface the review seat consumes. A
 * re-count over {@code store.all()} inside the test would be a second implementation of the
 * thing under test, and the two could agree while both were wrong.</p>
 */
class CatalogueSeedsAcceptedTest {

    /** Read the catalogue block out of the tool's own {@code stats} response. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> catalogueBlockOf(ExperienceStore store) {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode args = mapper.createObjectNode();
        args.put("kind", "stats");

        org.jawata.mcp.models.ToolResponse response =
            new org.jawata.mcp.tools.ExperienceTool(() -> null, store).execute(args);
        assertTrue(response.isSuccess(),
            () -> "stats must answer at all, or nothing below is measuring anything: "
                + response.getData());

        Map<String, Object> data = (Map<String, Object>) response.getData();
        Map<String, Object> catalogue = (Map<String, Object>) data.get("catalogue");
        assertNotNull(catalogue,
            () -> "stats must carry a 'catalogue' block — the counter this test is about"
                + " lives in it, and a missing key would otherwise read as a clean zero."
                + " Keys present: " + data.keySet());
        return catalogue;
    }

    @Test
    void the_whole_catalogue_is_accepted_so_nothing_sits_in_the_review_queue(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {

            // EVERY registered origin, unbounded. The claim is about the catalogue, not
            // about one of its sources — and seeding them all is what makes a future
            // origin that seeds `candidate` fail here rather than ship quietly.
            int yielded = 0;
            int seeded = 0;
            for (CatalogueOrigin origin : CatalogueSources.all()) {
                // items(0) rather than size(): it is what the seeder actually consumes, so
                // a manifest row the reader skips cannot fail this test for a reason that
                // has nothing to do with status.
                yielded += CatalogueManifest.read(origin).items(0).size();
                seeded += CatalogueSeeder.seed(store, origin).seeded();
            }
            // Final copies, so the failure messages below can read the counts from a
            // lambda. The accumulators themselves are not effectively final.
            final int population = yielded;
            final int written = seeded;
            System.out.println("=== catalogue population (derived, not written down) === "
                + population + " row(s) across " + CatalogueSources.all().size() + " origin(s)");

            assertTrue(population > 1,
                () -> "PROOF THERE IS A POPULATION AT ALL: the registered manifests must"
                    + " yield rows, or every count below is zero for a reason that has"
                    + " nothing to do with status. population=" + population);
            assertEquals(population, written,
                "the seeder must write every row the manifests yield — a short seed would"
                    + " leave awaitingReview honestly zero over a catalogue that is not"
                    + " actually there");

            Map<String, Object> catalogue = catalogueBlockOf(store);

            // The two counts are computed independently — the seeder reports what it wrote,
            // stats walks the stored rows and asks CatalogueSources who owns each source
            // ref. Making them agree is what rules out a seed that landed somewhere the
            // counter cannot see.
            assertEquals(population, catalogue.get("entries"),
                () -> "the production counter must see every written row, or its"
                    + " awaitingReview is a count over the wrong population: " + catalogue);

            // THE CLAIM.
            assertEquals(0, catalogue.get("awaitingReview"),
                () -> "A CATALOGUE ROW IS NOT AWAITING ANYONE'S REVIEW. `candidate` is the"
                    + " queue the review seat works from, and seeding the whole catalogue"
                    + " into it destroys that queue's usefulness for the few entries that"
                    + " genuinely wait on a human. Borrowed-versus-earned is carried by"
                    + " provenanceKind and the catalogue: source ref instead. " + catalogue);

            // THE CONTROL, and without it everything above is satisfied by a counter that
            // is wired to nothing. A row that IS a candidate, under a catalogue source ref,
            // has to move the number — so the zero asserted above is a measurement rather
            // than a constant.
            CatalogueOrigin first = CatalogueSources.all().get(0);
            ExperienceEntry planted = ExperienceEntry.of(
                    SymbolFact.of(CatalogueManifest.CATALOGUE_TYPE,
                        "a planted row that really is awaiting review",
                        Confidence.MEDIUM).build())
                .status(ExperienceEntry.CANDIDATE)
                .situation("when the control needs a candidate the counter must be able to see")
                .provenanceKind(CatalogueManifest.PROVENANCE)
                .build();
            store.putWithSource(planted, first.prefix() + "planted-control/README.md");

            Map<String, Object> afterPlant = catalogueBlockOf(store);
            assertEquals(population + 1, afterPlant.get("entries"),
                () -> "the planted row must land INSIDE the catalogue population, or the"
                    + " control below is about some other set of rows: " + afterPlant);
            assertEquals(1, afterPlant.get("awaitingReview"),
                () -> "THE CONTROL: awaitingReview must be able to reach a candidate."
                    + " If this reports 0 the assertion above measured nothing — a counter"
                    + " that never counts is indistinguishable from a catalogue that is"
                    + " wholly accepted. " + afterPlant);
        }
    }
}
