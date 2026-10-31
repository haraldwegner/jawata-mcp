package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindReferencesTool;
import org.jawata.mcp.tools.GetDocumentSymbolsTool;
import org.jawata.mcp.tools.GoToDefinitionTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28h S16a, the range locator: the one place a source span becomes a start and an end (line and column, zero-based, columns in UTF-16
 * units as the editor protocol counts them), and the tool answers that gained {@code endLine}, {@code endColumn} and {@code nameRange}
 * from it. The fixture is {@code Calculator.java} of simple-maven; the expected positions below are read off that file, line by line.
 *
 * <pre>
 *   2: /**                                  class Calculator: declaration 2:0 to 48:1, name 5:13 to 5:23
 *   5: public class Calculator {
 *   8: /**                                  add: declaration 8:4 to 17:5, name 14:15 to 14:18
 *  14:     public int add(int a, int b) {
 *  17:     }
 * </pre>
 */
class JavaRangesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    private JdtServiceImpl service;
    private Path calculator;

    private void load() throws Exception {
        service = helper.loadProject("simple-maven");
        calculator = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
    }

    private static JavaRanges.Range range(int line, int column, int endLine, int endColumn) {
        return new JavaRanges.Range(line, column, endLine, endColumn);
    }

    // ---- the conversion of a span of text -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a span is counted from line zero and column zero; a line ends at a newline, and a carriage return before it belongs to the line it ends")
    void aSpanIsLineAndColumnInTheTextItIsTakenFrom() {
        JavaRanges.Lines lines = JavaRanges.linesOf("ab\r\ncd\nef");
        assertEquals(range(0, 1, 0, 2), lines.span(1, 1), "inside the first line");
        assertEquals(range(1, 0, 1, 2), lines.span(4, 2), "the second line begins after \\r\\n, not inside it");
        assertEquals(range(0, 1, 2, 1), lines.span(1, 7), "a span over two line breaks ends on the third line");
        assertEquals(range(2, 0, 2, 2), lines.span(7, 2), "the line after a bare \\n");
    }

    @Test
    @DisplayName("a character outside the basic plane is two columns, as the protocol counts UTF-16 units")
    void aSurrogatePairIsTwoColumns() {
        JavaRanges.Lines lines = JavaRanges.linesOf("a😀b");
        assertEquals(range(0, 3, 0, 4), lines.span(3, 1), "the b after a pair is at column 3, not 2");
    }

    @Test
    @DisplayName("a span that runs past the end of the text ends at the end of the text, and an empty text has one empty line")
    void aSpanPastTheEndIsClamped() {
        assertEquals(range(0, 1, 0, 3), JavaRanges.linesOf("abc").span(1, 50));
        assertEquals(range(0, 0, 0, 0), JavaRanges.linesOf("").span(0, 0));
    }

    // ---- the declaration and the name of an element ------------------------------------------------------------------------------

    @Test
    @DisplayName("a method's declaration range runs from its doc comment to its closing brace, and its name range covers the name alone")
    void aMethodHasADeclarationRangeAndANameRange() throws Exception {
        load();
        ICompilationUnit unit = service.getCompilationUnit(calculator);
        IMethod add = unit.getType("Calculator").getMethods()[0];
        assertEquals("add", add.getElementName());
        JavaRanges.Lines lines = JavaRanges.linesOf(unit);

        assertEquals(Optional.of(range(8, 4, 17, 5)), lines.declaration(add));
        assertEquals(Optional.of(range(14, 15, 14, 18)), lines.name(add));
    }

    @Test
    @DisplayName("a type's name range is the name, not the doc comment the declaration starts at")
    void aTypeHasADeclarationRangeAndANameRange() throws Exception {
        load();
        ICompilationUnit unit = service.getCompilationUnit(calculator);
        IType type = unit.getType("Calculator");
        JavaRanges.Lines lines = JavaRanges.linesOf(unit);

        assertEquals(Optional.of(range(2, 0, 48, 1)), lines.declaration(type));
        assertEquals(Optional.of(range(5, 13, 5, 23)), lines.name(type));
    }

    @Test
    @DisplayName("an element that is not in the unit the lines were built from, a library type among them, has no range, and says so by being absent")
    void anElementWithoutSourceHasNoRange() throws Exception {
        load();
        IType string = service.findType("java.lang.String");
        JavaRanges.Lines lines = JavaRanges.linesOf(service.getCompilationUnit(calculator));

        assertEquals(Optional.empty(), lines.declaration(string));
        assertEquals(Optional.empty(), lines.name(string));
    }

    // ---- the tool answers that gained the fields -----------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse response) {
        assertTrue(response.isSuccess(), String.valueOf(response));
        return (Map<String, Object>) response.getData();
    }

    @Test
    @DisplayName("go_to_definition keeps its start position and adds the end of the declaration and the name range")
    @SuppressWarnings("unchecked")
    void goToDefinitionAnswersTheEndAndTheNameRange() throws Exception {
        load();
        ObjectNode arguments = JSON.createObjectNode().put("filePath", calculator.toString()).put("line", 14).put("column", 15);

        Map<String, Object> location = (Map<String, Object>) data(new GoToDefinitionTool(() -> service).execute(arguments)).get("location");

        assertEquals(8, location.get("line"), "the start stays where the tool always put it: the doc comment");
        assertEquals(4, location.get("column"));
        assertEquals(17, location.get("endLine"));
        assertEquals(5, location.get("endColumn"));
        assertEquals(Map.of("line", 14, "column", 15, "endLine", 14, "endColumn", 18), location.get("nameRange"));
    }

    @Test
    @DisplayName("find_references adds the end of each reference, so a reference is the span of its name")
    @SuppressWarnings("unchecked")
    void everyReferenceHasAnEnd() throws Exception {
        load();
        ObjectNode arguments = JSON.createObjectNode().put("filePath", calculator.toString()).put("line", 6).put("column", 16);

        List<Map<String, Object>> references = (List<Map<String, Object>>) data(new FindReferencesTool(() -> service).execute(arguments)).get("references");

        assertTrue(references.size() >= 3, "lastResult is read and written in the class: " + references);
        for (Map<String, Object> reference : references) {
            assertEquals(reference.get("line"), reference.get("endLine"), "a name does not span lines: " + reference);
            assertEquals((int) reference.get("column") + "lastResult".length(), reference.get("endColumn"), reference.toString());
        }
    }

    @Test
    @DisplayName("get_document_symbols adds the column, the end and the name range to every symbol, nested ones included")
    @SuppressWarnings("unchecked")
    void everySymbolHasItsRanges() throws Exception {
        load();
        ObjectNode arguments = JSON.createObjectNode().put("filePath", calculator.toString());

        List<Map<String, Object>> symbols = (List<Map<String, Object>>) data(new GetDocumentSymbolsTool(() -> service).execute(arguments)).get("symbols");

        Map<String, Object> type = symbols.get(0);
        assertEquals("Calculator", type.get("name"));
        assertEquals(2, type.get("line"), "the existing field keeps its meaning");
        assertEquals(0, type.get("column"));
        assertEquals(48, type.get("endLine"));
        assertEquals(1, type.get("endColumn"));
        assertEquals(Map.of("line", 5, "column", 13, "endLine", 5, "endColumn", 23), type.get("nameRange"));

        Map<String, Object> add = ((List<Map<String, Object>>) type.get("children")).stream()
            .filter(child -> "add".equals(child.get("name"))).findFirst().orElseThrow();
        assertEquals(8, add.get("line"));
        assertEquals(4, add.get("column"));
        assertEquals(17, add.get("endLine"));
        assertEquals(5, add.get("endColumn"));
        assertEquals(Map.of("line", 14, "column", 15, "endLine", 14, "endColumn", 18), add.get("nameRange"));
    }
}
