package org.jawata.mcp.tools.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28h-C27-20: no tool added a method, so a developer writing a test first wrote the empty stub the test needs to fail for its behaviour by
 * hand. {@code generate kind=add_method} adds it: a stub that throws on a class, a bare signature on an interface.
 */
class AddMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateTool generate;
    private org.jawata.mcp.tools.UndoRefactoringTool undo;
    private ObjectMapper json;
    private Path classFile;
    private Path interfaceFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        generate = new GenerateTool(() -> service, cache);
        undo = new org.jawata.mcp.tools.UndoRefactoringTool(() -> service, cache, org.jawata.mcp.fixtures.JavaLifecycle.changes(service));
        json = new ObjectMapper();
        Path sources = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example");
        classFile = sources.resolve("UnusedCode.java");
        interfaceFile = sources.resolve("IspTargets.java");
    }

    private ObjectNode request(Path file, int line, int column, String name) {
        ObjectNode arguments = json.createObjectNode();
        arguments.put("kind", "add_method");
        arguments.put("filePath", file.toString());
        arguments.put("line", line);
        arguments.put("column", column);
        arguments.put("methodName", name);
        return arguments;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse response) {
        assertTrue(response.isSuccess(), "the tool must succeed; got: " + response.getError());
        return (Map<String, Object>) response.getData();
    }

    @Test
    @DisplayName("a class gets a stub whose body throws, so a test of its behaviour fails for the behaviour and not for a missing method")
    void aClassGetsAThrowingStub() throws Exception {
        ObjectNode arguments = request(classFile, 7, 4, "describe");
        arguments.put("returnType", "String");
        arguments.putArray("parameters").addObject().put("name", "label").put("type", "String");
        arguments.withArray("parameters").addObject().put("name", "times").put("type", "int");

        Map<String, Object> data = data(generate.execute(arguments));

        String written = Files.readString(classFile);
        assertTrue(written.contains("public String describe(String label, int times)"), "the signature is not on disk:\n" + written);
        assertTrue(written.contains("throw new UnsupportedOperationException("), "the stub does not throw:\n" + written);
        assertEquals(java.util.List.of("describe"), data.get("methodsAdded"));
        assertNotNull(data.get("undoChangeId"), "the change must be reversible");
    }

    @Test
    @DisplayName("undo puts the file back byte for byte")
    void undoRestoresTheFile() throws Exception {
        String original = Files.readString(classFile);
        ObjectNode arguments = request(classFile, 7, 4, "describe");
        String undoChangeId = (String) data(generate.execute(arguments)).get("undoChangeId");

        ToolResponse undone = undo.execute(json.createObjectNode().put("undoChangeId", undoChangeId));

        assertTrue(undone.isSuccess(), "undo failed: " + undone.getError());
        assertEquals(original, Files.readString(classFile));
    }

    @Test
    @DisplayName("an interface gets the bare signature and no body")
    void anInterfaceGetsTheSignatureAlone() throws Exception {
        ObjectNode arguments = request(interfaceFile, 20, 12, "r");
        arguments.put("returnType", "int");

        data(generate.execute(arguments));

        String written = Files.readString(interfaceFile);
        int declared = written.indexOf("interface CohesiveService");
        String body = written.substring(declared, written.indexOf("}", declared));
        assertTrue(body.contains("int r();"), "the signature is not in the interface:\n" + body);
        assertFalse(body.contains("throw"), "an interface method must not get a body:\n" + body);
    }

    @Test
    @DisplayName("28h z105: an interface with one abstract method gets a default method, so lambdas and implementors still compile")
    void aFunctionalShapeGetsADefaultMethod() throws Exception {
        Path targets = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/SpeculativeGeneralityTargets.java");
        ObjectNode arguments = request(targets, 18, 10, "count");
        arguments.put("returnType", "int");

        Map<String, Object> data = data(generate.execute(arguments));

        String written = Files.readString(targets);
        assertTrue(written.contains("default int count()"), "THE CLAIM: the method is not a default one:\n" + written);
        assertTrue(written.contains("throw new UnsupportedOperationException("), "the default method has no throwing body:\n" + written);
        assertTrue(String.valueOf(data.get("note")).contains("default"), "the answer does not say why: " + data.get("note"));
    }

    @Test
    @DisplayName("28h z105: an interface that something implements gets a default method, whatever its number of methods")
    void anImplementedInterfaceGetsADefaultMethod() throws Exception {
        Files.writeString(interfaceFile, Files.readString(interfaceFile)
            + "\nabstract class CohesiveImpl implements CohesiveService {\n}\n");
        service.getJavaProject().getProject().refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
        ObjectNode arguments = request(interfaceFile, 20, 12, "r");

        data(generate.execute(arguments));

        String written = Files.readString(interfaceFile);
        assertTrue(written.contains("default void r()"), "THE CLAIM: an implemented interface got an abstract method:\n" + written);
    }

    @Test
    @DisplayName("a method that is already there is refused and the file is left as it was")
    void anExistingMethodIsRefused() throws Exception {
        String original = Files.readString(classFile);

        ToolResponse response = generate.execute(request(classFile, 7, 4, "unusedPrivateMethod"));

        assertFalse(response.isSuccess(), "a second method of the same name and parameter count was added");
        assertEquals(original, Files.readString(classFile));
    }

    @Test
    @DisplayName("a return type that is no type, and a name that is no name, are refused and nothing is written")
    void aMalformedSignatureIsRefused() throws Exception {
        String original = Files.readString(classFile);
        for (String notAType : java.util.List.of("String; drop()", "List<String", "int x, int y", "Strin g", "")) {
            ObjectNode badType = request(classFile, 7, 4, "fine");
            badType.put("returnType", notAType);
            assertFalse(generate.execute(badType).isSuccess(), "'" + notAType + "' was accepted as a return type");
        }
        assertFalse(generate.execute(request(classFile, 7, 4, "not a name")).isSuccess(), "a name with spaces was accepted");
        assertEquals(original, Files.readString(classFile));
    }
}
