package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROW 49 — Replace Inline Code with Function Call — ON CODE WE DID NOT AUTHOR.
 *
 * <p>{@code InventoryService.updateInventory} and {@code restoreInventory} are upstream's
 * own pair of inventory operations. They both pause, both log, and both handle the
 * interrupt the same way. See {@code PROVENANCE.md} beside the slice.</p>
 *
 * <h2>What this slice measures that the fixture could not</h2>
 *
 * <p>The fixture for this row had two byte-identical statement pairs. Upstream's methods
 * are identical in SHAPE and differ in their log messages, which is what real duplication
 * looks like — and it is the difference between two tools' definitions of a clone:</p>
 *
 * <ul>
 *   <li>{@code find_duplicate_code} normalizes string literals, so it reports these two as
 *       one group of 71 tokens.</li>
 *   <li>JDT's occurrence matcher — the engine behind {@code replaceDuplicates} — requires
 *       structural identity AND the same resolved bindings, so it does NOT match them.</li>
 * </ul>
 *
 * <p>Both are right about different questions, and the second test below pins that
 * disagreement rather than leaving it to be rediscovered. A caller acting on the finder's
 * group and expecting the rewriter to take it would get an honest no-op.</p>
 */
class MessagingForkSliceTest {

    private static final String PKG = "com/iluwatar/messaging";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ForkSliceSupport.Slice slice;

    @BeforeEach
    void setUp() throws Exception {
        slice = ForkSliceSupport.load(helper, "fork-messaging", PKG,
            "InventoryService.java", "private void restoreInventory(Message message)");
    }

    private ObjectNode extractAt(String startMarker, String endMarker, String name,
                                 boolean replaceDuplicates) throws Exception {
        ObjectNode args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        args.put("kind", "method");
        args.put("filePath", slice.pkg().resolve("InventoryService.java").toString());
        // COLUMNS READ OFF THE LINE, not assumed. Upstream indents differently at
        // different depths — the pause sits inside a try inside a method — and a hard-coded
        // start column lands mid-token, which the engine correctly refuses as an invalid
        // range. A fixture written at one indent would never have shown it.
        String[] lines = slice.read("InventoryService.java").split("\n", -1);
        int startLine = slice.lineOf("InventoryService.java", startMarker);
        int endLine = slice.lineOf("InventoryService.java", endMarker);
        args.put("startLine", startLine);
        args.put("startColumn", lines[startLine].indexOf(startMarker));
        args.put("endLine", endLine);
        args.put("endColumn", lines[endLine].indexOf(endMarker) + endMarker.length());
        args.put("methodName", name);
        args.put("replaceDuplicates", replaceDuplicates);
        return args;
    }

    /**
     * A caret ON the statement, at a column read off the line rather than assumed.
     *
     * <p>The pair below moves one statement between two nesting depths — a method body and
     * an {@code if} branch — so a column that is right before the move is wrong after it.
     * That is upstream's shape, not a difficulty anybody introduced.</p>
     */
    private ObjectNode atStatement(String kind, String statement) throws Exception {
        String[] lines = slice.read("InventoryService.java").split("\n", -1);
        int line = slice.lineOf("InventoryService.java", statement);
        return slice.at(kind, "InventoryService.java", statement,
            lines[line].indexOf(statement));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("row 49 on fork code: the pause both methods share becomes one call to a new method")
    void replaceInlineCodeOnForkCode() throws Exception {
        String before = slice.read("InventoryService.java");
        assertEquals(2, countOf(before, "Thread.sleep(100);"),
            "PROOF OF LIFE: upstream's two methods must both carry the pause, or this row"
                + " has nothing to find a second occurrence of:\n" + before);

        ToolResponse r = slice.door("extract")
            .execute(extractAt("Thread.sleep(100);", "Thread.sleep(100);", "pause", true));
        assertTrue(r.isSuccess(), () -> "row 49 refused real upstream code: " + r.getError());

        String after = slice.read("InventoryService.java");
        assertEquals(1, countOf(after, "Thread.sleep(100);"),
            "the only remaining pause is inside the extracted method:\n" + after);
        // TWO call sites, and the second one is the row. Nothing asked for it: the caller
        // pointed at ONE occurrence in updateInventory, and restoreInventory's copy — a
        // method they did not name — became a call to the same new method.
        assertEquals(2, countOf(after, "pause();"),
            "and BOTH of upstream's methods now call it, though only one was pointed at:\n"
                + after);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, ((Number) data.get("otherOccurrences")).intValue(),
            "with the count that tells a caller the flag was worth setting: " + data);
    }

