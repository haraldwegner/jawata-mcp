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
 * Sprint 28d-rescue, row 7 — Consolidate Conditional Expression.
 *
 * <p>The refusals are the point again, and here they are about a subtler thing than the
 * guard-clause rule's: {@code ||} SHORT-CIRCUITS. Joining two conditions means the second
 * stops being evaluated whenever the first holds, so a condition that does something —
 * calls, assigns, increments — must never be joined. The fixture's counting method is
 * that case made visible: consolidating it would silently reduce how many times a method
 * is called.</p>
 */
class ConsolidateConditionalToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path target;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        target = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/ConsolidateTargets.java");
    }

    private String rewritten() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "consolidate_conditional");
        args.put("filePath", target.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("three checks with one outcome become one check, and the outcome stays once")
    void theRunIsConsolidated() throws Exception {
        String body = methodBody(rewritten(), "disabilityAmount");
        assertEquals(1, count(body, "if ("),
            "three ifs with the same body are one decision written three times:\n" + body);
        assertEquals(2, count(body, "||"),
            "and the three conditions are joined by two ors:\n" + body);
        assertEquals(1, count(body, "return 0;"),
            "the shared outcome is kept once, not repeated per condition:\n" + body);
        for (String condition : List.of("seniority < 2", "monthsDisabled > 12", "partTime")) {
            assertTrue(body.contains(condition),
                "every original condition must survive — this joins them, it drops none."
                    + " Missing '" + condition + "' from:\n" + body);
        }
    }

    @Test
    @DisplayName("a throw is an outcome like any other")
    void identicalThrowsConsolidateToo() throws Exception {
        String body = methodBody(rewritten(), "refuse");
        assertEquals(1, count(body, "if ("), "two checks, one throw:\n" + body);
        assertEquals(1, count(body, "throw new"),
            "and the throw is written once:\n" + body);
    }

    @Test
    @DisplayName("a condition that CALLS something is refused — || would stop calling it")
    void aCallingConditionIsRefused() throws Exception {
        String body = methodBody(rewritten(), "countsItsChecks");
        assertEquals(2, count(body, "if ("),
            "expensiveCheck increments a counter. Joined with ||, it stops being called"
                + " whenever the first condition holds, so the program counts differently"
                + " — which is not a refactoring:\n" + body);
    }

    @Test
    @DisplayName("an increment in a condition is the same refusal, in the shape that looks harmless")
    void anIncrementingConditionIsRefused() throws Exception {
        String body = methodBody(rewritten(), "increments");
        assertEquals(2, count(body, "if ("),
            "local++ inside a condition is a side effect, and short-circuiting it away"
                + " changes the value the method returns:\n" + body);
    }

    @Test
    @DisplayName("different bodies are different decisions and stay apart")
    void differentBodiesAreLeftAlone() throws Exception {
        String body = methodBody(rewritten(), "differentBodies");
        assertEquals(2, count(body, "if ("),
            "these two checks return different values, so they are two decisions however"
                + " similar they look:\n" + body);
    }

    @Test
    @DisplayName("a shared body that FALLS THROUGH is refused — joining would run it once, not twice")
    void aFallThroughBodyIsRefused() throws Exception {
        String body = methodBody(rewritten(), "appendsTwice");
        assertEquals(2, count(body, "if ("),
            "both appends run when both conditions hold. Joined with ||, one append"
                + " happens — and the result COMPILES, so neither the suite nor the"
                + " compile gate could have caught it:\n" + body);
    }

    @Test
    @DisplayName("and the same refusal on the shape that looks most joinable")
    void twoCountersAreRefused() throws Exception {
        String body = methodBody(rewritten(), "counts");
        assertEquals(2, count(body, "if ("),
            "two increments with no exit is the most inviting shape for this rewrite and"
                + " the most wrong: joining it halves the count:\n" + body);
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
