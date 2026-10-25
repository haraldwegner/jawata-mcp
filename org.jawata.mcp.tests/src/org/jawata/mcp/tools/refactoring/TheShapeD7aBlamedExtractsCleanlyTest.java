package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExtractMethodTool;
import org.jawata.mcp.tools.ExtractVariableTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S3 / D7a — THE REFUTATION, pinned so nobody spends the morning I spent.
 *
 * <p>On one file in a foreign project ({@code pipeline/OrbEventProcessor.java})
 * {@code extract kind=method} answers
 * {@code IllegalStateException: source not specified at ASTParser.createAST} for EVERY
 * selection. The defect record names what it believed distinguished that file: <em>"two
 * nested interfaces (InferenceSubmitter, EntryDecisionHandler), a switch over an enum"</em>.</p>
 *
 * <p><b>Those features are NOT the cause.</b> {@code EventProcessorShape} carries both —
 * two nested interfaces declared between the fields and the constructor, and an arrow
 * switch over a nested enum — and extracts cleanly here. The controls that bound the
 * search, measured the same day: {@code extract kind=variable} stages a clean diff on that
 * same real method; {@code extract kind=method} stages cleanly on a sibling in the same
 * project FOUR TIMES larger, so file size is out in the direction that matters; and the
 * same operation reaches JDT's own precondition check on a jawata-mcp file, so it is not
 * broken in general.</p>
 *
 * <p>What this class is FOR, therefore, is not reproducing the defect — it cannot. It
 * fixes the negative result, so a later reader does not re-adopt the refuted hypothesis,
 * and it goes red if a change to the extract path ever breaks this shape. The cause is
 * still open and needs the resident's own stack; see the plan's S3 record.</p>
 */
class TheShapeD7aBlamedExtractsCleanlyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ExtractMethodTool tool;
    private ExtractVariableTool variableTool;
    private ObjectMapper objectMapper;
    private Path shapeFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new ExtractMethodTool(() -> service, cache);
        variableTool = new ExtractVariableTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        shapeFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/EventProcessorShape.java");
    }

    @Test
    void extractMethodOnTheShape() throws Exception {
        // handleTick's two statements. ZERO-BASED, which is the tool's own contract.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", shapeFile.toString());
        args.put("startLine", 46);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 42);
        args.put("auto_apply", false);
        args.put("methodName", "bumpAndLog");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> "D7a: " + response.getError());
    }

    /** The control: the same unit, extract VARIABLE, which works on the real file. */
    @Test
    void extractVariableOnTheShape() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", shapeFile.toString());
        args.put("startLine", 47);
        args.put("startColumn", 27);
        args.put("endLine", 47);
        args.put("endColumn", 40);
        args.put("variableName", "line");
        args.put("auto_apply", false);

        ToolResponse response = variableTool.execute(args);
        assertTrue(response.isSuccess(), () -> "control: " + response.getError());
    }
}
