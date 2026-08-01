package org.jawata.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindReferencesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FindReferencesTool.
 * Tests finding references across files.
 */
class FindReferencesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindReferencesTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindReferencesTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getReferences(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("references");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Type references returns symbol info, totalCount, and reference locations")
    void typeReferences_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Symbol info
        assertEquals("Calculator", data.get("symbol"));
        assertEquals("Class", data.get("symbolKind"));
        assertNotNull(data.get("totalReferences"));

        // References list with location details
        List<Map<String, Object>> references = getReferences(data);
        assertNotNull(references);
        assertTrue(references.stream().anyMatch(ref ->
            ref.get("filePath") != null &&
            ref.get("filePath").toString().contains("UserService")));

        if (!references.isEmpty()) {
            Map<String, Object> ref = references.get(0);
            assertNotNull(ref.get("filePath"));
            assertNotNull(ref.get("line"));
            assertNotNull(ref.get("column"));
        }
    }

    /**
     * jawata-mcp#5: EVERY type-reference row must carry a .java file and a line —
     * never the JDT project container directory with no line. The row's file now
     * comes from the compilation unit (consistent with the line), so a reference
     * you cannot click is impossible. On this healthy workspace it holds; the
     * synthesized-workspace degradation is verified at the macOS dogfood.
     */
    @Test
    @DisplayName("jawata-mcp#5: type-reference rows carry a .java file and a line, never a directory")
    void typeReferences_everyRowHasFileAndLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        Map<String, Object> data = getData(tool.execute(args));
        List<Map<String, Object>> references = getReferences(data);
        assertNotNull(references);
        assertFalse(references.isEmpty(), "Calculator has references");
        for (Map<String, Object> ref : references) {
            Object filePath = ref.get("filePath");
            assertNotNull(filePath, "a reference must carry a file path");
            assertTrue(filePath.toString().endsWith(".java"),
                "the file path is a .java source file, not a container directory: " + filePath);
            assertNotNull(ref.get("line"), "a reference must carry a line: " + ref);
        }
    }

    @Test
    @DisplayName("Method references returns symbol with containingType")
    void methodReferences_returnsSymbolWithContainingType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("symbol"));
        assertEquals("Method", data.get("symbolKind"));
        assertEquals("Calculator", data.get("containingType"));
        assertNotNull(getReferences(data));
    }

    @Test
    @DisplayName("Field references finds usages across methods")
    void fieldReferences_findsUsagesAcrossMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("symbol"));
        assertEquals("Field", data.get("symbolKind"));

        List<Map<String, Object>> references = getReferences(data);
        assertNotNull(references);
        assertFalse(references.isEmpty());
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("maxResults limits number of references returned")
    void maxResults_limitsReferences() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);
        args.put("maxResults", 1);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> references = getReferences(data);
        assertTrue(references.size() <= 1);
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 5);
        args1.put("column", 10);
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 10);
        assertFalse(tool.execute(args2).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Symbol with no external references returns empty or minimal list")
    void symbolWithNoReferences_returnsEmptyList() {
        String helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(getReferences(data));
    }

    @Test
    @DisplayName("Sprint 14 (bugs.md #12 schema-honesty): description discloses position-based contract + FQN overload")
    void schemaDescription_disclosesPositionContract() {
        String desc = tool.getDescription();
        assertTrue(desc.contains("filePath, line, column"),
            "description must spell out the (filePath, line, column) triple; got:\n" + desc);
        assertTrue(desc.contains("ZERO-BASED"),
            "description must keep the zero-based coordinate warning; got:\n" + desc);
        assertTrue(desc.contains("v1.8.0"),
            "description must reference the v1.8.0 FQN overload; got:\n" + desc);
    }

    @Test
    @DisplayName("Sprint 14 Phase B.2 (bugs.md #12 capability half): FQN form via 'symbol' param resolves and runs the search")
    void fqnForm_typeFqn_returnsReferences() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("symbol", "com.example.HelloWorld");
        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(),
            "FQN form must succeed for a known type; got: " + response.getError());
        Map<String, Object> data = getData(response);
        assertEquals("HelloWorld", data.get("symbol"));
        assertNotNull(getReferences(data));
    }

    /**
     * jawata-mcp#5 — a reference row must be usable or say why it is not.
     *
     * <p>The reported defect: querying a TYPE returned, for every non-declaration match, the
     * JDT project CONTAINER directory as {@code filePath} with {@code line}, {@code column}
     * and {@code context} simply absent. The row looked like an ordinary reference and was
     * not one — a directory is not somewhere a reader can go, and nothing in the response
     * said the resolution had failed. As the issue put it: <em>a reference you cannot click
     * is a count, not a reference.</em></p>
     *
     * <p>This asserts the INVARIANT rather than reproducing the exotic environment (the
     * original needed a synthesized workspace). The invariant holds everywhere and is
     * exactly what was violated, so it guards the whole class of failure rather than one
     * instance of it: <strong>every row either carries a usable source location, or declares
     * itself unresolved with a reason.</strong> Silence about a failure is the one option
     * removed.</p>
     */
    @Test
    @DisplayName("jawata-mcp#5: every reference row is usable or declares itself unresolved")
    void everyReferenceRowIsUsableOrDeclaresItselfUnresolved() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "references");
        args.put("symbol", "com.example.Calculator");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> "got: " + response.getError());
        List<Map<String, Object>> refs = getReferences(getData(response));
        assertFalse(refs.isEmpty(), "the fixture type must have references for this to assert anything");

        for (Map<String, Object> ref : refs) {
            boolean usable = ref.get("filePath") != null && ref.get("line") != null;
            boolean declaredUnresolved = Boolean.FALSE.equals(ref.get("resolved"));

            assertTrue(usable || declaredUnresolved,
                "a row with no line must say so — this row claims to be a reference and cannot "
                    + "be navigated to, which is a failed lookup returned as an ordinary "
                    + "result: " + ref);

            if (declaredUnresolved) {
                assertNotNull(ref.get("unresolvedReason"),
                    "an unresolved row states WHY, so the caller can tell a binary match from a "
                        + "broken workspace: " + ref);
                assertNull(ref.get("line"),
                    "a row cannot be both unresolved and carry a line: " + ref);
            }

            Object path = ref.get("filePath");
            if (path != null) {
                assertFalse(java.nio.file.Files.isDirectory(java.nio.file.Path.of(String.valueOf(path))),
                    "filePath must never name a DIRECTORY — that was the reported defect, the "
                        + "JDT project container standing in for the .java file: " + ref);
            }
        }
    }
}
