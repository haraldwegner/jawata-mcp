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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c D8 — every cue in a prompt reaches the store.
 *
 * <p>The deployed hook derives up to five cues from one prompt and asks about
 * them one at a time, <b>exiting on the first that answers</b>. So one cue's
 * knowledge reaches the agent and the other four are never asked — not because
 * anything judged them less relevant, but because one of them went first.</p>
 */
class MultiCueRecallTest {

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
        SessionShown.forget("session-under-test");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    private void record(String summary, String symptom) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", summary);
        a.put("situation", "when the quokka ledger is reconciled after the equinox");
        a.put("verdict", "worked");
        a.putArray("symptoms").add(symptom);
        data(tool.execute(a));
    }

    private Map<String, Object> recall(String session, String... symptoms) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "recall");
        if (session != null) {
            a.put("session", session);
        }
        var arr = a.putArray("symptoms");
        for (String s : symptoms) {
            arr.add(s);
        }
        return data(tool.execute(a));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> d) {
        Object e = d.get("entries");
        return e == null ? List.of() : (List<Map<String, Object>>) e;
    }

    /**
     * THE assertion. Two cues, two different entries, one ask — and both come
     * back. Under the old behaviour the first cue answers and the second entry
     * is never fetched, which looks from the agent's side exactly like a store
     * that does not have it.
     */
    @Test
    void two_cues_that_answer_different_entries_both_reach_the_caller() {
        record("The first thing, about wombat reconciliation.", "wombat reconciliation stalls");
        record("The second thing, about pangolin audits.", "pangolin audit returns nothing");

        Map<String, Object> d = recall(null,
            "wombat reconciliation stalls", "pangolin audit returns nothing");

        assertEquals(2, ((Number) d.get("cues_asked")).intValue(),
            () -> "both cues must be ASKED, not just carried: " + d);
        String body = String.valueOf(entries(d));
        assertTrue(body.contains("wombat"),
            () -> "the first cue's entry is missing: " + d);
        assertTrue(body.contains("pangolin"),
            () -> "the second cue's entry is missing — this is the defect: one cue"
                + " answered and the rest were never asked, which is indistinguishable"
                + " from a store that does not have them: " + d);
    }

    /** An entry that answers two cues is offered once, not twice. */
    @Test
    void an_entry_answering_two_cues_is_offered_once() {
        record("One thing, two ways in.", "wombat reconciliation stalls");
        Map<String, Object> d = recall(null,
            "wombat reconciliation stalls", "wombat reconciliation stalls");
        assertEquals(1, entries(d).size(), () -> "the same entry came back twice: " + d);
    }

    /**
     * Session dedup, and the report that keeps it honest. The hook fires on every
     * prompt, so without this a long conversation re-injects the same lines until
     * the agent learns to skim the block — and the one turn with something new
     * looks exactly like the twenty before it.
     *
     * <p>The withheld count is asserted too. An entry held back because the reader
     * already has it and an entry the store does not have are opposite facts, and
     * a silent drop renders the first as the second.</p>
     */
    @Test
    void a_session_is_not_shown_the_same_entry_twice_and_is_told_when_that_happens() {
        record("Said once, and once is enough.", "wombat reconciliation stalls");

        Map<String, Object> first = recall("session-under-test", "wombat reconciliation stalls");
        assertEquals(1, entries(first).size(), () -> "the first ask must answer: " + first);

        Map<String, Object> second = recall("session-under-test", "wombat reconciliation stalls");
        assertEquals(0, entries(second).size(),
            () -> "the same entry was injected twice into one session: " + second);
        assertEquals(1, ((Number) second.get("already_shown_this_session")).intValue(),
            () -> "a withheld entry must be REPORTED — silently dropping it reads as"
                + " an absence, which is the opposite claim: " + second);

        Map<String, Object> other = recall("a-different-session", "wombat reconciliation stalls");
        assertEquals(1, entries(other).size(),
            () -> "another session must still be shown it: " + other);
        SessionShown.forget("a-different-session");
    }

    /**
     * The singular form is untouched. Every existing caller passes one cue and no
     * arrays, and must take exactly the path it always took — without this the
     * whole hook fleet could change behaviour and nothing would go red.
     */
    @Test
    void a_single_cue_still_takes_the_single_cue_path() {
        record("Reached the old way.", "wombat reconciliation stalls");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "recall");
        a.put("symptom", "wombat reconciliation stalls");
        Map<String, Object> d = data(tool.execute(a));
        assertTrue(d.get("cues_asked") == null,
            () -> "a single-cue recall must not go through the union path: " + d);
        assertEquals(1, entries(d).size(), () -> "the old path still answers: " + d);
    }
}
