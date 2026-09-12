package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f D4 — the steering ledger reports what it OBSERVED apart from what it DERIVED.
 *
 * <p><b>The fact that could not be expressed.</b> {@code usage_query.chosen} is one BOOLEAN
 * carrying a THREE-valued fact: {@code nominated} opens the row FALSE, and {@code decided}
 * sets {@code !ids.isEmpty()} — also FALSE when the caller judged the candidates and kept
 * none. So <i>never answered</i> and <i>answered with none</i> were the same value, and
 * nothing downstream could tell them apart.</p>
 *
 * <p><b>Why that is not a nicety.</b> The review seat deletes rows from {@code deletionList},
 * which ranks entries shown-often-and-chosen-never. Over nominations a human dispositioned
 * that ranking is a judgement. Over nominations nobody ever answered the rows look identical
 * and mean nothing — so presenting the second as the first tells a reader nobody wanted a row
 * when nobody was consulted about it. {@code decided_at} (schema v16) records that a
 * disposition ARRIVED, separately from what it held.</p>
 *
 * <p><b>THE ASSERTION THAT CANNOT PASS ON THE OLD COLUMN, which is what makes this test
 * discriminate rather than describe.</b> The case below leaves BOTH queries at
 * {@code chosen = FALSE} — one answered with none, one never answered — and demands
 * {@code observed == 1}. Anything reading {@code chosen} alone must answer 0 or 2 for that
 * pair; 1 is unreachable. And the test does not merely assert that: it MEASURES it, by
 * reading the writing backlog in the same breath, which selects on {@code chosen = FALSE} and
 * duly returns both. Two instruments over one pair, disagreeing exactly where they should.</p>
 *
 * <p><b>Driven through the tool's real verbs, never the ledger directly</b> — the discipline
 * its sibling {@code UsageLedgerTest} states in its own words, because a test that supplies
 * its own wiring proves the class works and says nothing about whether anything calls it. D4
 * asks for the figure <i>"at the point of display"</i>, so the point of display is what is
 * read here: the {@code review_sweep} response the seat consumes.</p>
 */
