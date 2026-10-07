package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.ExperienceAdvisor;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.models.CallerIdentity;
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
 * Sprint 28g D42(a) — <b>the two cases where omitting the store's prior-work answer CORRUPTS
 * rather than merely degrades</b>, each answered on the response of the call that would corrupt.
 *
 * <p>The deliverable names them: <i>"`experience describe` naming an existing job for that
 * member, and a code-reading call naming a catalogued job that already does the work"</i>. Both
 * are delivered today by a PreToolUse hook that exists on one client of six, and the standing
 * instruction for the others — <i>"clients without hook injection must PULL it"</i> — is an
 * opt-in, which this project has measured at chosen-by-nobody. So both are asserted SERVER-SIDE,
 * which is client-independent by construction and is the half a live read cannot prove.</p>
 *
 * <p><b>Why "corrupts" rather than "degrades", because it decides the shape of these tests.</b>
 * In both cases the agent is about to WRITE something — a second description of work the store
 * already describes, or a second implementation of a job a catalogued member already does. The
 * answer does not merely help; without it the agent produces a duplicate and nothing downstream
 * can tell that it did. So each case asserts the EXISTING WORK'S OWN WORDS reach the caller,
 * never just that something was found.</p>
 */
class TheTwoCorruptingCallsCarryThePriorWorkAnswerTest {

    /** The member both cases ask about. */
    private static final String MEMBER = "com.example.corrupt.Ledger#reconcile";

    /** A sibling the store knows nothing about — the control in both cases. */
    private static final String UNDESCRIBED = "com.example.corrupt.Ledger#untouched";

    /** The existing job's own words, which are what must reach the caller. */
    private static final String EXISTING_JOB =
        "Settles the ledger against the broker and refuses to trade while they disagree";

