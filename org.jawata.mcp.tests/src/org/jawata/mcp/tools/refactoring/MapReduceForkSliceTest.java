package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FOUR STAGE 6 ROWS ON CODE WE DID NOT AUTHOR — rows 64, 58, 24 and 48.
 *
 * <p>The per-row contract asks for a demonstration on the fork corpus, and says why a
 * fixture cannot serve: one written beside a refactoring is written, without anyone
 * deciding to, in the shape that refactoring handles.</p>
 *
 * <p>{@code MapReduce.mapReduce} is nine lines of upstream's own code that do three things
 * in sequence — map every input, shuffle the results, reduce them — and it carries four of
 * the twelve rows at once. See {@code PROVENANCE.md} beside the slice.</p>
 *
 * <p>Each test reloads the slice, so a row acts on upstream's file rather than on what the
 * previous row left behind. Sharing one project would make a failure a question about
 * order.</p>
 */
class MapReduceForkSliceTest {

    private static final String PKG = "com/iluwatar";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ForkSliceSupport.Slice slice;

    @BeforeEach
    void setUp() throws Exception {
        slice = ForkSliceSupport.load(helper, "fork-map-reduce-mr", PKG,
            "MapReduce.java", "Map<String, List<Integer>> grouped = Shuffler.shuffleAndSort");
    }

    @Test
    @DisplayName("row 64 Split Phase: the real seam is between mapping and shuffling")
    void splitPhaseOnForkCode() throws Exception {
        ObjectNode args = slice.at("split_phase", "MapReduce.java",
            "public static List<Map.Entry<String, Integer>> mapReduce", 49);
        args.put("boundaryLine",
            slice.lineOf("MapReduce.java", "Shuffler.shuffleAndSort(mapped)"));

        ToolResponse r = slice.door("extract").execute(args);
        assertTrue(r.isSuccess(), () -> "Split Phase refused real upstream code: "
            + r.getError());

        String after = slice.read("MapReduce.java");
        assertTrue(after.contains("private record MapReduceIntermediate("),
            "a carrier was generated:\n" + after);
        // ONE local crosses the seam. `mapped` is built by the loop and read by the
        // shuffle; nothing else does. A tool that carried every phase-one local would put
        // more in the record than belongs there, and upstream's method is what shows the
        // difference — a fixture would have had exactly the locals the author needed.
        assertTrue(after.contains("List<Map<String, Integer>> mapped"),
            "carrying `mapped`, which the second phase reads:\n" + after);
        assertFalse(after.contains("MapReduceIntermediate(List<Map<String, Integer>> mapped,"),
            "and NOTHING else — a second component means the derivation took too much:\n"
                + after);
        assertTrue(after.contains("mapReducePhase1(") && after.contains("mapReducePhase2("),
            "both phases exist:\n" + after);
    }

    @Test
    @DisplayName("row 58 Replace Temp with Query: `grouped` becomes a call")
    void replaceTempWithQueryOnForkCode() throws Exception {
        ObjectNode args = slice.at("temp_to_query", "MapReduce.java",
            "Map<String, List<Integer>> grouped = Shuffler.shuffleAndSort", 4);

        ToolResponse r = slice.door("extract").execute(args);
        assertTrue(r.isSuccess(), () -> "Replace Temp with Query refused real upstream code: "
            + r.getError());

        String after = slice.read("MapReduce.java");
        assertTrue(after.contains("grouped("),
            "the initializer became a query the use now reads:\n" + after);
        assertFalse(after.contains("grouped = Shuffler.shuffleAndSort"),
            "and the temp is gone:\n" + after);
        // The query takes `mapped` as a parameter, because upstream's initializer reads a
        // local. Our own fixture's temp read a FIELD, so it produced a no-argument query
        // and never exercised this — the extract engine's parameter derivation is the half
        // a same-shaped fixture cannot reach.
        assertTrue(after.contains("grouped(mapped)"),
            "with the parameter the engine derived from the initializer:\n" + after);
    }

    @Test
    @DisplayName("row 24 Move Function: a static function moves, and its caller repoints")
    void moveStaticFunctionOnForkCode() throws Exception {
        ObjectNode args = slice.at("method", "Shuffler.java",
            "public static Map<String, List<Integer>> shuffleAndSort", 43);
        args.put("targetType", "com.iluwatar.Reducer");

        ToolResponse r = slice.door("move").execute(args);
        assertTrue(r.isSuccess(), () -> "Move Function refused real upstream code: "
            + r.getError());

        assertTrue(slice.read("Reducer.java").contains("shuffleAndSort"),
            "the function arrived:\n" + slice.read("Reducer.java"));
        assertFalse(slice.read("Shuffler.java").contains("shuffleAndSort"),
            "and left, with no forwarder:\n" + slice.read("Shuffler.java"));
        assertTrue(slice.read("MapReduce.java").contains("Reducer.shuffleAndSort"),
            "and upstream's caller — in a third file — names the new owner:\n"
                + slice.read("MapReduce.java"));
    }

    @Test
    @DisplayName("row 48 Replace Function with Command: `Mapper.map` becomes an object")
    void functionToCommandOnForkCode() throws Exception {
        ObjectNode args = slice.at("function_to_command", "Mapper.java",
            "public static Map<String, Integer> map", 37);
        args.put("newTypeName", "MapCommand");

        ToolResponse r = slice.door("extract").execute(args);
        assertTrue(r.isSuccess(), () -> "Replace Function with Command refused real upstream"
            + " code: " + r.getError());

        String command = slice.read("MapCommand.java");
        assertTrue(Files.exists(slice.pkg().resolve("MapCommand.java")),
            "the command must be written to its own file");
        assertTrue(command.contains("private final String"),
            "the parameter became a field:\n" + command);
        assertTrue(command.contains("execute()"), "and the body became execute():\n" + command);
        assertTrue(slice.read("MapReduce.java").contains("new MapCommand(input).execute()"),
            "and upstream's call site constructs and runs it:\n"
                + slice.read("MapReduce.java"));
    }

    @Test
    @DisplayName("row 17 Inline Class REFUSES this utility class, on two counts, in upstream's own code")
    void inlineClassRefusesTheUtilityClass() throws Exception {
        String before = slice.read("MapReduce.java");
        ObjectNode args = slice.at("class", "MapReduce.java", "public class MapReduce", 13);

        ToolResponse r = slice.door("inline").execute(args);

        // A refusal on foreign code is evidence too: it says the operation recognises what
        // it is pointed at rather than reshaping whatever it is given. Upstream's utility
        // class is referenced by two classes AND has a constructor that throws — either
        // one is disqualifying, and this is not a shape anyone would write into a fixture
        // for an operation they were trying to demonstrate.
        assertFalse(r.isSuccess(), "a utility class two others use is not inlinable");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("classes") || error.contains("constructor"),
            "and the refusal names which disqualifier it hit: " + error);
        assertEquals(before, slice.read("MapReduce.java"),
            "a refused refactoring leaves the source byte-identical");
    }
}
