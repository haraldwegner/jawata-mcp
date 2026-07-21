package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jawata.mcp.knowledge.EmbeddingIndex;
import org.jawata.mcp.knowledge.EmbeddingService;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.ToolExperienceStore;
import org.junit.jupiter.api.Test;

/**
 * Sprint 27 C5 — the precedent lane's two tiers.
 *
 * <p>The contract under test: the semantic retriever may WIDEN what the agent
 * sees, but identity keeps its monopoly on warning and charging — and a broken
 * embedder degrades to exactly the v3.3.1 keyword result.</p>
 */
class PrecedentTwoTierTest {

    @Test
    void identity_matches_are_exactly_the_situation_contains_target_rule() {
        ToolExperience e = new ToolExperience("s1", "extract com.foo.Bar#baz method",
            "extract", ToolExperience.OUTCOME_REVERTED, "{}");
        assertTrue(IdentityMatch.matches(e, "com.foo.Bar#baz"));
        assertFalse(IdentityMatch.matches(e, "com.other.Thing"),
            "a different target is similar at best, never identical");
        assertFalse(IdentityMatch.matches(e, ""), "a blank target matches nothing");
        assertFalse(IdentityMatch.matches(null, "x"));
    }

    @Test
    void the_semantic_retriever_widens_but_keyword_hits_come_first() {
        EmbeddingService svc = EmbeddingService.shared();
        if (!svc.available()) {
            return;
        }
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            ToolExperienceStore lane = new ToolExperienceStore(store);
            lane.append(new ToolExperience("s1", "extract com.foo.Bar#baz long method",
                "extract", ToolExperience.OUTCOME_REVERTED, "{}"));
            lane.append(new ToolExperience("s1",
                "extract pulling a block out of a big routine broke the behaviour",
                "extract", ToolExperience.OUTCOME_REVERTED, "{}"));

            EmbeddingPrecedentRetriever r = new EmbeddingPrecedentRetriever(
                lane, new EmbeddingIndex(store, svc));
            List<ToolExperience> hits = r.retrieve("com.foo.Bar#baz", 20);
            assertFalse(hits.isEmpty());
            assertTrue(IdentityMatch.matches(hits.get(0), "com.foo.Bar#baz"),
                "the identity hit leads — v3.3.1's result, first and intact");
        } finally {
            store.close();
        }
    }

    @Test
    void a_broken_embedder_degrades_to_exactly_the_keyword_result() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            ToolExperienceStore lane = new ToolExperienceStore(store);
            lane.append(new ToolExperience("s1", "rename_symbol com.foo.Bar",
                "rename_symbol", ToolExperience.OUTCOME_COMPILED, "{}"));

            // index == null IS the broken-embedder shape the retriever handles.
            EmbeddingPrecedentRetriever degraded =
                new EmbeddingPrecedentRetriever(lane, null);
            KeywordPrecedentRetriever baseline = new KeywordPrecedentRetriever(lane);

            List<ToolExperience> a = degraded.retrieve("com.foo.Bar", 10);
            List<ToolExperience> b = baseline.retrieve("com.foo.Bar", 10);
            assertEquals(b.size(), a.size(), "degrade = the v3.3.1 baseline, exactly");
            for (int i = 0; i < a.size(); i++) {
                assertEquals(b.get(i).situation(), a.get(i).situation());
                assertEquals(b.get(i).tool(), a.get(i).tool());
            }
            assertTrue(degraded.retrieve("", 10).isEmpty());
        } finally {
            store.close();
        }
    }
}
