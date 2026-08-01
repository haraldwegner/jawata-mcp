package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 (v3.6.4) — {@code summary} must do what it says, on every call shape.
 *
 * <p>Found by working v3.6.3 in anger (Cursor, 2026-07-29). Two defects, both the same
 * shape: a response that does not answer the request it was given.</p>
 *
 * <ul>
 *   <li>{@code summary=true} with a single {@code kind} was accepted and silently dropped —
 *       the caller asked for counts and received the full findings array, with nothing in the
 *       response saying the flag had been ignored.</li>
 *   <li>The summary that DID work was not consumable: it correctly omitted {@code findings}
 *       and then returned the whole {@code conflicts} list — measured at 295 entries and
 *       ~111,000 characters on a real project, past an MCP client's result limit. The one
 *       shape built so a broad sweep could be read was the shape that could not be read.</li>
 * </ul>
 */
class FindQualityIssueSummaryTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;
    private FindQualityIssueTool tool;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        mapper = new ObjectMapper();
        tool = new FindQualityIssueTool(() -> service);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "got: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    /**
     * The defect itself: a single kind must honour {@code summary}. Before the fix the
     * single-kind branch returned the detector's response untouched, so this assertion
     * fails — the findings array is present.
     */
    @Test
    @DisplayName("summary=true on a SINGLE kind drops the findings array")
    void summaryIsHonouredForASingleKind() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "long_method");
        args.put("summary", true);
        Map<String, Object> data = run(args);

        assertFalse(
            data.containsKey("findings"),
            "summary=true asked for counts, not findings — a silently ignored parameter is "
                + "still a wrong answer to the request: " + data.keySet());
        assertEquals(Boolean.TRUE, data.get("summary"), "the response declares it is a summary");
        assertNotNull(data.get("byKind"), "counts-by-kind is what summary returns instead");
    }

    /**
     * And the counts it returns must be the TRUE totals, not the size of a page — a summary
     * that under-reports is worse than no summary, because it reads like a complete answer.
     */
    @Test
    @DisplayName("a single-kind summary reports the true finding total, not a page size")
    void singleKindSummaryReportsTrueTotals() {
        ObjectNode full = mapper.createObjectNode();
        full.put("kind", "long_method");
        Object fullFindings = run(full).get("findings");
        int trueTotal = fullFindings instanceof List<?> l ? l.size() : 0;

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "long_method");
        args.put("summary", true);
        Map<String, Object> data = run(args);

        int counted = ((Map<String, Integer>) data.get("byKind"))
            .values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(trueTotal, counted,
            "byKind must count every finding, not the ones that would have fit on a page");
    }

    /**
     * The default shape for a single kind is UNCHANGED: no caller silently starts losing
     * findings because bounding was extended to this path.
     */
    @Test
    @DisplayName("a single kind without limit/offset still returns every finding")
    void singleKindWithoutPagingIsUnchanged() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "long_method");
        Map<String, Object> data = run(args);

        assertTrue(data.containsKey("findings"), "the default shape still carries findings");
        assertFalse(
            Boolean.TRUE.equals(data.get("truncated")),
            "nothing was capped: the caller did not ask for paging — " + data.keySet());
    }

    /**
     * The conflicts list is bounded under summary, and says so. Without the fix it is
     * returned whole, which is how the summary shape came to exceed a client's result limit.
     */
    @Test
    @DisplayName("summary caps the conflicts list by `limit` and keeps the true count")
    void summaryBoundsTheConflictsList() {
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "fowler");
        args.put("summary", true);
        args.put("limit", 2);
        Map<String, Object> data = run(args);

        assertFalse(data.containsKey("findings"), "summary omits findings");
        Object conflicts = data.get("conflicts");
        assertTrue(conflicts instanceof List<?>, "conflicts is a list: " + conflicts);
        List<?> page = (List<?>) conflicts;
        assertTrue(page.size() <= 2,
            "the conflicts list obeys `limit` — an unbounded list is what made the summary "
                + "unreadable on a real project: got " + page.size());

        int trueCount = ((Number) data.get("conflictCount")).intValue();
        assertTrue(trueCount >= page.size(),
            "conflictCount is the TRUE total, never the page size: " + trueCount
                + " vs " + page.size());
        if (trueCount > page.size()) {
            assertEquals(Boolean.TRUE, data.get("conflictsTruncated"),
                "a capped list must declare itself capped, never read as complete: " + data.keySet());
        }
    }

    /**
     * The honesty fields survive the summary. They are the reason a zero finding count can
     * be trusted, so a shape that drops them would turn "nothing found" back into a claim
     * the response cannot support.
     */
    @Test
    @DisplayName("summary keeps the scan-coverage fields")
    void summaryKeepsTheCoverageFields() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "long_method");
        args.put("summary", true);
        Map<String, Object> data = run(args);

        assertNotNull(data.get("filesListed"), "coverage must survive: " + data.keySet());
        assertNotNull(data.get("filesExamined"), "coverage must survive: " + data.keySet());
    }

    /**
     * Sprint 28 — the v3.6.4 fix covered ONE of SIX result shapes.
     *
     * <p>{@code boundResponse} read {@code findings} and nothing else, so every detector that
     * names its list differently was still handed the full array with the flag silently
     * dropped: {@code unusedItems} ({@code unused}), {@code violations} ({@code naming},
     * {@code large_classes}), {@code issues} ({@code bugs}) and {@code cycles}
     * ({@code circular_deps}). Fixing one shape and declaring the defect closed is the same
     * error a size smaller — which is why this is parameterised over every kind rather than
     * written once for the one that was reported.</p>
     *
     * <p>Each of these fails on the unfixed build: the response still carries its result
     * list.</p>
     */
    /**
     * Metadata lists that ride alongside a result list and are NOT the detector's answer:
     * {@code conflicts} is retained by the summary on purpose, {@code affectedPackages} is a
     * companion of {@code cycles}.
     */
    private static final java.util.Set<String> NON_RESULT_LISTS =
        java.util.Set.of("conflicts", "affectedPackages", "candidates");

    /**
     * Derives the detector's result-list key FROM ITS OWN RESPONSE.
     *
     * <p>Deliberately independent of the production list. The first version of this test
     * hard-coded the mapping from a text search and asserted {@code large_classes} returns
     * {@code violations} — that literal is a nested field, and the real key is
     * {@code largeClasses}. A test that repeats the production list's assumption cannot
     * catch the production list being wrong.</p>
     */
    private String resultKeyOf(Map<String, Object> data) {
        return data.entrySet().stream()
            .filter(e -> e.getValue() instanceof List<?>)
            .map(Map.Entry::getKey)
            .filter(k -> !NON_RESULT_LISTS.contains(k))
            .findFirst()
            .orElse(null);
    }

    @ParameterizedTest(name = "summary=true is honoured for kind={0}")
    @ValueSource(strings = {"long_method", "unused", "naming", "large_classes", "bugs", "circular_deps"})
    @DisplayName("summary=true drops the result list for EVERY detector shape, not just findings")
    void summaryIsHonouredForEveryResultShape(String kind) {
        ObjectNode plain = mapper.createObjectNode();
        plain.put("kind", kind);
        Map<String, Object> unbounded = run(plain);

        String listKey = resultKeyOf(unbounded);
        // Guard the guard: a kind that returns no list at all would make every assertion
        // below pass vacuously, and a vacuous green is what let the original defect survive.
        assertNotNull(listKey,
            kind + " returned no result list, so this test would assert nothing: "
                + unbounded.keySet());

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        args.put("summary", true);
        Map<String, Object> data = run(args);

        assertFalse(data.containsKey(listKey),
            "summary=true asked for counts; `" + listKey + "` was returned anyway — a silently "
                + "ignored parameter is still a wrong answer: " + data.keySet());
        assertEquals(Boolean.TRUE, data.get("summary"), "the response declares it is a summary");
        assertEquals(listKey, data.get("resultKey"),
            "the summary names WHICH list it summarised, so the caller need not guess");
        assertNotNull(data.get("count"),
            "a summary always carries the total — byKind is empty for shapes whose entries have "
                + "no `kind` field, and a summary whose only number is an empty map answers "
                + "nothing: " + data.keySet());
    }

    /**
     * Paging must follow the same key. Bounding that only ever pages {@code findings} would
     * hand back an unpaged list while reporting {@code truncated}/{@code returnedCount} for a
     * list it never touched — a response actively describing itself wrongly.
     */
    @ParameterizedTest(name = "limit pages kind={0} through its own result list")
    @ValueSource(strings = {"unused", "naming", "bugs", "large_classes"})
    @DisplayName("limit pages the detector's OWN result list")
    void pagingFollowsTheDetectorsResultKey(String kind) {
        ObjectNode plain = mapper.createObjectNode();
        plain.put("kind", kind);
        Map<String, Object> unbounded = run(plain);
        String listKey = resultKeyOf(unbounded);
        assertNotNull(listKey, kind + " returned no result list: " + unbounded.keySet());
        Object all = unbounded.get(listKey);
        int trueTotal = all instanceof List<?> l ? l.size() : 0;
        if (trueTotal < 2) {
            return; // nothing to page on this fixture; the summary test above still covers it
        }

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        args.put("limit", 1);
        Map<String, Object> data = run(args);

        Object page = data.get(listKey);
        assertTrue(page instanceof List<?>, "the paged list keeps its own key: " + data.keySet());
        assertEquals(1, ((List<?>) page).size(), "`limit` bounds the detector's own list");
        assertEquals(trueTotal, ((Number) data.get("count")).intValue(),
            "count is the TRUE total, never the page size");
        assertEquals(Boolean.TRUE, data.get("truncated"), "a capped list declares itself capped");
    }
}