    private static final ObjectMapper OM = new ObjectMapper();

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        store.put(ExperienceEntry.of(
            SymbolFact.of("job", EXISTING_JOB, Confidence.MEDIUM).symbol(MEMBER).build())
            .status(ExperienceEntry.ACCEPTED).build());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static JsonNode jobsFor(String... symbols) {
        ObjectNode a = OM.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "jobs_for");
        for (String s : symbols) {
            a.withArray("symbols").add(s);
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    // ---------------------------------------------------------------- case one

    /**
     * CASE ONE. A describing call about a member the store already has a job for must come back
     * with that job's OWN WORDS. Before D42 it answered the member's own name back, which tells
     * a caller a job exists and not what it says — and what it says is the only thing that
     * decides whether the work in front of them is the same work.
     */
    @Test
    @SuppressWarnings("unchecked")
    void a_describing_call_names_the_existing_job_for_that_member() {
        ToolResponse r = new ExperienceTool(() -> null, store).execute(jobsFor(MEMBER));

        assertTrue(r.isSuccess(), "a store-only jobs_for question needs no project");
        List<Map<String, Object>> described =
            (List<Map<String, Object>>) dataOf(r).get("described");
        assertEquals(1, described.size(), () -> "one member asked, one answered: " + described);
        assertEquals(MEMBER, described.get(0).get("symbol"));
        assertEquals(EXISTING_JOB, described.get(0).get("job"),
            () -> "THE JOB'S OWN WORDS, not a flag that one exists: " + described);
    }

    /**
     * THE CONTROL. A member the store knows nothing about is reported missing and carries no job
     * text — so the case above is the store answering about THAT member, not a response that
     * attaches the only job it holds to whatever it is asked.
     */
    @Test
    @SuppressWarnings("unchecked")
    void a_member_with_no_job_is_missing_and_carries_no_words() {
        ToolResponse r = new ExperienceTool(() -> null, store).execute(jobsFor(UNDESCRIBED));

        Map<String, Object> data = dataOf(r);
        assertEquals(List.of(UNDESCRIBED), data.get("missing"));
        assertTrue(((List<Map<String, Object>>) data.get("described")).isEmpty(),
            () -> "nothing is described, so nothing is named: " + data);
        assertFalse(String.valueOf(data).contains(EXISTING_JOB),
            () -> "and the job it holds for the SIBLING must not travel with this answer: " + data);
    }

    /**
     * BOTH AT ONCE, which is the shape the cataloguer actually calls in: the described half names
     * its job and the missing half names only the member. A response that answered correctly for
     * one member at a time could still mix the two up when asked about several.
     */
    @Test
    @SuppressWarnings("unchecked")
    void asked_about_both_it_keeps_them_apart() {
        ToolResponse r = new ExperienceTool(() -> null, store)
            .execute(jobsFor(MEMBER, UNDESCRIBED));

        Map<String, Object> data = dataOf(r);
        List<Map<String, Object>> described =
            (List<Map<String, Object>>) data.get("described");
        assertEquals(1, described.size(), () -> "" + described);
        assertEquals(MEMBER, described.get(0).get("symbol"));
        assertEquals(List.of(UNDESCRIBED), data.get("missing"));
        assertNotNull(data.get("next"),
            "and the instruction for the missing one is still rendered");
    }

    /**
     * THE PER-MEMBER READ MUST NOT ANSWER FOR A SIBLING — the property the C14a round-2 fold
     * introduced and nothing else pins.
     *
     * <p>That fold replaced a whole-corpus read with one scoped per member, which closed a false
     * absence past the corpus cap. The scope filter is the store's own and matches a PREFIX as
     * well as an exact anchor ({@code symbol_fqn = ? OR symbol_fqn LIKE ?}), so the rows it
     * returns are checked for an exact anchor rather than trusted. Without that check, asking
     * about {@code Ledger#reconcile} could be answered by the job of
     * {@code Ledger#reconcileAll} — a plausible, wrong answer on the surface D42(a) makes
     * authoritative for "is this the same work".</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void a_job_on_a_longer_sibling_name_does_not_answer_for_this_member() {
        String sibling = MEMBER + "All";
        store.put(ExperienceEntry.of(
            SymbolFact.of("job", "Settles EVERY ledger, which is a different job",
                Confidence.MEDIUM).symbol(sibling).build())
            .status(ExperienceEntry.ACCEPTED).build());

        ToolResponse r = new ExperienceTool(() -> null, store).execute(jobsFor(UNDESCRIBED));

        Map<String, Object> data = dataOf(r);
        assertEquals(List.of(UNDESCRIBED), data.get("missing"),
            () -> "the undescribed member is still missing: " + data);
        assertTrue(((List<Map<String, Object>>) data.get("described")).isEmpty(),
            () -> "and no sibling's job was offered in its place: " + data);
        assertFalse(String.valueOf(data).contains("different job"),
            () -> "the longer-named sibling's words must not travel with this answer: " + data);
    }

    // ---------------------------------------------------------------- case two

    private static Tool mock(String name) {
        return new Tool() {
            @Override public String getName() {
                return name;
            }
            @Override public String getDescription() {
                return name;
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of("ok", true));
            }
        };
    }

    /** A code-reading call: the registry derives its target from the `symbol` it was given. */
    private String steeringForACodeReadingCallOn(String symbol) throws Exception {
        ToolRegistry reg = new ToolRegistry();
        reg.setExperienceAdvisor(new ExperienceAdvisor(store, () -> null));
        reg.register(mock("find_references"));
        ToolResponse r = reg.callTool("find_references",
            OM.readTree("{\"symbol\":\"" + symbol + "\"}"), CallerIdentity.supplied("s1"));
        return r.getMeta() == null ? null : r.getMeta().getSteering();
    }

    /**
     * CASE TWO. A code-reading call on a member the catalogue already describes must carry that
     * description. This is the case that produced the deliverable: in the session that measured
     * it, the injection <i>"prevented a duplicate implementation of a resolver the store already
     * described"</i> — on the one client that has the hook.
     */
    @Test
    void a_code_reading_call_names_the_catalogued_job_that_already_does_the_work() throws Exception {
        String steer = steeringForACodeReadingCallOn(MEMBER);

        assertNotNull(steer, "the block rides a code-reading answer");
        assertTrue(steer.contains("refuses to trade while they disagree"),
            () -> "it carries the catalogued job's OWN WORDS, which is what lets a reader see"
                + " the work already exists: " + steer);
    }

    /**
     * THE CONTROL for case two, and it does double duty: it shows the block is about the symbol
     * asked for rather than attached to every response, AND that a code-shaped cue reaching the
     * code lane — D42(b)'s derivation — is what makes case two possible at all.
     */
    @Test
    void a_code_reading_call_on_an_undescribed_member_carries_no_job() throws Exception {
        String steer = steeringForACodeReadingCallOn(UNDESCRIBED);

        assertFalse(steer != null && steer.contains(EXISTING_JOB),
            () -> "the sibling's job must not be offered for a member nobody described: " + steer);
    }
}
