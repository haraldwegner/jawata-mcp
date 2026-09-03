package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.jawata.mcp.tools.RefactorToPatternTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — the per-row parity batteries, and an honest label for what they
 * are.
 *
 * <h2>What a golden proves here, and what it does not</h2>
 *
 * <p>The existing {@link ApplyCleanupParityTest} compares against goldens captured from
 * the OLD hand-rolled rewrite BEFORE it was migrated onto a JDT engine, so a divergence
 * there is a genuine before-and-after. <b>Stage 3's rows have no "before".</b> They are
 * new operations, so a golden recorded from them is a snapshot of themselves.</p>
 *
 * <p>So say plainly what these are: <b>regression locks</b>. They pin the exact edit each
 * row produces on its fixture, and they fail the day it changes for any reason — a JDT
 * upgrade, a refactor of the rule, a fixture edit. That is worth having and it is not a
 * proof that behaviour is preserved.</p>
 *
 * <p>What DOES carry behaviour preservation, per row, and where to look for it:</p>
 * <ul>
 *   <li>the apply pipeline compiles every modified file and reverts the change when new
 *       errors appear — a staged or applied success is compile-verified;</li>
 *   <li>each row's {@code <Name>ToolTest} pins the cases it must REFUSE, and every one of
 *       those refusals exists because the rewrite would otherwise compile and behave
 *       differently — that is where the real behavioural reasoning lives;</li>
 *   <li>each row's {@code <Name>ForkSliceTest}, where one exists, runs it on code written
 *       by someone who never heard of the operation.</li>
 * </ul>
 *
 * <p>Recording or refreshing a golden: {@code -Djawata.test.parity.record=true}.</p>
 */
final class RowParity {

    private static final List<String> HEADER = List.of("kind", "editCount", "filesChanged");

    private RowParity() {
    }

    /** A sweep row: stage the change and pin the planned diff. */
    static void sweepRow(TestProjectHelper helper, String kind, String fixture) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/" + fixture);
        assertTrue(Files.exists(file),
            "PROOF OF LIFE: the fixture " + fixture + " must exist, or this pins nothing");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", kind);
        args.put("filePath", file.toString());
        args.put("auto_apply", false);   // stage: compare the PLANNED change

        ToolResponse response =
            new ApplyCleanupTool(() -> service, new RefactoringChangeCache()).execute(args);
        ParitySupport.assertParity("apply-cleanup", "row-" + kind, response, projectPath,
            helper.getTempDirectory(), HEADER);
    }

    /**
     * Row 8 cannot be staged — a multi-step recipe has no half-applied state, and its own
     * tool refuses {@code auto_apply:false}. So its parity is the RESULTING SOURCE rather
     * than a planned diff: it applies, then pins the file the caller is left holding.
     */
    static void recipeRow(TestProjectHelper helper, String fixture, String caretMarker,
                          ObjectNode extra) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/" + fixture);
        String before = Files.readString(file, StandardCharsets.UTF_8);

        // The caret is FOUND, not counted. A line number in a test is a second copy of
        // the fixture's layout, and it goes stale the first time anyone adds a comment.
        int line = -1;
        String[] lines = before.split("\n", -1);
        for (int i = 0; i < lines.length && line < 0; i++) {
            if (lines[i].contains(caretMarker)) {
                line = i;
            }
        }
        assertTrue(line >= 0,
            "PROOF OF LIFE: the fixture no longer contains " + caretMarker);

        ObjectNode args = extra.deepCopy();
        args.put("kind", "decompose_conditional");
        args.put("filePath", file.toString());
        args.put("line", line);
        args.put("column", 8);
        ToolResponse response =
            new RefactorToPatternTool(() -> service, new RefactoringChangeCache()).execute(args);
        assertTrue(response.isSuccess(), "the recipe must apply; got: " + response.getError());

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(!after.equals(before),
            "PROOF OF LIFE: the recipe changed nothing, so the golden below would pin the"
                + " INPUT and pass forever");
        ParitySupport.assertSourceParity("refactor-to-pattern", "row-decompose_conditional",
            after);
    }
}
