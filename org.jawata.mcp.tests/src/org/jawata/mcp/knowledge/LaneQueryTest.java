package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f Stage 5 — {@code recall(lane=…)} narrows what answers to one lifecycle.
 *
 * <h2>The fixture is built so the filter is the ONLY thing that can separate the rows</h2>
 *
 * <p>Both entries carry the SAME symbol cue and both are findable. If they differed in
 * anything the query reads — a different anchor, a different word in the summary — a passing
 * test would not say whether the lane did the separating or the cue did. They differ in TYPE
 * alone, which is what decides the lane.</p>
 *
 * <p>Every filtered assertion is paired with the UNFILTERED recall that returns both. Without
 * that, "the lesson is absent from the domain lane" is equally true of a store that answers
 * nothing at all — which is how a filter that rejects everything passes for one that works.</p>
 */
class LaneQueryTest {

    /** The one cue both rows answer to, so only the lane can tell them apart. */
    private static final String CUE = "com.example.laneq.Ledger";

    private static String put(H2ExperienceStore store, String type, String summary) {
        ExperienceEntry e = ExperienceEntry.of(
                SymbolFact.of(type, summary, Confidence.MEDIUM).symbol(CUE).build())
            .status(ExperienceEntry.ACCEPTED)
            .situation("when the lane filter is being measured")
            .build();
        return store.putWithSource(e, "lane-query/" + type + ".md");
    }

    private static Set<String> typesOf(List<StoredEntry> rows) {
        return rows.stream().map(StoredEntry::type).collect(Collectors.toSet());
    }

    @Test
    void a_lane_narrows_the_answer_to_that_lifecycle(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            put(store, "lesson", "what happened when the ledger was amended twice");
            put(store, "domain_fact", "what the ledger guarantees about its own totals");

            // PROOF OF LIFE: unfiltered, the cue finds BOTH. Every assertion below is
            // meaningless without it — an empty answer satisfies both exclusions at once.
            Set<String> unfiltered =
                typesOf(store.query(new RecallQuery(CUE, null, null, null, null)));
            assertTrue(unfiltered.contains("lesson") && unfiltered.contains("domain_fact"),
                () -> "the cue must find both rows before a filter can be measured; found "
                    + unfiltered);

            Set<String> domain =
                typesOf(store.query(new RecallQuery(CUE, null, null, null, null, "domain")));
            assertTrue(domain.contains("domain_fact"),
                () -> "lane=domain must still answer the domain row; found " + domain);
            assertFalse(domain.contains("lesson"),
                () -> "A DOMAIN QUESTION MUST NOT BE ANSWERED BY A LESSON. Both rows carry"
                    + " the same cue, so the lane is the only thing that can exclude it —"
                    + " drop the lane predicate from the query and this is what goes red."
                    + " Found " + domain);

            Set<String> experience =
                typesOf(store.query(new RecallQuery(CUE, null, null, null, null, "experience")));
            assertTrue(experience.contains("lesson"),
                () -> "lane=experience must still answer the lesson; found " + experience);
            assertFalse(experience.contains("domain_fact"),
                () -> "THE MIRROR, and it is why the pair is worth more than either half:"
                    + " a filter that simply dropped everything would satisfy the exclusion"
                    + " above. Found " + experience);
        }
    }

    /**
     * A lane with NO cue is an EMPTY query, so it retrieves nothing rather than scanning.
     *
     * <p>The lane says which part of the corpus may answer; it never says what to look for.
     * Reading it in {@code isEmpty} would turn {@code recall(lane=domain)} into a whole-corpus
     * sweep returning whatever sorts first — an answer that looks like retrieval and is a
     * listing. This is the assertion that keeps {@code lane} out of that method.</p>
     */
    @Test
    void a_lane_on_its_own_is_not_a_query() {
        assertTrue(new RecallQuery(null, null, null, null, null, "domain").isEmpty(),
            "A LANE IS A FILTER, NOT A CUE. With no cue beside it the query is empty and"
                + " resolves to absence; a lane that made a query non-empty would scan the"
                + " whole corpus and return whatever came first.");
        assertFalse(new RecallQuery(CUE, null, null, null, null, "domain").isEmpty(),
            "the control: a cue WITH a lane is a real query");
        assertFalse(new RecallQuery(CUE, null, null, null, null).isEmpty(),
            "and so is a cue without one");
    }

    /**
     * The five-cue constructor means NO lane filter — every one of the 64 call sites that
     * predates the column keeps searching every lane.
     */
    /** Drive the published verb the way a client does, and return what it rendered. */
    private static String recallText(ExperienceTool tool, ObjectMapper mapper, String lane) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "recall");
        a.put("symbol", CUE);
        if (lane != null) {
            a.put("lane", lane);
        }
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "recall failed: " + r.getError());
        return String.valueOf(r.getData());
    }

    /**
     * THE FRONT DOOR: the {@code lane} argument actually reaches the store.
     *
     * <p>Every other test here builds a {@link RecallQuery} by hand, so all of them stay green
     * if the verb reads the argument under a name nobody sends — a mistyped key, or a
     * parameter declared in the schema and never read. That is the defect this product has
     * shipped before: Sprint 27 released semantic recall INERT, because a test suite that does
     * its own wiring cannot see that the product's wiring is missing.</p>
     *
     * <p>The needles are nonsense words that occur nowhere else in the corpus, so a word is in
     * the answer only if its row was returned — the response cannot echo them from the query,
     * which carries neither.</p>
     */
    @Test
    void the_recall_verb_passes_its_lane_argument_through() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            ObjectMapper mapper = new ObjectMapper();
            put(store, "lesson", "the quokka lantern was restocked before the inspection");
            put(store, "domain_fact", "the pangolin ledger settles its totals at dusk");

            String unfiltered = recallText(tool, mapper, null);
            assertTrue(unfiltered.contains("quokka") && unfiltered.contains("pangolin"),
                () -> "PROOF OF LIFE: unfiltered, the verb must return both rows, or the"
                    + " exclusion below is true of an answer that is simply empty. Got: "
                    + unfiltered);

            String domain = recallText(tool, mapper, "domain");
            assertTrue(domain.contains("pangolin"),
                () -> "lane=domain must still answer the domain row. Got: " + domain);
            assertFalse(domain.contains("quokka"),
                () -> "THE WIRING: the lane must travel from the verb's arguments to the"
                    + " store's predicate. Read the argument under a name no client sends and"
                    + " every other test in this class stays green while the filter is inert."
                    + " Got: " + domain);
        }
    }

    @Test
    void the_five_cue_form_filters_nothing() {
        RecallQuery q = new RecallQuery(CUE, "com.example", "rename_symbol", "a symptom", "h2");
        assertFalse(q.hasLane(), "the compatibility form must not filter by lane");
        assertEquals(null, q.lane(), "and it carries no lane value to be misread downstream");
    }
}
