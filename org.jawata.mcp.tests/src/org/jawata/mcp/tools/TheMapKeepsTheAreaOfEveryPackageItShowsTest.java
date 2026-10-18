package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.ExperienceRetrieval;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.KnowledgeLane;
import org.jawata.mcp.knowledge.LaneScope;
import org.jawata.mcp.knowledge.SymbolFact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * REL44-34 — the map a task opens with carries the area of every package it shows a member of,
 * whatever the ranking did with the area row.
 *
 * <p>The map exists to give "the ground before the detail": what a package is for, then the
 * members in it. With no embedder the ranking is on words alone, an area's text shares few words
 * with a task, and the shortlist holds eight rows, so whether the area was shortlisted was decided
 * by row-id order. The cure adds no score bar: for each package that has a member on the shortlist,
 * that package's area row (if the store holds one) is listed too.</p>
 *
 * <p>These cases use the keyword-only retrieval, so they are about the degraded path whether or not
 * the embedder is present in the run. The question is built from words the member rows contain and
 * the area row does not, so the area scores zero and ranks below every member — the old behaviour,
 * on purpose, not by luck of id order.</p>
 */
class TheMapKeepsTheAreaOfEveryPackageItShowsTest {

    /** Words that occur in twelve of the member rows below and in no area row. */
    private static final String QUESTION =
        "marks answers hands moves chooses drops turns pushes ends waits opens throws";

