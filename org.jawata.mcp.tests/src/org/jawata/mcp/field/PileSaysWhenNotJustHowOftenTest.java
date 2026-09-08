package org.jawata.mcp.field;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FieldTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#74 — <b>a rank says which shape recurs most; only a date says whether it is
 * still happening.</b>
 *
 * <p>`field(action=pile)` ranked shapes by a count taken over the install's whole life and
 * reported no dates at all. On a long-lived install that top row can be a fault fixed months
 * ago, and mcp#74 could only be filed as a FIELD REPORT rather than a diagnosis for exactly
 * that reason — its own words: <i>"the pile carries no timestamps, so these 323 may predate a
 * fix"</i>, and <i>"without that the pile cannot distinguish a fixed bug from a current
 * one."</i></p>
 *
 * <p><b>The timestamp was never missing.</b> Every {@link FieldEvent} carries
 * {@code epochMillis} and every pile line writes it as {@code "t"} — the AGGREGATION threw it
 * away. So this needed no schema change and no migration, and it answers about piles already
 * on disk, which is the whole point: the 323 that prompted the issue can be dated now.</p>
 *
 * <p>Each case here fails on the pre-fix code for a different reason, so no one of them
 * carries the others.</p>
 */
class PileSaysWhenNotJustHowOftenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DAY = 86_400_000L;

    private static FieldTool tool(Path dir) {
        return new FieldTool(() -> null, () -> dir);
    }

    private static ObjectNode pileArgs() {
        return MAPPER.createObjectNode().put("action", "pile");
    }

    private static FieldEvent failureAt(long millis, String tool, String code) {
        return new FieldEvent(millis, Token.of(tool), Token.of("recall"), false,
            new Token(code), 3, Token.of("claude_code"), new Version(4, 1, 3));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse response) {
        return (Map<String, Object>) response.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shapes(ToolResponse response) {
        return (List<Map<String, Object>>) data(response).get("shapes");
    }

    private static Map<String, Object> row(ToolResponse response, String shape) {
        return shapes(response).stream()
            .filter(r -> shape.equals(r.get("shape")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no row for " + shape + "; got: " + shapes(response)));
    }

    /**
     * The pile mcp#74 describes: one shape that dominates by COUNT and stopped happening
     * long ago, and one that is rare and is happening now. By recurrence alone the dead one
     * ranks first, which is exactly the reading that made the issue unanswerable.
     */
    private static void writeTheIssuesPile(Path dir, long now) {
        FieldPile pile = new FieldPile(dir);
        for (int i = 0; i < 12; i++) {
            pile.append(failureAt(now - (200 * DAY) + i, "experience", "KNOWLEDGE_UNAVAILABLE"));
        }
        pile.append(failureAt(now - (2 * DAY), "run_tests", "RUNNER_TIMEOUT"));
        pile.append(failureAt(now - (1 * DAY), "run_tests", "RUNNER_TIMEOUT"));
    }

    @Test
    @DisplayName("mcp#74: the top-ranked shape carries the date that says it is no longer happening")
    void theRankedRowSaysWhenItWasLastSeen(@TempDir Path dir) {
        long now = System.currentTimeMillis();
        writeTheIssuesPile(dir, now);

        ToolResponse response = tool(dir).execute(pileArgs());
        Map<String, Object> dead = row(response, "experience/recall/KNOWLEDGE_UNAVAILABLE");
        Map<String, Object> live = row(response, "run_tests/recall/RUNNER_TIMEOUT");

        assertAll(
            () -> assertTrue(response.isSuccess()),
            // The control, and it runs on the same response: ranking still works. Without
            // it, a build that had simply stopped counting would satisfy everything below.
            () -> assertEquals(12L, ((Number) dead.get("count")).longValue(),
                "the recurrence count is unchanged — this adds a date, it does not replace"
                    + " the ranking"),
            () -> assertEquals("experience/recall/KNOWLEDGE_UNAVAILABLE",
                shapes(response).get(0).get("shape"),
                "and the dead shape still ranks FIRST by count, which is the reading that"
                    + " made mcp#74 unanswerable"),
            // THE FIELD THE ISSUE ASKED FOR.
            () -> assertTrue(((Number) dead.get("lastSeenDaysAgo")).longValue() >= 199,
                "the top-ranked shape last happened ~200 days ago and must say so; got: "
                    + dead.get("lastSeenDaysAgo")),
            () -> assertTrue(((Number) live.get("lastSeenDaysAgo")).longValue() <= 1,
                "the rare shape is happening NOW and must say so; got: "
                    + live.get("lastSeenDaysAgo")),
            // Both directions on one response is what makes this a discriminator rather
            // than a value that merely exists: the two rows must DISAGREE.
            () -> assertTrue(((Number) dead.get("lastSeenDaysAgo")).longValue()
                    > ((Number) live.get("lastSeenDaysAgo")).longValue(),
                "a date that did not separate these two would answer nothing"));
    }

    @Test
    @DisplayName("mcp#74: a count never travels without the span it was taken over")
    void theCountCarriesItsSpan(@TempDir Path dir) {
        long now = System.currentTimeMillis();
        writeTheIssuesPile(dir, now);

        Map<String, Object> data = data(tool(dir).execute(pileArgs()));

        assertAll(
            () -> assertEquals(14, ((Number) data.get("events")).intValue()),
            () -> assertEquals(14L, ((Number) data.get("failures")).longValue()),
            // "548 failures" is not a fact anyone can act on until they know whether it is a
            // week or a year. The issue had to say that in prose because the response could not.
            () -> assertTrue(((Number) data.get("coversDays")).longValue() >= 198,
                "the span must cover the oldest event, which is ~200 days back; got: "
                    + data.get("coversDays")),
            () -> assertTrue(((Number) data.get("coversFromMillis")).longValue() > 0
                    && ((Number) data.get("coversToMillis")).longValue() > 0,
                "both bounds must be real instants; got: " + data.get("coversFromMillis")
                    + ".." + data.get("coversToMillis")),
            () -> assertEquals(0, ((Number) data.get("windowDays")).intValue(),
                "an unnarrowed call reports windowDays=0, so a reader can tell it is the"
                    + " whole recording rather than a window someone chose"));
    }

    @Test
    @DisplayName("mcp#74: sinceDays narrows the fold, and reports the lifetime beside it")
    void theWindowNarrowsAndStaysComparable(@TempDir Path dir) {
        long now = System.currentTimeMillis();
        writeTheIssuesPile(dir, now);

        ToolResponse windowed = tool(dir).execute(pileArgs().put("sinceDays", 30));
        Map<String, Object> data = data(windowed);

        assertAll(
            () -> assertEquals(1, shapes(windowed).size(),
                "only the live shape is inside 30 days; got: " + shapes(windowed)),
            () -> assertEquals("run_tests/recall/RUNNER_TIMEOUT",
                shapes(windowed).get(0).get("shape"),
                "and narrowing REVERSES the ranking, which is the answer mcp#74 wanted"),
            () -> assertEquals(2, ((Number) data.get("events")).intValue()),
            () -> assertEquals(30, ((Number) data.get("windowDays")).intValue()),
            // Both numbers on one response: the comparison that tells a live failure rate
            // from a historical total, without a second call to line up by hand.
            () -> assertEquals(14, ((Number) data.get("lifetimeEvents")).intValue(),
                "a narrowed call must still report the lifetime, or the two cannot be"
                    + " compared; got: " + data.get("lifetimeEvents")));
    }

    @Test
    @DisplayName("mcp#74: an EMPTY recording is not the same answer as one that could not be read")
    void anEmptyPileIsNotAFailedRead(@TempDir Path dir) {
        Map<String, Object> data = data(tool(dir).execute(pileArgs()));

        assertAll(
            () -> assertEquals(0, ((Number) data.get("events")).intValue()),
            () -> assertEquals(0L, ((Number) data.get("failedReads")).longValue(),
                "an empty pile read cleanly — failedReads is what separates this from a"
                    + " recording nobody could open, which reports the same zero events"),
            () -> assertEquals(0L, ((Number) data.get("coversFromMillis")).longValue(),
                "and an empty span reports 0 rather than an instant — the events count is"
                    + " what says the zero means empty and not 1970"));
    }

}
