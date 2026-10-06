package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S13 / D25 — a pattern accumulates our own worked samples, countably.
 *
 * <p>Harald's decision, asked whether a second use should replace the first:
 * <em>"Why replace. It adds as a sample. You can have 1..n here."</em> So the
 * deliverable is not "can an experience point at a pattern" — links already
 * existed as rows in {@code experience_link} — but that the pointing is a
 * NAMED relation in the closed vocabulary and that its multiplicity is
 * answerable as a NUMBER.</p>
 *
 * <p><b>The fixture is built so each claim has something that would break it.</b>
 * Two experiences apply the same pattern and a third merely {@code related}s to
 * it, which is what lets the count discriminate: drop the relation filter and it
 * reads three, collapse the rows and it reads one. A fixture with a single
 * applied link would satisfy every plausible implementation, including the two
 * wrong ones.</p>
 */
class AppliedSamplesAreCountedTest {

    private static final String PATTERN = "design:step-builder";
    private static final String OTHER_PATTERN = "design:null-object";

    private static ExperienceEntry entryApplying(String summary, String rel, String target) {
        SymbolFact fact = SymbolFact.of("lesson", summary, Confidence.MEDIUM)
            .scope(List.of("com.example.sel"), List.of())
            .details("fixture for D25's sample count")
            .build();
        return ExperienceEntry.of(fact)
            .scopeKind("package")
            .addLink(rel, target)
            .build();
    }

    @Test
    @DisplayName("D25: two experiences using one pattern count TWICE — it adds, it does not replace")
    void aSecondUseAddsRatherThanReplacing() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.put(entryApplying("the builder kept the half-built object unreachable",
                "applied", PATTERN));
            store.put(entryApplying("a second place where the same shape fitted",
                "applied", PATTERN));
            // THE DISCRIMINATOR for the relation filter: same target, different
            // relation. An implementation that counted every link to a pattern
            // would read three here and could not be told apart on a fixture
            // where every link happens to be an applied one.
            store.put(entryApplying("this one only mentions it", "related", PATTERN));

            assertAll(
                () -> assertEquals(2, store.appliedSampleCount(PATTERN),
                    "two experiences applied it, so the count is two — ONE would mean the rows "
                        + "were collapsed, which is the ruling this deliverable exists to carry "
                        + "out ('it adds as a sample, you can have 1..n here'), and THREE would "
                        + "mean the relation was not filtered and a mere mention counted"),
                () -> assertEquals(0, store.appliedSampleCount(OTHER_PATTERN),
                    "and a pattern nobody reached for is zero rather than an error — most of "
                        + "the catalogue is in that state and it is not a verdict on the pattern"),
                () -> assertEquals(0, store.appliedSampleCount(null),
                    "no pattern named is zero too, not a thrown query"));
        }
    }

    @Test
    @DisplayName("D25: `applied` is IN the closed vocabulary — the gate would otherwise refuse it")
    void theRelationIsPartOfTheClosedVocabulary() {
        // The vocabulary is closed and checked at the one door an author writes
        // through, so a rel that is not in this set is refused with the allowed
        // list quoted. Before this stage the set held eight and `applied` was
        // not among them: the relation could not be written at all, which is
        // why D25 is a deliverable rather than a convention.
        assertAll(
            () -> assertTrue(EntryForm.LINK_RELS.contains("applied"),
                "got: " + EntryForm.LINK_RELS),
            () -> assertTrue(EntryForm.linkVocabulary().contains("applied"),
                "and the rendered vocabulary a refusal quotes must name it too, or an author "
                    + "told 'applied is not one this store records' has no way to learn that it "
                    + "is; got: " + EntryForm.linkVocabulary()));
    }

    @Test
    @DisplayName("D25: the link survives the round trip as a row, not as prose in the summary")
    void theLinkIsAStoredRelationRatherThanText() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String id = store.put(entryApplying("used the builder here", "applied", PATTERN));

            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> links =
                (List<java.util.Map<String, Object>>) store.get(id).orElseThrow().get("links");

            assertAll(
                () -> assertEquals(1, links.size(), "got: " + links),
                () -> assertEquals("applied", links.get(0).get("rel"), "got: " + links),
                () -> assertEquals(PATTERN, links.get(0).get("target"), "got: " + links),
                () -> assertEquals(1, store.appliedSampleCount(PATTERN),
                    "and the count reads the same row the round trip returned, so the two "
                        + "cannot disagree about whether the relation is there"));
        }
    }
}
