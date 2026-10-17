package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S22 C22 — what the first audit found no test standing behind.
 *
 * <p>Three claims, each pinned where a mutation of the production code turns it red:</p>
 * <ul>
 *   <li><b>The wire.</b> Every real hook sends {@code lanes} as an ARRAY; every earlier test sent
 *       {@code lane}. Reading the array under another key would have refused every hook call in
 *       production while the whole engine suite stayed green.</li>
 *   <li><b>The meaning scan is scoped.</b> The Java filter that used to remove other lanes' rows
 *       from the analogy pool is gone, so the vector scan is the only barrier.</li>
 *   <li><b>The cut is per lane, on every road in.</b> Including {@code recallAll}, which the hook
 *       reaches through the {@code symbols} and {@code symptoms} arrays.</li>
 * </ul>
 */
class TheLaneIsChosenBeforeEverySourceTest {

    private static final String SYMBOL = "com.example.lanes.Ledger#amend";

    private H2ExperienceStore store;
    private ObjectMapper json;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        json = new ObjectMapper();
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private String put(String type, String summary, String anchor) {
        SymbolFact.Builder f = SymbolFact.of(type, summary, Confidence.MEDIUM);
        if (anchor != null) {
            f.symbol(anchor);
        }
        ExperienceEntry.Builder e = ExperienceEntry.of(f.build()).status(ExperienceEntry.ACCEPTED);
        if (type.equals("lesson")) {
            e.situation("when the ledger is amended twice").verdict("worked");
        }
        return store.put(e.build());
    }

