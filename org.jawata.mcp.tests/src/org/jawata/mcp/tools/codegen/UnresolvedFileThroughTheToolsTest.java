package org.jawata.mcp.tools.codegen;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.AnalyzeFileTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * C27-27, the C27 audit's finding 3: what a Java tool answers for a path it cannot resolve is decided from the loaded projects' roots, and a
 * test of {@code UnresolvedFile} alone cannot see whether a tool hands it those projects. These tests go through real tools over a loaded
 * project, and they say which tools: the generate door, which decides it once for every generate kind, and {@code AnalyzeFileTool} as the
 * representative of the tools that each pass {@code service.allProjects()} themselves. With the projects not handed on (an empty list in place
 * of the loaded ones) the file inside the project is answered as outside every project, and each assertion below turns red. The other call
 * sites are not tested one by one; that is recorded in the sprint 28h plan, Stage 27, with its owner.
 */
class UnresolvedFileThroughTheToolsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GenerateTool generate;
    private AnalyzeFileTool analyze;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        generate = new GenerateTool(() -> service, new RefactoringChangeCache());
        analyze = new AnalyzeFileTool(() -> service);
    }

    private ToolResponse generate(String kind, Path file) {
        ObjectNode arguments = json.createObjectNode();
        arguments.put("kind", kind);
        arguments.put("filePath", file.toString());
        arguments.put("line", 0);
        arguments.put("column", 0);
        arguments.put("methodName", "x");
        arguments.put("returnType", "int");
        arguments.putArray("fields").add("x");
        return generate.execute(arguments);
    }

    @Test
    @DisplayName("the generate door answers a non-Java file inside a loaded project as not a Java source, one outside every loaded project as not a Java project, and a missing path as not found, for four of its kinds")
    void theGenerateDoorHandsItsLoadedProjectsToTheAnswer(@TempDir Path elsewhere) throws Exception {
        Path pom = helper.getFixturePath("simple-maven").resolve("pom.xml");
        Path outside = Files.writeString(elsewhere.resolve("notes.txt"), "x");

        for (String kind : new String[] {"add_method", "constructor", "getters_setters", "tostring"}) {
            ToolResponse inside = generate(kind, pom);
            ToolResponse beside = generate(kind, outside);
            ToolResponse missing = generate(kind, elsewhere.resolve("Missing.java"));

            assertAll(kind,
                () -> assertEquals("NOT_A_JAVA_SOURCE", inside.getError().getCode(), kind + " on a non-Java file inside the project: " + inside.getError().getMessage()),
                () -> assertEquals("NOT_A_JAVA_PROJECT", beside.getError().getCode(), kind + " on a file outside every loaded project: " + beside.getError().getMessage()),
                () -> assertEquals("FILE_NOT_FOUND", missing.getError().getCode(), "CONTROL: " + kind + " on a path that is not there"));
        }
    }

    @Test
    @DisplayName("an analysis tool, which passes the loaded projects itself, answers the same three ways")
    void anAnalysisToolHandsItsLoadedProjectsToTheAnswer(@TempDir Path elsewhere) throws Exception {
        Path pom = helper.getFixturePath("simple-maven").resolve("pom.xml");
        Path outside = Files.writeString(elsewhere.resolve("notes.txt"), "x");

        ToolResponse inside = analyze.execute(json.createObjectNode().put("filePath", pom.toString()));
        ToolResponse beside = analyze.execute(json.createObjectNode().put("filePath", outside.toString()));
        ToolResponse missing = analyze.execute(json.createObjectNode().put("filePath", elsewhere.resolve("Missing.java").toString()));

        assertAll(
            () -> assertEquals("NOT_A_JAVA_SOURCE", inside.getError().getCode(), "a non-Java file inside the project: " + inside.getError().getMessage()),
            () -> assertEquals("NOT_A_JAVA_PROJECT", beside.getError().getCode(), "a file outside every loaded project: " + beside.getError().getMessage()),
            () -> assertEquals("FILE_NOT_FOUND", missing.getError().getCode(), "CONTROL: a path that is not there"));
    }
}