    private static final String AREA_TEXT =
        "The lane between an agent's questions and what this machine already learned.";

    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ExperienceRetrieval retrieval;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        tool = new ExperienceTool(() -> null, store);
        retrieval = ExperienceRetrieval.keywordOnly(store, () -> null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void job(String symbol, String summary) {
        store.put(ExperienceEntry.of(
            SymbolFact.of("job", summary, Confidence.MEDIUM).symbol(symbol).build()).build());
    }

    private void area(String pkg, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary", summary);
        a.putArray("packages").add(pkg);
        assertTrue(tool.execute(a).isSuccess(), "fixture area refused");
    }

    /** Twelve members in com.example whose texts carry the question's words. */
    private void theMembers() {
        job("com.example.Ledger#recordFailure", "Marks the breaker as having just seen an error.");
        job("com.example.Gauge#scale", "Answers how many units one screen pixel stands for.");
        job("com.example.Inbox#drain", "Hands out every message that arrived since the last ask.");
        job("com.example.Clock#advance", "Moves the simulated time forward by a named amount.");
        job("com.example.Router#route", "Chooses which handler answers a request from its path.");
        job("com.example.Cache#evict", "Drops the entry that has gone longest without a read.");
        job("com.example.Parser#parse", "Turns a source file into a tree the tool can walk.");
        job("com.example.Writer#flush", "Pushes everything buffered out to disk first.");
        job("com.example.Session#expire", "Ends a session whose last activity is too old.");
        job("com.example.Retry#backoff", "Waits longer before each attempt on a struggling server.");
        job("com.example.Vault#unseal", "Opens the secret store once enough key shares arrive.");
        job("com.example.Index#rebuild", "Throws away the stored lookup table and derives it again.");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> candidates(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("candidates");
    }

    private long countOfType(List<Map<String, Object>> cs, String type) {
        return cs.stream().filter(c -> type.equals(c.get("type"))).count();
    }

    @Test
    void theAreaOfAShownPackageIsListedEvenWhenItsWordsDidNotRankIt() {
        theMembers();
        area("com.example", AREA_TEXT);

        Map<String, Object> answer = retrieval.nominate(
            QUESTION, LaneScope.of(KnowledgeLane.CODE), 15_000L);
        List<Map<String, Object>> cs = candidates(answer);

        assertEquals(ExperienceRetrieval.MAX_CANDIDATES, countOfType(cs, "job"),
            "PROOF OF LIFE: the shortlist is eight ranked members, so the area was not among"
                + " the ranked rows (it scores zero against these words): " + cs);
        assertEquals(1, countOfType(cs, "area"),
            "the area of the package whose members are shown must be listed: " + cs);
        String map = ExperienceRetrieval.renderMap(answer);
        assertTrue(map.indexOf("  area: The lane between") >= 0
                && map.indexOf("  area:") < map.indexOf("  job:"),
            "and the map puts it first, before the members: " + map);
    }

    @Test
    void anAreaOfAPackageWithNoShownMemberIsNotAdded() {
        theMembers();
        area("com.example", AREA_TEXT);
        job("com.unshown.Thing#idle", "Sits there and is mentioned by nothing in the question.");
        area("com.unshown", "A package none of whose members the question reaches.");

        List<Map<String, Object>> cs = candidates(retrieval.nominate(
            QUESTION, LaneScope.of(KnowledgeLane.CODE), 15_000L));

        assertEquals(1, countOfType(cs, "area"),
            "only the area of a package with a shown member is added; adding every area would"
                + " be a pile, not a map: " + cs);
        assertFalse(cs.stream().anyMatch(c -> String.valueOf(c.get("principle"))
                .contains("none of whose members")),
            "CONTROL: the other package's area stays out: " + cs);
    }

    @Test
    void anAreaThatRankedOnItsOwnIsListedOnce() {
        theMembers();
        area("com.example", "Marks answers hands moves chooses drops turns pushes ends waits"
            + " opens throws: the words of the question themselves.");

        List<Map<String, Object>> cs = candidates(retrieval.nominate(
            QUESTION, LaneScope.of(KnowledgeLane.CODE), 15_000L));

        assertEquals(1, countOfType(cs, "area"),
            "an area the ranking already chose is not listed a second time: " + cs);
    }

    /**
     * THE BOUND: one area per distinct shown package, so eight shown members in eight packages give
     * eight added areas and sixteen rows. Ten packages each hold one member and one area, so the two
     * packages whose member is not on the shortlist must not add theirs, and every area that is
     * listed belongs to a package a listed member stands in.
     */
    @Test
    void theBoundIsOneAreaPerShownPackageSoAtMostSixteenRows() {
        String[] texts = {
            "Marks the breaker as having just seen an error.",
            "Answers how many units one screen pixel stands for.",
            "Hands out every message that arrived since the last ask.",
            "Moves the simulated time forward by a named amount.",
            "Chooses which handler answers a request from its path.",
            "Drops the entry that has gone longest without a read.",
            "Turns a source file into a tree the tool can walk.",
            "Pushes everything buffered out to disk first.",
            "Ends a session whose last activity is too old.",
            "Waits longer before each attempt on a struggling server."};
        for (int i = 0; i < texts.length; i++) {
            job("com.pkg" + i + ".Thing#act", texts[i]);
            area("com.pkg" + i, "What package number " + i + " is for, in its own words.");
        }

        List<Map<String, Object>> cs = candidates(retrieval.nominate(
            QUESTION, LaneScope.of(KnowledgeLane.CODE), 15_000L));

        assertEquals(ExperienceRetrieval.MAX_CANDIDATES, countOfType(cs, "job"), "the eight ranked");
        assertEquals(ExperienceRetrieval.MAX_CANDIDATES, countOfType(cs, "area"),
            "one area per shown package, eight packages: " + cs);
        List<String> shownPackages = cs.stream().filter(c -> "job".equals(c.get("type")))
            .map(c -> String.valueOf(c.get("symbol")).replaceAll("\\.Thing#act$", "")).toList();
        for (Map<String, Object> area : cs.stream().filter(c -> "area".equals(c.get("type"))).toList()) {
            String text = String.valueOf(area.get("principle"));
            String number = text.replaceAll("\\D+", "");
            assertTrue(shownPackages.contains("com.pkg" + number),
                "every listed area belongs to a package a listed member stands in: " + text);
        }
    }

    @Test
    void aPackageWithNoAreaAddsNothingAndFailsNothing() {
        theMembers();

        List<Map<String, Object>> cs = candidates(retrieval.nominate(
            QUESTION, LaneScope.of(KnowledgeLane.CODE), 15_000L));

        assertEquals(0, countOfType(cs, "area"), "nothing to add, nothing invented: " + cs);
        assertEquals(ExperienceRetrieval.MAX_CANDIDATES, countOfType(cs, "job"));
    }
}
