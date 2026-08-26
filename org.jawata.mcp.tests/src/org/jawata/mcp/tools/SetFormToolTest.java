package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.StoredEntry;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c Stage 15 — the repair verb, tested against the stage's own list.
 *
 * <p>Until {@code set_form}, the store could DIAGNOSE a badly-formed entry and
 * not FIX one: {@code ExperienceStore#setForm} had three references and none
 * was a tool verb. The mechanical migration is no substitute — on the real
 * corpus it derived situations reading "when by construction" and
 * "when $8 on one day".</p>
 *
 * <p>Every write here goes through the FRONT DOOR ({@code tool.execute}), not
 * the store API, because a verb proven only at the store layer is the
 * built-but-unreachable shape this sprint has already shipped twice.</p>
 */
class SetFormToolTest {

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
    void tearDown() throws Exception {
        store.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: "
            + (r.getError() == null ? "?" : r.getError().getMessage()));
        return (Map<String, Object>) r.getData();
    }

    private ToolResponse call(ObjectNode args) {
        return tool.execute(args);
    }

    /** A lesson whose situation is migration-grade garbage — the repair target. */
    private String badlyFormedLesson() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", "the walrus ledger loses a fill when the amend races the cancel");
        a.put("situation", "when by construction");
        a.put("verdict", "failed_avoid");
        return (String) data(call(a)).get("id");
    }

    /** A reference row — owes no situation and may never carry a verdict. */
    private String referenceRow() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "reference");
        a.put("summary", "the walrus ledger settlement window is documented upstream");
        return (String) data(call(a)).get("id");
    }

    private ObjectNode setForm(String id, String situation) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "set_form");
        if (id != null) {
            a.put("id", id);
        }
        if (situation != null) {
            a.put("situation", situation);
        }
        return a;
    }

    private StoredEntry only(String id) {
        List<StoredEntry> rows = store.byIds(List.of(id));
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    /**
     * The whole point in one test: rewrite, then FIND it by the new situation.
     * The write is proven by the read, not by the write's return value.
     */
    @Test
    void a_rewritten_situation_is_recallable_and_stamped_seat_rewritten() {
        String id = badlyFormedLesson();

        Map<String, Object> d = data(call(setForm(id,
            "when an amend and a cancel race on the same walrus ledger slot")));
        assertEquals("seat_rewritten", d.get("provenance_kind"));
        assertEquals("failed_avoid", d.get("verdict"),
            "an unsupplied verdict keeps the one the row already had — fixing the"
                + " situation must not force the caller to re-state a correct outcome");

        StoredEntry e = only(id);
        assertEquals("when an amend and a cancel race on the same walrus ledger slot",
            e.facets().situation());
        assertEquals("seat_rewritten", e.facets().provenanceKind(),
            "a reader must be able to tell a reviewed correction from the author's"
                + " own words");

        // Recallable BY the new situation, through the production nomination.
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "nominate");
        n.put("question", "what happens when an amend races a cancel on a walrus ledger slot");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates =
            (List<Map<String, Object>>) data(call(n)).get("candidates");
        assertTrue(candidates.stream().anyMatch(c -> id.equals(c.get("id"))),
            () -> "the rewritten entry must be findable by its NEW situation: " + candidates);
    }

    /**
     * THE GATE STANDS AT THIS DOOR TOO. A heading-shaped situation is refused
     * with the same teaching message {@code record} gives — asserted on the
     * MESSAGE, so the two surfaces cannot drift apart silently.
     */
    @Test
    void a_location_shaped_situation_is_refused_and_the_row_is_unchanged() {
        String id = badlyFormedLesson();
        ToolResponse r = call(setForm(id, "docs/sprints/sprint-28c.md"));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().getMessage().contains("a situation says WHEN an entry applies"),
            () -> "the refusal must TEACH, with record's own rule text: "
                + r.getError().getMessage());
        assertEquals("when by construction", only(id).facets().situation(),
            "a refused rewrite must leave the row exactly as it was");
    }

    /** A verdict on a fact is refused, not ignored — the invented-value lesson. */
    @Test
    void a_verdict_on_a_reference_is_refused() {
        String id = referenceRow();
        ObjectNode a = setForm(id, "when the settlement window is consulted");
        a.put("verdict", "worked");
        ToolResponse r = call(a);
        assertFalse(r.isSuccess());
        assertTrue(r.getError().getMessage().contains("never turned out"),
            () -> "the refusal names the rule: " + r.getError().getMessage());
    }

    /** A reference CAN gain a situation — it owes no verdict, it may still declare when it applies. */
    @Test
    void a_reference_can_gain_a_situation_without_a_verdict() {
        String id = referenceRow();
        Map<String, Object> d = data(call(setForm(id,
            "when the settlement window is consulted before an amend")));
        assertEquals("seat_rewritten", d.get("provenance_kind"));
        assertNotNull(only(id).facets().situation());
    }

    /** Unknown id is a REFUSAL naming the id — never a silent no-op. */
    @Test
    void an_unknown_id_is_refused_by_name() {
        ToolResponse r = call(setForm("no-such-id-anywhere", "when nothing exists"));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().getMessage().contains("no-such-id-anywhere"),
            () -> "the refusal names the id, so a stale finding list is diagnosable: "
                + r.getError().getMessage());
        assertTrue(r.getError().getMessage().contains("Nothing was written"));
    }

    /**
     * The rewrite hands the row back to the embedding backfill. Asserted on the
     * stored row: identity and lanes cleared — because leaving the OLD vectors
     * in place would keep the old text answering on the meaning lanes forever,
     * which is F2's permanent case created by the very call that fixed the words.
     */
    @Test
    void a_rewrite_clears_the_stale_vectors_for_the_backfill() {
        String id = badlyFormedLesson();
        data(call(setForm(id, "when an amend and a cancel race on one slot")));
        Map<String, Object> row = store.get(id).orElseThrow();
        assertTrue(row.get("embedder_identity") == null,
            "a cleared identity is what the backfill selects on — an intact one"
                + " would strand the OLD situation's vectors as the row's meaning"
                + " forever");
    }
}
