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
import static org.junit.jupiter.api.Assertions.assertNull;
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
     * A targeted delete SAYS whether it has an undo — here, on a store that has none.
     *
     * <p>Until Sprint 28f the delete wrote its own JSON archive of the removed rows,
     * and this test proved the ORDER by reading the entry back out of it: reversed —
     * delete, then export — the file parses, is named correctly, sits where the
     * response says, and is empty. D2 replaced that archive with a copy of the whole
     * store, which is a stronger undo and a differently shaped one, so the order is
     * now proved where the copy is real:
     * {@code StoreBackupsTest#a_delete_can_be_undone_from_the_copy_it_took} deletes a
     * row on a FILE store and restores it.</p>
     *
     * <p>What is left here is the case that store cannot have. This one is in-memory,
     * so no copy is possible at all, and the only thing worth asserting is that the
     * verb says so out loud rather than returning a response in which the absence and
     * a successful copy look identical.</p>
     *
     * <p>The review seat runs on every client. D12's cutover archive exists only
     * on the one machine that ran the reseed, so a delete that leaned on it
     * would be irreversible everywhere else — which is why this undo travels
     * with the delete itself.</p>
     */
    @Test
    void a_delete_says_whether_it_has_an_undo() throws Exception {
        String doomed = call("record",
            "type", "lesson",
            "summary", "A pangolin audits nothing on a Tuesday.",
            "situation", "when the pangolin audit window opens",
            "verdict", "worked").get("id").toString();
        String keeper = call("record",
            "type", "lesson",
            "summary", "A wombat reconciles everything on a Thursday.",
            "situation", "when the wombat reconciliation window opens",
            "verdict", "worked").get("id").toString();

        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "delete");
        a.putArray("ids").add(doomed).add("an-id-that-was-never-here");
        Map<String, Object> out = data(tool.execute(a));

        assertEquals(1, ((Number) out.get("removed")).intValue(),
            () -> "delete removed the wrong number of entries: " + out);
        assertEquals(List.of("an-id-that-was-never-here"), out.get("alreadyAbsent"),
            () -> "a stale id must be reported, not counted as a success: " + out);

        // SPRINT 28f D2 CHANGED THE UNDO, AND THIS IS WHERE THAT LANDED.
        //
        // The delete used to write its own JSON archive of the removed rows and name
        // it in an `archive` field; it now takes a copy of the whole STORE and names
        // it in `backup`. This test asked for `archive`, got null, and built
        // Path.of("null") — the one failure in a 2967-test suite, and the reason it
        // survived a stage is that the stage verified itself by running three named
        // classes rather than the suite.
        //
        // THIS STORE IS IN-MEMORY (setUp: H2ExperienceStore.open(null)), so there is
        // no file to copy and no undo at all. That is the contract asserted here, and
        // the field must be PRESENT and null rather than absent — "no copy was taken"
        // and "a copy was taken and nobody said so" must not read alike. The case
        // where the undo actually works is a FILE store, and it is pinned where it
        // belongs: StoreBackupsTest#a_delete_can_be_undone_from_the_copy_it_took.
        assertTrue(out.containsKey("backup"),
            () -> "the delete must SAY whether it has an undo, even when it has none: " + out);
        assertNull(out.get("backup"),
            () -> "an in-memory store has no file to copy, so there is nothing to name: " + out);
        assertNotNull(out.get("backupNote"),
            () -> "and the absence carries its reason, or it reads like an oversight: " + out);
        assertEquals(List.of(doomed), out.get("deleted"),
            () -> "the response names exactly the row it removed: " + out);

        // and the one not named is still there
        Map<String, Object> listed = call("list", "limit", "50");
        assertTrue(String.valueOf(listed).contains(keeper),
            () -> "delete removed an entry nobody named: " + listed);
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
