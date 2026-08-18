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

        long start = System.currentTimeMillis();
        Map<String, Object> result = retrieval.recall(
            new RecallQuery("com.example.Anything", null, null, null, null),
            org.jawata.mcp.knowledge.QualityLedger.SURFACE_QUESTION_HOOK, 200);
        long waited = System.currentTimeMillis() - start;

        assertEquals(ExperienceRetrieval.RESULT_UNAVAILABLE, result.get("result"),
            "a stall must be reported as unavailable, never as an absence");
        assertTrue(waited < 5_000,
            "the caller must be released on the budget, not on the read; waited " + waited + "ms");
        assertTrue(String.valueOf(result.get("reason")).contains("200"),
            "and the reason names the budget it exceeded: " + result.get("reason"));
    }

    /**
     * THE DEADLINE IS A VALUE, NOT STATE — the structural fix, pinned.
     *
     * <p>Two earlier shapes of this test were false greens. The first asserted the
     * constant's range (a tautology over a literal). The second asserted a fresh
     * instance's field — which existed only because the budget was mutable instance
     * state, and that state was the defect: the first hook request's 1200 ms silently
     * became every later caller's budget, including the studio canary's in another
     * process. Now there is no field to assert. What is asserted instead is the
     * BEHAVIOUR: one caller's budget must not leak into the next call on the SAME
     * instance.</p>
     */
    @Test
    void one_callers_budget_does_not_leak_into_the_next_call() {
        // A store that stalls 800 ms then answers normally. Call 1 brings a 200 ms
        // budget → unavailable. Call 2 states no budget → the 15 s default applies,
        // the 800 ms read completes, and the answer is a normal absence. On the old
        // sticky-field shape, call 2 inherited call 1's 200 ms and reported a
        // manufactured outage — this test fails on that code.
        ExperienceRetrieval retrieval = ExperienceRetrieval.keywordOnly(
            new UnreadableStore(800, true), () -> null);
        RecallQuery q = new RecallQuery("com.example.Anything", null, null, null, null);
        String surface = org.jawata.mcp.knowledge.QualityLedger.SURFACE_QUESTION_HOOK;

        Map<String, Object> first = retrieval.recall(q, surface, 200);
        assertEquals(ExperienceRetrieval.RESULT_UNAVAILABLE, first.get("result"),
            "call 1: an 800ms read against a 200ms budget is unavailable");

        Map<String, Object> second = retrieval.recall(q, surface);
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, second.get("result"),
            "call 2 stated NO budget and must get the default — if this reads unavailable,"
                + " call 1's budget leaked into it, which is the sticky-state defect");
    }

    /**
     * A caller may buy a FASTER answer, never a longer wait.
     *
     * <p>The hook's 1500 ms HTTP deadline is why the parameter exists at all; the clamp
     * is why it cannot be turned into the unbounded wait #37 was filed about. Pure
     * function, no instance, no state — deliberately.
     */
    @Test
    void the_budget_clamps_in_both_directions() {
        assertEquals(1_200, ExperienceRetrieval.clampBudget(1_200), "a caller may ask for less");
        assertEquals(ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS,
            ExperienceRetrieval.clampBudget(Long.MAX_VALUE),
            "and may NOT ask for more — that is the state the issue was filed about");
        assertEquals(ExperienceRetrieval.MIN_RETRIEVAL_BUDGET_MILLIS,
            ExperienceRetrieval.clampBudget(0),
            "nor for a budget so small that a healthy read is reported as an outage");
    }

    /**
     * A STRAGGLER THAT SUCCEEDS must not overwrite the caller's verdict.
     *
     * <p>The first shape of this test used a fixture that always THROWS — and a throwing
     * task never writes a verdict at all, so the race it claimed to pin was unreachable
     * through it: reverting the fix left it green. This fixture stalls past the budget
     * and then completes NORMALLY, which is the only completion that can race the
     * caller's {@code result=unavailable} write.</p>
     */
    @Test
    void a_straggler_that_completes_normally_cannot_overwrite_the_verdict() {
        ExperienceRetrieval retrieval = ExperienceRetrieval.keywordOnly(
            new UnreadableStore(1_000, true), () -> null);

        Map<String, Object> result = retrieval.recall(
            new RecallQuery("com.example.Anything", null, null, null, null),
            org.jawata.mcp.knowledge.QualityLedger.SURFACE_QUESTION_HOOK, 200);
        assertEquals(ExperienceRetrieval.RESULT_UNAVAILABLE, result.get("result"));

        // Let the straggler COMPLETE — normally, with a verdict of its own to write.
        // Its full path stalls TWICE (query, then the analogies pass' all()), so the
        // wait must cover both; a window shorter than the straggler's own run would
        // assert before the race it exists to pin could even happen.
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(ExperienceRetrieval.RESULT_UNAVAILABLE, result.get("result"),
            "a late NORMAL completion must not turn the outage back into an answer");
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