    @Test
    @DisplayName("rows 26 and 25 on fork code: a statement moves out to the caller, and back")
    void moveStatementsOutAndBackOnForkCode() throws Exception {
        String upstream = slice.read("InventoryService.java");
        String logLine = "LOGGER.info(\"Updating inventory for message: {}\", message.getId());";
        assertTrue(upstream.contains(logLine),
            "PROOF OF LIFE: the statement this pair moves must be upstream's own:\n" + upstream);

        // ROW 26 — Move Statements to Callers. The statement is updateInventory's FIRST, and
        // its one call site is a statement of its own inside handleMessage's if/else. Both
        // are the tool's stated preconditions, and upstream satisfies them without being
        // arranged to.
        ToolResponse out = slice.door("move").execute(
            atStatement("statements_to_callers", logLine));
        assertTrue(out.isSuccess(), () -> "row 26 refused real upstream code: "
            + out.getError());

        String moved = slice.read("InventoryService.java");
        assertEquals(1, countOf(moved, logLine),
            "the statement exists once — moved, not copied:\n" + moved);
        assertTrue(moved.indexOf(logLine) < moved.indexOf("private void updateInventory"),
            "and it now sits in the CALLER, which upstream declares first:\n" + moved);

        // ROW 25 — Move Statements into Function. The inverse, on what row 26 just produced.
        // Running the pair as a round trip is the assertion a fixture cannot make: each row
        // alone can only be checked against an expected output somebody wrote, while the two
        // together are checked against UPSTREAM'S OWN FILE, which nobody here chose.
        ToolResponse back = slice.door("move").execute(
            atStatement("statements_into_function", logLine));
        assertTrue(back.isSuccess(), () -> "row 25 refused what row 26 produced — the two are"
            + " inverses, so this is the sharper failure of the pair: " + back.getError());

        String after = slice.read("InventoryService.java");
        assertTrue(after.indexOf(logLine) > after.indexOf("private void updateInventory"),
            "the statement is back inside the method it came from:\n" + after);
        assertEquals(1, countOf(after, logLine),
            "still exactly once — a round trip that duplicates is not a round trip:\n" + after);
    }

    @Test
    @DisplayName("the FINDER and the REWRITER disagree about this pair, and both are right")
    void theCloneDetectorAndTheEngineDisagree() throws Exception {
        // find_duplicate_code normalizes string literals, so it groups updateInventory and
        // restoreInventory as one clone of 71 tokens. JDT's matcher requires the same
        // resolved bindings AND the same literals, so extracting either method's body whole
        // finds NO second occurrence. This pins the gap rather than leaving a caller to meet
        // it as an honest no-op — the same finder-versus-rewriter shape Stage 3 measured
        // for row 50, on a different pair of tools.
        ToolResponse r = slice.door("extract").execute(extractAt(
            "LOGGER.info(\"Updating inventory for message: {}\", message.getId());",
            "LOGGER.info(\"Updating inventory for message: {}\", message.getId());",
            "logUpdateStart", true));
        assertTrue(r.isSuccess(), () -> "the extraction itself must run: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(0, ((Number) data.get("otherOccurrences")).intValue(),
            "the log line differs by its MESSAGE between the two methods, so JDT sees no"
                + " second occurrence — while the clone detector, which normalizes string"
                + " literals, reports the two methods as one group. Both answers are correct"
                + " about different questions: " + data);
    }
}
