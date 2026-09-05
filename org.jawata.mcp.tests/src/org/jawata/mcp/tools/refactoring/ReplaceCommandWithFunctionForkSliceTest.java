package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.ReplaceCommandWithFunctionTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 41 ON CODE WE DID NOT AUTHOR — pinning the refusal the census made this row's main job.
 *
 * <h2>The census, run BEFORE the row was written</h2>
 *
 * <p>Over the fork's <b>1354 main sources</b>, <b>33 classes</b> have exactly one public method
 * and it is named like a command. <b>27 of the 33 declare it because a SUPERTYPE does</b> — six
 * {@code Runnable}s, a {@code Callable}, three strategies, five filters, two HTTP handlers, and so
 * on. Only <b>5</b> are free of a supertype, and two of those are Spring application classes.</p>
 *
 * <p>So this row refuses far more often than it performs, and it was designed that way rather than
 * discovering it afterwards: the dispatch check runs before everything except the shape check.</p>
 *
 * <h2>What is pinned, and why this class rather than another</h2>
 *
 * <p>Upstream's {@code Worker} passes EVERY other precondition — one public method, one
 * constructor, four final fields each assigned straight from a parameter, no field written
 * afterwards. It is refused for exactly one reason: {@code Runnable} declares {@code run()}, so a
 * thread pool chose this implementation and a static function would have nothing to choose.</p>
 *
 * <p>That makes it a discriminating pin rather than a decorative one. A class refused for several
 * reasons at once would pass this test with the dispatch check deleted; this one would be
 * PERFORMED.</p>
 */
class ReplaceCommandWithFunctionForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PKG = "com/iluwatar/leaderfollowers";

    /** Upstream's own line — the supertype that makes this a dispatch. */
    private static final String UPSTREAM_DISPATCH = "public class Worker implements Runnable {";

    @Test
    @DisplayName("row 41 REFUSES upstream's Worker: it passes every other precondition and is a "
        + "Runnable, which is the corpus's dominant case")
    void refusesUpstreamsDispatchedCommand() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-leader-followers", PKG,
            "Worker.java", UPSTREAM_DISPATCH);
        String before = slice.read("Worker.java");

        Assertions.assertAll(
            () -> assertTrue(before.contains("public Worker(long id, WorkCenter workCenter,"),
                "PROOF OF LIFE, the single constructor: without it the row would refuse for THAT"
                    + " instead and this test would prove nothing about dispatch:\n" + before),
            () -> assertTrue(before.contains("public void run() {"),
                "PROOF OF LIFE, the single command method:\n" + before));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_command_with_function");
        args.put("typeName", "com.iluwatar.leaderfollowers.Worker");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache()).execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(),
                "a thread pool chose this implementation by being handed it; a static function"
                    + " has nothing to choose"),
            () -> assertEquals(ReplaceCommandWithFunctionTool.Refusal.METHOD_IS_INHERITED,
                r.getError().getReason(),
                "and it must be the DISPATCH precondition — every other one passes on this"
                    + " class, which is what makes it a discriminating pin: " + r.getError()),
            () -> assertTrue(String.valueOf(r.getError()).contains("Runnable"),
                "naming the supertype, so a reader sees what would be collapsed: " + r.getError()),
            () -> assertEquals(before, slice.read("Worker.java"),
                "upstream's file is untouched"));
    }
}
