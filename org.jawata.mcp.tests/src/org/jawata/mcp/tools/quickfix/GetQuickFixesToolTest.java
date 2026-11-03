package org.jawata.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.GetQuickFixesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetQuickFixesTool.
 * Tests getting available quick fixes for problems at positions.
 */
class GetQuickFixesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetQuickFixesTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetQuickFixesTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getFixes(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("fixes");
    }

    @Test
    @DisplayName("returns complete response structure with fixes list")
    void returnsCompleteResponseStructure() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify response structure
        assertNotNull(data.get("filePath"));
        assertEquals(5, data.get("line"));
        assertNotNull(data.get("fixes"));

        // Verify fixes is a list
        List<Map<String, Object>> fixes = getFixes(data);
        assertNotNull(fixes);
    }

    @Test
    @DisplayName("works with optional column parameter")
    void worksWithColumnParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("fixes"));
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 0);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("requires line parameter")
    void requiresLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("handles non-existent file")
    void handlesNonExistentFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("NonExistent.java").toString());
        args.put("line", 0);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    /**
     * REL44-22. This asked about line 0 of a file with no problem on it, and checked the fixes'
     * structure only inside {@code if (!fixes.isEmpty())} — so it could never check anything, and
     * it stayed green while the tool answered zero problems on EVERY file (REL44-26). It now asks
     * about a line that has a problem, in a copy of the fixture it is free to break.
     */
    @Test
    @DisplayName("returns fixes with fixId and label when problems exist")
    void returnsFixesWithStructure() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy("simple-maven");
        java.nio.file.Path broken = copy.getProjectRoot()
            .resolve("src/main/java/com/example/HasAProblem.java");
        java.nio.file.Files.writeString(broken, """
            package com.example;

            public class HasAProblem {
                Object made = new ArrayList<String>();
            }
            """);
        new org.jawata.resident.workspace.OwnedReconciler(() -> copy).syncBeforeCall();

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", broken.toString());
        args.put("line", 3);

        ToolResponse response = new GetQuickFixesTool(() -> copy).execute(args);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> fixes = getFixes(data);

        assertFalse(fixes.isEmpty(), "a line with an unresolved type has fixes: " + data);
        Map<String, Object> fix = fixes.get(0);
        assertNotNull(fix.get("fixId"));
        assertNotNull(fix.get("label"));
    }

    /**
     * Sprint 28h, S16f (g). The editor cancels the request it no longer wants by interrupting the thread working it, and the file's parse
     * is then cancelled by JDT with an exception that has no message; the tool answered it as an internal error, the same answer as a
     * bug in the tool. A parse cancelled that way is answered as cancelled, which says to ask again.
     */
    @Test
    @DisplayName("a request whose thread is interrupted while its file is parsed is answered as cancelled, not as an internal error")
    void aCancelledParseIsNotAnInternalError() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy("simple-maven");
        java.nio.file.Path broken = copy.getProjectRoot().resolve("src/main/java/com/example/HasAProblem.java");
        java.nio.file.Files.writeString(broken, """
            package com.example;

            public class HasAProblem {
                Object made = new ArrayList<String>();
            }
            """);
        new org.jawata.resident.workspace.OwnedReconciler(() -> copy).syncBeforeCall();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", broken.toString());
        args.put("line", 3);

        ToolResponse response;
        Thread.currentThread().interrupt();
        try {
            response = new GetQuickFixesTool(() -> copy).execute(args);
        } finally {
            Thread.interrupted();
        }

        assertTrue(response.isSuccess() || "OPERATION_CANCELLED".equals(response.getError().getCode()),
            "an interrupted parse was answered as: " + response.getError());
    }

    /**
     * Sprint 28h, S16e (b), the audit's finding 3. A type of the default package cannot be imported, and offering it made the list of fixes
     * fail on the package it does not have. With one in the project, the import of the library type is still offered and the default
     * package's is not.
     */
    @Test
    @DisplayName("a type of the default package is not offered as an import and does not spoil the fixes: the import of the library type is still offered")
    void aTypeInTheDefaultPackageIsNotOfferedAndDoesNotSpoilTheFixes() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy("simple-maven");
        java.nio.file.Files.writeString(copy.getProjectRoot().resolve("src/main/java/ArrayList.java"), "public class ArrayList {\n}\n");
        java.nio.file.Path broken = copy.getProjectRoot().resolve("src/main/java/com/example/HasAProblem.java");
        java.nio.file.Files.writeString(broken, """
            package com.example;

            public class HasAProblem {
                Object made = new ArrayList<String>();
            }
            """);
        new org.jawata.resident.workspace.OwnedReconciler(() -> copy).syncBeforeCall();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", broken.toString());
        args.put("line", 3);

        ToolResponse response = new GetQuickFixesTool(() -> copy).execute(args);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        List<String> imports = getFixes(getData(response)).stream().map(f -> String.valueOf(f.get("fixId"))).filter(id -> id.startsWith("add_import:")).toList();
        assertTrue(imports.contains("add_import:java.util.ArrayList"), "the library type is offered: " + imports);
        assertTrue(!imports.contains("add_import:ArrayList"), "a type that cannot be imported was offered: " + imports);
    }

    /**
     * Sprint 28h, S16e (b). A type search that FAILED was logged at debug level and answered as an empty list of fixes, which is the same
     * answer as "no type of that name exists": the editor's import fix vanished and nothing said why (about 2 of 37 window runs on a Maven
     * tree). A failure is now an error answer, and a search that finds nothing stays a real answer of no import fix.
     */
    @Test
    @DisplayName("a failed type search is an error answer and not an empty list of fixes; a search that finds nothing is still an answer of no import fix")
    void aFailedTypeSearchIsNotAnEmptyListOfFixes() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy("simple-maven");
        java.nio.file.Path broken = copy.getProjectRoot().resolve("src/main/java/com/example/HasAProblem.java");
        java.nio.file.Files.writeString(broken, """
            package com.example;

            public class HasAProblem {
                Object made = new ArrayList<String>();
            }
            """);
        new org.jawata.resident.workspace.OwnedReconciler(() -> copy).syncBeforeCall();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", broken.toString());
        args.put("line", 3);

        ToolResponse failed = new GetQuickFixesTool(() -> copy,
            (service, typeName, requestor) -> { throw new IllegalStateException("the index is not ready"); }).execute(args);
        ToolResponse foundNothing = new GetQuickFixesTool(() -> copy, (service, typeName, requestor) -> { }).execute(args);

        assertAll(
            () -> assertFalse(failed.isSuccess(), "a failed search was answered as success: " + failed.getData()),
            () -> assertEquals("FIX_SEARCH_FAILED", failed.getError().getCode()),
            () -> assertTrue(failed.getError().getMessage().contains("ArrayList"), failed.getError().getMessage()),
            () -> assertTrue(foundNothing.isSuccess(), () -> String.valueOf(foundNothing.getError())),
            () -> assertTrue(getFixes(getData(foundNothing)).stream().noneMatch(f -> String.valueOf(f.get("fixId")).startsWith("add_import:")),
                "control: a search that found nothing offered an import"));
    }
}
