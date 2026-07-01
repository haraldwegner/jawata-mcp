package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.RefactorToPatternTool;
import org.goja.mcp.tools.UndoRefactoringTool;
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
 * Sprint 19 — compose_method via refactor_to_pattern (dependent recipe / RecipeEngine).
 * Fixture com.example.ComposeMethodTargets: ReportBuilder.build() with a header
 * section (0-based line 13) and a footer section (0-based line 18), both single
 * statements ending at column 44.
 */
class ComposeMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RefactorToPatternTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper mapper;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new RefactorToPatternTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        targetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/ComposeMethodTargets.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode section(int startLine, int endLine, String name) {
        ObjectNode s = mapper.createObjectNode();
        s.put("startLine", startLine);
        s.put("startColumn", 8);
        s.put("endLine", endLine);
        s.put("endColumn", 44);
        s.put("methodName", name);
        return s;
    }

    private ObjectNode composeArgs() {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "compose_method");
        n.put("filePath", targetFile.toString());
        ArrayNode sections = n.putArray("sections");
        sections.add(section(13, 13, "writeHeader"));
        sections.add(section(18, 18, "writeFooter"));
        return n;
    }

    @Test
    @DisplayName("extracts both sections (bottom-up), applies atomically, undo restores")
    void composeMethod_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(targetFile);

        ToolResponse response = tool.execute(composeArgs());
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals(2, data.get("sectionsExtracted"));
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(targetFile);
        assertTrue(onDisk.contains("writeHeader()"), "build() should call writeHeader():\n" + onDisk);
        assertTrue(onDisk.contains("writeFooter()"), "build() should call writeFooter():\n" + onDisk);
        assertTrue(onDisk.contains("private void writeHeader()"), "writeHeader method created:\n" + onDisk);
        assertTrue(onDisk.contains("private void writeFooter()"), "writeFooter method created:\n" + onDisk);
        // the extracted statements' literals moved into the new methods, out of build()'s inline body
        assertTrue(onDisk.contains("\"header line 1\""), "header literal preserved:\n" + onDisk);
        assertTrue(onDisk.contains("\"footer line 2\""), "footer literal preserved:\n" + onDisk);

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(targetFile), "undo must restore the original byte-for-byte");
    }

    @Test
    @DisplayName("fewer than two sections is rejected without touching disk")
    void rejectsSingleSection() throws Exception {
        String original = Files.readString(targetFile);
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "compose_method");
        args.put("filePath", targetFile.toString());
        args.putArray("sections").add(section(13, 13, "writeHeader"));

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(targetFile));
    }

    @Test
    @DisplayName("auto_apply=false is rejected for the multi-step recipe")
    void rejectsStaging() throws Exception {
        String original = Files.readString(targetFile);
        ObjectNode args = composeArgs();
        args.put("auto_apply", false);

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(targetFile));
    }
}
