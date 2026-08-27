package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c D5 — the catalogue seeds itself, once, without touching anything
 * that is not its own.
 *
 * <p>The gate that matters is the SECOND start. A loader that re-seeds is not
 * merely wasteful: it duplicates 187 rows per boot, and duplicates are what
 * spend the budget an answer has.</p>
 */
class PatternCatalogueLoaderTest {

    /**
     * The bounded sample, printed for a human to read.
     *
     * <p>Clause 5b exists because this loader is a second writer, and the
     * extractor's own sample proved the extractor, not this. What is checked
     * here is the SHAPE the loader gives a row: a situation that is a
     * condition, not a heading; the catalogue's provenance; candidate status.</p>
     */
    @Test
    void the_sample_writes_one_pattern_and_it_has_the_shape_we_want(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            PatternCatalogueLoader.Result r = new PatternCatalogueLoader().load(store, 1);

            assertEquals(1, r.inSnapshot(), "the sample must consider exactly one pattern");
            assertEquals(1, r.seeded());
            assertEquals(1, store.count(), "and write exactly one row");

            StoredEntry only = store.all().get(0);
            System.out.println("\n=== CATALOGUE SAMPLE (clause 5b, read this) ===");
            System.out.println("summary   : " + only.summary());
            System.out.println("situation : " + only.facets().situation());
            System.out.println("provenance: " + only.facets().provenanceKind());
            System.out.println("status    : " + only.status());
            System.out.println("operation : " + only.operation());
            System.out.println("sourceRef : " + only.sourceRef());

            assertEquals(PatternCatalogueLoader.PROVENANCE, only.facets().provenanceKind());
            assertEquals(ExperienceEntry.CANDIDATE, only.status(),
                "somebody else's pattern is a candidate, never the user's earned knowledge");
            assertNotNull(only.facets().situation(), "a pattern without a situation is a heading");
            assertEquals(Integer.valueOf(1), only.facets().form(),
                "form means 'carries a situation', and this row does — rows seeded"
                    + " without the stamp were listed as quality DEFECTS despite"
                    + " perfect situations, 187 of them, measured 2026-08-27");
            // Sprint 28c M8 — a published pattern is a REFERENCE, not an experience.
            // Nobody here lived it, so it has no outcome, and the form rules only
            // demand one from an experience. Labelling the catalogue `lesson` made
            // 187 library descriptions owe a verdict they cannot earn, and the value
            // invented so they could pay it was the rule announcing it was wrong
            // about them. Both halves are asserted: the type AND the absence of a
            // verdict — a type change alone would leave the invented value behind.
            assertEquals(PatternCatalogueLoader.CATALOGUE_TYPE, only.type(),
                "the catalogue must be typed as a reference, not as an experience");
            assertNull(only.facets().verdict(),
                "a pattern carries no outcome — an entry that reports one is reporting"
                    + " something nobody on this machine observed");
            assertFalse(only.facets().situation().startsWith("#"),
                "a heading is not knowledge, whatever it is labelled");
            assertTrue(only.sourceRef().startsWith(PatternCatalogueLoader.SOURCE_PREFIX),
                "sourceRef must mark the row as ours: " + only.sourceRef());
            assertFalse(only.sourceRef().contains("22a34127"),
                "the pinned commit must NOT be in the identity key — an entry keyed by "
                    + "commit is unmatchable from the next snapshot, so every update would "
                    + "look like a brand-new pattern");
        }
    }

    /**
     * Seeding twice equals seeding once. The row count is the assertion a
     * duplicate cannot survive.
     */
    /**
     * Sprint 28c D7 — a nominated REFERENCE carries the address it can be opened
     * at; a nominated EXPERIENCE does not.
     *
     * <p>The architect seat is told to read a pattern's canonical address off the
     * entry rather than compose one, and before this it could not: the candidate
     * map carried id, situation, principle, outcome and scores, and nothing that
     * locates anything. The seat's only options were to invent a path or to drop
     * the requirement, and both look like a finished report.</p>
     *
     * <p>The pair is asserted together, because either half alone passes for the
     * wrong reason. Emitting the address on everything would leak the file path
     * of whoever recorded an experience into every response — nobody else's
     * business, and noise besides. The rule is about what the row IS, not a
     * privacy patch bolted onto one rule for both.</p>
     */
    @Test
    void a_pattern_is_nominated_with_its_address_and_an_experience_is_not(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            new PatternCatalogueLoader().load(store, 1);
            StoredEntry pattern = store.all().get(0);
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson",
                        "An experience keeps its author's file path to itself.",
                        Confidence.HIGH).symbol("com.example.Private").build())
                .status(ExperienceEntry.ACCEPTED)
                .situation("when an experience is nominated beside a catalogue pattern")
                .verdict("worked")
                .form(1)
                .build());

            ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
            Map<String, Object> out = retrieval.nominate(
                "when objects of one family share attributes and each type adds its own",
                ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) out.getOrDefault("candidates", List.of());
            assertFalse(candidates.isEmpty(), "the nomination returned nothing to judge");

            boolean sawPattern = false;
            for (Map<String, Object> c : candidates) {
                if (pattern.id().equals(c.get("id"))) {
                    sawPattern = true;
                    assertEquals(pattern.sourceRef(), c.get("address"),
                        "a nominated pattern must carry the address it can be OPENED at —"
                            + " without it the seat has to invent the path or drop the"
                            + " requirement, and both look like a finished report");
                } else {
                    assertNull(c.get("address"),
                        "an experience carried an address: that is the file path of"
                            + " whoever recorded it, and it is nobody else's business");
                }
            }
            assertTrue(sawPattern, "the catalogue pattern was not among the candidates");
        }
    }

    @Test
    void a_second_start_writes_nothing(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            PatternCatalogueLoader loader = new PatternCatalogueLoader();

            PatternCatalogueLoader.Result first = loader.load(store);
            long afterFirst = store.count();
            PatternCatalogueLoader.Result second = loader.load(store);

            assertTrue(first.seeded() > 100,
                "the snapshot should carry the whole catalogue, got " + first.seeded());
            assertEquals(first.inSnapshot(), first.seeded(),
                "a fresh store must take every pattern");
            assertEquals(0, second.seeded(), "THE SECOND START RE-SEEDED THE CATALOGUE");
            assertEquals(first.inSnapshot(), second.unchanged(),
                "and must recognise every one of them as already current");
            assertEquals(afterFirst, store.count(), "the row count must not move");
            assertTrue(second.quiet(), "a quiet run is what the start-up line keys on");
        }
    }

    /**
     * The user's own rows are not the loader's business. Compared by export
     * rather than by count, because a count survives a row being rewritten.
     */
    @Test
    void a_foreign_store_keeps_every_user_row_byte_identical(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("failure_mode", "The user's own hard-won lesson.",
                        Confidence.HIGH).symbol("com.example.Mine").build())
                .status(ExperienceEntry.ACCEPTED)
                .situation("when the user recorded something themselves")
                .verdict("failed_avoid")
                .provenanceKind("recorded")
                .form(1)
                .build());
            List<Map<String, Object>> before = store.exportEntries(null, null);

            new PatternCatalogueLoader().load(store);

            List<Map<String, Object>> after = store.exportEntries(null, null);
            Map<String, Object> mineBefore = onlyRecorded(before);
            Map<String, Object> mineAfter = onlyRecorded(after);
            assertEquals(mineBefore, mineAfter, "A USER ROW WAS MODIFIED BY THE SEEDER");
            assertTrue(after.size() > before.size(), "and the catalogue rows were added");
        }
    }

    private static Map<String, Object> onlyRecorded(List<Map<String, Object>> rows) {
        return rows.stream()
            .filter(r -> "recorded".equals(r.get("provenance_kind")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the user's row vanished entirely"));
    }

    /**
     * An upstream edit is visible as a changed hash, which is the whole reason
     * identity is content-based rather than marker-based. Stage 5 decides what
     * to DO with a changed pattern; this pins that it is noticed at all.
     */
    @Test
    void a_changed_pattern_is_noticed_on_the_next_start(@TempDir Path dir) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode pattern = json.createObjectNode();
        pattern.put("slug", "some-pattern");
        pattern.put("type", "lesson");
        pattern.put("situation", "when two objects must vary independently");
        pattern.put("principle", "Separate the abstraction from its implementation.");
        com.fasterxml.jackson.databind.node.ObjectNode snap = json.createObjectNode();
        snap.put("pinned_commit", "deadbeef");
        snap.putArray("patterns").add(pattern);

        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1, new PatternCatalogueLoader(snap).load(store).seeded());
            assertEquals(0, new PatternCatalogueLoader(snap).load(store).seeded());

            pattern.put("principle", "Separate the abstraction from its implementation, "
                + "so the two can vary without touching each other.");
            PatternCatalogueLoader.Result after =
                new PatternCatalogueLoader(snap).load(store);

            assertEquals(1, after.seeded(),
                "AN UPSTREAM EDIT WENT UNNOTICED — the catalogue can never be updated");
            assertEquals(0, after.unchanged());
        }
    }

    /**
     * The hash covers the whole pattern, not just its first line. A body
     * rewritten under an unchanged summary must still count as changed.
     */
    @Test
    void the_hash_covers_the_body_not_only_the_summary() {
        com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode a = json.createObjectNode();
        a.put("slug", "x");
        a.put("principle", "Same first line.");
        a.put("details", "The original body.");
        com.fasterxml.jackson.databind.node.ObjectNode b = a.deepCopy();
        b.put("details", "A completely rewritten body.");

        assertFalse(PatternCatalogueLoader.hashOf(a).equals(PatternCatalogueLoader.hashOf(b)),
            "a rewritten body under an unchanged summary would ship forever");
    }

    /**
     * D6's OTHER half: what an upstream edit leaves BEHIND.
     *
     * <p>{@code a_changed_pattern_is_noticed_on_the_next_start} says in its own
     * javadoc that it "pins that it is noticed at all" and leaves the disposition
     * to a later stage. So the deliverable's actual sentence — <em>a newer jawata
     * proposes, and never overwrites</em> — had no test at all: the sprint could
     * close with detection proven and disposition unexamined.</p>
     *
     * <p>The mechanism says overwriting cannot happen: {@code putWithSource}
     * inserts under a fresh {@code UUID.randomUUID()}, so the incumbent row is
     * never updated in place. What that leaves open is whether the two rows are
     * RELATED. If the newcomer neither supersedes the incumbent nor retires it,
     * an upstream edit does not update the catalogue — it duplicates it, and both
     * versions answer questions forever, the stale one indistinguishable from the
     * current one.</p>
     *
     * <p>This test states the contract as the deliverable words it. A failure
     * here is not a broken build: it is the disposition being reported for the
     * first time, and the message names what the store actually did.</p>
     */
    @Test
    void an_upstream_edit_supersedes_the_incumbent_rather_than_duplicating_it(@TempDir Path dir)
            throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode pattern = json.createObjectNode();
        pattern.put("slug", "bridge");
        pattern.put("type", "lesson");
        pattern.put("situation", "when two objects must vary independently");
        pattern.put("principle", "Separate the abstraction from its implementation.");
        com.fasterxml.jackson.databind.node.ObjectNode snap = json.createObjectNode();
        snap.put("pinned_commit", "deadbeef");
        snap.putArray("patterns").add(pattern);

        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1, new PatternCatalogueLoader(snap).load(store).seeded());

            pattern.put("principle", "Separate the abstraction from its implementation, "
                + "so the two can vary without touching each other.");
            assertEquals(1, new PatternCatalogueLoader(snap).load(store).seeded(),
                "precondition: the edit is noticed");

            String ref = PatternCatalogueLoader.SOURCE_PREFIX + "bridge/README.md";
            List<StoredEntry> rows = new java.util.ArrayList<>();
            for (StoredEntry e : store.all()) {
                if (ref.equals(e.sourceRef())) {
                    rows.add(e);
                }
            }

            assertEquals(2, rows.size(),
                () -> "the incumbent must SURVIVE the edit — one row would mean the newcomer"
                    + " overwrote it: " + rows.size());

            long live = rows.stream().filter(e -> !"superseded".equals(e.status())).count();
            assertEquals(1, live,
                () -> "exactly ONE version of a pattern may answer questions. Both rows are"
                    + " live, so an upstream edit DUPLICATES the catalogue instead of"
                    + " updating it, and the stale text keeps answering beside the current"
                    + " one with nothing to tell them apart. Statuses: "
                    + rows.stream().map(StoredEntry::status).toList());
        }
    }
}
