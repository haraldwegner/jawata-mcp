package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.CompileWorkspaceTool;
import org.goja.mcp.tools.MoveMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a P1-a.1 — {@code move_method} (Move Instance Method) on the
 * {@code move-method} composition fixture.
 */
class MoveMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private MoveMethodTool tool;
    private ObjectMapper mapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("move-method");
        tool = new MoveMethodTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        mapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    /** Zero-based [line, column] of the first occurrence of {@code needle} in {@code text}. */
    private static int[] posOf(String text, String needle) {
        int idx = text.indexOf(needle);
        int line = 0, col = 0;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }

    @Test
    @DisplayName("happy: move Mover.reset(Cell) onto Cell, rewrite the call site, stay compiling")
    void happy_moveResetOntoCell() throws Exception {
        Path mover = projectPath.resolve("src/main/java/com/example/Mover.java");
        Path cell = projectPath.resolve("src/main/java/com/example/Cell.java");
        Path client = projectPath.resolve("src/main/java/com/example/Client.java");
        int[] pos = posOf(Files.readString(mover), "reset(Cell");

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", mover.toString());
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        args.put("target", "c");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "move_method must succeed; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"), "an undo handle must be returned");

        String cellAfter = Files.readString(cell);
        String moverAfter = Files.readString(mover);
        String clientAfter = Files.readString(client);

        assertTrue(cellAfter.contains("reset("), "reset must now live on Cell:\n" + cellAfter);
        assertFalse(moverAfter.contains("reset(Cell"), "reset(Cell) must be gone from Mover:\n" + moverAfter);
        assertTrue(clientAfter.contains(".reset()"),
            "the call site must be rewritten onto the new receiver:\n" + clientAfter);

        // Non-vacuous: the move + call-site rewrite leaves the project compiling.
        CompileWorkspaceTool compile = new CompileWorkspaceTool(() -> service);
        ToolResponse cr = compile.execute(mapper.createObjectNode());
        assertTrue(cr.isSuccess(), "compile must succeed; got: " + cr.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> cdata = (Map<String, Object>) cr.getData();
        assertEquals(0, ((Number) cdata.get("errorCount")).intValue(),
            "the moved method + rewritten call site must compile cleanly; diagnostics=" + cdata.get("diagnostics"));
    }

    @Test
    @DisplayName("target inference: with a single possible target, no target arg is needed")
    void happy_inferredSingleTarget() throws Exception {
        Path mover = projectPath.resolve("src/main/java/com/example/Mover.java");
        int[] pos = posOf(Files.readString(mover), "reset(Cell");

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", mover.toString());
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        // no target — exactly one possible target (c) should be inferred

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "single-target move must succeed without a target arg; got: " + r.getError());
    }

    @Test
    @DisplayName("validation: missing filePath returns INVALID_PARAMETER")
    void validation_missingFilePath() {
        ObjectNode args = mapper.createObjectNode();
        args.put("line", 0);
        args.put("column", 0);
        assertFalse(tool.execute(args).isSuccess());
    }
}
