package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sprint 28d Stage 6 / S2 — THE SAMPLES LANE GETS THE ONE LIFECYCLE.
 *
 * <p><b>Why this class did not exist before, which is the whole point.</b> There
 * were two catalogue sources and only one of them had a test for its lifecycle.
 * The fork's loader was made to retire an incumbent on an edit (28c D6) and then
 * to sweep patterns upstream had dropped (28d); both were pinned by
 * {@link CatalogueSeederLifecycleTest}. `SampleSource.seed` was nine lines that did
 * NEITHER, and nothing noticed, because the assertions lived in a test bound to
 * the other class. A contract asserted about one implementation says nothing
 * about the second.</p>
 *
 * <p><b>Why it matters more here than for the fork, not less.</b> These samples
 * version with the product, so EDITING one is the ordinary case rather than a
 * rare upstream event — and {@code CatalogueAddresses.of} merges with
 * {@code putIfAbsent}, so two live rows carrying one operation key resolve to
 * whichever the store hands back first. Arbitrarily the stale one.</p>
 *
 * <p><b>Red before green, and honestly which ones.</b> The first two assertions
 * FAIL on the pre-S2 code: it wrote a new row and left the old one live. The
 * third cannot fail beforehand — a lane with no sweep at all trivially "sweeps
 * nothing" — so it is a guard on behaviour that does not exist yet, and it earns
 * its keep only against the sweep, proven by removing the guard.</p>
 */
class SamplesOriginLifecycleTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The specimens origin, selected by name rather than by registry position. */
    private static final CatalogueOrigin SAMPLES = CatalogueSources.all().stream()
        .filter(o -> "jawata-samples".equals(o.namespace()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "the samples origin is not registered — every contract below is about its"
                + " lifecycle and none of them can run without it"));

    /**
     * Seed from an index built in memory. S6: seeding is no longer a method on the
     * source — an origin is a record, so there is nothing for it to implement
     * wrongly — and the one lifecycle takes the origin plus its manifest.
     */
    private static CatalogueSeeder.Outcome seedFrom(H2ExperienceStore store, JsonNode index) {
        return CatalogueSeeder.seed(store, SAMPLES, CatalogueManifest.of(SAMPLES, index), 0);
    }

    /** An index carrying these slugs and DECLARING its own count, as the real one must. */
    private static JsonNode indexOf(String... slugs) {
        return indexDeclaring(slugs.length, slugs);
    }

    /**
     * An index that DECLARES {@code declared} samples while carrying {@code slugs}
     * — the truncated-input case the completeness guard exists for.
     */
    private static JsonNode indexDeclaring(int declared, String... slugs) {
        ObjectNode root = M.createObjectNode();
        root.put("authority", "org.jawata.samples, versioned with this product");
        root.put("count", declared);
        ArrayNode arr = root.putArray("samples");
        for (String slug : slugs) {
            ObjectNode s = arr.addObject();
            s.put("slug", slug);
            s.put("capability", "compose_method");
            s.put("principle", "Split a method that does several things into named steps");
            s.put("situation", "when one method mixes gathering, formatting and writing");
            s.put("cause", "the steps have no names, so the sequence can only be read line by line");
            s.put("details", "BEFORE and AFTER live beside this entry.");
        }
        return root;
    }

    /** The same index with one sample's CONTENT changed, so its hash moves. */
    private static JsonNode edited(String... slugs) {
        ObjectNode root = (ObjectNode) indexOf(slugs);
        ((ObjectNode) root.path("samples").get(0))
            .put("principle", "EDITED — split a long method into intention-revealing steps");
        return root;
    }

    /**
     * S4: the ref carries the {@code /README.md} suffix, unified with the fork's
     * scheme. Composed here the same way the source composes it — if the two ever
     * disagree this returns nothing, and every assertion below reads as "the row
     * vanished" rather than "the test is looking in the wrong place".
     */
    private static List<StoredEntry> rowsFor(H2ExperienceStore store, String slug) {
        String ref = SAMPLES.prefix() + slug + "/README.md";
        return store.all().stream().filter(e -> ref.equals(e.sourceRef())).toList();
    }

    private static long liveCount(List<StoredEntry> rows) {
        return rows.stream().filter(e -> !ExperienceEntry.SUPERSEDED.equals(e.status())).count();
    }

    @Test
    void an_edited_sample_supersedes_its_incumbent_rather_than_duplicating_it(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            seedFrom(store, indexOf("compose-method"));
            assertEquals(1, liveCount(rowsFor(store, "compose-method")),
                "PROOF OF LIFE: one live row after the first seed");

            seedFrom(store, edited("compose-method"));

            List<StoredEntry> rows = rowsFor(store, "compose-method");
            assertEquals(2, rows.size(),
                () -> "the incumbent must SURVIVE the edit — one row would mean the newcomer"
                    + " overwrote it: " + rows.size());
            assertEquals(1, liveCount(rows),
                () -> "exactly ONE version of a sample may answer. Both rows live means an edit"
                    + " DUPLICATES the entry instead of updating it, and because"
                    + " CatalogueAddresses merges with putIfAbsent the address resolves to"
                    + " whichever the store returns first — arbitrarily the stale one."
                    + " Statuses: " + rows.stream().map(StoredEntry::status).toList());
        }
    }

    @Test
    void a_sample_gone_from_the_index_is_retired_and_stops_answering(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            seedFrom(store, indexOf("compose-method", "replace-pattern-with-idiom"));
            assertEquals(1, liveCount(rowsFor(store, "replace-pattern-with-idiom")),
                "PROOF OF LIFE: the sample must be live before its removal can mean anything");

            seedFrom(store, indexOf("compose-method"));

            assertEquals(0, liveCount(rowsFor(store, "replace-pattern-with-idiom")),
                () -> "a sample the index no longer carries must stop answering — while it stays"
                    + " live it hands out an address that no longer exists, and the cure sweep"
                    + " reports CLEAN because the key still resolves to it. Statuses: "
                    + rowsFor(store, "replace-pattern-with-idiom").stream()
                        .map(StoredEntry::status).toList());
            assertEquals(1, liveCount(rowsFor(store, "compose-method")),
                "the sample the index still carries must be untouched — a sweep that retires"
                    + " the survivors too is worse than the bug");
        }
    }

    @Test
    void a_partial_index_retires_nothing(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            seedFrom(store, indexOf("compose-method", "replace-pattern-with-idiom"));

            // An index that PARSES but is truncated — one sample where it declares
            // two — must not be read as "the other one was deleted upstream".
            seedFrom(store, indexDeclaring(2, "compose-method"));

            assertEquals(1, liveCount(rowsFor(store, "replace-pattern-with-idiom")),
                "an index carrying fewer samples than it declares is a truncated read, not a"
                    + " deletion — nothing may be retired on it");
        }
    }
}
