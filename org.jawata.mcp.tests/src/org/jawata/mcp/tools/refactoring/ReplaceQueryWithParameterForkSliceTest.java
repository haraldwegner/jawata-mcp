package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 55 ON CODE WE DID NOT AUTHOR — and this pins a REFUSAL and a MEASURED LIMITATION, not a
 * success. Both halves are stated plainly, because a refusal on foreign code is evidence and is
 * not a demonstration.
 *
 * <h2>The candidate, and it is a real one</h2>
 *
 * <p>{@code Reducer.reduce} sorts by a hard-coded ordering —
 * {@code result.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()))} — which is a
 * question the method answers for itself while every caller is stuck with the answer. It is
 * exactly Fowler's shape, it is NESTED as an argument rather than standing alone, and its one
 * call site is in another file. Nobody wrote any of that for us.</p>
 *
 * <h2>What stops it, and it is OURS rather than upstream's</h2>
 *
 * <p>The engine copies the expression's text to the call site and <b>does not carry the imports
 * that text needs</b>. {@code MapReduce} does not import {@code java.util.Comparator}, so the
 * pasted {@code Comparator.reverseOrder()} does not resolve there and the compile gate refuses
 * the whole change. The gate is right and upstream's file is left byte-for-byte untouched —
 * which is what this test pins — but the row is narrower than its own description implies: it
 * performs where the expression's types are ALREADY in scope at every call site.</p>
 *
 * <h2>The absence is a census, not a shrug</h2>
 *
 * <p>All twelve vendored slices were scanned for a no-argument query with an explicit receiver
 * whose type needs no import at the call sites — that is, a type declared in the same package.
 * There are exactly TWO: {@code CarFactory.createCars()} and
 * {@code CharacterStepBuilder.newBuilder()}, and both sit inside {@code App.main}, which has no
 * callers at all. So no slice offers a candidate satisfying both halves, and the one that
 * satisfies the harder half is blocked by the import gap above.</p>
 *
 * <p><b>The fix is known and is not a mystery</b>: run the copied expression's types through
 * {@code ImportRewrite} per affected compilation unit, which is what Stage 5's row 16 did when
 * a forwarder written with a bare simple name broke the same gate. It is recorded for C4 rather
 * than built here, and until it is built this test is what keeps the limitation honest — if the
 * row ever starts succeeding on this input, this test fails and someone must come and read
 * why.</p>
 */
class ReplaceQueryWithParameterForkSliceTest {

    private static final String PKG = "com/iluwatar";

    /** Upstream's own line, verbatim — the hard-coded answer this row would hand to the caller. */
    private static final String UPSTREAM_SORT =
        "result.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));";

    /** Upstream's own call site, in a file this test never points the tool at. */
    private static final String UPSTREAM_CALL = "return Reducer.reduce(grouped);";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("upstream's ordering is a real candidate, and the change is REFUSED because "
        + "the copied expression's import does not travel with it")
    void theImportGapStopsUpstreamsCandidate() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-map-reduce-mr", PKG,
            "Reducer.java", UPSTREAM_SORT);
        String beforeCaller = slice.read("MapReduce.java");
        String beforeTarget = slice.read("Reducer.java");
        assertTrue(beforeCaller.contains(UPSTREAM_CALL),
            "PROOF OF LIFE: MapReduce must still call Reducer.reduce");
        assertFalse(beforeCaller.contains("import java.util.Comparator;"),
            "PROOF OF LIFE: and it must NOT import Comparator — that absence is the whole"
                + " reason this refuses, so if upstream ever adds the import this test is"
                + " measuring something else and must be re-read");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_query_with_parameter");
        args.put("symbol", "com.iluwatar.Reducer#reduce");
        args.put("queryCall", "reverseOrder");
        args.put("parameterName", "order");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache())
            .execute(args);

        assertFalse(r.isSuccess(),
            "the copied expression names a type the caller cannot resolve, so the change"
                + " cannot stand");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("Comparator cannot be resolved"),
            "and the refusal must name WHAT could not resolve, which is the whole diagnosis"
                + " a reader needs: " + error);
        assertEquals(beforeTarget, slice.read("Reducer.java"),
            "upstream's file must be byte-for-byte untouched — the gate UNDOES, it does not"
                + " leave a half-applied change behind");
        assertEquals(beforeCaller, slice.read("MapReduce.java"),
            "and so must the caller's");
    }
}
