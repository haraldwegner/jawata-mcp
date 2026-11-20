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
 * of the loaded ones) the file inside the project is answered as outside every project, and the inside assertion below turns red (the outside and missing ones are controls and stay green). The other call
 * sites are not tested one by one; that is recorded in the sprint 28h plan, Stage 27, with its owner.
 */
class UnresolvedFileThroughTheToolsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateTool generate;
    private AnalyzeFileTool analyze;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
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
    @DisplayName("the analyze door answers the same three ways for every kind that takes a file: file, method, control_flow, data_flow, javadocs and nullness")
    void theAnalyzeDoorAnswersAnUnresolvedFileTheSameWayForEveryKindThatTakesOne(@TempDir Path elsewhere) throws Exception {
        org.jawata.mcp.tools.AnalyzeTool door = new org.jawata.mcp.tools.AnalyzeTool(() -> service);
        Path pom = helper.getFixturePath("simple-maven").resolve("pom.xml");
        Path outside = Files.writeString(elsewhere.resolve("notes.txt"), "x");
        Path missing = elsewhere.resolve("Missing.java");
        java.util.List<String> differing = new java.util.ArrayList<>();

        for (String kind : new String[] {"file", "method", "control_flow", "data_flow", "javadocs", "nullness"}) {
            java.util.Map<String, String> expected = java.util.Map.of(
                pom.toString(), "NOT_A_JAVA_SOURCE", outside.toString(), "NOT_A_JAVA_PROJECT", missing.toString(), "FILE_NOT_FOUND");
            for (java.util.Map.Entry<String, String> place : expected.entrySet()) {
                ObjectNode arguments = json.createObjectNode().put("kind", kind).put("filePath", place.getKey()).put("line", 0).put("column", 0);
                if (kind.equals("javadocs") || kind.equals("nullness")) {
                    arguments.put("subkind", kind.equals("javadocs") ? "validate" : "check");
                }
                ToolResponse answer = door.execute(arguments);
                String code = answer.getError() == null ? "(no error: " + answer.getData() + ")" : answer.getError().getCode();
                if (!place.getValue().equals(code)) {
                    differing.add(kind + " on " + place.getValue() + "-place answered " + code
                        + (answer.getError() == null ? "" : ": " + answer.getError().getMessage()));
                }
            }
        }

        assertEquals(java.util.List.of(), differing, "kinds of the analyze door that do not answer an unresolved file as the others do");
    }

    private org.jawata.mcp.tools.Tool doorOf(Class<?> type) throws Exception {
        java.util.function.Supplier<org.jawata.core.IJdtService> supplier = () -> service;
        for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && java.util.function.Supplier.class.isAssignableFrom(parameters[0])) {
                return (org.jawata.mcp.tools.Tool) constructor.newInstance(supplier);
            }
            if (parameters.length == 2 && java.util.function.Supplier.class.isAssignableFrom(parameters[0]) && parameters[1] == RefactoringChangeCache.class) {
                return (org.jawata.mcp.tools.Tool) constructor.newInstance(supplier, new RefactoringChangeCache());
            }
            if (parameters.length == 3 && java.util.function.Supplier.class.isAssignableFrom(parameters[0]) && parameters[1] == RefactoringChangeCache.class
                    && java.util.function.Supplier.class.isAssignableFrom(parameters[2])) {
                // rename_symbol also takes the experience store, which an unresolved file is answered without ever reading
                java.util.function.Supplier<Object> noStore = () -> null;
                return (org.jawata.mcp.tools.Tool) constructor.newInstance(supplier, new RefactoringChangeCache(), noStore);
            }
        }
        throw new IllegalStateException(type.getSimpleName() + " has no constructor this test knows how to call");
    }

    @Test
    @DisplayName("the other front doors answer an unresolved file the same three ways for each kind that takes one: extract, inline, quick_fix, apply_cleanup, refactor_to_pattern, get_at_position, the call hierarchy, inspect, validate, diagnostics, organize imports and format")
    void theOtherDoorsAnswerAnUnresolvedFileTheSameWayForEveryKindThatTakesOne(@TempDir Path elsewhere) throws Exception {
        Path pom = helper.getFixturePath("simple-maven").resolve("pom.xml");
        Path outside = Files.writeString(elsewhere.resolve("notes.txt"), "x");
        Path missing = elsewhere.resolve("Missing.java");
        java.util.Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put(pom.toString(), "NOT_A_JAVA_SOURCE");
        expected.put(outside.toString(), "NOT_A_JAVA_PROJECT");
        expected.put(missing.toString(), "FILE_NOT_FOUND");
        String position = "\"line\":0,\"column\":0";
        String range = "\"startLine\":0,\"startColumn\":0,\"endLine\":1,\"endColumn\":0";
        java.util.List<Object[]> rows = java.util.List.of(
            new Object[] {org.jawata.mcp.tools.ExtractTool.class, "{\"kind\":\"method\"," + range + ",\"methodName\":\"x\"}"},
            new Object[] {org.jawata.mcp.tools.ExtractTool.class, "{\"kind\":\"variable\"," + range + "}"},
            new Object[] {org.jawata.mcp.tools.ExtractTool.class, "{\"kind\":\"constant\"," + range + ",\"constantName\":\"X\"}"},
            new Object[] {org.jawata.mcp.tools.ExtractTool.class, "{\"kind\":\"interface\"," + position + ",\"interfaceName\":\"I\"}"},
            new Object[] {org.jawata.mcp.tools.InlineTool.class, "{\"kind\":\"method\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.InlineTool.class, "{\"kind\":\"variable\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.QuickFixTool.class, "{\"action\":\"list\",\"line\":0}"},
            new Object[] {org.jawata.mcp.tools.ApplyCleanupTool.class, "{\"kind\":\"add_final\"}"},
            new Object[] {org.jawata.mcp.tools.RefactorToPatternTool.class, "{\"kind\":\"refactor_to_state\"," + position + ",\"states\":[\"a\",\"b\"]}"},
            new Object[] {org.jawata.mcp.tools.RefactorToPatternTool.class, "{\"kind\":\"refactor_to_command_dispatcher\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.RefactorToPatternTool.class, "{\"kind\":\"form_template_method\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.RefactorToPatternTool.class, "{\"kind\":\"decompose_conditional\"," + position + ",\"conditionName\":\"c\"}"},
            new Object[] {org.jawata.mcp.tools.RefactorToPatternTool.class, "{\"kind\":\"replace_conditional_with_polymorphism\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetAtPositionTool.class, "{\"kind\":\"type\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetAtPositionTool.class, "{\"kind\":\"method\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetAtPositionTool.class, "{\"kind\":\"enclosing\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetAtPositionTool.class, "{\"kind\":\"field\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetAtPositionTool.class, "{\"kind\":\"hover\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetAtPositionTool.class, "{\"kind\":\"javadoc\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetAtPositionTool.class, "{\"kind\":\"signature\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetAtPositionTool.class, "{\"kind\":\"super\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetAtPositionTool.class, "{\"kind\":\"symbol\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.GoToDefinitionTool.class, "{" + position + "}"},
            new Object[] {org.jawata.mcp.tools.MoveTool.class, "{\"kind\":\"class\"," + position + ",\"targetPackage\":\"a.b\"}"},
            new Object[] {org.jawata.mcp.tools.MoveTool.class, "{\"kind\":\"method\"," + position + ",\"target\":\"t\"}"},
            new Object[] {org.jawata.mcp.tools.MoveTool.class, "{\"kind\":\"field\"," + position + ",\"targetType\":\"a.B\"}"},
            new Object[] {org.jawata.mcp.tools.HierarchyTool.class, "{\"direction\":\"up\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.HierarchyTool.class, "{\"direction\":\"down\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.DataTool.class, "{\"kind\":\"encapsulate_field\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.DataTool.class, "{\"kind\":\"hide_delegate\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.DataTool.class, "{\"kind\":\"special_case\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.DataTool.class, "{\"kind\":\"replace_primitive\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.DataTool.class, "{\"kind\":\"encapsulate_collection\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.DataTool.class, "{\"kind\":\"split_variable\"," + position + ",\"newName\":\"x\"}"},
            new Object[] {org.jawata.mcp.tools.ChangeMethodSignatureTool.class, "{\"kind\":\"change_signature\"," + position + ",\"newName\":\"x\"}"},
            new Object[] {org.jawata.mcp.tools.RenameSymbolTool.class, "{" + position + ",\"newName\":\"Renamed\"}"},
            new Object[] {org.jawata.mcp.tools.FindReferencesTool.class, "{\"kind\":\"references\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.FindFieldWritesTool.class, "{" + position + "}"},
            new Object[] {org.jawata.mcp.tools.GetCallHierarchyTool.class, "{\"direction\":\"outgoing\"," + position + "}"},
            new Object[] {org.jawata.mcp.tools.InspectTool.class, "{\"kind\":\"document_symbols\"}"},
            new Object[] {org.jawata.mcp.tools.InspectTool.class, "{\"kind\":\"complexity\"}"},
            new Object[] {org.jawata.mcp.tools.ValidateSyntaxTool.class, "{}"},
            new Object[] {org.jawata.mcp.tools.OrganizeImportsTool.class, "{}"},
            new Object[] {org.jawata.mcp.tools.workflow.FormatTool.class, "{\"scope\":{\"kind\":\"file\"}}"});
        java.util.List<String> differing = new java.util.ArrayList<>();

        for (Object[] row : rows) {
            Class<?> type = (Class<?>) row[0];
            org.jawata.mcp.tools.Tool door;
            try {
                door = doorOf(type);
            } catch (IllegalStateException unknownShape) {
                differing.add("NOT PROBED: " + unknownShape.getMessage());
                continue;
            }
            for (java.util.Map.Entry<String, String> place : expected.entrySet()) {
                ObjectNode arguments = (ObjectNode) json.readTree((String) row[1]);
                if (arguments.has("scope")) {
                    ((ObjectNode) arguments.get("scope")).put("filePath", place.getKey());
                } else {
                    arguments.put("filePath", place.getKey());
                }
                ToolResponse answer = door.execute(arguments);
                String code = answer.getError() == null ? "(no error)" : answer.getError().getCode();
                if (!place.getValue().equals(code)) {
                    differing.add(type.getSimpleName() + " " + row[1] + " on a " + place.getValue() + " place answered " + code
                        + (answer.getError() == null ? "" : ": " + answer.getError().getMessage()));
                }
            }
        }

        assertEquals(java.util.List.of(), differing, "doors and kinds that do not answer an unresolved file as the others do");
    }

    @Test
    @DisplayName("get_diagnostics, which holds the language-neutral workspace and a diagnostics source, answers the same three ways")
    void theDiagnosticsToolAnswersAnUnresolvedFileTheSameWay(@TempDir Path elsewhere) throws Exception {
        org.jawata.mcp.tools.GetDiagnosticsTool diagnostics = new org.jawata.mcp.tools.GetDiagnosticsTool(() -> service,
            () -> new org.jawata.mcp.java.JavaSourceDiagnostics());
        Path pom = helper.getFixturePath("simple-maven").resolve("pom.xml");
        Path outside = Files.writeString(elsewhere.resolve("notes.txt"), "x");

        ToolResponse inside = diagnostics.execute(json.createObjectNode().put("filePath", pom.toString()));
        ToolResponse beside = diagnostics.execute(json.createObjectNode().put("filePath", outside.toString()));
        ToolResponse missing = diagnostics.execute(json.createObjectNode().put("filePath", elsewhere.resolve("Missing.java").toString()));

        assertAll(
            () -> assertEquals("NOT_A_JAVA_SOURCE", code(inside), "a non-Java file inside the project"),
            () -> assertEquals("NOT_A_JAVA_PROJECT", code(beside), "a file outside every loaded project"),
            () -> assertEquals("FILE_NOT_FOUND", code(missing), "CONTROL: a path that is not there"));
    }

    private static String code(ToolResponse answer) {
        return answer.getError() == null ? "(no error)" : answer.getError().getCode();
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
