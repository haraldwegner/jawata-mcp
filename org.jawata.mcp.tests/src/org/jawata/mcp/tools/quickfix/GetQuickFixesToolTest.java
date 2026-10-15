package org.jawata.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
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
        new org.jawata.core.workspace.StrictDiskSync(() -> copy).syncBeforeCall();

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
}
