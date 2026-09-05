package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.PreserveWholeObjectTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 28 ON CODE WE DID NOT AUTHOR — a MEASURED ABSENCE with three named walls, and the refusal
 * pinned on the module that demonstrates the very pattern.
 *
 * <h2>The census</h2>
 *
 * <p>Over the fork's <b>1354 main sources</b> there are <b>2683 multi-argument call sites</b>, of
 * which <b>39</b> pass two or more no-argument accessors on the same receiver. Every one hits one
 * of three walls, and {@code fork-parameter-object/PROVENANCE.md} names them with examples: the
 * called method is a JDK or library one with no declaration to rewrite (most of the 39, nearly all
 * loggers); it is a CONSTRUCTOR, which this row does not reach; or its accessors are
 * Lombok-generated, which JDT cannot see.</p>
 *
 * <p><b>Two genuine successes exist and both fall to the third wall</b> —
 * {@code table-module}'s {@code login} with three agreeing callers, and
 * {@code event-sourcing}'s {@code handleWithdrawal} with one. That is row 54's ruling arriving on
 * another row: a demonstration on accessors the compiler cannot see is not a demonstration.</p>
 *
 * <h2>What is pinned</h2>
 *
 * <p>Upstream's {@code SearchService.getQuerySummary} has three call sites and only one unpacks a
 * {@code ParameterObject} — the other two are overloads that exist so a caller need NOT build the
 * object, and they pass plain values. The callers disagree and the row declines.</p>
 *
 * <p>The refusal is decided from the CALL SITES before any type resolves, so upstream's unresolved
 * Lombok and slf4j imports cannot be what declined it — and the assertion is on the reason CODE
 * rather than a substring, so a refusal arriving from anywhere else fails this test rather than
 * satisfying it.</p>
 */
class PreserveWholeObjectForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PKG = "com/iluwatar/parameter/object";

    /** Upstream's own line — the ONE call that does unpack the object. */
    private static final String UPSTREAM_UNPACKS =
        "parameterObject.getType(), parameterObject.getSortBy(), parameterObject.getSortOrder()";

    @Test
    @DisplayName("row 28 REFUSES upstream's getQuerySummary: one caller unpacks the object and "
        + "two exist precisely so a caller need not")
    void refusesUpstreamsDisagreeingCallers() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-parameter-object", PKG,
            "SearchService.java", UPSTREAM_UNPACKS);
        String before = slice.read("SearchService.java");

        Assertions.assertAll(
            () -> assertTrue(before.contains("getQuerySummary(type, sortBy, SortOrder.ASC)"),
                "PROOF OF LIFE, the first disagreeing caller — without it the callers are"
                    + " unanimous and this row would PERFORM rather than refuse:\n" + before),
            () -> assertTrue(before.contains("getQuerySummary(type, \"price\", sortOrder)"),
                "PROOF OF LIFE, the second: two overloads supplying defaults are exactly why the"
                    + " object cannot be folded in here:\n" + before));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "preserve_whole_object");
        args.put("symbol", "com.iluwatar.parameter.object.SearchService#getQuerySummary");
        args.putArray("parameters").add("type").add("sortBy");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache()).execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(),
                "folding these would break the two overloads, which pass values rather than an"
                    + " object — that is what unanimity is for"),
            () -> assertEquals(PreserveWholeObjectTool.Refusal.CALLERS_DISAGREE,
                r.getError().getReason(),
                "and it must be the unanimity precondition, decided from the call sites, rather"
                    + " than anything caused by the slice's unresolved lombok imports: "
                    + r.getError()),
            () -> assertEquals(before, slice.read("SearchService.java"),
                "upstream's file is untouched"));
    }
}
