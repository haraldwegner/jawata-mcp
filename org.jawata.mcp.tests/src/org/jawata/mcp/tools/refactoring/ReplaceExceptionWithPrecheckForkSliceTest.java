package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.ReplaceExceptionWithPrecheckTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 47 ON CODE WE DID NOT AUTHOR — a MEASURED ABSENCE, and the corpus explains itself.
 *
 * <h2>The census, and it is a census rather than a sample</h2>
 *
 * <p>The row acts on a handler for an exception the LANGUAGE raises, because only those have a
 * condition that can be checked instead. Over the fork's <b>1354 main sources</b> there are
 * <b>246 catch clauses naming 30 distinct types</b>, and <b>ZERO</b> of them catch
 * {@code ArrayIndexOutOfBoundsException}, {@code IndexOutOfBoundsException},
 * {@code NullPointerException} or {@code ArithmeticException}.</p>
 *
 * <p>The distribution says why, so the absence is explained rather than merely reported: 201 of
 * the 246 are {@code InterruptedException} (71), a bare {@code Exception} (67),
 * {@code IOException} (39) and {@code SQLException} (24). Those are checked or library
 * exceptions — a precheck cannot replace them, because whether they occur is not something the
 * caller can ask about first. A teaching corpus demonstrating patterns over threads, files and
 * databases is exactly where that distribution comes from.</p>
 *
 * <h2>What IS pinned, and it is a REFUSAL rather than a demonstration</h2>
 *
 * <p>Stage 6 recorded the distinction and it holds here: a refusal on foreign code is evidence,
 * not a demonstration. Upstream's {@code MonitoringService.delayedServiceResponse} is the nearest
 * thing the corpus offers — a try whose body is one statement and whose catch returns — and the
 * row declines it.</p>
 *
 * <p><b>Which refusal fires is stated rather than assumed, because TWO are true of this method
 * and only the first is reached.</b> The handler is {@code return e.getMessage();}, so it READS
 * the exception, and that check runs before the guarded-call check. The call refusal is equally
 * true here — {@code attemptRequest()} could raise the caught type from inside itself — and is
 * never reached on this input. Saying so is the point: a refusal test proves the refusal fired,
 * never that the branch you had in mind is what fired it, which two rows of this stage learned
 * from mutations that stayed green.</p>
 */
class ReplaceExceptionWithPrecheckForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PKG = "com/iluwatar/circuitbreaker";

    /** Upstream's own line — the single statement its try guards. */
    private static final String UPSTREAM_GUARDED = "return this.delayedService.attemptRequest();";

    @Test
    @DisplayName("row 47 REFUSES upstream's delayedServiceResponse, whose handler reads the "
        + "exception there would no longer be")
    void refusesUpstreamsHandlerThatReadsItsException() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-circuit-breaker", PKG,
            "MonitoringService.java", UPSTREAM_GUARDED);
        String before = slice.read("MonitoringService.java");

        Assertions.assertAll(
            () -> assertTrue(before.contains("catch (RemoteServiceException e)"),
                "PROOF OF LIFE, the handler: without a catch there is nothing to refuse and this"
                    + " test would pass over nothing:\n" + before),
            () -> assertTrue(before.contains("return e.getMessage();"),
                "PROOF OF LIFE, the exception being READ: it is what makes the refusal below"
                    + " THIS one rather than the guarded-call one:\n" + before));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_exception_with_precheck");
        args.put("symbol",
            "com.iluwatar.circuitbreaker.MonitoringService#delayedServiceResponse");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache()).execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "there is no condition to check here — whether a"
                + " remote service fails is not something this method can ask first"),
            () -> assertEquals(ReplaceExceptionWithPrecheckTool.Refusal.HANDLER_CANNOT_MOVE,
                r.getError().getReason(),
                "and it must be the handler-reads-its-exception precondition, which runs before"
                    + " the guarded-call one: " + r.getError()),
            () -> assertEquals(before, slice.read("MonitoringService.java"),
                "upstream's file is untouched"));
    }
}
