package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.mcp.knowledge.EmbeddingIndex;
import org.jawata.mcp.knowledge.EmbeddingService;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.RecoveringExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 27 D5 — the write-path dedup, on the fixtures the plan names: the
 * byte-identical "jawata-fallback slip" cluster and the re-phrased-knowledge
 * pairs the C0 corpus scan found.
 *
 * <p>The contract has three halves and all three are failures if broken:
 * a re-phrasing is FLAGGED, genuinely-new knowledge is admitted UNFLAGGED, and
 * the entry lands either way — a dedup that can lose knowledge is worse than
 * the duplicate it prevents.</p>
 */
class WritePathDedupTest {

    /** The Stage-6 hand-labeled pair set, committed alongside the C0 goldens. */
    private static final String LABELS = "/test-resources/embed-goldens/dedup-labels.json";

    private ObjectMapper mapper;
    private ExperienceStore store;
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

    private ObjectNode record(String summary, String details) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", summary);
        if (details != null) {
            a.put("details", details);
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), "record must succeed even when it flags a duplicate");
        return (Map<String, Object>) r.getData();
    }

    /**
     * True when the bundled embedder loaded. A silently-skipped test counts as
     * a pass, which is how a green suite comes to prove nothing — so a skip
     * SAYS so.
     */
    private boolean embedderAvailable() {
        boolean up = EmbeddingService.shared().available();
        if (!up) {
            System.out.println("WritePathDedupTest: NOT RUN — no embedder available "
                + "(the dedup assertions below assert nothing in this run)");
        }
        return up;
    }

    // ---- the fixtures: real text shapes from the C0 corpus scan ----------

    private static final String SLIP_A =
        "jawata-fallback slip: Bash: locating method bodies for reading";
    private static final String SLIP_B =
        "jawata-fallback slip: Bash: locating method bodies for reading (cwd was lost)";

    /** A genuinely different lesson — no shared subject with the slip cluster. */
    private static final String NEW_LESSON =
        "The release workflow must verify /releases/latest advances on BOTH products "
        + "before the fleet is flipped; a tag that builds is not a release that shipped.";

    @Test
    void a_byte_identical_re_record_is_flagged_as_a_duplicate() {
        if (!embedderAvailable()) {
            return;                        // C3's degrade path has its own test
        }
        Map<String, Object> first = data(tool.execute(record(SLIP_A, null)));
        String firstId = (String) first.get("id");
        assertNull(first.get("duplicate_of"),
            "the first record of a thing has nothing to duplicate");

        Map<String, Object> second = data(tool.execute(record(SLIP_A, null)));
        assertEquals(firstId, second.get("duplicate_of"),
            "the same text recorded twice must name the entry it duplicates");
        assertEquals(Boolean.TRUE, second.get("stored"),
            "STORED REGARDLESS — the flag is a proposal, never a rejection");
        assertNotNull(second.get("duplicate_note"));
        assertTrue(second.get("duplicate_note").toString().contains("nothing was lost"));
        assertEquals(2, store.count(), "both rows are in the store");
    }

    @Test
    void a_re_phrasing_of_the_same_knowledge_is_flagged() {
        if (!embedderAvailable()) {
            return;
        }
        String firstId = (String) data(tool.execute(record(SLIP_A, null))).get("id");
        Map<String, Object> second = data(tool.execute(record(SLIP_B, null)));
        assertEquals(firstId, second.get("duplicate_of"),
            "a parenthetical added to the same reason is the same knowledge "
            + "(hand-labeled duplicate, C0 pair #0 at 0.9282)");
    }

    @Test
    void genuinely_new_knowledge_is_admitted_unflagged() {
        if (!embedderAvailable()) {
            return;
        }
        data(tool.execute(record(SLIP_A, null)));
        Map<String, Object> fresh = data(tool.execute(record(NEW_LESSON, null)));
        assertNull(fresh.get("duplicate_of"),
            "an unrelated lesson must arrive unflagged — a dedup that fires on "
            + "everything teaches the human to ignore it");
        assertNull(fresh.get("dedup_check"),
            "and the check itself must have RUN (a failure would say so here)");
        assertEquals(Boolean.TRUE, fresh.get("stored"));
    }

    @Test
    void near_but_distinct_knowledge_is_not_flagged() {
        if (!embedderAvailable()) {
            return;
        }
        // The hand-labeled WALL: the highest-scoring pair that is NOT a
        // duplicate (0.8970 — two different sprints of the same project). If
        // the threshold ever slides down to catch more, this is the first
        // thing it wrongly catches.
        data(tool.execute(record("ORB Strategy — Sprint 5 (CLOSED 2026-04-13)", null)));
        Map<String, Object> other =
            data(tool.execute(record("ORB Strategy — Sprint 7", null)));
        assertNull(other.get("duplicate_of"),
            "two different sprints are two different facts, however alike they read");
    }

    /**
     * The 21c ECHO-FIT case, which the plan names as a Stage-6 fixture: a
     * changelog section that QUOTES a canonical fact. 21c recorded it as a
     * retrieval ambiguity — for DEDUP it is a negative, and an important one.
     * A section that cites a fact is not a re-phrasing of it: merging them
     * would delete the section, and dropping the section would lose everything
     * around the quote.
     */
    @Test
    void a_section_that_quotes_a_fact_is_not_a_duplicate_of_it() {
        if (!embedderAvailable()) {
            return;
        }
        String fact = "Never remove a slot until the broker confirms the cancel.";
        data(tool.execute(record(fact, null)));

        Map<String, Object> section = data(tool.execute(record(
            "Sprint 6 changelog — slot lifecycle",
            "Three changes landed this sprint. The slot-identity race is closed by "
            + "the rule \"" + fact + "\" which now guards freeSlot; the flatten path "
            + "reaches dormant slots; and the EXECSIM Top-K drop check no longer "
            + "requires filledQty == 0.")));

        assertNull(section.get("duplicate_of"),
            "a section that CITES a fact must stay its own entry — flagging it "
            + "would propose deleting everything the section says around the quote");
    }

    @Test
    void the_production_wrapper_path_flags_too() {
        if (!embedderAvailable()) {
            return;
        }
        // In the resident the store is WRAPPED (RecoveringExperienceStore), and
        // an `instanceof H2ExperienceStore` against it is FALSE — the C4-F1
        // lesson. This test exists so the hook can never silently stop working
        // in production while every direct-store test stays green.
        RecoveringExperienceStore wrapped = new RecoveringExperienceStore(
            "test: the real open is held down", () -> {
                throw new IllegalStateException("still down");
            }, 3_600_000L);
        try {
            ExperienceTool wrappedTool = new ExperienceTool(() -> null, wrapped);
            String firstId = (String) data(wrappedTool.execute(record(SLIP_A, null))).get("id");
            Map<String, Object> second = data(wrappedTool.execute(record(SLIP_A, null)));
            assertEquals(firstId, second.get("duplicate_of"),
                "the dedup must reach through the recovery wrapper, not past it");
        } finally {
            wrapped.close();
        }
    }

    // ---- the derivation itself, pinned ----------------------------------

    /**
     * The threshold is not a preference — it is a measurement, and this test
     * fails if the number and the evidence ever drift apart.
     */
    @Test
    void the_committed_hand_labels_support_the_threshold() throws Exception {
        String json;
        try (java.io.InputStream in = WritePathDedupTest.class
                .getResourceAsStream(LABELS)) {
            assertNotNull(in, "the hand-labeled pair set must ship with the tests: "
                + LABELS);
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var root = new ObjectMapper().readTree(json);
        var pairs = root.get("pairs");
        assertTrue(pairs.size() >= 30,
            "the plan requires at least 30 hand-labeled pairs; found " + pairs.size());

        int proposed = 0;
        int wrong = 0;
        int duplicatesInSet = 0;
        double highestDistinct = 0.0;
        for (var p : pairs) {
            boolean dup = "duplicate".equals(p.get("label").asText());
            double score = p.get("score").asDouble();
            if (dup) {
                duplicatesInSet++;
            } else {
                highestDistinct = Math.max(highestDistinct, score);
            }
            if (score >= EmbeddingIndex.DEDUP_THRESHOLD) {
                proposed++;
                if (!dup) {
                    wrong++;
                }
            }
        }
        assertTrue(proposed > 0, "a threshold that proposes nothing is not calibrated");
        assertEquals(0, wrong,
            "every pair at or above the threshold must be a hand-labeled duplicate");
        assertTrue(highestDistinct < EmbeddingIndex.DEDUP_THRESHOLD,
            "the threshold must sit ABOVE the highest-scoring non-duplicate ("
            + highestDistinct + ")");
        // Both classes present, or the labels prove nothing about a threshold.
        assertTrue(duplicatesInSet > 0 && duplicatesInSet < pairs.size(),
            "the labeled set must contain both duplicates and non-duplicates");
    }

    @Test
    void the_flag_never_claims_a_number() {
        if (!embedderAvailable()) {
            return;
        }
        data(tool.execute(record(SLIP_A, null)));
        Map<String, Object> second = data(tool.execute(record(SLIP_A, null)));
        String note = String.valueOf(second.get("duplicate_note"));
        assertFalse(note.matches(".*0\\.\\d+.*"),
            "no similarity score in any rendering — the ontology's standing rule");
    }
}
