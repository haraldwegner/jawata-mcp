package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 27 ON CODE WE DID NOT AUTHOR — Parameterize Function on upstream's collection pipeline.
 *
 * <p>{@code FunctionalProgramming.getModelsAfter2000} is Fowler's smell in its loudest form: the
 * constant is in the method's NAME as well as its body, so the method can only ever answer one
 * question and a caller wanting 1990 has nowhere to go. Nobody wrote it that way for us.</p>
 *
 * <h2>Three things here that no fixture of mine had</h2>
 *
 * <ul>
 *   <li>The literal sits inside a LAMBDA — {@code car -> car.year() > 2000} — so the new
 *       parameter has to be captured by it. My fixture's constant sits in a plain expression.</li>
 *   <li>The method is STATIC and called through its class name from another file.</li>
 *   <li><b>A SAME-NAMED method exists in a sibling class, and the same caller calls both.</b>
 *       {@code ImperativeProgramming.getModelsAfter2000} is a different method with an
 *       identical name, and {@code App} calls each on consecutive lines. So this test also
 *       discriminates: the row must rewrite the call it was pointed at and leave its twin
 *       alone. That is the sibling hazard this sprint met on nested classes, arriving here for
 *       free rather than by being constructed.</li>
 * </ul>
 */
class ParameterizeFunctionForkSliceTest {

    private static final String PKG = "com/iluwatar/collectionpipeline";

    /** Upstream's own line, verbatim — the constant the method's name promises. */
    private static final String UPSTREAM_FILTER = ".filter(car -> car.year() > 2000)";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("upstream's hard-coded year becomes a parameter, its caller supplies it, and "
        + "the same-named method in the sibling class is untouched")
    void parameterizesUpstreamsYear() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-collection-pipeline",
            PKG, "FunctionalProgramming.java", UPSTREAM_FILTER);
        String beforeTwin = slice.read("ImperativeProgramming.java");
        assertTrue(slice.read("App.java")
                .contains("FunctionalProgramming.getModelsAfter2000(cars)"),
            "PROOF OF LIFE: App must still call it — the cross-file rewrite is the half a"
                + " single-file demonstration cannot show");
        assertTrue(beforeTwin.contains("getModelsAfter2000"),
            "PROOF OF LIFE: and the sibling class must still declare a method of the SAME NAME,"
                + " which is what makes the discrimination assertion below mean anything");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "parameterize_function");
        args.put("symbol",
            "com.iluwatar.collectionpipeline.FunctionalProgramming#getModelsAfter2000");
        args.put("literal", "2000");
        args.put("parameterName", "year");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache())
            .execute(args);
        assertTrue(r.isSuccess(), "the row must run on foreign code; got: " + r.getError());

        org.junit.jupiter.api.Assertions.assertAll(

            () -> {
                String after = slice.read("FunctionalProgramming.java");
                assertFalse(after.contains(UPSTREAM_FILTER),
                    "upstream's hard-coded year must be gone from the body:\n" + after);
                assertTrue(after.contains("car.year() > year"),
                    "and the lambda must read the new parameter — which it CAPTURES, a shape no"
                        + " fixture of mine had:\n" + after);
            },

            () -> {
                String caller = slice.read("App.java");
                assertTrue(caller.contains(
                        "FunctionalProgramming.getModelsAfter2000(cars, 2000)"),
                    "the caller in another file must supply the year:\n" + caller);
            },

            // THE DISCRIMINATION. A same-named method in a sibling class, called by the same
            // file two lines earlier, must be untouched — the row rewrote the symbol it was
            // given and not everything spelled like it.
            () -> {
                assertTrue(slice.read("App.java").contains(
                        "ImperativeProgramming.getModelsAfter2000(cars)"),
                    "the TWIN's call must still take one argument:\n" + slice.read("App.java"));
                assertEquals(beforeTwin, slice.read("ImperativeProgramming.java"),
                    "and the twin's own file must be byte-for-byte untouched");
            });
    }

    private static void assertEquals(String expected, String actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }
}