    private ObjectNode recallArgs() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "recall");
        a.put("symbol", SYMBOL);
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> answer(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "the call must be answered: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    private String refusal(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "this call must be refused");
        return String.valueOf(r.getError());
    }

    private static String text(Map<String, Object> answer) {
        return String.valueOf(answer);
    }

    // ---- the wire ------------------------------------------------------------------------

    @Test
    void theLanesArrayIsReadAndNarrowsTheAnswer() {
        put("lesson", "quokka lantern restocked before inspection", SYMBOL);
        put("domain_fact", "pangolin ledger settles at dusk", SYMBOL);

        ObjectNode a = recallArgs();
        ArrayNode lanes = a.putArray("lanes");
        lanes.add("experience");
        String onlyLessons = text(answer(a));
        assertTrue(onlyLessons.contains("quokka"), () -> "PROOF OF LIFE: the lesson arrives: " + onlyLessons);
        assertFalse(onlyLessons.contains("pangolin"),
            () -> "the domain fact is another lane's and must not arrive: " + onlyLessons);

        lanes.add("domain");
        String both = text(answer(a));
        assertTrue(both.contains("quokka") && both.contains("pangolin"),
            () -> "two lanes named, two lanes answer: " + both);
    }

    @Test
    void hisWordsAreAcceptedAsNames() {
        put("lesson", "quokka lantern restocked before inspection", SYMBOL);
        put("domain_fact", "pangolin ledger settles at dusk", SYMBOL);

        ObjectNode lessons = recallArgs();
        lessons.put("lane", "lessons");
        String l = text(answer(lessons));
        assertTrue(l.contains("quokka") && !l.contains("pangolin"), () -> "lessons = experience: " + l);

        ObjectNode business = recallArgs();
        business.put("lane", "business");
        String b = text(answer(business));
        assertTrue(b.contains("pangolin") && !b.contains("quokka"), () -> "business = domain: " + b);
    }

    @Test
    void allNamesEveryLaneAndAnUnknownNameIsRefusedWithTheList() {
        put("lesson", "quokka lantern restocked before inspection", SYMBOL);
        put("domain_fact", "pangolin ledger settles at dusk", SYMBOL);

        ObjectNode all = recallArgs();
        all.put("lane", "all");
        String everything = text(answer(all));
        assertTrue(everything.contains("quokka") && everything.contains("pangolin"), everything);

        ObjectNode bad = recallArgs();
        bad.put("lane", "experiance");
        String said = refusal(bad);
        assertTrue(said.contains("'experiance' is not a lane"), said);
        for (String lane : List.of("experience", "patterns", "domain", "code", "rules")) {
            assertTrue(said.contains(lane), () -> "the refusal lists " + lane + ": " + said);
        }

        ObjectNode none = recallArgs();
        none.putArray("lanes");
        assertTrue(refusal(none).contains("must name at least one lane"), "an empty list names none");
    }

    // ---- the meaning scan ---------------------------------------------------------------

    @Test
    void theMeaningScanNeverReachesARowOutsideTheScope() {
        EmbeddingService svc = EmbeddingService.shared();
        Assumptions.assumeTrue(svc.available(),
            "embedder unavailable — the meaning-scan scope test did NOT run: " + svc.unavailableReason());
        ExperienceRetrieval semantic =
            new ExperienceRetrieval(store, () -> null, new EmbeddingIndex(store, svc));

        String lesson = put("lesson",
            "a forked test run that reports fewer classes than it planned has lost a shard", null);
        String area = store.put(ExperienceEntry.of(
                SymbolFact.of("area", "this package forks test runs and reports how many "
                        + "classes the forked run planned and actually reached", Confidence.MEDIUM)
                    .scope(List.of("com.example.runner"), List.of()).build())
            .status(ExperienceEntry.ACCEPTED).build());

        RecallQuery cue = new RecallQuery(null, null, null,
            "the forked run reports fewer classes than it planned", null,
            LaneScope.of(KnowledgeLane.CODE));
        Set<String> inCode = idsOf(semantic.recall(cue));
        assertTrue(inCode.contains(area),
            () -> "PROOF OF LIFE: the area row is reachable by meaning when its lane is named: " + inCode);

        RecallQuery lessonsOnly = new RecallQuery(null, null, null,
            "the forked run reports fewer classes than it planned", null,
            LaneScope.of(KnowledgeLane.EXPERIENCE));
        Set<String> got = idsOf(semantic.recall(lessonsOnly));
        assertTrue(got.contains(lesson), () -> "the lesson is the lane's own answer: " + got);
        assertFalse(got.contains(area),
            () -> "THE VECTOR SCAN IS THE ONLY BARRIER NOW — the Java filter that used to remove "
                + "another lane's row from the analogy pool is gone, so a scan that stops "
                + "skipping rows of other lanes puts this area row in an experience-only answer: "
                + got);

        Set<String> nominated = candidateIds(semantic.nominate(
            "a forked run reports fewer classes than it planned",
            LaneScope.of(KnowledgeLane.EXPERIENCE), 15_000));
        assertFalse(nominated.contains(area), () -> "and the nomination's meaning scan too: " + nominated);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> idsOf(Map<String, Object> answer) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (String key : List.of("entries", "analogies")) {
            if (answer.get(key) instanceof List<?> rows) {
                for (Object o : rows) {
                    ids.add(String.valueOf(((Map<String, Object>) o).get("id")));
                }
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> candidateIds(Map<String, Object> answer) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        if (answer.get("candidates") instanceof List<?> rows) {
            for (Object o : rows) {
                ids.add(String.valueOf(((Map<String, Object>) o).get("id")));
            }
        }
        return ids;
    }

    // ---- the cut is per lane --------------------------------------------------------------

    private void sevenLessonsAndSevenJobsOnOneSymbol() {
        for (int i = 0; i < 7; i++) {
            put("lesson", "lesson number " + i + " about amending the ledger twice", SYMBOL);
            put("job", "job number " + i + " amends one posting of the ledger", SYMBOL);
        }
    }

    @Test
    void eachLaneIsCutToItsOwnLimitNotOneLimitAcrossThem() {
        sevenLessonsAndSevenJobsOnOneSymbol();
        ObjectNode a = recallArgs();
        a.put("lane", "experience,code");
        Map<String, Object> answer = answer(a);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) answer.get("entries");
        long lessons = entries.stream().filter(e -> "experience".equals(e.get("lane"))).count();
        long jobs = entries.stream().filter(e -> "code".equals(e.get("lane"))).count();
        assertEquals(5, lessons, "five lessons, the lane's own limit");
        assertEquals(5, jobs,
            () -> "and five jobs: a single limit of five over both would have given the lessons "
                + "all of them. Entries were " + entries.size());
        assertNotNull(answer.get("capped_from"), "and the cut says it was a cut");
    }

    @Test
    void theHooksRoadInThroughSymbolsAndSymptomsIsCutPerLaneToo() {
        sevenLessonsAndSevenJobsOnOneSymbol();
        ObjectNode a = json.createObjectNode();
        a.put("kind", "recall");
        a.putArray("lanes").add("experience").add("code");
        a.putArray("symbols").add(SYMBOL);
        Map<String, Object> answer = answer(a);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) answer.get("entries");
        List<Object> lanes = new ArrayList<>();
        entries.forEach(e -> lanes.add(e.get("lane")));
        assertTrue(lanes.contains("experience") && lanes.contains("code"),
            () -> "recallAll stopped at one limit over the concatenated cues, and the lane that "
                + "answers first filled the page: " + lanes);
    }
}
