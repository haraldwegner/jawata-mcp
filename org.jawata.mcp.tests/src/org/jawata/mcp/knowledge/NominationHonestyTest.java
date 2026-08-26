package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT THE NOMINATION DOES NOT KNOW MUST REACH THE CALLER.
 *
 * <h2>The two things it was hiding</h2>
 *
 * <p><b>Coverage.</b> {@code meaning_lanes: "ok"} says the lane scan did not
 * fail. It has never said how much of the store the lanes could actually see,
 * and during the start-up backfill that is most of it. An entry with no vector
 * scores 0 on all three meaning lanes — the same number as an entry that means
 * the OPPOSITE — so it is ranked down for a reason that has nothing to do with
 * its fit.</p>
 *
 * <p>Measured on the live store, v3.14.0: the catalogue's Observer entry, whose
 * situation is <em>"when several objects must react to a subject's change
 * without the subject knowing them"</em>, was asked almost exactly that and came
 * back at rank 3 with a PERFECT word score of 1.0 and zeros on all three meaning
 * lanes. Asked again minutes later — same question — it had fallen to rank 6,
 * because three neighbours had been embedded in between and it had not. Nothing
 * in either response said so.</p>
 *
 * <p><b>Unsituated candidates.</b> The message tells the reader to "read each
 * situation and decide which apply". In one live run 6 of 8 candidates had no
 * situation at all — the substrate-derived rows largely predate the form — so
 * the instruction was unfollowable for three quarters of what was returned.</p>
 *
 * <h2>What is deliberately NOT changed here</h2>
 *
 * <p>{@link RelevanceMerge} does not renormalise a missing lane, and that is its
 * own stated ruling: under-declaring is meant to cost something. These tests do
 * not touch the arithmetic. They make the caller able to tell a zero that is
 * that penalty from a zero that is merely a queue.</p>
 */
class NominationHonestyTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /**
     * Record with the embedder switched OFF, so the row lands with NULL vector
     * columns — the state every entry is in until the backfill reaches it.
     *
     * <p>Two earlier attempts at this fixture failed, and both failures were
     * informative: the two-argument {@code ExperienceRetrieval} constructor
     * builds an index rather than omitting one, and {@code EmbeddingIndex.forStore}
     * reads the store LIVE rather than snapshotting it. So neither "no index" nor
     * "an index built first" can produce an unembedded row — the vector has to be
     * genuinely absent from the database, which is what this does.</p>
     */
    private void recordUnembedded(Runnable write) {
        String previous = System.getProperty(EmbeddingService.DISABLE_PROPERTY);
        System.setProperty(EmbeddingService.DISABLE_PROPERTY, "true");
        EmbeddingService.resetForTests();
        try {
            write.run();
        } finally {
            if (previous == null) {
                System.clearProperty(EmbeddingService.DISABLE_PROPERTY);
            } else {
                System.setProperty(EmbeddingService.DISABLE_PROPERTY, previous);
            }
            EmbeddingService.resetForTests();
        }
    }

    /** A form-1 experience: it declares when it applies. */
    private void recordWithSituation() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", "the pelican ledger reconciles twice before it settles");
        a.put("situation", "when a pelican ledger is reconciled while a settlement is open");
        a.put("verdict", "worked");
        assertTrue(tool.execute(a).isSuccess(), "the situated record must land");
    }

    /** A reference row: it owes no situation, and declaring none is legitimate. */
    private void recordWithoutSituation() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "reference");
        a.put("summary", "the pelican ledger settlement window is documented upstream");
        assertTrue(tool.execute(a).isSuccess(), "the unsituated record must land");
    }

    /** Nominate through a live index over the current store — the production path. */
    private Map<String, Object> nominate(String question) {
        ExperienceRetrieval retrieval =
            new ExperienceRetrieval(store, () -> null, EmbeddingIndex.forStore(store));
        return retrieval.nominate(question, ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> candidatesOf(Map<String, Object> nomination) {
        return (List<Map<String, Object>>) nomination.get("candidates");
    }

    /**
     * THE COVERAGE SIGNAL. The count is reported as a structured fact AND said in
     * the message, because the message is the channel an agent actually reads.
     */
    @Test
    void a_ranking_over_unembedded_entries_says_how_much_it_could_see() {
        // ONE of the two lands with no vector — the genuinely partial case, which
        // is stronger than a store where nothing is embedded: a total blackout
        // could be reported by a check that merely noticed the index was empty.
        recordUnembedded(this::recordWithSituation);
        recordWithoutSituation();

        Map<String, Object> n = nominate(
            "what happens when a pelican ledger is reconciled during settlement");

        assertTrue(!candidatesOf(n).isEmpty(), () -> "precondition: the word lane found rows: " + n);

        Map<?, ?> coverage = (Map<?, ?>) n.get("meaning_coverage");
        assertNotNull(coverage, "the nomination must report meaning-lane coverage");
        assertEquals(2, coverage.get("entries"),
            () -> "the denominator is the live entries it ranked over: " + coverage);
        assertEquals(1, coverage.get("embedded"),
            () -> "one row was written with the embedder off, so exactly one carries a"
                + " vector — reporting full coverage is the claim that hid the defect: "
                + coverage);

        String message = String.valueOf(n.get("message"));
        assertTrue(message.contains("PARTIAL"),
            () -> "an agent reads the MESSAGE, not the map — it must carry the shortfall: " + message);
        assertTrue(message.contains("1 of 2"),
            () -> "and it must carry the NUMBERS, not just the word 'partial': " + message);
    }

    /**
     * Full coverage must stay quiet. A warning that fires on every call is a
     * warning nobody reads, and it would make the partial case invisible again —
     * by noise instead of by silence.
     */
    @Test
    void a_fully_covered_ranking_adds_no_partial_warning() {
        recordWithSituation();
        recordWithoutSituation();

        EmbeddingIndex index = EmbeddingIndex.forStore(store);
        ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null, index);
        Map<String, Object> n = retrieval.nominate(
            "what happens when a pelican ledger is reconciled during settlement",
            ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS);

        Map<?, ?> coverage = (Map<?, ?>) n.get("meaning_coverage");
        assertNotNull(coverage, "coverage is reported either way");
        // Guarded rather than asserted blind: if this build has no embedder the
        // index covers nothing, and demanding silence would fail for a reason that
        // is not this test's subject. The assertion runs when there IS coverage.
        if (coverage.get("embedded").equals(coverage.get("entries"))) {
            assertTrue(!String.valueOf(n.get("message")).contains("PARTIAL"),
                () -> "full coverage must not warn: " + n.get("message"));
        }
    }

    /**
     * THE UNFOLLOWABLE INSTRUCTION. "Read each situation" is addressed to rows
     * that have one; the message must say how many do not and what to do with
     * them instead.
     */
    @Test
    void candidates_that_declare_no_situation_are_counted_in_the_message() {
        recordWithSituation();
        recordWithoutSituation();

        Map<String, Object> n = nominate(
            "what happens when a pelican ledger is reconciled during settlement");

        long unsituated = candidatesOf(n).stream().filter(c -> c.get("situation") == null).count();
        assertEquals(1, unsituated,
            () -> "precondition: exactly one candidate declares no situation: " + candidatesOf(n));

        String message = String.valueOf(n.get("message"));
        assertTrue(message.contains("1 of these declare no situation"),
            () -> "the message tells the reader to judge by situation, so it must name the"
                + " rows that have none: " + message);
        assertTrue(message.contains("judge it by its claim alone or discount it"),
            () -> "and it must say what to do with them, not merely that they exist: " + message);
    }
}
