package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.RemoveFlagArgumentTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 35 ON CODE WE DID NOT AUTHOR — and the corpus did not demonstrate the row, it ADDED A
 * PRECONDITION to it.
 *
 * <h2>The census</h2>
 *
 * <p>Over the fork's <b>1354 main sources</b>, exactly <b>14 methods declare a boolean
 * parameter</b> and <b>7 are called with a boolean literal</b>. Every one of the seven passes ONE
 * literal value and only one: three setters, two constructors this row does not reach,
 * {@code Future.cancel(boolean)} whose signature the JDK fixes, and one guard helper whose other
 * callers pass expressions.</p>
 *
 * <p><b>So the row would have generated dead code on every candidate the corpus offers</b> — a
 * second named method with no caller — and it had no rule against that until this census was run.
 * {@code FLAG_IS_ONE_SIDED} exists because of these files.</p>
 *
 * <h2>The two refusals, on two slices, and WHICH one fires is the point</h2>
 *
 * <p>Upstream's {@code AbstractInstance.setAlive} is called twice and both pass {@code false} —
 * the one-sided shape, on somebody else's code. Upstream's {@code RegisterWorker.fail} has five
 * callers of which four pass expressions, so the VARIABLE refusal fires there and the one-sided
 * check is never reached, which is stated rather than assumed.</p>
 */
class RemoveFlagArgumentForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** Upstream's own line — one of the two callers that pass the same literal. */
    private static final String UPSTREAM_ONE_SIDED = "instance1.setAlive(false);";

    /** Upstream's own line — the caller that passes an expression rather than a literal. */
    private static final String UPSTREAM_VARIABLE =
        "fail(isNullOrBlank(ourData.getName()), RegisterWorkerDto.MISSING_NAME);";

    @Test
    @DisplayName("row 35 REFUSES upstream's setAlive, whose two callers both pass false — the "
        + "shape that made this precondition exist")
    void refusesUpstreamsOneSidedFlag() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-leader-election",
            "com/iluwatar/leaderelection/ring", "RingApp.java", UPSTREAM_ONE_SIDED);
        String before = slice.read("RingApp.java");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "remove_flag_argument");
        args.put("symbol", "com.iluwatar.leaderelection.AbstractInstance#setAlive");
        args.put("parameter", "alive");
        args.put("whenTrue", "revive");
        args.put("whenFalse", "kill");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache()).execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(),
                "generating a revive() nobody calls is dead code, which is what the row would"
                    + " have done here before this census"),
            () -> assertEquals(RemoveFlagArgumentTool.Refusal.FLAG_IS_ONE_SIDED,
                r.getError().getReason(),
                "and it must be the one-sided precondition rather than anything about the"
                    + " slice's unresolved lombok imports: " + r.getError()),
            () -> assertEquals(before, slice.read("RingApp.java"),
                "upstream's file is untouched"));
    }

    @Test
    @DisplayName("row 35 REFUSES upstream's fail on the VARIABLE precondition, which runs before "
        + "the one-sided one and is what its callers actually trip")
    void refusesUpstreamsExpressionCaller() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-notification",
            "com/iluwatar", "RegisterWorker.java", UPSTREAM_VARIABLE);
        String before = slice.read("RegisterWorker.java");

        assertTrue(before.contains("fail(true, RegisterWorkerDto.MISSING_DOB);"),
            "PROOF OF LIFE: one caller DOES pass a literal, so the method is a candidate at all"
                + " and the refusal below is about the OTHERS:\n" + before);

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "remove_flag_argument");
        args.put("symbol", "com.iluwatar.RegisterWorker#fail");
        args.put("parameter", "condition");
        args.put("whenTrue", "failAlways");
        args.put("whenFalse", "failNever");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache()).execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "four of its five callers compute the condition"),
            () -> assertEquals(RemoveFlagArgumentTool.Refusal.CALLER_PASSES_A_VARIABLE,
                r.getError().getReason(),
                "and it must be the VARIABLE precondition — the one-sided one is also true of"
                    + " this method's literal callers and is never reached, which is exactly the"
                    + " kind of thing a refusal test cannot tell you unless it asserts the code: "
                    + r.getError()),
            () -> assertEquals(before, slice.read("RegisterWorker.java"),
                "upstream's file is untouched"));
    }
}
