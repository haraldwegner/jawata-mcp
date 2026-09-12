package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.ExperienceRetrieval;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 8 — ASKING THE CODE LANE BY MEANING RETURNS A JOB THAT NO DISTANCE BAR
 * WOULD ADMIT.
 *
 * <h2>What this test is for, and why its fixture is named in the plan rather than left to
 * whoever wrote the class</h2>
 *
 * <p>Stage 0 measured that this corpus has NO usable cutoff: 3 of 10 genuine task-to-job
 * pairs score at or below the noise floor, so no bar admits the real answers without
 * admitting noise. Stage 8's map is therefore the UNION the product's retrieval already
 * uses — the identity and keyword path together with meaning — and NOT a threshold over
 * job texts.</p>
 *
 * <p>A paragraph saying that goes stale silently. This test does not: it asks with the
 * corpus's own SUB-FLOOR pair, measured at <b>0.1061</b> against a noise floor of
 * <b>0.2027</b> in measurement 3. The pair is below the floor by construction, so an
 * implementation that quietly ships a distance bar turns this RED rather than turning a
 * paragraph wrong. The architect's C0 watch proposed exactly this, having found that
 * nothing in Stage 8's exit could otherwise have caught a bar coming back.</p>
 *
 * <h2>Why there are more distractors than the shortlist can hold</h2>
 *
 * <p>{@link ExperienceRetrieval#MAX_CANDIDATES} is the cap, and with fewer rows than that
 * every row comes back whatever the ranking does — so "the job is returned" would be true
 * of a nomination that had never scored anything at all. The store here holds MORE jobs
 * than the shortlist can carry, which makes the assertion a claim about RANKING: the job
 * has to beat distractors to be in the answer at all.</p>
 */
class SeatAsksByMeaningTest {

    /**
     * The task, verbatim from measurement 3. It names no symbol, no package and no
     * operation — which is the whole reason the anchorless path exists.
     */
    private static final String TASK =
        "which parts of the product did the checks actually exercise?";

    /** And the job it must reach, whose words are almost none of the task's. */
    private static final String TARGET_JOB =
        "Run the project's tests in a forked machine and report which lines they reached";

    private static final String TARGET_SYMBOL = "com.example.CoverageRunner#runForked";

    private ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private void recordJob(String symbol, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", symbol);
        a.put("summary", summary);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(),
            "the gate must ACCEPT every fixture row, or this measures the gate rather than"
                + " the ranking: " + r.getError());
    }

    /**
     * The distractors are ORDINARY jobs, not nonsense. A pile of gibberish would rank last
     * against anything and the target would arrive by default, which proves nothing about
     * a real corpus.
     */
    private void recordTheCorpus() {
        recordJob(TARGET_SYMBOL, TARGET_JOB);
        recordJob("com.example.Ledger#recordFailure",
            "Marks the breaker as having just seen an error, so the window can open.");
        recordJob("com.example.Gauge#scale",
            "Answers how many units one screen pixel stands for right now.");
        recordJob("com.example.Inbox#drain",
            "Hands out every message that arrived since the last caller asked.");
        recordJob("com.example.Clock#advance",
            "Moves the simulated time forward by the amount a caller names.");
        recordJob("com.example.Router#route",
            "Chooses which handler answers a request from its declared path.");
        recordJob("com.example.Cache#evict",
            "Drops the entry that has gone longest without being read.");
        recordJob("com.example.Parser#parse",
            "Turns a source file into a tree the rest of the tool can walk.");
        recordJob("com.example.Writer#flush",
            "Pushes everything buffered out to disk before the caller continues.");
        recordJob("com.example.Session#expire",
            "Ends a session whose last activity is older than the configured window.");
        recordJob("com.example.Retry#backoff",
            "Waits longer before each attempt so a struggling server is not hammered.");
        recordJob("com.example.Vault#unseal",
            "Opens the secret store once the operator supplies enough key shares.");
        recordJob("com.example.Index#rebuild",
            "Throws away the stored lookup table and derives it from scratch again.");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nominate(String question) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "nominate");
        a.put("question", question);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        Object raw = data.get("candidates");
        assertTrue(raw instanceof List,
            "PROOF OF LIFE: a nomination answers with candidates at all — without this"
                + " every assertion below could pass over a missing key: " + data.keySet());
        return (List<Map<String, Object>>) raw;
    }

    /**
     * A candidate's summary rides under {@code principle}, not {@code summary} — read off
     * the response rather than guessed, after the guess returned eight nulls and turned
     * both assertions red for a reason that had nothing to do with ranking.
     */
    private static List<String> summaries(List<Map<String, Object>> candidates) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            out.add(String.valueOf(c.get("principle")));
        }
        return out;
    }

    /**
     * THE CLAUSE. The pair is below the measured noise floor, so this passes only where
     * nothing is filtering on distance.
     */
    @Test
    @DisplayName("the sub-floor job is nominated for the task that names none of its words")
    void the_sub_floor_job_is_nominated() {
        recordTheCorpus();

        List<Map<String, Object>> candidates = nominate(TASK);

        assertEquals(ExperienceRetrieval.MAX_CANDIDATES, candidates.size(),
            "PROOF THE SHORTLIST BITES: the store holds more jobs than the cap, so a full"
                + " shortlist is what makes the assertion below a claim about RANKING"
                + " rather than about listing everything: " + summaries(candidates));

        assertTrue(summaries(candidates).contains(TARGET_JOB),
            "the job measured at 0.1061 against a 0.2027 noise floor must still be"
                + " offered — no cutoff over job texts can admit it, which is the whole"
                + " reason the map is a union and not a bar: " + summaries(candidates));
    }

    /**
     * THE CONTROL, and without it the case above passes against a nomination that returns
     * the same eight rows whatever it is asked.
     *
     * <p>It asserts only that the ORDER responds to the question — not that any particular
     * row wins, which would be a cosine claim this corpus cannot support and which is
     * exactly the kind of threshold reasoning Stage 0 measured away.</p>
     */
    @Test
    @DisplayName("the shortlist is not the same eight rows whatever it is asked")
    void the_shortlist_responds_to_the_question() {
        recordTheCorpus();

        List<String> forTheTask = summaries(nominate(TASK));
        List<String> forSomethingElse = summaries(nominate(
            "the operator has supplied the key shares and the secrets are still locked"));

        assertTrue(!forTheTask.equals(forSomethingElse),
            "two unrelated questions returning the identical shortlist in the identical"
                + " order would mean nothing scored anything: " + forTheTask);
    }
}
