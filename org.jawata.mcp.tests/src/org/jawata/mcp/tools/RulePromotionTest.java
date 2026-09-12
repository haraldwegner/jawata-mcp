package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.KnowledgeLane;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28f Stage 5 — the RULE lifecycle: derived from sources, versioned, retired.
 *
 * <h2>What makes a rule a different lifecycle rather than a flag on an entry</h2>
 *
 * <p>Three things, and each is asserted here. A rule is DERIVED FROM entries that are not
 * superseded by it and go on answering as themselves. It is AMENDED, which writes a new
 * version and leaves the old one readable — what a rule USED to say is precisely what a
 * version number is for. And it is RETIRED, which is neither {@code rejected} (it was not
 * wrong) nor {@code superseded} (nothing replaced it): it stopped applying, on a date.</p>
 *
 * <p>Every case drives the PUBLISHED verbs rather than the store, because the verbs are
 * where a caller meets this and where an unwired argument would hide.</p>
 */
class RulePromotionTest {

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

    /** A source entry the rule will be drawn from. */
    private String source(String summary) {
        return (String) data(exec("record", a -> {
            a.put("type", "lesson");
            a.put("summary", summary);
            a.put("situation", "when a scheduled task overlaps the previous run");
            a.put("verdict", "worked");
        })).get("id");
    }

    private ToolResponse promoteRule(String summary, String... ids) {
        return exec("promote_rule", a -> {
            a.put("summary", summary);
            ArrayNode arr = a.putArray("ids");
            for (String id : ids) {
                arr.add(id);
            }
        });
    }

    /**
     * Whether a recall for this cue RETURNS the given entry, optionally filtered to a lane.
     *
     * <p><b>The needle is the entry's ID, and choosing the cue's own words instead is a
     * mistake this class made first.</b> The recall response ECHOES the query — it renders
     * {@code cue={symptom=quokka}} — so asserting that the answer contains "quokka" is
     * satisfied by a recall that returned nothing at all. The id appears only if the row
     * was actually returned, and it appears in no cue.</p>
     *
     * <p>The lane is asserted THROUGH the product's own filter rather than by reading the
     * column: the column says what was written, and this says the written value is the one
     * a caller asking for the rules lane actually gets.</p>
     */
    private boolean recallReturns(String cue, String lane, String id) {
        ToolResponse r = exec("recall", a -> {
            a.put("symptom", cue);
            if (lane != null) {
                a.put("lane", lane);
            }
        });
        assertTrue(r.isSuccess(), () -> "recall failed: " + r.getError());
        return String.valueOf(r.getData()).contains(id);
    }

    @Test
    void a_promoted_rule_is_version_one_linked_to_its_sources_which_are_left_alone() {
        String a = source("the amend quantity is the total, not the remainder");
        String b = source("a cancel is done when the broker confirms, not when it is sent");

        Map<String, Object> promoted = data(promoteRule(
            "read the pangolin state back from the counterparty before acting on it", a, b));

        String ruleId = (String) promoted.get("id");
        assertNotNull(ruleId);
        assertEquals(1, promoted.get("rule_version"), "a promotion is version ONE");
        assertEquals(List.of(a, b), promoted.get("derived_from"),
            "the response names what the rule was drawn from, in the order given");

        // The lane, through the product: nothing in promote_rule sets it — it follows from
        // the TYPE, and the rules lane is where a caller asking for rules will find it.
        assertTrue(recallReturns("pangolin", KnowledgeLane.RULES.wire(), ruleId),
            "a promoted rule must be in the rules lane, which nothing here sets explicitly");
        assertFalse(recallReturns("pangolin", KnowledgeLane.DOMAIN.wire(), ruleId),
            "THE CONTROL: and it is in no other lane — without this, a filter that let"
                + " everything through would satisfy the assertion above");

        // The link survives into the stored row. The needle is a generated id, which
        // cannot occur by accident in any other part of the entry.
        String stored = String.valueOf(store.get(ruleId).orElseThrow());
        assertTrue(stored.contains("derived_from"), () -> "the rel must be stored: " + stored);
        assertTrue(stored.contains(a) && stored.contains(b),
            () -> "both source ids must be stored: " + stored);

        // THE CONTROL, and it is what distinguishes derived_from from supersedes: a source
        // is not replaced by the rule drawn from it. It goes on answering as itself.
        //
        // Asserted as UNCHANGED rather than against a named status, which is the actual
        // claim — and the first version named `accepted` and was simply wrong about the
        // product: `record` writes a CANDIDATE. Pinning a status here would have made this
        // control fail whenever the recording default moved, for a reason that has nothing
        // to do with what it guards.
        assertEquals(0, data(exec("list", x -> x.put("status", ExperienceEntry.SUPERSEDED)))
                .get("count"),
            "NO row was superseded by the promotion: a rule is DERIVED FROM its sources,"
                + " which go on answering as themselves. That is the whole difference"
                + " between derived_from and supersedes.");
        for (String id : List.of(a, b)) {
            assertTrue(store.get(id).isPresent(), "and the source is still there: " + id);
        }
    }

