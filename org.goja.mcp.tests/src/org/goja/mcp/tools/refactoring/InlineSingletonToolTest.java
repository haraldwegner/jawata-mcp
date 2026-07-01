package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Sprint 19 — inline_singleton via the refactor_to_pattern front door, under the
 * auto-apply contract (temp fixture copy; on-disk verification; undo round-trip).
 * Fixture: com.example.SingletonTargets ({@code Registry} singleton at 0-based 12:6).
 */
class InlineSingletonToolTest {

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
            .resolve("simple-maven/src/main/java/com/example/SingletonTargets.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode args(String kind, int line, int column) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", kind);
        n.put("filePath", targetFile.toString());
        n.put("line", line);
        n.put("column", column);
        return n;
    }

    @Test
    @DisplayName("inlines the singleton on disk; undo restores the original")
    void inlineSingleton_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(targetFile);

        ToolResponse response = tool.execute(args("inline_singleton", 12, 6)); // class Registry
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("Registry", data.get("typeName"));
        assertTrue((int) data.get("callSitesRewritten") >= 2, "both RegistryUser call sites rewritten");
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(targetFile);
        assertTrue(onDisk.contains("public Registry()"), "constructor should be public:\n" + onDisk);
        assertFalse(onDisk.contains("getInstance()"), "accessor should be gone:\n" + onDisk);
        assertFalse(onDisk.contains("INSTANCE"), "holder field should be gone:\n" + onDisk);
        assertTrue(onDisk.contains("new Registry()"), "call sites should use new Registry():\n" + onDisk);

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(targetFile), "undo must restore the original byte-for-byte");
    }

    @Test
    @DisplayName("auto_apply=false stages without touching disk")
    void inlineSingleton_stagesWhenAutoApplyFalse() throws Exception {
        String original = Files.readString(targetFile);
        ObjectNode args = args("inline_singleton", 12, 6);
        args.put("auto_apply", false);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.FALSE, data.get("applied"));
        assertNotNull(data.get("changeId"));
        assertEquals(original, Files.readString(targetFile), "staging must not modify disk");
    }

    @Test
    @DisplayName("non-singleton position is rejected without touching disk")
    void rejectsNonSingleton() throws Exception {
        String original = Files.readString(targetFile);
        ToolResponse response = tool.execute(args("inline_singleton", 40, 6)); // class PlainService
        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(targetFile));
    }

    @Test
    @DisplayName("unknown kind is rejected")
    void rejectsUnknownKind() {
        assertFalse(tool.execute(args("bogus_kind", 12, 6)).isSuccess());
    }
}
