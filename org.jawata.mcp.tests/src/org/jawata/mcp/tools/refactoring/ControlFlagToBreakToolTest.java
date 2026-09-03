package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 44 — Replace Control Flag with Break.
 *
 * <p>Four conditions have to hold before this rewrite fires, and the fixture fails one
 * apiece. That structure is deliberate: a refusal test that varies several things at once
 * proves the rule refuses, and not which condition did the refusing — so a condition
 * could be deleted and the test would stay green.</p>
 *
 * <p>The rewrite is also all-or-nothing in a way most cleanups are not. It changes the
 * loop condition, replaces the assignment and deletes the declaration, and any two of
 * those without the third leave code that does not compile or a variable nothing sets.
 * The compile gate would catch that, which is why the assertions below check the three
 * together rather than one at a time.</p>
 */
class ControlFlagToBreakToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path target;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        target = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/ControlFlagTargets.java");
    }

    private String rewritten() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "control_flag_to_break");
        args.put("filePath", target.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the flag that carries only the exit becomes a break, and the flag goes")
    void thePureExitIsRewritten() throws Exception {
        String body = methodBody(rewritten(), "pureExit");
        assertTrue(body.contains("while (true)"),
            "the guard was the flag and nothing else, so the loop leaves through its"
                + " body:\n" + body);
        assertTrue(body.contains("break;"),
            "and the assignment that used to end the pass is now the exit:\n" + body);
        assertFalse(body.contains("boolean done"),
            "the declaration goes with it — a variable nothing sets and nothing reads is"
                + " worse than the flag was:\n" + body);
        assertTrue(body.contains("total += values[index];"),
            "and the work the loop did is untouched:\n" + body);
    }

    @Test
    @DisplayName("a flag that is read afterwards is carrying an answer, and stays")
    void aFlagReadAfterTheLoopIsRefused() throws Exception {
        String body = methodBody(rewritten(), "readAfterwards");
        assertTrue(body.contains("boolean found"),
            "this flag is read in the return statement. Deleting it deletes the answer,"
                + " and rewriting it to a break loses which case the method is in:\n"
                + body);
    }

    @Test
    @DisplayName("an assignment that is not last would skip the work after it")
    void anEarlyAssignmentIsRefused() throws Exception {
        String body = methodBody(rewritten(), "assignsThenWorks");
        assertTrue(body.contains("done = true;") && !body.contains("break;"),
            "the assignment here is followed by more work in the same pass, and a break"
                + " would skip it:\n" + body);
    }

    @Test
    @DisplayName("a compound condition would lose its other half")
    void aCompoundConditionIsRefused() throws Exception {
        String body = methodBody(rewritten(), "compoundCondition");
        assertTrue(body.contains("index < values.length"),
            "replacing this condition with `true` drops a bound check, which is how a"
                + " rewrite turns a loop into an overrun:\n" + body);
    }

    @Test
    @DisplayName("a flag not declared above its loop cannot be shown to be free of it")
    void aDistantDeclarationIsRefused() throws Exception {
        String body = methodBody(rewritten(), "declaredFarAway");
        assertTrue(body.contains("boolean done"),
            "two statements sit between the declaration and the loop. The rewrite has no"
                + " reading of what they depend on, so it does not act:\n" + body);
    }

    /** One method's source, stopping before the next member's javadoc. */
    private static String methodBody(String source, String methodName) {
        int start = source.indexOf(" " + methodName + "(");
        assertTrue(start >= 0, "method '" + methodName + "' is gone from the fixture");
        int end = source.length();
        for (String boundary : List.of("\n    /**", "\n    public ", "\n    private ")) {
            int next = source.indexOf(boundary, start);
            if (next >= 0) {
                end = Math.min(end, next);
            }
        }
        return source.substring(start, end);
    }
}
