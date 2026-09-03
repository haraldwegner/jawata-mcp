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
 * Sprint 28d-rescue, row 60 — Return Modified Value, the LOCAL cure for {@code cqs}.
 *
 * <p>Every refusal here produces code that COMPILES, which is why each one needs its own
 * case: a wrong rewrite would pass the compile gate and the suite, and only show up as a
 * behaviour someone eventually misses. The sharpest is
 * {@code logsAfterAssigning} — returning early skips the statement after the assignment,
 * and nothing about the result looks wrong.</p>
 */
class ReturnModifiedValueToolTest {

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
            .resolve("src/main/java/com/example/ReturnModifiedValueTargets.java");
    }

    private String rewritten() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "return_modified_value");
        args.put("filePath", target.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a local that only carries the answer becomes a return per branch")
    void theHolderBecomesDirectReturns() throws Exception {
        String body = methodBody(rewritten(), "describe");
        assertEquals(0, count(body, "String result"),
            "the variable existed only to hold the answer, so it goes:\n" + body);
        assertEquals(2, count(body, "return "),
            "one return per branch, and the trailing one is gone:\n" + body);
        assertTrue(body.contains("return \"negative\";") && body.contains("return \"positive\";"),
            "each branch returns its own value:\n" + body);
    }

    @Test
    @DisplayName("nested branches convert too, as long as every path assigns last")
    void nestedBranchesConvert() throws Exception {
        String body = methodBody(rewritten(), "band");
        assertEquals(0, count(body, "int result"), body);
        assertEquals(3, count(body, "return "), "one per leaf:\n" + body);
    }

    @Test
    @DisplayName("an assignment that is NOT last in its branch is refused — the next statement would stop running")
    void aTrailingStatementIsRefused() throws Exception {
        String body = methodBody(rewritten(), "logsAfterAssigning");
        assertTrue(body.contains("String result"),
            "returning early here skips note(), and the result compiles and looks right —"
                + " which is the only kind of defect this rule can produce:\n" + body);
        assertTrue(body.contains("note();"), "the call must still be reachable:\n" + body);
    }

    @Test
    @DisplayName("a path that leaves the variable unassigned is refused")
    void anUnassignedPathIsRefused() throws Exception {
        String body = methodBody(rewritten(), "fallsThrough");
        assertTrue(body.contains("String result = \"unknown\""),
            "the initializer survives on the missing path, so the trailing return is still"
                + " reachable and there would be nothing to return:\n" + body);
    }

    @Test
    @DisplayName("a variable read before the return is carrying information, not holding an answer")
    void aSecondReadIsRefused() throws Exception {
        String body = methodBody(rewritten(), "readsItself");
        assertTrue(body.contains("String result"),
            "it is read by the isEmpty() check, so removing the declaration would not"
                + " compile and returning early would skip the check:\n" + body);
    }

    @Test
    @DisplayName("accumulation in a loop is a running total, not the method's answer")
    void aLoopAccumulationIsRefused() throws Exception {
        String body = methodBody(rewritten(), "accumulates");
        assertTrue(body.contains("int result = 0"),
            "one return per iteration is not what this loop means:\n" + body);
    }

    @Test
    @DisplayName("an if with no else is refused for the same reason as the unassigned path")
    void anIfWithoutElseIsRefused() throws Exception {
        String body = methodBody(rewritten(), "noElse");
        assertTrue(body.contains("String result = \"unknown\""), body);
        assertTrue(body.contains("note();"), "and the statement after the branch stays:\n" + body);
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
