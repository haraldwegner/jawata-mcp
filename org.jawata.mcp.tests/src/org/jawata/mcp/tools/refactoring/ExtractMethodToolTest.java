package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractMethodTool;
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
 * Integration tests for ExtractMethodTool under the Sprint 14b auto-apply
 * contract (temp fixture copy; on-disk verification; undo round-trip).
 */
class ExtractMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ExtractMethodTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path refactoringTargetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new ExtractMethodTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        refactoringTargetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode extractionArgs(String methodName) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("startLine", 44);  // Start of sum calculation loop
        args.put("startColumn", 8);
        args.put("endLine", 47);    // End of loop
        args.put("endColumn", 9);
        if (methodName != null) {
            args.put("methodName", methodName);
        }
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("extracts code block to method on disk; undo restores the original")
    void extractsCodeBlock_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(extractionArgs("calculateSum"));

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals(Boolean.TRUE, data.get("compileVerified"),
            "an applied refactoring must have verified its own compile");
        assertEquals("calculateSum", data.get("methodName"));
        assertNotNull(data.get("signature"), "the JDT engine reports the generated signature");
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(refactoringTargetFile);
        assertTrue(onDisk.contains("calculateSum("),
            "new method and its call must be on disk");
        assertNotEquals(original, onDisk);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    // ========== C13-c / v2.12.1: the shape that broke the hand-rolled engine ==========

    /**
     * The exact dataflow that produced the Stage-14 failure: a variable DECLARED
     * inside the selection, mutated only through METHOD CALLS (no assignment
     * operator anywhere — the old heuristic only recognized {@code =}/{@code ++}),
     * and used AFTER the selection. Written into this test's own fixture copy so
     * the coordinates are exact and no shared fixture changes shape.
     */
    private Path writeDeclaredInSelectionShape() throws Exception {
        Path file = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/GreetCasual.java");
        Files.writeString(file, """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;

            public class GreetCasual {
                public int reportSize(int n) {
                    List<String> box = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        box.add("x" + i);
                    }
                    return box.size();
                }
            }
            """);
        return file;
    }

    @Test
    @DisplayName("a variable DECLARED in the selection, mutated only via method calls, used after — returned, not dropped (the Stage-14 self-refactor shape)")
    void declaredInSelectionUsedAfter_isReturned() throws Exception {
        Path file = writeDeclaredInSelectionShape();
        // Selection = the declaration + the loop (0-based lines 7-10): `box` is
        // declared inside, only `box.add(...)` mutates it, `return box.size()`
        // uses it after. The hand-rolled engine generated a void method here
        // (dropped variable, dangling reference, no compile) and reported success.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("startLine", 7);
        args.put("startColumn", 8);
        args.put("endLine", 10);
        args.put("endColumn", 9);
        args.put("methodName", "fillBox");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("compileVerified"),
            "the result must compile — verified by the tool itself, not the caller");
        String onDisk = Files.readString(file);
        assertTrue(onDisk.contains("fillBox("), "extracted method present");
        assertTrue(onDisk.contains("box = fillBox(") || onDisk.contains("List<String> box = fillBox("),
            "the declared-in-selection variable must come back out of the extracted method: " + onDisk);
    }

    @Test
    @DisplayName("an untransformable selection is REFUSED with the engine's reason — never garbage")
    void untransformableSelection_isRefused() throws Exception {
        Path file = writeDeclaredInSelectionShape();
        String original = Files.readString(file);
        // Mid-statement, spanning partial tokens of two statements — not a set of
        // complete statements; the engine must refuse and touch nothing.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("startLine", 8);
        args.put("startColumn", 13);
        args.put("endLine", 9);
        args.put("endColumn", 20);
        args.put("methodName", "nonsense");

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess(), "must refuse, not generate");
        assertEquals(original, Files.readString(file), "disk untouched");
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires methodName parameter")
    void requiresMethodName() {
        ToolResponse response = tool.execute(extractionArgs(null));
        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("startLine", 44);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 9);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid method names and reserved words — without touching disk")
    void rejectsInvalidMethodNames() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        assertFalse(tool.execute(extractionArgs("123invalid")).isSuccess());
        assertFalse(tool.execute(extractionArgs("while")).isSuccess());

        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    @Test
    @DisplayName("handles invalid range gracefully")
    void handlesInvalidRange() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("startLine", -1);
        args.put("startColumn", -1);
        args.put("endLine", -1);
        args.put("endColumn", -1);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
