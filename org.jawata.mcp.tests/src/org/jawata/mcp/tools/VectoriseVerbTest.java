package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.knowledge.EmbeddingService;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28f D4 — the vectoriser is DRIVABLE TO COMPLETION BY A VERB, and says what it did.
 *
 * <p><b>Why a fourth door exists at all,</b> given that {@code load}, {@code import} and the
 * catalogue seed now drain before they return: those cover stores going forward. This verb is
 * for a store that is ALREADY behind — after a restore, after an interrupted run, after an
 * older build wrote rows with no vector. Nothing else can reach that state.</p>
 *
 * <p><b>The two cases are complementary on purpose, so exactly one runs and says which.</b>
 * Whether an embedder is installed is a property of the machine, not of the test; a single
 * case would either skip silently on half the machines or assert the wrong branch. The
 * degradation case is the one that runs where there is no embedder, and it is the more
 * important of the two: an answer of {@code remaining: 0} where nothing could be counted
 * would report the work finished, which is the exact shape this stage was opened to
 * remove.</p>
 */
class VectoriseVerbTest {

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String kind) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> kind + " failed: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> embeddingBlock() {
        Map<String, Object> block = (Map<String, Object>) call("stats").get("embedding");
        assertNotNull(block, "stats must carry the embedding block");
        return block;
    }

    /**
     * Rows that exist and cannot yet be found by meaning.
     *
     * <p>Written through the STORE rather than the import verb, and the distinction is this
     * same stage's work: the verb now indexes before it answers, so it cannot leave the
     * window this test needs. {@code importEntries} writes rows and embeds nothing, which is
     * exactly what a restore does — the state the verb exists for.</p>
     */
    private void seedUnembedded(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            rows.add(Map.of(
                "id", "5e6f7a8b-0000-4000-8000-00000000000" + i,
                "type", "lesson",
                "summary", "a restored lesson number " + i + " about draining a backlog",
                "status", "accepted"));
        }
        ((H2ExperienceStore) store).importEntries(rows);
    }

    @Test
    void the_verb_drives_the_remainder_to_zero_and_says_so() {
        Assumptions.assumeTrue(EmbeddingService.shared().available(),
            "no embedder on this machine — convergence cannot be asserted, and the"
                + " degradation case below is what runs instead");

        seedUnembedded(3);

        // PROOF OF LIFE. Without it, "converged" below is equally true of a store that had
        // nothing to do, and the verb would be asserted by a tautology.
        Object pendingBefore = embeddingBlock().get("unembedded");
        assertEquals(3L, ((Number) pendingBefore).longValue(),
            () -> "three restored rows must be waiting for a vector, or the verb converges"
                + " nothing and this test measures nothing: " + pendingBefore);

        Map<String, Object> done = call("vectorise");
        assertEquals(Boolean.TRUE, done.get("converged"),
            () -> "the verb's whole contract is that it runs the backlog to ZERO: " + done);
        assertEquals(0L, ((Number) done.get("remaining")).longValue(),
            () -> "and reports the remainder it reached: " + done);
        assertTrue(((Number) done.get("embedded")).intValue() >= 3,
            () -> "it must account for the rows it actually vectorised: " + done);
        assertEquals(3L, ((Number) done.get("before")).longValue(),
            () -> "and for the backlog it started from, so a caller can tell a long run"
                + " from a no-op: " + done);

        // AND THE INDEPENDENT INSTRUMENT AGREES. The verb reporting its own success is one
        // claim; stats reading the same store afterwards is another, and a verb that
        // returned a hopeful number without writing anything would satisfy only the first.
        assertEquals(0L, ((Number) embeddingBlock().get("unembedded")).longValue(),
            "stats must see the same zero the verb claimed");
    }

    @Test
    void with_no_embedder_the_remainder_is_unknown_rather_than_zero() {
        Assumptions.assumeFalse(EmbeddingService.shared().available(),
            "an embedder IS installed here — the convergence case above is what runs, and"
                + " this degradation branch is unreachable on this machine");

        Map<String, Object> done = call("vectorise");

        // THE CLAIM, and it is about a lie rather than about a number. Nothing can be
        // vectorised and nothing can be counted, and the one answer that must never be
        // given is the one that reads as "finished".
        assertEquals("unknown", done.get("remaining"),
            () -> "a remainder that could not be counted must say so. A 0 here would report"
                + " the backlog cleared on a machine that cannot clear it: " + done);
        assertNotEquals(Boolean.TRUE, done.get("converged"),
            () -> "and nothing converged: " + done);
        assertEquals("unavailable", done.get("embedder"),
            () -> "the reason is named rather than left for the caller to infer: " + done);
        assertNotNull(done.get("note"),
            () -> "and it says what the caller should expect instead — recall runs on the"
                + " keyword path alone until an embedder is present: " + done);
    }
}
