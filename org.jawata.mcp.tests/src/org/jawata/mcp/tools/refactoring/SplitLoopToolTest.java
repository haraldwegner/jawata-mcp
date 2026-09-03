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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 63 — Split Loop.
 *
 * <p>The interesting refusal is the last one, and it is not about correctness of the
 * halves at all: an {@code Iterable} may be one-shot, so the second loop would walk
 * nothing and the method would quietly return half an answer. That failure has no
 * symptom at the point of the change, which is exactly the kind this rewrite must not
 * introduce.</p>
 */
class SplitLoopToolTest {

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
            .resolve("src/main/java/com/example/SplitLoopTargets.java");
    }

    private String rewritten() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "split_loop");
        args.put("filePath", target.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("two independent jobs become two loops, each keeping one")
    void theLoopSplits() throws Exception {
        String body = methodBody(rewritten(), "twoJobs");
        assertEquals(2, count(body, "for (Calculator person : people)"),
            "one walk doing two things becomes two walks doing one each:\n" + body);
        assertEquals(1, count(body, "total +="),
            "the first job is in exactly one of them:\n" + body);
        assertEquals(1, count(body, "count++"),
            "and the second in the other — neither is duplicated, which is what a copy"
                + " that forgot to drop a statement would do:\n" + body);
    }

    @Test
    @DisplayName("halves that talk to each other stay in one walk")
    void dependentHalvesAreRefused() throws Exception {
        String body = methodBody(rewritten(), "halvesTalk");
        assertEquals(1, count(body, "for (Calculator person : people)"),
            "the second statement reads the running total. A second pass would see the"
                + " finished total instead, which is a different computation:\n" + body);
    }

    @Test
    @DisplayName("a break has no meaning once the body is in two places")
    void aJumpIsRefused() throws Exception {
        String body = methodBody(rewritten(), "stopsEarly");
        assertEquals(1, count(body, "for (Calculator person : people)"),
            "the exit was defined over the whole body; each half would need its own copy"
                + " of it, and they would not mean the same thing:\n" + body);
    }

    @Test
    @DisplayName("a bare Iterable may be one-shot, and the second walk would find nothing")
    void aOneShotWalkIsRefused() throws Exception {
        String body = methodBody(rewritten(), "overAnIterable");
        assertEquals(1, count(body, "for (Calculator person : people)"),
            "this is the refusal with no symptom at the point of change: a stream's"
                + " iterator or a result-set wrapper yields nothing the second time, and"
                + " the method quietly returns half an answer:\n" + body);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
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
