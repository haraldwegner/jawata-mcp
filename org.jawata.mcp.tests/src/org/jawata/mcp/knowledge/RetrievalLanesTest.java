package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S22, D43 — <b>the lane is chosen before the search</b>, so a lane is searched and
 * ranked on its own and nothing is filtered afterwards.
 *
 * <p>Written FIRST (architecture step M1) and red on the code that had the lane applied after the
 * search. Each case is driven through the published tool, because the claim is about what a
 * caller receives, and uses the wire arguments ({@code kind}, {@code symptom}, {@code lane}) so
 * the cases stay valid while the retrieval object's own signatures change under them.</p>
 *
 * <h2>The fixture is built so only a lane can separate the rows</h2>
 *
 * <p>Forty unrelated code rows keep every cue word under the half-of-the-corpus line the word
 * index needs to call a word discriminating; twelve code rows carry the whole cue; one lesson
 * carries only part of it. Under today's retrieval the cut to the eleven best is taken over the
 * whole store, so the lesson ranks thirteenth and is gone before the lane is looked at.</p>
 */
class RetrievalLanesTest {

    /** The words every one of the twelve code rows carries. */
    private static final String CUE = "ledger totals drift amendment";

    private static final String LESSON_SUMMARY =
        "When ledger totals shift, reconcile with the broker before trading";

    private H2ExperienceStore store;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        objectMapper = new ObjectMapper();

        // Forty code rows about nothing the cue names: the corpus the cue words must stay rare in.
        for (int i = 0; i < 40; i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("job", "Parses the " + word(i) + " header of an inbound feed",
                        Confidence.MEDIUM)
                        .symbol("com.example.filler.F" + i + "#parse").build())
                .status(ExperienceEntry.ACCEPTED).build());
        }
        // Twelve code rows that carry the WHOLE cue: they outrank the lesson on words.
        for (int i = 0; i < 12; i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("job", "Reports " + CUE + " for account " + word(i),
                        Confidence.MEDIUM)
                        .symbol("com.example.ledger.L" + i + "#report").build())
                .status(ExperienceEntry.ACCEPTED).build());
        }
        // The lesson carries two of the four cue words, so the keyword gate does not admit it and
        // it can reach an answer only by being ranked.
        store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", LESSON_SUMMARY, Confidence.MEDIUM).build())
            .status(ExperienceEntry.ACCEPTED)
            .situation("when the ledger and the broker disagree")
            .verdict("worked")
            .build());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void aLessonRankedBehindTheCodeRowsStillArrives() {
        Map<String, Object> answer = recall("symptom", CUE, "experience");

        assertTrue(textOf(answer).contains(LESSON_SUMMARY),
            () -> "a lessons-only search must rank within the lessons lane: the lesson shares "
                + "two of the cue's words and is the lane's best row, yet twelve code rows "
                + "outrank it across the whole store and the cut to eleven drops it before the "
                + "lane is applied: " + textOf(answer));
    }

    @Test
    void aWordCommonInCodeIsWeighedByTheLessonsOwnRarity() {
        // 35 further code rows carry the word "settlement": over the whole store it sits in more
        // than half the rows and the word index calls it non-discriminating, so a lesson that
        // shares only that word scores zero. Over the five lessons it is rare.
        for (int i = 0; i < 35; i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("job", "Settlement step " + word(i) + " of the close",
                        Confidence.MEDIUM)
                        .symbol("com.example.settle.S" + i + "#close").build())
                .status(ExperienceEntry.ACCEPTED).build());
        }
        String settlementLesson = "A settlement that fails is retried the next business day";
        store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", settlementLesson, Confidence.MEDIUM).build())
            .status(ExperienceEntry.ACCEPTED)
            .situation("when a settlement fails overnight")
            .verdict("worked")
            .build());
        for (int i = 0; i < 4; i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "Unrelated lesson number " + word(i) + " about caches",
                        Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED)
                .situation("when a cache is cold " + word(i))
                .verdict("worked")
                .build());
        }

        // The second word appears in no row, so the keyword gate admits nothing.
        Map<String, Object> answer = recall("symptom", "settlement zzqqxx", "experience");

        assertTrue(textOf(answer).contains(settlementLesson),
            () -> "rarity must be counted over the lane that is searched, not over the store: "
                + textOf(answer));
    }

    @Test
    void aPatternsSearchReturnsNoLessonsAndALessonsSearchNoPatterns() {
        String pattern = "Reconcile pattern: keep two ledgers equal by replaying their differences";
        store.put(ExperienceEntry.of(
                SymbolFact.of("reference", pattern, Confidence.MEDIUM).build())
            .status(ExperienceEntry.ACCEPTED)
            .provenanceKind(CatalogueManifest.PROVENANCE)
            .build());

        Map<String, Object> patterns = recall("symptom", "reconcile ledger", "patterns");
        assertTrue(textOf(patterns).contains(pattern),
            () -> "the patterns lane must hold the catalogue's patterns: " + textOf(patterns));
        assertFalse(textOf(patterns).contains(LESSON_SUMMARY),
            () -> "and no lesson: " + textOf(patterns));

        Map<String, Object> lessons = recall("symptom", "reconcile ledger", "experience");
        assertTrue(textOf(lessons).contains(LESSON_SUMMARY),
            () -> "the lessons lane must still answer with the lesson: " + textOf(lessons));
        assertFalse(textOf(lessons).contains(pattern),
            () -> "and no pattern: " + textOf(lessons));
    }

    @Test
    void aCallThatNamesNoLaneIsRefusedAndTheRefusalListsTheLanes() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "recall");
        args.put("symptom", CUE);

        ToolResponse r = new ExperienceTool(() -> null, store).execute(args);

        assertFalse(r.isSuccess(), "a call with no lane must be refused, not given a default");
        String said = String.valueOf(r.getError());
        for (String lane : List.of("experience", "patterns", "code", "domain", "rules")) {
            assertTrue(said.contains(lane),
                () -> "the refusal must list the lanes a caller can ask for, and '" + lane
                    + "' is missing: " + said);
        }
    }

    @Test
    void aLessonsOnlySearchSaysHowManyRowsItSearched() {
        Map<String, Object> answer = recall("symptom", CUE, "experience");

        Object scanned = answer.get("scanned");
        assertNotNull(scanned, () -> "the answer must say how many rows each lane searched: "
            + answer.keySet());
        assertTrue(String.valueOf(scanned).contains("1"),
            () -> "the lessons lane holds one lesson, not the store's 53 rows: " + scanned);
    }

    private Map<String, Object> recall(String cueKey, String cue, String lane) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "recall");
        args.put(cueKey, cue);
        args.put("lane", lane);
        ToolResponse r = new ExperienceTool(() -> null, store).execute(args);
        assertTrue(r.isSuccess(), () -> "the store must answer at all: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data, "a successful recall carries data");
        return data;
    }

    /** The whole answer as text, so an assertion about a row reads what a caller receives. */
    private static String textOf(Map<String, Object> answer) {
        return String.valueOf(answer);
    }

    private static String word(int i) {
        List<String> names = new ArrayList<>(List.of("alpha", "bravo", "charlie", "delta", "echo",
            "foxtrot", "golf", "hotel", "india", "juliet", "kilo", "lima", "mike", "november",
            "oscar", "papa", "quebec", "romeo", "sierra", "tango", "uniform", "victor", "whiskey",
            "xray", "yankee", "zulu"));
        return names.get(i % names.size()) + (i / names.size());
    }
}
