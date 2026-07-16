package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ConvertAnonymousToLambdaTool;
import org.jawata.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ConvertAnonymousToLambdaTool under the Sprint 14b
 * auto-apply contract (temp fixture copy; on-disk verification; undo
 * round-trip).
 */
class ConvertAnonymousToLambdaToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ConvertAnonymousToLambdaTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path anonymousExamplesFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new ConvertAnonymousToLambdaTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        anonymousExamplesFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/AnonymousClassExamples.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode args(int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesFile.toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("converts simple Runnable on disk; undo restores the original")
    void convertsSimpleRunnable_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(anonymousExamplesFile);

        ToolResponse response = tool.execute(args(19, 28)); // new Runnable() in simpleRunnable()

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertNotNull(data.get("interfaceType"));
        assertNotNull(data.get("methodName"));
        assertNotNull(data.get("undoChangeId"));

        // Sprint 25 (spec D1a item 4): JDT performs the real conversion rather than
        // the old tool's synthesized `lambdaExpression` string — verify the on-disk
        // rewrite (same pattern as inline_method's dropped `inlinedCode`).
        String onDisk = Files.readString(anonymousExamplesFile);
        assertTrue(onDisk.contains("->"), "a lambda must be on disk");
        assertNotEquals(original, onDisk);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(anonymousExamplesFile));
    }

    @Test
    @DisplayName("converts Comparator with two parameters")
    void convertsComparator() {
        ToolResponse response = tool.execute(args(33, 31)); // new Comparator<String>()

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        String diff = (String) getData(response).get("diff");
        assertTrue(diff.contains("->"), "conversion must produce a lambda:\n" + diff);
    }

    @Test
    @DisplayName("handles single parameter Consumer")
    void handlesSingleParameter() {
        ToolResponse response = tool.execute(args(45, 55)); // Consumer<String>

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        String diff = (String) getData(response).get("diff");
        assertTrue(diff.contains("->"), "conversion must produce a lambda:\n" + diff);
    }

    @Test
    @DisplayName("generates block body for multiple statements")
    void generatesBlockBodyForMultipleStatements() {
        ToolResponse response = tool.execute(args(98, 28)); // blockBodyExample()

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        String diff = (String) getData(response).get("diff");
        assertTrue(diff.contains("->"), "conversion must produce a lambda:\n" + diff);
        assertTrue(diff.contains("{"), "block body must have braces:\n" + diff);
    }

    @Test
    @DisplayName("handles Supplier with return expression")
    void handlesSupplierWithReturn() {
        ToolResponse response = tool.execute(args(113, 55)); // Supplier in withReturn()
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
    }

    // ========== Rejection Tests ==========

    @Test
    @DisplayName("refuses anonymous class with this reference — without touching disk")
    void refusesAnonymousClassWithThisReference() throws Exception {
        String original = Files.readString(anonymousExamplesFile);

        ToolResponse response = tool.execute(args(58, 27)); // uses this.toString()

        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(anonymousExamplesFile));
    }

    @Test
    @DisplayName("refuses anonymous class with multiple methods")
    void refusesAnonymousClassWithMultipleMethods() {
        ToolResponse response = tool.execute(args(84, 21)); // multipleMethodsExample()
        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("refuses non-functional interface")
    void refusesNonFunctionalInterface() {
        ToolResponse response = tool.execute(args(126, 31)); // extends ArrayList
        assertFalse(response.isSuccess());
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 18);
        args.put("column", 27);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires line and column parameters")
    void requiresLineAndColumn() {
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", anonymousExamplesFile.toString());
        args1.put("column", 27);

        assertFalse(tool.execute(args1).isSuccess());

        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", anonymousExamplesFile.toString());
        args2.put("line", 18);

        assertFalse(tool.execute(args2).isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles non-anonymous class position gracefully")
    void handlesNotAnAnonymousClass() {
        ToolResponse response = tool.execute(args(10, 13)); // Class declaration
        assertFalse(response.isSuccess());
    }
}
