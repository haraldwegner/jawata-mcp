package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.KnowledgeLane;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28f Stage 5 — the review sweep reports PER LANE and PER TRIGGER, and the combined
 * lists are gone.
 *
 * <h2>Why dropping the aggregate is the deliverable, not a presentation choice</h2>
 *
 * <p>A deletion list mixing lifecycles is one a reader cannot rule on: dropping a stale
 * experience and dropping a domain fact nobody consulted are different acts with different
 * consequences. Keeping the combined list beside the groups would invite whoever is in a
 * hurry to use the shorter one, so it is REMOVED — and its absence is asserted here, because
 * a clause about dropping something is met only if the thing is actually gone.</p>
 *
 * <h2>The honesty clause, which is the part that could most easily have been skipped</h2>
 *
 * <p>Only ONE surface opens a demand row today: {@code UsageLedger.nominated} has a single
 * caller. A per-trigger breakdown therefore has one group, and four ABSENT ones. An absent
 * group reads as "nobody asked from there" when the truth is "that surface records nothing",
 * and those are opposite instructions to a reader deciding what to write next. So the sweep
 * publishes {@code backlogRecordedBy}, and this class asserts it — the figure is bounded by
 * a statement of what it can possibly contain.</p>
 */
class SweepPerTriggerTest {

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

    private ToolResponse exec(String kind, java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        fill.accept(a);
        return tool.execute(a);
    }

    private String record(String type, String summary) {
        return (String) data(exec("record", a -> {
            a.put("type", type);
            a.put("summary", summary);
            a.put("situation", "when the sweep's grouping is being measured");
            a.put("verdict", "worked");
        })).get("id");
    }

    /** Ask, and decline everything offered — which is what puts rows on both lists. */
    private void askAndKeepNothing(String question) {
        Map<String, Object> nominated = data(exec("nominate", a -> a.put("question", question)));
        String queryId = String.valueOf(nominated.get("query_id"));
        data(exec("decide", a -> {
            a.put("query_id", queryId);
            a.putArray("selected_ids");
        }));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sweep() {
        return data(exec("review_sweep", a -> {
            a.put("min_shown", 1);
            a.put("min_times", 1);
        }));
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_deletion_list_is_grouped_by_lane_and_the_combined_list_is_gone() {
        record("lesson", "the quokka lantern was restocked before the solstice inspection");
        record("domain_fact", "the quokka ledger settles its totals at dusk");
        askAndKeepNothing("what happens to the quokka lantern and the quokka ledger");

        Map<String, Object> out = sweep();

        // THE AGGREGATE IS GONE. Asserted, because "drops the aggregate" is met only if the
        // key is actually absent — and a consumer still reading it now gets nothing and
        // notices, which is the loud direction this file's own `wipe_and_import` rename took.
        assertFalse(out.containsKey("deletionList"),
            () -> "the combined deletion list must be GONE, not kept beside the groups: "
                + out.keySet());
        assertFalse(out.containsKey("writingBacklog"),
            () -> "and so must the combined backlog: " + out.keySet());

        Map<String, List<Map<String, Object>>> byLane =
            (Map<String, List<Map<String, Object>>>) out.get("deletionListByLane");
        assertTrue(byLane != null && !byLane.isEmpty(),
            "PROOF OF LIFE: the sweep must have rows to group, or every assertion about"
                + " the grouping is true of an empty map");

        // The two rows differ ONLY in type, so the lane is the only thing that can separate
        // them — the same fixture discipline the lane query tests use.
        assertTrue(byLane.containsKey(KnowledgeLane.EXPERIENCE.wire()),
            () -> "a lesson belongs to the experience lane; got " + byLane.keySet());
        assertTrue(byLane.containsKey(KnowledgeLane.DOMAIN.wire()),
            () -> "a domain fact belongs to the domain lane; got " + byLane.keySet());
        assertEquals(1, byLane.get(KnowledgeLane.EXPERIENCE.wire()).size(),
            "one lesson was shown and declined");
        assertEquals(1, byLane.get(KnowledgeLane.DOMAIN.wire()).size(),
            "THE CONTROL: and the domain fact is in its OWN group, not folded in with it."
                + " A grouping that put everything in one bucket would satisfy the two"
                + " containment checks above.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_backlog_is_grouped_by_trigger_and_says_which_surfaces_record_at_all() {
        askAndKeepNothing("a question about pangolins that this store cannot answer");

        // The OTHER surface, driven so the declaration below is measured rather than
        // copied: a recall nothing can answer. Today it opens no demand row, which is the
        // very bound `backlogRecordedBy` publishes — so this call is what makes that claim
        // falsifiable instead of a second copy of the production literal.
        exec("recall", a -> a.put("symptom", "a pangolin symptom this store cannot answer"));

        Map<String, Object> out = sweep();

        Map<String, List<Map<String, Object>>> byTrigger =
            (Map<String, List<Map<String, Object>>>) out.get("writingBacklogByTrigger");
        assertTrue(byTrigger != null && !byTrigger.isEmpty(),
            "PROOF OF LIFE: an unanswered question must reach the backlog, or the grouping"
                + " below is asserted over nothing");
        assertTrue(byTrigger.containsKey("nominate"),
            () -> "the demand row carries the surface that opened it; got "
                + byTrigger.keySet());

        // THE HONESTY CLAUSE, and it is asserted against what the run OBSERVED rather than
        // against a copy of the production literal.
        //
        // The first version of this compared `List.of("nominate")` with a field whose
        // production value is `List.of("nominate")` — two hardcoded literals, so the day
        // `recall` started recording a demand row the map above would gain a group, this
        // field would keep saying only `nominate`, the published sentence would become a
        // lie, and the assertion would stay GREEN. It claimed in its own comment to catch
        // exactly that. A C5 audit found it; it is the unfalsifiable-assertion shape this
        // sprint has now shipped at six checkpoints, and it was sitting on the one clause
        // that BOUNDS a narrowed deliverable.
        //
        // So the run drives an unanswered RECALL as well as the nominate above. Every
        // surface that opened a row appears in `byTrigger`; the declaration must name
        // exactly those. Teach `recall` to record and the observed set grows while the
        // declaration does not — which is the red this clause was always supposed to give.
        assertEquals(byTrigger.keySet(),
            new java.util.LinkedHashSet<>((List<String>) out.get("backlogRecordedBy")),
            "THE SWEEP MUST SAY WHICH SURFACES RECORD DEMAND AT ALL, and say it about the"
                + " surfaces that actually recorded. A missing group means that surface"
                + " records nothing — NOT that nobody asked from it, and those are opposite"
                + " instructions to whoever is deciding what to write next.");

        assertTrue(String.valueOf(out.get("howToRead")).contains("backlogRecordedBy"),
            () -> "and the reading note must point at it, or the bound is published in a"
                + " field nobody is told to read: " + out.get("howToRead"));
    }
}
