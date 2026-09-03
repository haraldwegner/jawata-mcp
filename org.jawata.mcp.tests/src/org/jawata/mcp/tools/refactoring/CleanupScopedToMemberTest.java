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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — a cleanup answers about the MEMBER a finding named, not the file
 * around it.
 *
 * <p>C3's per-row contract says every row must be "callable straight from the finding that
 * names it, by symbol name and by file position". An audit found that unmet on all nine
 * rows, for one reason: {@code apply_cleanup} took a file and nothing finer, so a finding
 * about one method rewrote every occurrence in the file. A caller who asked a narrow
 * question got a wide answer, silently.</p>
 *
 * <p>The discriminator here is the PAIR. Asserting that the named method was rewritten
 * proves nothing on its own — the unscoped sweep rewrites it too. What proves the
 * narrowing is that the method NOT named comes back untouched in the same fixture where
 * the unscoped run converts it.</p>
 */
class CleanupScopedToMemberTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path target;
    private String before;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        target = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/ReturnModifiedValueTargets.java");
        before = Files.readString(target, StandardCharsets.UTF_8);
    }

    private ObjectNode args() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "return_modified_value");
        args.put("filePath", target.toString());
        return args;
    }

    /** One method's source, stopping before the next member. */
    private static String methodBody(String source, String methodName) {
        int start = source.indexOf(" " + methodName + "(");
        assertTrue(start >= 0, "method '" + methodName + "' is gone from the fixture");
        int end = source.length();
        for (String boundary : java.util.List.of("\n    /**", "\n    public ", "\n    private ")) {
            int next = source.indexOf(boundary, start);
            if (next >= 0) {
                end = Math.min(end, next);
            }
        }
        return source.substring(start, end);
    }

    /** Zero-based line of the first line containing this marker. */
    private int lineOf(String marker) {
        String[] lines = before.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: the fixture no longer contains " + marker);
    }

    @Test
    @DisplayName("without a position the whole file is rewritten — the control for the case below")
    void unscopedRewritesEveryMatch() throws Exception {
        ToolResponse r = tool.execute(args());
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        String after = Files.readString(target, StandardCharsets.UTF_8);

        assertFalse(methodBody(after, "describe").contains("String result"),
            "describe is converted:\n" + after);
        assertFalse(methodBody(after, "band").contains("int result"),
            "and so is band:\n" + after);
    }

    @Test
    @DisplayName("with a position only that member changes, and the other match survives")
    void scopedRewritesOnlyTheNamedMember() throws Exception {
        ObjectNode args = args();
        args.put("line", lineOf("String describe("));
        args.put("column", 4);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        String after = Files.readString(target, StandardCharsets.UTF_8);

        assertFalse(methodBody(after, "describe").contains("String result"),
            "the member the caller pointed at IS rewritten:\n" + after);
        // THE POINT. The control above shows the unscoped run converts this one too, so
        // its survival here is the narrowing and not a rule that declined.
        assertTrue(methodBody(after, "band").contains("int result"),
            "and the member nobody named is untouched — the unscoped run converts it,"
                + " so this is the position doing work:\n" + after);
    }

    @Test
    @DisplayName("a position pointing at a member with nothing to clean changes nothing at all")
    void aQuietMemberYieldsNoChange() throws Exception {
        ObjectNode args = args();
        args.put("line", lineOf("int accumulates("));
        args.put("column", 4);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "a no-op is a success, not an error; got: " + r.getError());
        assertEquals(before, Files.readString(target, StandardCharsets.UTF_8),
            "the rule refuses this member, so narrowing to it leaves the file alone");
    }

    @Test
    @DisplayName("a position without a file is refused rather than guessed at")
    void aPositionWithoutAFileIsRefused() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "return_modified_value");
        args.put("line", 10);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "a line number with no file names no member anywhere");
        assertEquals(before, Files.readString(target, StandardCharsets.UTF_8),
            "and nothing may be written on a refusal");
    }
}
