package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.mcp.knowledge.EmbeddingIndex;
import org.jawata.mcp.knowledge.EmbeddingService;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 27a Stage 6 (D5's first half) — embedding coverage is VISIBLE through
 * the tool path: {@code experience(kind=stats)} shows n-of-total while rows
 * are unembedded and total-of-total once the backfill has converged. Driven
 * through {@code tool.execute}, not a helper — the wired path or nothing.
 */
class StatsEmbeddingCoverageTest {

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

    // C6 audit F1: ABORT, not return — a returned "pass" that asserted nothing
    // is the hollow-green shape; a skip must report as SKIPPED (the
    // BackfillConvergenceTest convention).

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "stats");
        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess());
        return (Map<String, Object>) resp.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entryLane(Map<String, Object> stats) {
        Object embedding = stats.get("embedding");
        assertNotNull(embedding, "stats carries the embedding block: " + stats);
        return (Map<String, Object>) ((Map<String, Object>) embedding).get("experience_entry");
    }

    /** The two states the plan's C6 exit names, driven through the tool. */
    @Test
    void stats_shows_n_of_total_during_backfill_and_total_of_total_after() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            EmbeddingService.shared().available(),
            "no embedder available — the coverage assertions cannot run");
        // Seeded through the STORE rather than the tool's import verb, and the
        // difference is Sprint 28f E5.
        //
        // This drove `kind=import` under a comment reading "a live record embeds on
        // write, but a restored backup does not — the backfill owns its coverage."
        // E5 removed that premise: the import verb now indexes the rows it wrote
        // before it answers, precisely so a caller is never handed rows it can list
        // and cannot find. So the tool path no longer leaves an n-of-total window,
        // and this test failed with "3/3" — the change working, seen from the one
        // test that depended on the old behaviour.
        //
        // The window is still a REAL state, which is why the test keeps its subject:
        // a restore, or an embedder identity bump, leaves rows the backfill owns.
        // The store's own importEntries is that state's honest source — it writes
        // rows and embeds nothing, which is what a restore does. Whether the VERB
        // embeds on write is a different claim and earns its own test; asserting
        // both here would make one test answer two questions.
        int n = 1;
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (String s : new String[] {
                "the first seeded lesson about queue retries",
                "the second seeded lesson about socket timeouts",
                "the third seeded lesson about cache warmup"}) {
            rows.add(java.util.Map.of(
                "id", "0a1b2c3d-0000-4000-8000-00000000000" + n++,
                "type", "lesson",
                "summary", s,
                "status", "accepted"));
        }
        ((H2ExperienceStore) store).importEntries(rows);

        Map<String, Object> before = entryLane(stats());
        long embeddedBefore = ((Number) before.get("embedded")).longValue();
        long totalBefore = ((Number) before.get("total")).longValue();
        assertEquals(3L, totalBefore, "three rows exist");
        assertTrue(embeddedBefore < totalBefore,
            "during the backfill window the stats show n of total, honestly: "
            + embeddedBefore + "/" + totalBefore);

        // Converge the backfill (the Stage-3b loop's unit), then re-read.
        EmbeddingIndex index = new EmbeddingIndex(
            (H2ExperienceStore) store, EmbeddingService.shared());
        while (index.backfill(1000) > 0) {
            // each pass persists rows; loop until the delta closes
        }
        assertEquals(0L, index.remainingUnembedded(), "backfill converged");

        Map<String, Object> after = entryLane(stats());
        assertEquals(((Number) after.get("total")).longValue(),
            ((Number) after.get("embedded")).longValue(),
            "after convergence the stats show total of total: " + after);
    }
}
