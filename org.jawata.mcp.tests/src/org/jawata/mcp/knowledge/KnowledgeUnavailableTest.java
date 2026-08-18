package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #37 — <b>"the store said nothing" and "the store could not answer" are different
 * answers, and the difference must be visible in the RESPONSE SHAPE.</b>
 *
 * <p>The measured incident: a store read parked in a socket read for 58 minutes while
 * every other caller queued behind it. Callers saw a bare timeout. The quieter half of
 * the same defect is worse, because it is silent: a store serving from its degraded
 * in-memory fallback answers "No known knowledge for this cue" — indistinguishable, in
 * shape, from a real corpus that genuinely holds nothing.</p>
 *
 * <p>These tests pin the distinction at the two places a consumer can read it: the
 * structured result word, and the tool's error code. They are written as PAIRS — the
 * healthy absence beside the unavailable — because a test that only asserts the new
 * behaviour cannot show that the two are told apart.</p>
 */
class KnowledgeUnavailableTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode recall(String symbol) {
        ObjectNode a = JSON.createObjectNode();
        a.put("kind", "recall");
        a.put("symbol", symbol);
        return a;
    }

    /** A store that is open and cannot be read — see {@link UnreadableStore}. */
    private static ExperienceTool overADeadStore() {
        return new ExperienceTool(() -> null, new UnreadableStore());
    }

    /** A store serving the in-memory fallback, with recovery permanently failing. */
    private static RecoveringExperienceStore degradedStore() {
        return new RecoveringExperienceStore("open failed: disk full",
            () -> {
                throw new IllegalStateException("still down");
            },
            3_600_000);
    }

    @Test
    void a_store_that_cannot_answer_is_not_an_absence() {
        ToolResponse r = overADeadStore().execute(recall("com.example.Anything"));

        assertFalse(r.isSuccess(),
            "a store that could not be read must not answer with a successful absence");
        assertEquals(ExperienceTool.KNOWLEDGE_UNAVAILABLE, r.getError().getCode(),
            "and not INTERNAL_ERROR — the caller's next move is different");
    }

    /**
     * THE PAIR. The same call, once against a healthy empty corpus and once against a
     * store that cannot answer. If these two ever produce the same shape, every consumer
     * downstream is back to guessing — which is the state this whole issue is about.
     */
    @Test
    void an_observed_absence_and_an_unavailable_store_do_not_look_alike() {
        try (H2ExperienceStore healthy = H2ExperienceStore.open(null)) {
            ToolResponse present = new ExperienceTool(() -> null, healthy)
                .execute(recall("com.example.Anything"));
            ToolResponse missing = overADeadStore().execute(recall("com.example.Anything"));

            assertTrue(present.isSuccess(), "an empty corpus answers successfully");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) present.getData();
            assertEquals(ExperienceRetrieval.RESULT_ABSENCE, data.get("result"),
                "the healthy store OBSERVED an absence");

            assertNotEquals(present.isSuccess(), missing.isSuccess(),
                "the two must differ in the response shape, not merely in prose");
        }
    }

    /**
     * The degraded half — and the one that was live today. The fallback answers, so the
     * old code path had nothing to catch: no exception, no timeout, just an empty corpus
     * that was never the real corpus.
     */
    @Test
    void a_degraded_store_reports_unavailable_rather_than_no_knowledge() {
        try (RecoveringExperienceStore store = degradedStore()) {
            ToolResponse r = new ExperienceTool(() -> null, store)
                .execute(recall("com.example.Anything"));

            assertFalse(r.isSuccess(), "the fallback's emptiness is not an observed absence");
            assertEquals(ExperienceTool.KNOWLEDGE_UNAVAILABLE, r.getError().getCode());
            assertTrue(r.getError().getMessage().contains("disk full"),
                "and it carries WHY it is degraded: " + r.getError().getMessage());
        }
    }

    /**
     * THE DISCRIMINATOR for the test above. Without it, "degraded ⇒ unavailable" could
     * be implemented as a blanket refusal, which would throw away answers the fallback
     * genuinely holds — a fix that loses knowledge to report honesty.
     */
    @Test
    void a_degraded_store_still_returns_what_it_actually_has() {
        try (RecoveringExperienceStore store = degradedStore()) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            ObjectNode record = JSON.createObjectNode();
            record.put("kind", "record");
            record.put("type", "lesson");
            record.put("summary", "recorded while the store was degraded");
            record.put("symbol", "com.example.WrittenWhileDegraded");
            tool.execute(record);

            ToolResponse r = tool.execute(recall("com.example.WrittenWhileDegraded"));
            assertTrue(r.isSuccess(), "a real hit is a real hit, degraded or not");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.getData();
            assertEquals(ExperienceRetrieval.RESULT_MATCH, data.get("result"));
        }
    }

    /**
     * The text carrier is the one the hooks use, and rendering runs BEFORE any caller
     * inspects the result — so if the typed answer existed only in the JSON branch, the
     * channel that actually consumes recall would still print an absence.
     */
    @Test
    void the_text_format_cannot_print_an_absence_for_a_store_that_could_not_answer() {
        ObjectNode args = recall("com.example.Anything");
        args.put("format", "text");
        ToolResponse r = overADeadStore().execute(args);

        assertFalse(r.isSuccess(), "format=text must not turn an outage into a line of prose");
        assertEquals(ExperienceTool.KNOWLEDGE_UNAVAILABLE, r.getError().getCode());
    }

    /** The primer runs at session start — starting blind must be said, not implied. */
    @Test
    void a_primer_from_a_degraded_store_is_unavailable_not_an_empty_corpus() {
        try (RecoveringExperienceStore store = degradedStore()) {
            ObjectNode args = JSON.createObjectNode();
            args.put("kind", "primer");
            ToolResponse r = new ExperienceTool(() -> null, store).execute(args);

            assertFalse(r.isSuccess(),
                "an empty primer from a fallback is not 'no domain knowledge loaded'");
            assertEquals(ExperienceTool.KNOWLEDGE_UNAVAILABLE, r.getError().getCode());
        }
    }

    /**
     * THE MEASURED INCIDENT. A read that does not fail and does not return — 3459
     * seconds of it, while every other caller queued behind. Nothing in the store
     * layer can end that read (H2 discards {@code setNetworkTimeout}), so the property
     * under test is the one that IS achievable: the caller comes back, on a deadline,
     * with a typed answer instead of waiting.
     */
    @Test
    void a_read_that_never_returns_releases_the_caller_on_its_budget() {
        ExperienceRetrieval retrieval = ExperienceRetrieval.keywordOnly(
            new UnreadableStore(60_000), () -> null);
        retrieval.setRetrievalBudgetMillis(200);

        long start = System.currentTimeMillis();
        Map<String, Object> result = retrieval.recall(
            new RecallQuery("com.example.Anything", null, null, null, null));
        long waited = System.currentTimeMillis() - start;

        assertEquals(ExperienceRetrieval.RESULT_UNAVAILABLE, result.get("result"),
            "a stall must be reported as unavailable, never as an absence");
        assertTrue(waited < 5_000,
            "the caller must be released on the budget, not on the read; waited " + waited + "ms");
        assertTrue(String.valueOf(result.get("reason")).contains("200"),
            "and the reason names the budget it exceeded: " + result.get("reason"));
    }

    /**
     * The budget is WIRED, not merely declared.
     *
     * <p>The first version of this test asserted {@code RETRIEVAL_BUDGET_MILLIS > 0 &&
     * <= 60_000} — a tautology over a literal, under a javadoc claiming it proved the
     * wiring. Its false-green path was exact: set the instance field to
     * {@code Long.MAX_VALUE} and production is unbounded again — the precise #37 state —
     * while every test still passes, because the stall test sets 200 explicitly and no
     * other test stalls. So this asks a FRESHLY CONSTRUCTED retrieval what budget it is
     * actually carrying.</p>
     */
    @Test
    void a_freshly_built_retrieval_carries_the_shipped_budget() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceRetrieval fresh = ExperienceRetrieval.keywordOnly(store, () -> null);
            assertEquals(ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS,
                fresh.retrievalBudgetMillis(),
                "production builds retrieval without touching any setter — if this is not the"
                    + " constant, the live path is running on some other budget");
        }
        assertTrue(ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS <= 60_000,
            "and the constant itself is a bound, not a synonym for waiting");
    }

    /**
     * A caller may buy a FASTER answer, never a longer wait.
     *
     * <p>The hook's 1500 ms HTTP deadline is why the parameter exists at all; the clamp
     * is why it cannot be turned into the unbounded wait #37 was filed about.
     */
    @Test
    void the_budget_clamps_in_both_directions() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceRetrieval r = ExperienceRetrieval.keywordOnly(store, () -> null);

            r.setRetrievalBudgetMillis(1_200);
            assertEquals(1_200, r.retrievalBudgetMillis(), "a caller may ask for less");

            r.setRetrievalBudgetMillis(Long.MAX_VALUE);
            assertEquals(ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS, r.retrievalBudgetMillis(),
                "and may NOT ask for more — that is the state the issue was filed about");

            r.setRetrievalBudgetMillis(0);
            assertEquals(ExperienceRetrieval.MIN_RETRIEVAL_BUDGET_MILLIS, r.retrievalBudgetMillis(),
                "nor for a budget so small that a healthy read is reported as an outage");
        }
    }

    /**
     * The partial answer must not survive into the unavailable body — and, more
     * importantly, a straggler must not be able to overwrite the verdict.
     *
     * <p>The task now works on its OWN map. Sharing the caller's map was a data race
     * with one specific ugly outcome: a read that finally completes writes
     * {@code result=absence} over {@code result=unavailable}, and the tool — which reads
     * the map after the call returns — emits a successful absence during an outage.
     */
    @Test
    void an_unavailable_body_carries_no_fragment_of_the_answer_it_never_got() {
        ExperienceRetrieval retrieval = ExperienceRetrieval.keywordOnly(
            new UnreadableStore(2_000), () -> null);
        retrieval.setRetrievalBudgetMillis(200);

        Map<String, Object> result = retrieval.recall(
            new RecallQuery("com.example.Anything", null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_UNAVAILABLE, result.get("result"));

        // Give the straggler time to finish and try to write its own verdict.
        try {
            Thread.sleep(2_500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(ExperienceRetrieval.RESULT_UNAVAILABLE, result.get("result"),
            "a late completion must not turn the outage back into an answer");
        assertTrue(result.get("count") == null && result.get("analogies") == null,
            "and no fragment of the answer that never arrived: " + result.keySet());
    }

    /**
     * The rendered text says it too. The tool answers unavailable as an error in both
     * formats, so this path is not what the tool takes — it is here because a second
     * rendering path that quietly lapses is exactly how one honesty rule holds while
     * its twin does not (the lesson that produced {@code renderAnalogyLine}).
     */
    @Test
    void the_text_renderer_never_renders_an_unavailable_as_an_absence() {
        String rendered = ExperienceRetrieval.renderText(
            Map.of("result", ExperienceRetrieval.RESULT_UNAVAILABLE,
                "reason", "the store failed while answering",
                "message", "Knowledge layer UNAVAILABLE — this is NOT an absence: boom."));

        assertTrue(rendered.contains("UNAVAILABLE"), "got: " + rendered);
        assertFalse(rendered.isBlank(),
            "an empty render is what the hook reads as a changed contract, not as an outage");
    }
}