    @Test
    void a_rule_whose_sources_are_not_in_the_store_is_refused_before_anything_is_written() {
        String real = source("something that is actually here");
        long before = store.count();

        ToolResponse r = promoteRule("a rule drawn from a ghost", real, "no-such-id");
        assertFalse(r.isSuccess(), "a rule cannot be drawn from an id that is not there");
        assertTrue(String.valueOf(r.getError()).contains("no-such-id"),
            () -> "the refusal must NAME the missing id: " + r.getError());

        assertEquals(before, store.count(),
            "AND NOTHING WAS WRITTEN. The check runs before the insert, so a refused"
                + " promotion leaves no half-made rule behind — a rule whose sources"
                + " cannot be read is one nobody can hold to account.");
    }

    @Test
    void an_amendment_is_a_new_version_and_the_old_one_stays_readable() {
        String src = source("a broker id is the only key that identifies an order");
        String v1 = (String) data(promoteRule("match orders on the broker id", src)).get("id");

        Map<String, Object> v2 = data(exec("amend_rule", a -> {
            a.put("id", v1);
            a.put("summary", "match orders on the broker id, never on the symbol");
        }));

        String v2Id = (String) v2.get("id");
        assertEquals(2, v2.get("rule_version"), "an amendment is the NEXT version");
        assertEquals(v1, v2.get("supersedes"), "and it says which version it replaces");

        // Asked of the `list` verb rather than of store.get: get() returns the FROZEN
        // body_json, whose status is the one written at insert and does not move when
        // setStatus does. StoredEntry's own javadoc says so, and the first version of
        // this assertion read the stale value and reported `accepted`.
        assertEquals(1, data(exec("list", a -> a.put("status", ExperienceEntry.SUPERSEDED)))
                .get("count"),
            "the old version is superseded — exactly one row, which is v1");

        Map<String, Object> old = store.get(v1).orElseThrow();
        assertTrue(String.valueOf(old.get("summary")).contains("match orders on the broker id"),
            "AND IT IS STILL READABLE, unedited. Rewriting v1 in place would destroy the"
                + " one question a version number exists to answer — what the rule USED"
                + " to say — leaving a store that knows the rule and not its history.");
        assertFalse(v2Id.equals(v1), "an amendment writes a new row rather than editing one");
    }

    @Test
    void amending_something_that_is_not_a_rule_is_refused_by_name() {
        String lesson = source("an ordinary lesson, which has a different lifecycle");
        ToolResponse r = exec("amend_rule", a -> {
            a.put("id", lesson);
            a.put("summary", "an amendment that should not be accepted");
        });
        assertFalse(r.isSuccess());
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("lesson"),
            () -> "the refusal must name the type it actually found: " + error);
    }

    /**
     * Retiring stops RECALL and leaves the entry READABLE — the plan's own clause, and the
     * two halves are asserted together because either alone describes a different feature.
     */
    @Test
    void a_retired_rule_stops_being_recalled_and_stays_readable() {
        String src = source("the pangolin ledger settles its totals at dusk");
        String ruleId = (String) data(promoteRule(
            "settle the quokka ledger before the inspection", src)).get("id");

        // PROOF OF LIFE: it is recalled BEFORE retirement, or the absence below is true
        // of a rule that was never findable in the first place.
        assertTrue(recallReturns("quokka", null, ruleId),
            "a live rule must be recalled, or this test measures nothing");

        Map<String, Object> retired = data(exec("retire_rule", a -> a.put("id", ruleId)));
        assertEquals(true, retired.get("retired"), "the first retirement changes a row");

        assertFalse(recallReturns("quokka", null, ruleId),
            "A RETIRED RULE MUST NOT BE HANDED OVER AS LIVE GUIDANCE. That is the whole"
                + " content of retiring one.");

        Map<String, Object> readable = store.get(ruleId).orElseThrow();
        assertTrue(String.valueOf(readable.get("summary")).contains("quokka"),
            "AND IT IS STILL READABLE: retiring is not deleting, and 'what did this rule"
                + " say, and until when' is a question the store should answer.");

        Map<String, Object> again = data(exec("retire_rule", a -> a.put("id", ruleId)));
        assertEquals(false, again.get("retired"),
            "a second retirement changes nothing — moving the date would rewrite the"
                + " answer to 'until when did this apply'");
        assertTrue(String.valueOf(again.get("note")).contains("already retired"),
            () -> "and the caller is told so rather than handed a no-op success: " + again);
    }

}
