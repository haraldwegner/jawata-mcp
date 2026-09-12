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

    /** An AREA — the code lane's other kind of row, and what the map lists first. */
    private void recordArea(String pkg, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary", summary);
        a.putArray("packages").add(pkg);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "the area must be recordable; got " + r.getError());
    }

    /** A LESSON, which lives in another lifecycle — the row the code lane must exclude. */
    private void recordLesson(String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", summary);
        a.put("situation", "when a forked test run reports fewer classes than were planned");
        a.put("verdict", "worked");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "the lesson must be recordable; got " + r.getError());
    }

    private List<Map<String, Object>> nominate(String question) {
        return nominate(question, null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nominate(String question, String lane) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "nominate");
        a.put("question", question);
        if (lane != null) {
            a.put("lane", lane);
        }
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

    /**
     * Sprint 28f Stage 8 D1 — THE MAP ASKS ONE LIFECYCLE.
     *
     * <p>An agent opening a task wants the areas and jobs describing what it is about to
     * touch. A lesson answering the same prose is a different KIND of answer wearing the
     * same shape, and the other lanes keep their own moments.</p>
     *
     * <p>The lesson here is written to be a strong competitor — it names the forked test
     * run and the classes it reached, so a nomination that ignored the lane would very
     * likely rank it well. That is what makes its absence evidence.</p>
     */
    @Test
    @DisplayName("the code lane answers with jobs and leaves the other lifecycles out")
    void the_code_lane_answers_with_jobs_alone() {
        recordTheCorpus();
        recordLesson("A forked run that reports fewer classes than it planned has lost"
            + " a shard, and the missing lines read as untested code.");

        List<String> everyLane = summaries(nominate(TASK));
        assertTrue(everyLane.contains("A forked run that reports fewer classes than it"
                + " planned has lost a shard, and the missing lines read as untested code."),
            "PROOF OF LIFE: unfiltered, the lesson is a strong enough competitor to be"
                + " nominated at all — without that its absence below proves nothing about"
                + " the filter: " + everyLane);

        List<String> codeOnly = summaries(nominate(TASK, "code"));
        assertTrue(codeOnly.contains(TARGET_JOB),
            "the sub-floor job still arrives — the lane narrows the pool and must not"
                + " introduce a bar: " + codeOnly);
        assertTrue(!codeOnly.contains("A forked run that reports fewer classes than it"
                + " planned has lost a shard, and the missing lines read as untested code."),
            "and the lesson does not, because it belongs to another lifecycle: " + codeOnly);
    }

    /**
     * THE CONTROL FOR THE FILTER ITSELF: a lane nobody recorded into answers with nothing.
     *
     * <p>Without it, "the lesson is absent" is satisfied by a filter that drops everything
     * and by one that drops the right rows, and the two are the same list when the thing
     * you are looking for is missing.</p>
     */
    @Test
    @DisplayName("a lane with no rows answers empty rather than falling back to all of them")
    void a_lane_with_no_rows_answers_empty() {
        recordTheCorpus();

        assertEquals(List.of(), summaries(nominate(TASK, "domain")),
            "nothing in this store is a domain fact, so the honest answer is nothing —"
                + " a filter that fell back to every lane would return the shortlist here");
    }

    /**
     * The architect seat's POPULATION QUERY, exactly as `seats/architect.md` instructs it:
     * a recall carrying the package under review and the code lane.
     *
     * <p>The entries are the ANCHORED answer. {@code analogies} beside them are nominees
     * ranked by resemblance to anything in the store, so asserting over those would pass
     * on a cue that matched nothing — which is the distinction this whole retrieval path
     * exists to keep.</p>
     */
    @SuppressWarnings("unchecked")
    private List<String> populationQuery(String pkg) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "recall");
        a.put("package", pkg);
        a.put("lane", "code");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        Object raw = data.get("entries");
        if (!(raw instanceof List)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) raw) {
            out.add(String.valueOf(e.get("summary")));
        }
        return out;
    }

    /**
     * D-SIX's DESCRIBED HALF, ON A PACKAGE HOLDING A KNOWN DUPLICATE.
     *
     * <p>The architect seat runs two detectors at DETECT: {@code re_derived_job}, which is
     * structural, and this one, which is what the store already says this code does. This
     * pins the second — the seat's own call, on a package where two members do one job.</p>
     *
     * <p>It exists because that call had never worked. The seat asked for
     * {@code lane="code", population=true}, which names no CUE: {@code population} is not a
     * parameter and {@code lane} is a filter over what the cues found, so the store's entire
     * answer was "No cue — provide symbol / package / operation / symptom." Half of D-SIX's
     * detection was one round-trip to a refusal, and a seat's instructions are prose, so
     * nothing compiled and nothing complained.</p>
     *
     * <p>The companion guard lives in studio's conductor, over the seat TEXT, and asks
     * whether an instructed recall names a cue at all. This one asks the other half: that
     * the call, well-formed, reaches the duplicate.</p>
     */
    @Test
    @DisplayName("the population query answers rows anchored in the package it is given")
    void the_population_query_answers_its_package() {
        recordTheCorpus();
        final String secondImplementation =
            "Reads a source file and answers the syntax tree for it.";
        recordJob("com.example.PlanTool#buildTree", secondImplementation);

        List<String> anchored = populationQuery("com.example");

        assertTrue(anchored.contains(secondImplementation),
            "the call the seat instructs must reach the code lane's rows for this package"
                + " at all — this is the half that was false while it named no cue: "
                + anchored);
    }

    /**
     * THE CLAUSE IT CANNOT MEET, PINNED AS A DEFECT RATHER THAN LEFT AS A PASS.
     *
     * <p>C8 asks that "the architect seat run on a package holding a known duplicate names
     * it from the population query". Building that test MEASURED why it cannot: a package
     * recall answers at most FIVE anchored rows, and {@code limit} does not lift it —
     * probed at {@code limit=50}, which returned the same five. So the seat's "population
     * query" does not return a population, it returns a ranked five.</p>
     *
     * <p>What that costs D-SIX is the whole point rather than a rounding error. The fixture
     * below records fourteen jobs in one package, two of which are ONE JOB written twice —
     * and the five that come back carry the new implementation and NOT its partner. The
     * seat would read that answer and see nothing to refuse, on a package whose duplicate
     * it was pointed at. A detector that reports the second half of a pair and drops the
     * first cannot find a pair.</p>
     *
     * <p>This test ASSERTS the defect, so it is visible and so it turns RED the day the cap
     * moves — at which point whoever moved it re-reads this and C8's clause becomes
     * meetable. Raising or paging that cap is a change to a published retrieval surface and
     * is the architect's call, not a mid-stage edit at a stage's close.</p>
     */
    @Test
    @DisplayName("a package recall caps at five, so a duplicate's two halves are split")
    void the_population_query_is_capped_below_its_package() {
        recordTheCorpus();
        final String firstImplementation =
            "Turns a source file into a tree the rest of the tool can walk.";
        final String secondImplementation =
            "Reads a source file and answers the syntax tree for it.";
        recordJob("com.example.PlanTool#buildTree", secondImplementation);

        List<String> anchored = populationQuery("com.example");

        assertEquals(5, anchored.size(),
            "fourteen jobs are anchored in this package and five come back. If this is no"
                + " longer 5, the cap moved: re-read this test, because C8's clause about"
                + " naming a duplicate from the population query may now be meetable: "
                + anchored);
        assertTrue(anchored.contains(secondImplementation)
                && !anchored.contains(firstImplementation),
            "and the pair is SPLIT — the second implementation is returned and the job it"
                + " re-derives is not. This is the defect, stated as the failure the seat"
                + " would actually suffer: it is shown one half and has nothing to refuse: "
                + anchored);
    }

    /**
     * THE CONTROL, and without it the case above passes against a recall that answers with
     * the whole store whatever package it is handed.
     */
    @Test
    @DisplayName("a package nobody recorded into answers with nothing anchored")
    void a_package_with_no_rows_anchors_nothing() {
        recordTheCorpus();

        assertEquals(List.of(), populationQuery("com.elsewhere.untouched"),
            "nothing is anchored in that package, so the anchored answer is empty. A recall"
                + " that ignored the cue would hand back the corpus here, and then the"
                + " duplicate above would have arrived by default rather than by being found");
    }

    /** The text form the hook emits verbatim — Stage 8 D1's `JAWATA MAP —` block. */
    private String mapText(String question) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "nominate");
        a.put("question", question);
        a.put("lane", "code");
        a.put("format", "text");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        return String.valueOf(r.getData());
    }

    /**
     * THE MAP IS A DIFFERENT BLOCK FROM THE SHORTLIST, and says which rows are which.
     *
     * <p>An area says what a package is for and a job says what one member does; printed as
     * one ranked list they read as a pile. And the block must not read as {@code NOMINEES}:
     * a nomination is a shortlist to judge, a map is an orientation written ABOUT this code
     * — confusing them invites weighing a description of your own codebase the way you weigh
     * a borrowed pattern.</p>
     */
    @Test
    @DisplayName("the map groups areas and jobs and anchors each job to its member")
    void the_map_groups_areas_and_jobs() {
        recordTheCorpus();
        recordArea("com.example",
            "The lane between an agent's questions and what this machine already learned.");

        String map = mapText(TASK);

        assertTrue(map.startsWith("JAWATA MAP —"),
            "the hook emits this verbatim, so the heading is the contract: " + map);
        assertTrue(map.contains("  area: The lane between an agent's questions"),
            "an area is labelled as one: " + map);
        assertTrue(map.contains("  job:  " + TARGET_SYMBOL + " — " + TARGET_JOB),
            "and a job carries the member it explains — without the anchor the map prints"
                + " sentences with nothing to attach them to: " + map);
        assertTrue(map.indexOf("  area:") < map.indexOf("  job:"),
            "areas first: a reader wants the ground before the detail: " + map);
    }

    /**
     * NOTHING IS AN ANSWER AND IS SAID OUT LOUD.
     *
     * <p>A blank would leave a reader unable to tell "the store has nothing about this code"
     * from "the lookup did not run", which is the distinction this whole retrieval path
     * exists to keep.</p>
     */
    @Test
    @DisplayName("an empty map says so rather than rendering nothing")
    void an_empty_map_says_so() {
        // No corpus at all: the code lane is empty.
        String map = mapText(TASK);

        assertTrue(map.startsWith("JAWATA MAP — nothing"),
            "the absence is SPOKEN: " + map);
        assertTrue(map.contains("not a failed lookup"),
            "and it names which of the two it is, which is the only thing a reader cannot"
                + " work out for themselves: " + map);
    }
}
