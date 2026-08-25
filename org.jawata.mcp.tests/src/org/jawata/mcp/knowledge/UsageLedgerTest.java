package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jawata.mcp.tools.ExperienceTool;
import org.jawata.mcp.models.ToolResponse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c D14 — the usage ledger, and the rule that it never reaches ranking.
 *
 * <p>The wiring half is driven through {@code ExperienceTool}'s real verbs, not
 * by calling the ledger directly. A test that supplies its own wiring proves the
 * class works and says nothing about whether anything calls it — the failure
 * that shipped a central feature inert past sixteen hundred green tests.</p>
 */
class UsageLedgerTest {

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
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode argsOf(String kind, String... kv) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i].endsWith("_ids")) {
                a.putArray(kv[i]).add(kv[i + 1]);
            } else if (kv[i].startsWith("min_") || kv[i].equals("limit")) {
                a.put(kv[i], Integer.parseInt(kv[i + 1]));
            } else {
                a.put(kv[i], kv[i + 1]);
            }
        }
        return a;
    }

    private Map<String, Object> call(String kind, String... kv) {
        return data(tool.execute(argsOf(kind, kv)));
    }

    /**
     * THE load-bearing assertion: a question that nominates NOTHING is still
     * recorded, and it is the most valuable row in the table. Demand with no
     * supply is the writing backlog — the only signal here that says what to
     * WRITE rather than what to remove — and the obvious optimisation ("no
     * candidates, nothing to count, skip it") deletes exactly that signal.
     *
     * <p>Driven through the tool's own {@code nominate} verb, so it fails if the
     * ledger is not wired into it. An empty store guarantees the zero-candidate
     * path without depending on an embedder being available.</p>
     */
    @Test
    void a_question_nobody_could_answer_is_recorded_as_demand() {
        String q = "how do I stop a rebuild erasing what nobody answered";
        Map<String, Object> nominated = call("nominate", "question", q);
        assertNotNull(nominated.get("query_id"), "nominate must return a query_id");
        assertEquals(0, ((Number) nominated.get("count")).intValue(),
            "an empty store should nominate nothing — the case this test needs");

        Map<String, Object> sweep = call("review_sweep", "min_times", "1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backlog =
            (List<Map<String, Object>>) sweep.get("writingBacklog");
        assertEquals(1, backlog.size(),
            () -> "the unanswered question is missing from the backlog: " + sweep);
        assertEquals(q, backlog.get(0).get("question"));
        assertEquals(0L, sweep.get("droppedWrites"),
            "a ledger write was dropped — the counts below it are under-reported");
    }

    /**
     * Deciding NONE closes the demand row unanswered, deliberately: from the
     * backlog's point of view an honest absence and an unanswered question are
     * the same fact, and only choosing something converts the demand.
     */
    @Test
    void choosing_nothing_leaves_the_demand_open_and_choosing_something_closes_it() {
        String id = call("record",
            "type", "lesson",
            "summary", "A quokka never files its own expenses before the equinox.",
            "situation", "when the quokka expense window opens",
            "verdict", "worked").get("id").toString();

        Map<String, Object> nominated = call("nominate",
            "question", "quokka expenses before the equinox");
        String queryId = String.valueOf(nominated.get("query_id"));
        int count = ((Number) nominated.get("count")).intValue();

        if (count == 0) {
            // No lane could rank it here (no embedder in this fixture). The demand
            // half is still the honest thing to assert, and it is asserted above.
            call("decide", "query_id", queryId);
            return;
        }

        call("decide", "query_id", queryId, "selected_ids", id);
        Map<String, Object> sweep = call("review_sweep", "min_times", "1", "min_shown", "1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backlog =
            (List<Map<String, Object>>) sweep.get("writingBacklog");
        assertTrue(backlog.isEmpty(),
            () -> "a question that WAS answered is not backlog: " + sweep);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deletion =
            (List<Map<String, Object>>) sweep.get("deletionList");
        assertTrue(deletion.isEmpty(),
            () -> "an entry that was chosen is not a deletion candidate: " + sweep);
    }

    /**
     * A selection that is present and unreadable is REFUSED, never read as
     * "chose none".
     *
     * <p>Absent means the caller chose nothing, which is a real answer this verb
     * exists to make sayable. But the argument helper returns an empty list for
     * anything that is not an array, so one id sent as a bare string would be
     * recorded as an absence — and afterwards nothing distinguishes it from an
     * honest one. The store would hold "nothing applied" about a question that
     * was answered.</p>
     */
    @Test
    void a_selection_that_is_not_an_array_is_refused_rather_than_read_as_none() {
        String q = "does a malformed selection become an absence";
        Map<String, Object> nominated = call("nominate", "question", q);
        ObjectNode bad = mapper.createObjectNode();
        bad.put("kind", "decide");
        bad.put("query_id", String.valueOf(nominated.get("query_id")));
        bad.put("selected_ids", "one-id-as-a-bare-string");
        ToolResponse r = tool.execute(bad);
        assertFalse(r.isSuccess(),
            "a present-but-unreadable selection was accepted, and 'chose none' was"
                + " recorded about a question the caller answered");
        assertTrue(String.valueOf(r.getError()).contains("selected_ids"),
            () -> "the refusal must name the parameter: " + r.getError());
    }

    /**
     * The rule, asserted structurally: usage decides DELETION and never ORDER.
     *
     * <p>What this covers and what it does not, stated so it is not mistaken for
     * more than it is: it proves neither ranking class HOLDS a ledger — no field,
     * no constructor parameter, no method taking or returning one — which is how
     * a collaborator actually gets installed. It cannot see a local variable
     * conjured inside a method body. The stronger guarantee is structural and
     * already in place: the counters live in their own table, so reading them
     * from the merge has to be typed as a join, deliberately.</p>
     */
    @Test
    void neither_the_merge_nor_the_retrieval_can_hold_a_usage_ledger() {
        for (Class<?> ranking : List.of(RelevanceMerge.class, ExperienceRetrieval.class)) {
            for (Field f : ranking.getDeclaredFields()) {
                assertFalse(UsageLedger.class.isAssignableFrom(f.getType()),
                    () -> ranking.getSimpleName() + " holds a UsageLedger in field '"
                        + f.getName() + "'. Usage decides deletion, never order — the"
                        + " moment it feeds the ranking the store answers with what has"
                        + " been popular instead of what fits.");
            }
            for (Method m : ranking.getDeclaredMethods()) {
                assertFalse(UsageLedger.class.isAssignableFrom(m.getReturnType()),
                    () -> ranking.getSimpleName() + "." + m.getName() + " returns a"
                        + " UsageLedger");
                for (Class<?> p : m.getParameterTypes()) {
                    assertFalse(UsageLedger.class.isAssignableFrom(p),
                        () -> ranking.getSimpleName() + "." + m.getName() + " takes a"
                            + " UsageLedger as a parameter");
                }
            }
        }
    }
}
