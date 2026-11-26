package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.InlineMethodTool;
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
 * Integration tests for InlineMethodTool under the Sprint 14b auto-apply
 * contract (temp fixture copy; on-disk verification; undo round-trip).
 */
class InlineMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private InlineMethodTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path refactoringTargetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new InlineMethodTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache, org.jawata.mcp.fixtures.JavaLifecycle.changes(service));
        objectMapper = new ObjectMapper();
        refactoringTargetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode args(int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("inlines method call on disk; undo restores the original")
    void inlinesMethodCall_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(args(64, 22)); // int doubled = doubleValue(x);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("doubleValue", data.get("methodName"));
        assertNotNull(data.get("methodClass"));
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(refactoringTargetFile);
        assertNotEquals(original, onDisk, "inlined call must be on disk");
        // Sprint 25 (D1b): JDT's Inline Method performs the real substitution rather
        // than the old tool's synthesized `inlinedCode` string — verify the call site
        // was rewritten (the `doubleValue(x)` call is gone from processValue).
        assertFalse(onDisk.contains("int doubled = doubleValue(x)"),
            "the inlined call must be gone from the call site:\n" + onDisk);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    @Test
    @DisplayName("28h z133: a call that is a lambda's whole body stays an expression lambda, and no other line is re-indented")
    void aLambdaBodyStaysAnExpression() throws Exception {
        Path file = service.getProjectRoot().resolve("src/main/java/com/example/Ledgers.java");
        Files.writeString(file, """
            package com.example;

            public class Ledgers {
                static void open(int plan) {
                    open(plan, 0);
                }

                static void open(int plan, int step) {
                }

                Runnable later() {
                    Runnable first = () -> open(1);
                    return first;
                }
            }
            """);
        new org.jawata.resident.workspace.OwnedReconciler(() -> service).syncBeforeCall();
        ObjectNode arguments = objectMapper.createObjectNode().put("filePath", file.toString()).put("line", 3).put("column", 16);

        ToolResponse response = tool.execute(arguments);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        String after = Files.readString(file);
        assertTrue(after.contains("        Runnable first = () -> open(1, 0);"), "THE CLAIM: the lambda or its line changed:\n" + after);
    }

    @Test
    @DisplayName("28h z133: the same for a method that returns a value, called by a lambda that returns it")
    void aValueLambdaBodyStaysAnExpression() throws Exception {
        Path file = service.getProjectRoot().resolve("src/main/java/com/example/Shelf.java");
        Files.writeString(file, """
            package com.example;

            import java.util.function.Supplier;

            public class Shelf {
                static String open(int plan) {
                    return open(plan, 0);
                }

                static String open(int plan, int step) {
                    return "";
                }

                Supplier<String> later() {
                    Supplier<String> first = () -> open(1);
                    return first;
                }
            }
            """);
        new org.jawata.resident.workspace.OwnedReconciler(() -> service).syncBeforeCall();
        ObjectNode arguments = objectMapper.createObjectNode().put("filePath", file.toString()).put("line", 5).put("column", 18);

        ToolResponse response = tool.execute(arguments);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        String after = Files.readString(file);
        assertTrue(after.contains("        Supplier<String> first = () -> open(1, 0);"), "THE CLAIM: the lambda or its line changed:\n" + after);
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 64);
        args.put("column", 22);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires line and column parameters")
    void requiresLineAndColumn() {
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetFile.toString());
        args1.put("column", 22);

        assertFalse(tool.execute(args1).isSuccess());

        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetFile.toString());
        args2.put("line", 64);

        assertFalse(tool.execute(args2).isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles non-method call position gracefully — without touching disk")
    void handlesNonMethodCallPosition() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(args(15, 19)); // Field declaration

        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(refactoringTargetFile));
    }
}
