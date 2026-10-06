package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S13 / D40b — a row's symptoms and links are written under ONE commit,
 * so nothing can read the row in the window where they are missing.
 *
 * <p><b>The state before this was measured, not assumed:</b> the store contained no
 * {@code setAutoCommit} and no {@code commit} at all, so every statement was its own
 * transaction. A rewrite is {@code deleteChildRows}, then the symptom inserts, then
 * the link inserts — three of them — and between the first and the second the row
 * exists with NO symptoms. A recall running then serves it as a row that has none,
 * which is a wrong answer rather than a slow one.</p>
 *
 * <p><b>The monitor does not close that window, and that is the part a reader would
 * get wrong.</b> Every write in the store is {@code synchronized}; the reads
 * deliberately are not — {@code live()} is unsynchronized on purpose, and the comment
 * above {@code stats()} records that synchronizing it put the whole of stats behind
 * the write lock. So a concurrent recall never contends for the monitor and sees
 * exactly the half-written state the monitor looks like it is protecting.</p>
 *
 * <p><b>Why this test forces a failure instead of racing a reader.</b> Observing the
 * gap directly means winning a race against a few microseconds, which makes a test
 * that passes for the wrong reason most of the time. Atomicity has a second, exactly
 * equivalent face that is deterministic: if the children are one unit, a failure part
 * way through must leave NONE of them. That is what is asserted.</p>
 */
class ChildRowsAreWrittenInOneTransactionTest {

    /**
     * {@code experience_link.rel} is {@code VARCHAR(32)}. This is 40 characters, so
     * the link insert fails inside the database rather than at any check of ours —
     * which is what makes it a failure the transaction has to answer for. The store
     * layer does not police the relation vocabulary (measured: its only checker is
     * {@code EntryForm}, the authoring door), so the builder lets this through.
     */
    private static final String REL_TOO_LONG_FOR_ITS_COLUMN =
        "a-relation-name-far-past-the-column-width";

    private static final String SYMPTOM = "the count read two where the store held three";

    private static ExperienceEntry entryWith(String rel) {
        SymbolFact fact = SymbolFact.of("domain_fact",
                "a row whose children are written as one unit or not at all",
                Confidence.MEDIUM)
            .scope(List.of("com.example.tx"), List.of())
            .details("fixture for D40b")
            .build();
        return ExperienceEntry.of(fact)
            .scopeKind("package")
            .symptoms(List.of(SYMPTOM))
            .addLink(rel, "design:step-builder")
            .build();
    }

    @Test
    @DisplayName("D40b: a child write that fails leaves NO children — not the ones before it")
    void aFailedChildWriteRollsBackTheOnesBeforeIt() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            // Control FIRST: the same fixture with a legal relation stores its symptom.
            // Without this, the absence asserted below would hold for a fixture whose
            // symptom never reached the database at all, and the test would pass having
            // measured nothing — which is the failure this store's own record calls the
            // vacuous assertion.
            store.put(entryWith("related"));
            assertEquals(1, recalledBySymptom(store).size(),
                () -> "PRECONDITION: a legal write really does reach the symptom INDEX, or the "
                    + "absence below proves nothing: " + recalledBySymptom(store));

            RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> store.put(entryWith(REL_TOO_LONG_FOR_ITS_COLUMN)),
                "the over-long relation must fail the insert rather than be silently dropped");
            // WHICH failure, not just that one happened. A throw from an unrelated
            // precondition would satisfy assertThrows while the link insert never ran,
            // and the rollback assertion below would then be measuring nothing.
            assertTrue(String.valueOf(thrown.getMessage()).toLowerCase().contains("too long")
                    || String.valueOf(thrown.getMessage()).toLowerCase().contains("rel"),
                () -> "the failure must be the link column rejecting the value: " + thrown);

            // READ THE INDEX, NOT THE ENTRY. An earlier version of this assertion read
            // store.get(id).symptoms and FAILED against correct code: an entry's body
            // JSON carries its own symptoms and is written with the row, so it is there
            // whatever the child tables did. The experience_symptom table is a separate
            // write and is what a cue is matched against — which is precisely why D40b's
            // gap is a RECALL serving a row whose symptoms are missing.
            assertEquals(1, recalledBySymptom(store).size(),
                () -> "only the CONTROL row is findable by this cue. TWO means the failed "
                    + "write's symptom committed before its link failed, which is the gap "
                    + "D40b closes — a row the index can serve with half its children: "
                    + recalledBySymptom(store));
        }
    }

    @Test
    @DisplayName("D40b: the successful path is unchanged — children still land")
    void aSuccessfulWriteStillStoresItsChildren() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String id = store.put(entryWith("applied"));

            assertAll(
                () -> assertEquals(1, recalledBySymptom(store).size(),
                    "the symptom reached the index, so the cue finds the row"),
                () -> assertEquals(1, store.appliedSampleCount("design:step-builder"),
                    "and so is the link — a commit that never happened would lose both, so "
                    + "this is the half that says the boundary COMMITS rather than only "
                    + "rolling back"));
        }
    }

    /**
     * The rows a SYMPTOM CUE finds — which is the index D40b is about, and not the
     * entry's own body. The two disagree exactly in the window this test exists for.
     */
    private static List<StoredEntry> recalledBySymptom(ExperienceStore store) {
        return store.query(new RecallQuery(null, null, null, SYMPTOM, null));
    }
}
