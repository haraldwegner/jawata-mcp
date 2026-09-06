package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 46 ON CODE WE DID NOT AUTHOR — and it PERFORMS, which most of this stage's slices do not.
 *
 * <h2>The census, and why this is the corpus's one genuine error code</h2>
 *
 * <p>Over the fork's <b>1354 main sources</b> the sentinel-shaped returns are <b>43
 * {@code return null}</b>, <b>38 {@code return false}</b> and <b>3 {@code return -1}</b>. Almost
 * all are ordinary control flow, which is why this row REQUIRES the caller to name the error value
 * rather than inferring it. Of the three {@code -1}s, two are the tail of a swallowed
 * {@code SQLException}; the third is {@code Bartender.orderDrink}, which is Fowler's example
 * almost word for word — {@code -1} when throttled, a generated id otherwise.</p>
 *
 * <p><b>Its one caller DISCARDS the value.</b> Upstream's {@code App} calls it as a statement, so
 * the throttling decision is thrown away at the only place that could act on it. That is the bug
 * this refactoring exists for, written by somebody else.</p>
 *
 * <h2>The exception is UNCHECKED here, and that is the row's own warning demonstrated</h2>
 *
 * <p>With a checked exception upstream's {@code App} would not compile and this row's compile gate
 * would refuse the whole change — correctly. With an unchecked one it compiles, and the failure
 * now propagates at run time where it used to be discarded. <b>The response's statement that
 * behaviour changed is the only thing telling a reader what they just did</b>, so this test pins
 * that as hard as it pins the rewrite.</p>
 */
class ReplaceErrorCodeWithExceptionForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PKG = "com/iluwatar/throttling";

    /** Upstream's own line — the sentinel this row replaces. */
    private static final String UPSTREAM_SENTINEL = "return -1;";

    @Test
    @DisplayName("row 46 PERFORMS on upstream's orderDrink, whose one caller discards the code — "
        + "and the response says the behaviour changed")
    void performsOnUpstreamsDiscardedErrorCode() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-throttling", PKG,
            "Bartender.java", UPSTREAM_SENTINEL);
        String before = slice.read("Bartender.java");

        Assertions.assertAll(
            () -> assertTrue(before.contains("return getRandomCustomerId();"),
                "PROOF OF LIFE, the SUCCESS return: without one the row refuses for having no"
                    + " success path, and this test would prove nothing:\n" + before),
            () -> assertTrue(slice.read("App.java").contains("service.orderDrink(barCustomer);"),
                "PROOF OF LIFE, the discarding caller: a caller that TESTED the code would make"
                    + " this a refusal instead:\n" + slice.read("App.java")));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_error_code_with_exception");
        args.put("symbol", "com.iluwatar.throttling.Bartender#orderDrink");
        args.put("errorValue", "-1");
        args.put("exceptionType", "IllegalStateException");
        args.put("message", "throttled");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache()).execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = slice.read("Bartender.java");
        Assertions.assertAll(
            () -> assertTrue(after.contains("throw new IllegalStateException(\"throttled\");"),
                "upstream's discarded sentinel must become a throw:\n" + after),
            () -> assertFalse(after.contains("return -1;"),
                "and be gone — this file has exactly one, so a whole-file check is sound HERE"
                    + " where it would not be in the fixture:\n" + after),
            () -> assertTrue(after.contains("return getRandomCustomerId();"),
                "while upstream's success path is untouched:\n" + after),

            // The row's own warning, on the input that makes it matter. An unchecked exception
            // leaves App compiling, so nothing but this sentence tells the caller that the
            // throttling failure now propagates where upstream discarded it.
            () -> assertTrue(String.valueOf(r.getData()).contains("CHANGES BEHAVIOUR"),
                "the response must say so: " + r.getData()),

            // NOT an assertion on the word CHECKED. That sentence is emitted unconditionally,
            // so it is entailed by the line above and could detect nothing — a C4 audit found
            // it by reading the production line. What IS variable here, and what actually
            // measures upstream's shape, is that exactly one caller was read and none of them
            // tested the code. If upstream's App ever starts checking the return, this row
            // must refuse instead, and this number is what notices.
            () -> Assertions.assertEquals(1,
                ((java.util.Map<?, ?>) r.getData()).get("callersChecked"),
                "upstream's orderDrink has exactly one caller, and the whole demonstration is"
                    + " that it DISCARDS the code: " + r.getData()));
    }
}
