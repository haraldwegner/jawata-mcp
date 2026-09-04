package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROW 5 — Combine Functions into Class — ON CODE WE DID NOT AUTHOR.
 *
 * <p>{@code FunctionalProgramming} is upstream's own utility class of three static
 * functions. See {@code PROVENANCE.md} beside the slice.</p>
 *
 * <h2>The third function is the control, and nobody put it there for us</h2>
 *
 * <p>Two of the three take {@code List<Car>}; the third takes {@code List<Person>}. A
 * fixture written beside this row would have had all of them agree — that is the shape you
 * write when demonstrating that something works — and it could not have distinguished an
 * operation that GROUPS BY the shared parameter from one that sweeps up whatever static
 * functions it finds on the class. Here the two are told apart by upstream's own code.</p>
 */
class CollectionPipelineForkSliceTest {

    private static final String PKG = "com/iluwatar/collectionpipeline";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ForkSliceSupport.Slice slice;

    @BeforeEach
    void setUp() throws Exception {
        slice = ForkSliceSupport.load(helper, "fork-collection-pipeline", PKG,
            "FunctionalProgramming.java", "getSedanCarsOwnedSortedByDate(List<Person> persons)");
    }

    private ObjectNode combine(String newTypeName, String... functions) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "combine_functions");
        args.put("filePath", slice.pkg().resolve("FunctionalProgramming.java").toString());
        args.put("newTypeName", newTypeName);
        args.putArray("functions").addAll(
            java.util.Arrays.stream(functions)
                .map(f -> (com.fasterxml.jackson.databind.JsonNode)
                    com.fasterxml.jackson.databind.node.TextNode.valueOf(f))
                .toList());
        return args;
    }

    @Test
    @DisplayName("row 5 on fork code: the two Car functions become a class holding the cars")
    void combineFunctionsOnForkCode() throws Exception {
        ToolResponse r = slice.door("extract").execute(combine("CarQueries",
            "getModelsAfter2000", "getGroupingOfCarsByCategory"));
        assertTrue(r.isSuccess(), () -> "row 5 refused real upstream code: " + r.getError());

        String combined = slice.read("CarQueries.java");
        assertTrue(combined.contains("class CarQueries"),
            "the class exists:\n" + combined);
        // The shared parameter becomes the class's state — that is what "combine functions
        // INTO A CLASS" means, as against gathering them into a namespace.
        assertTrue(combined.contains("List<Car>") && combined.contains("private final"),
            "holding the data the functions shared:\n" + combined);
        assertTrue(combined.contains("getModelsAfter2000")
                && combined.contains("getGroupingOfCarsByCategory"),
            "with both named functions as members:\n" + combined);

        // THE CONTROL. The function upstream wrote over List<Person> was not named and does
        // not share the type, and it must not have been swept along.
        assertFalse(combined.contains("getSedanCarsOwnedSortedByDate"),
            "and NOT the third function, which takes List<Person> and was not named. A"
                + " version of this that gathered every static on the class would pass every"
                + " other assertion here and fail only this one:\n" + combined);
        assertTrue(slice.read("FunctionalProgramming.java")
                .contains("getSedanCarsOwnedSortedByDate"),
            "it stays where upstream put it:\n" + slice.read("FunctionalProgramming.java"));
    }

    @Test
    @DisplayName("row 5 REFUSES functions that do not share a first parameter type")
    void refusesFunctionsThatShareNothing() throws Exception {
        String before = slice.read("FunctionalProgramming.java");

        ToolResponse r = slice.door("extract").execute(combine("Mixed",
            "getModelsAfter2000", "getSedanCarsOwnedSortedByDate"));

        // Upstream supplies a genuine negative case: two real functions on one real class
        // with genuinely different data. The grouping this row performs is only meaningful
        // if it can say no, and this is the pair it must say no to.
        assertFalse(r.isSuccess(),
            "List<Car> and List<Person> are not one piece of data, so there is no class here");
        assertTrue(String.valueOf(r.getError()).contains("List"),
            "and the refusal names the types that disagree: " + r.getError());
        assertTrue(before.equals(slice.read("FunctionalProgramming.java")),
            "a refused refactoring leaves the source byte-identical");
    }
}