class LedgerObservedVsDerivedTest {

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String kind, String... kv) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i].startsWith("min_") || kv[i].equals("limit")) {
                a.put(kv[i], Integer.parseInt(kv[i + 1]));
            } else {
                a.put(kv[i], kv[i + 1]);
            }
        }
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> kind + " failed: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> conformance() {
        Map<String, Object> sweep = call("review_sweep", "min_times", "1", "min_shown", "1");
        Map<String, Object> c = (Map<String, Object>) sweep.get("conformance");
        assertNotNull(c,
            () -> "the sweep must carry a `conformance` block — D4 asks for the figure AT THE"
                + " POINT OF DISPLAY, and this response is that point. Keys present: "
                + sweep.keySet());
        return c;
    }

    /**
     * The backlog rows, flattened across surfaces.
     *
     * <p>Sprint 28f Stage 5 grouped the backlog by the surface that asked and dropped the
     * combined list. This class's subject is the CONFORMANCE figure — observed against
     * derived — which is a property of the whole ledger and not of any one surface, so it
     * flattens deliberately rather than picking a group. {@code SweepPerTriggerTest} is
     * what asserts the grouping itself.</p>
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> backlog() {
        Map<String, Object> sweep = call("review_sweep", "min_times", "1", "min_shown", "1");
        Map<String, List<Map<String, Object>>> byTrigger =
            (Map<String, List<Map<String, Object>>>) sweep.get("writingBacklogByTrigger");
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        if (byTrigger != null) {
            byTrigger.values().forEach(all::addAll);
        }
        return all;
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        assertNotNull(v, () -> "the conformance block has no `" + key + "`: " + m);
        return ((Number) v).longValue();
    }

    @Test
    void an_answered_absence_is_observed_and_an_unanswered_question_is_not() {
        String answered = "whether the quokka ledger was ever consulted about the equinox";
        String unanswered = "whether anybody asked the wombat about the solstice at all";

        String q1 = String.valueOf(call("nominate", "question", answered).get("query_id"));
        String q2 = String.valueOf(call("nominate", "question", unanswered).get("query_id"));
        assertNotNull(q1);
        assertNotNull(q2);

        // ONE of them is dispositioned, choosing NOTHING. The other is abandoned —
        // which is the ordinary case, since nothing forces an agent to call decide.
        call("decide", "query_id", q1);

        // THE CONTROL, measured rather than argued: the backlog selects on
        // `chosen = FALSE` and returns BOTH questions, so the old column provably
        // cannot separate this pair. Any figure below that DOES separate them is
        // therefore reading something else.
        List<Map<String, Object>> open = backlog();
        assertEquals(2, open.size(),
            () -> "PROOF THE OLD COLUMN CANNOT TELL THESE APART: both queries must still"
                + " read chosen=FALSE, or the pair below is not the ambiguous case this"
                + " test is about. Backlog was: " + open);

        Map<String, Object> c = conformance();
        assertEquals(2L, num(c, "nominations"),
            () -> "both nominations must be on the ledger at all: " + c);

        // THE CLAIM. Unreachable from `chosen`, which is FALSE for both.
        assertEquals(1L, num(c, "observed"),
            () -> "A DISPOSITION THAT CHOSE NOTHING IS STILL A DISPOSITION. Reading `chosen`"
                + " alone this pair is 0 or 2 and never 1 — so a 1 here is the ledger"
                + " reporting what it WITNESSED rather than what it inferred. Block: " + c);
        assertEquals(1L, num(c, "derived"),
            () -> "and the abandoned question must count DERIVED — its zero chosen-count is"
                + " an absence of observation, not a judgement against anything: " + c);
        assertEquals(50.0, ((Number) c.get("percentObserved")).doubleValue(), 0.001,
            () -> "one of two: " + c);

        // D4's own measure: "not only in a field comment". The sentence rides with the
        // figure, so a caller who never opens the source still gets the caveat.
        String caveat = String.valueOf(c.get("caveat"));
        assertTrue(caveat.contains("OBSERVED") && caveat.contains("DERIVED"),
            () -> "the caveat must name both halves at the point of display: " + caveat);
        assertTrue(caveat.contains("NOT") && caveat.contains("OBSERVED"),
            () -> "and it must say what a zero MEANS, which is the whole point: " + caveat);

        // Answering the second one moves it across — the two counts are a partition of
        // the nominations, not two independent tallies that could both be wrong.
        call("decide", "query_id", q2);
        Map<String, Object> after = conformance();
        assertEquals(2L, num(after, "observed"),
            () -> "the second disposition must move its nomination to observed: " + after);
        assertEquals(0L, num(after, "derived"),
            () -> "and nothing may remain derived once every question was answered: " + after);
        assertEquals(2L, num(after, "nominations"),
            () -> "the total must not move — this is a re-classification, not a new row: "
                + after);
    }

    /**
     * An empty denominator is not a perfect score.
     *
     * <p>Borrowed knowingly from studio's own utilization figure, whose comment says printing
     * 100 % over nothing observed <i>"would be the exact lie this sprint exists to end"</i>.
     * A ledger nobody has used yet must not report perfect conformance.</p>
     */
    @Test
    void an_unused_ledger_reports_no_percentage_rather_than_a_perfect_one() {
        Map<String, Object> c = conformance();
        assertEquals(0L, num(c, "nominations"), () -> "a fresh store has no nominations: " + c);
        assertEquals(0L, num(c, "observed"), () -> "" + c);
        assertEquals(0L, num(c, "derived"), () -> "" + c);
        assertNull(c.get("percentObserved"),
            () -> "nothing was observed, so there is no share to report — a 100 here would"
                + " read as perfect conformance over an empty ledger: " + c);
    }
}
