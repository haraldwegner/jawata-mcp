package org.jawata.mcp.tools.codegen;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
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
 * test of {@code UnresolvedFile} alone cannot see whether each tool hands it those projects. These tests go through a real tool over a loaded
 * project, one delegate of the generate door per kind that has its own call site: with the projects not handed on (an empty list in place of
 * the loaded ones) the file inside the project is answered as outside every project, and each assertion below turns red.
 */
class UnresolvedFileThroughTheToolsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GenerateTool generate;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        generate = new GenerateTool(() -> service, new RefactoringChangeCache());
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
    @DisplayName("a file inside a loaded project that is no Java source of it is answered as not a Java source, and one outside every loaded project as not a Java project, through each generate kind")
    void eachToolHandsItsLoadedProjectsToTheAnswer(@TempDir Path elsewhere) throws Exception {
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
}
