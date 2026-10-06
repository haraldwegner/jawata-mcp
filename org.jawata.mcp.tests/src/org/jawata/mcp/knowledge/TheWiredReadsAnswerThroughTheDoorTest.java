package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S13 — the two reads this stage wired answer THROUGH THE DOOR a caller
 * actually uses, and the strings the wiring depends on are the product's own.
 *
 * <p><b>Why this class exists: the C13 audit's B7.</b> Both wirings shipped as
 * production code with zero assertions — {@code appliedHere} written in one commit and
 * {@code hygiene} in another, each verified only by the hollow-wiring gate, which asks
 * whether a member has a non-test caller and nothing whatever about what it answers. A
 * gate that counts callers is satisfied the moment a line exists; it would stay green
 * over a block that reported the wrong number, or the right number about the wrong
 * store.</p>
 *
 * <p><b>And the coupling underneath was the part nothing could see.</b> D25's count
 * works only because three independently-written strings agree:
 * {@link CatalogueManifest} stamps a catalogue row {@code operation("design:" + slug)},
 * the retrieval gate offers a count only for an operation that
 * {@code startsWith("design:")}, and {@link ExperienceStore#appliedSampleCount} counts
 * the link rows whose TARGET equals that operation. The commit that wired it called
 * that agreement "by construction" — which is true today and is not a mechanism. If any
 * one of the three moved, every pattern in the catalogue would report zero samples
 * forever, and zero is the normal answer for most of the catalogue, so nobody would
 * ever see a thing wrong.</p>
 *
 * <p><b>Nothing here types the operation string.</b> It is read back off a row the real
 * seeder wrote, so a change to the prefix moves this test rather than leaving it
 * agreeing with a format the product no longer uses.</p>
 */
class TheWiredReadsAnswerThroughTheDoorTest {

    private static final String HEADING = "# The case";

    /** The {@code stats} response, through the same door a caller knocks on. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> statsOf(ExperienceStore store) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "stats");

        ToolResponse response = new ExperienceTool(() -> null, store).execute(args);
        assertTrue(response.isSuccess(),
            () -> "stats must answer at all, or nothing below measures anything: "
                + response.getError());
        return (Map<String, Object>) response.getData();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hygieneOf(ExperienceStore store) {
        Map<String, Object> data = statsOf(store);
        Map<String, Object> hygiene = (Map<String, Object>) data.get("hygiene");
        assertNotNull(hygiene,
            () -> "stats must carry a 'hygiene' block — D28's whole deliverable is that the "
                + "audit is reachable through a door, and a MISSING key reads to a caller "
                + "exactly like a clean store. Keys present: " + data.keySet());
        return hygiene;
    }

    private static ExperienceEntry plain(String summary) {
        return ExperienceEntry.of(
                SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
                    .scope(List.of("com.example.wired"), List.of())
                    .build())
            .scopeKind("package")
            .build();
    }

    /**
     * D28's wiring. The count AND its control, because a block reporting zero over a store
     * that holds a heading is the exact defect, and zero is also the answer for a clean
     * store — so neither number means anything without the other.
     */
    @Test
    @DisplayName("D28: stats reports the heading audit, and a clean store is told from a dirty one")
    void theHeadingAuditIsReportedThroughStats(@TempDir Path dirty, @TempDir Path clean) {
        try (H2ExperienceStore dirtyStore = H2ExperienceStore.open(dirty);
                H2ExperienceStore cleanStore = H2ExperienceStore.open(clean)) {

            // Put directly rather than through the record verb: AdmissionPolicy REFUSES a
            // heading summary at that door, which is why a heading row in a real store got
            // there some other way — loaded from a file, or written before the gate existed.
            // That is the population this audit is for.
            dirtyStore.put(plain(HEADING));
            dirtyStore.put(plain("a real sentence of knowledge that a reader could act on"));
            cleanStore.put(plain("a real sentence of knowledge that a reader could act on"));

            Map<String, Object> dirtyHygiene = hygieneOf(dirtyStore);
            Map<String, Object> cleanHygiene = hygieneOf(cleanStore);

            assertAll(
                () -> assertEquals(1, dirtyHygiene.get("headingShapedSummaries"),
                    () -> "the one heading row, counted through the door: " + dirtyHygiene),
                () -> assertTrue(String.valueOf(dirtyHygiene.get("sample")).contains(HEADING),
                    () -> "and the sample carries the offending text, so a reader can go and "
                        + "look at one rather than take the number: " + dirtyHygiene),
                // THE CONTROL. Without it every assertion above is satisfied by a block that
                // reports the same thing whatever the store holds.
                () -> assertEquals(0, cleanHygiene.get("headingShapedSummaries"),
                    () -> "a clean store reports zero, which is what makes the 1 above a "
                        + "measurement rather than a constant: " + cleanHygiene),
                () -> assertFalse(cleanHygiene.containsKey("sample"),
                    () -> "and a clean store carries NO sample key, rather than an empty list "
                        + "a reader has to interpret: " + cleanHygiene));
        }
    }

    /**
     * D25's coupling — the three-way string agreement the wiring commit called "by
     * construction" and nothing checked.
     *
     * <p>The operation is READ BACK off a row the real seeder wrote, never typed here. So
     * if {@link CatalogueManifest} stops stamping {@code design:}, or stamps something
     * else, this fails — where a literal of mine would go on agreeing with a format the
     * product had abandoned.</p>
     */
    @Test
    @DisplayName("D25: a catalogue row's own operation is what an `applied` link targets")
    void theAppliedLinkTargetsTheOperationTheCatalogueStamps(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {

            CatalogueOrigin origin = CatalogueSources.all().get(0);
            int seeded = CatalogueSeeder.seed(store, origin).seeded();
            assertTrue(seeded > 0,
                () -> "PROOF OF LIFE: the seeder must write rows, or the search below finds "
                    + "nothing and every assertion after it is vacuous. seeded=" + seeded);

            // The product's own string, off a row the product wrote.
            String operation = store.all().stream()
                .map(e -> e.facets() == null ? null : e.operation())
                .filter(java.util.Objects::nonNull)
                .filter(op -> !op.isBlank())
                .findFirst()
                .orElse(null);
            assertNotNull(operation,
                "a seeded catalogue row must carry an operation — it is the key an `applied` "
                    + "link points at, and without one D25's count has nothing to count");

            assertAll(
                // (a) The gate in ExperienceRetrieval offers a count only for an operation
                // with this prefix. A catalogue row the gate skips can never show a count,
                // however many samples it has.
                () -> assertTrue(operation.startsWith("design:"),
                    () -> "the retrieval gate offers `appliedHere` only when the operation "
                        + "starts with `design:`; a catalogue row it skips is one whose "
                        + "samples are invisible. got: " + operation),

                // (b) ZERO FIRST, so the one below is a change rather than a coincidence.
                () -> assertEquals(0, store.appliedSampleCount(operation),
                    "nobody has applied it yet, and that is the normal state for most of the "
                        + "catalogue — it must read as zero rather than as an error"),

                // (c) THE COUPLING. An experience points at that exact string, and the count
                // sees it. This is the assertion that fails if any of the three places that
                // spell the operation ever disagree.
                () -> {
                    store.put(ExperienceEntry.of(
                            SymbolFact.of("lesson",
                                "the builder kept the half-built object unreachable",
                                Confidence.MEDIUM)
                                .scope(List.of("com.example.wired"), List.of())
                                .build())
                        .scopeKind("package")
                        .situation("when a constructor would otherwise publish a partial object")
                        .verdict("worked")
                        .addLink("applied", operation)
                        .build());

                    assertEquals(1, store.appliedSampleCount(operation),
                        "the link's TARGET and the catalogue's OPERATION are one string, and "
                            + "the count joins them. A zero here means the three places that "
                            + "spell it have drifted, and every pattern in the catalogue would "
                            + "report no samples forever — silently, because zero is what most "
                            + "of the catalogue honestly reads");
                });
        }
    }
}
