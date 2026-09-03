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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 52 — Replace Nested Conditional with Guard Clauses.
 *
 * <p>The REFUSALS carry this test. A rewrite that drops an {@code else} without checking
 * that the {@code if} branch always leaves is not a noisy tool, it is a corrupting one:
 * it would run both bodies when the condition holds. Three shapes in the fixture are
 * exactly that case, and each must come through untouched.</p>
 */
class GuardClausesToolTest {

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
            .resolve("src/main/java/com/example/GuardClauseTargets.java");
    }

    private ToolResponse run() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "guard_clauses");
        args.put("filePath", target.toString());
        return tool.execute(args);
    }

    private String rewritten() throws Exception {
        ToolResponse r = run();
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("one level per run, and a second run takes the next — proven, not promised")
    void theNestingIsUnwrappedOneLevelAtATime() throws Exception {
        String afterFirst = rewritten();

        String nested = methodBody(afterFirst, "nestedReturns");
        assertTrue(nested.contains("return 1;") && nested.contains("return 2;")
                && nested.contains("return 3;"),
            "all three answers must still be there — this moves code, it removes none:\n"
                + nested);
        assertEquals(1, count(nested, "else"),
            "the OUTER else is gone and the inner one is not, which is the documented"
                + " contract: a nested candidate lives inside the block its ancestor is"
                + " removing, so one pass cannot do both. Body:\n" + nested);

        // THE RE-RUN, executed rather than described. The description tells a caller to
        // run it again; a claim about what a second run does is worth exactly as much as
        // a second run.
        String afterSecond = rewritten();
        String twice = methodBody(afterSecond, "nestedReturns");
        assertEquals(0, count(twice, "else"),
            "the second run takes the level the first left, because unwrapping the outer"
                + " if made the inner one a sibling in the parent block:\n" + twice);
        assertTrue(twice.contains("return 1;") && twice.contains("return 2;")
                && twice.contains("return 3;"),
            "and still all three answers:\n" + twice);
    }

    @Test
    @DisplayName("a throw leaves the method exactly as a return does")
    void aThrowIsAnExitToo() throws Exception {
        String thrown = methodBody(rewritten(), "throwsThenElse");
        assertEquals(0, count(thrown, "else"),
            "this else guarded a branch that throws, so it is redundant nesting:\n"
                + thrown);
        assertTrue(thrown.contains("return value * 2;"),
            "and the normal path survives as the method's last statement:\n" + thrown);
    }

    /** Occurrences of a word, so an assertion can say "one" rather than "some". */
    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("a branch that falls through is left alone — dropping its else would run both")
    void aFallThroughBranchIsRefused() throws Exception {
        String after = rewritten();
        String body = methodBody(after, "fallsThrough");
        assertTrue(body.contains("else"),
            "this then-branch assigns and continues. Dropping the else would run BOTH"
                + " assignments when the flag is set, which is a different program:\n"
                + body);
    }

    @Test
    @DisplayName("an else-if chain and a break are both left alone")
    void theOtherTwoRefusalsHold() throws Exception {
        String after = rewritten();
        assertTrue(methodBody(after, "chain").contains("else if"),
            "an else-if chain is already the honest form; unwrapping one level of it"
                + " leaves a shape a reader has to re-derive");
        assertTrue(methodBody(after, "breaksOutOfLoop").contains("else"),
            "a break leaves the LOOP, not the method — the else's statements would still"
                + " run, so this is not an exit at this level at all");
    }

    @Test
    @DisplayName("it is one reversible change, like every other cleanup")
    void theChangeIsReversible() {
        ToolResponse r = run();
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"),
            "a cleanup sweep returns one undo handle — this kind joins that contract"
                + " rather than inventing one: " + data);
        assertTrue(data.get("filesModified") instanceof List<?> files && !files.isEmpty(),
            "and it must name what it changed: " + data);
    }

    /**
     * The source of one method, ending before the NEXT member's javadoc.
     *
     * <p>Cutting at the next {@code public} was wrong and the test caught it: the fixture
     * documents each refusal in a javadoc that explains why dropping the {@code else}
     * would be wrong, so the slice swept the next method's prose in and counted the word
     * it was there to explain. A comment about a keyword is not the keyword.</p>
     */
    private static String methodBody(String source, String methodName) {
        int start = source.indexOf(" " + methodName + "(");
        assertTrue(start >= 0, "method '" + methodName + "' is gone from the fixture");
        int end = source.length();
        for (String boundary : List.of("\n    /**", "\n    public ")) {
            int next = source.indexOf(boundary, start);
            if (next >= 0) {
                end = Math.min(end, next);
            }
        }
        return source.substring(start, end);
    }
}
