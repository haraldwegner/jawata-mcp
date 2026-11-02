package org.jawata.mcp.java.lsp;

import static org.jawata.mcp.java.lsp.LspSession.columnOf;
import static org.jawata.mcp.java.lsp.LspSession.lineOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindRefsTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28h S16d: folding ranges, selection ranges, semantic tokens, inlay hints and code lenses, one row each over a text the editor holds
 * unsaved (it replaces the saved Calculator.java of simple-maven), so every answer is about the person's text and each row names the
 * content it expects on a known file.
 */
class JavaLanguageServerStructureTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TEXT = String.join("\n",
        "package com.example;",
        "",
        "import java.util.List;",
        "import java.util.Map;",
        "",
        "/**",
        " * Known text for the structure rows.",
        " */",
        "public class Calculator {",
        "    private final int base = 1;",
        "    public static final int LIMIT = 10;",
        "",
        "    /** Adds. */",
        "    public int add(int first, int second) {",
        "        var total = first + second;",
        "        List<String> names = List.of(\"a\");",
        "        for (var each : names) {",
        "            total += each.length();",
        "        }",
        "        return total + base;",
        "    }",
        "",
        "    public int twice(int value) {",
        "        return add(value, 2);",
        "    }",
        "",
        "    enum Colour { RED, GREEN }",
        "}",
        "");

    private JdtServiceImpl service;
    private LspSession session;
    private Path file;

    private void start() throws Exception {
        service = helper.loadProject("simple-maven");
        file = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        session = new LspSession(service, "structure-test");
        session.open(file, TEXT);
    }

    private Map<String, Object> document() {
        return Map.of("textDocument", Map.of("uri", file.toUri().toString()));
    }

    private Map<String, Object> lines(int firstLine, int lastLine) {
        return Map.of("textDocument", Map.of("uri", file.toUri().toString()),
            "range", Map.of("start", Map.of("line", firstLine, "character", 0), "end", Map.of("line", lastLine, "character", 0)));
    }

    @Test
    @DisplayName("initialize announces folding, selection ranges, semantic tokens with their legend, inlay hints and code lens with resolve")
    void theFiveFeaturesAreAnnounced() throws Exception {
        start();
        JsonNode capabilities = session.ask("initialize", Map.of()).path("capabilities");
        assertTrue(capabilities.path("foldingRangeProvider").asBoolean(), capabilities.toString());
        assertTrue(capabilities.path("selectionRangeProvider").asBoolean(), capabilities.toString());
        assertTrue(capabilities.path("inlayHintProvider").asBoolean(), capabilities.toString());
        assertTrue(capabilities.path("codeLensProvider").path("resolveProvider").asBoolean(), capabilities.toString());
        JsonNode legend = capabilities.path("semanticTokensProvider").path("legend");
        assertTrue(legend.path("tokenTypes").toString().contains("method") && legend.path("tokenModifiers").toString().contains("static"),
            capabilities.toString());
        assertTrue(capabilities.path("semanticTokensProvider").path("full").asBoolean(), capabilities.toString());
    }

    @Test
    @DisplayName("folding ranges cover the class body, a method body, the imports and the comment of the text")
    void foldingCoversKnownRegions() throws Exception {
        start();
        JsonNode ranges = session.ask("textDocument/foldingRange", document());
        int commentStart = lineOf(TEXT, "Known text") - 1;
        assertTrue(hasFold(ranges, lineOf(TEXT, "public class Calculator"), lineOf(TEXT, "enum Colour"), "region"), "the class body: " + ranges);
        assertTrue(hasFold(ranges, lineOf(TEXT, "public int add("), lineOf(TEXT, "return total + base;"), "region"), "the body of add: " + ranges);
        assertTrue(hasFold(ranges, lineOf(TEXT, "import java.util.List"), lineOf(TEXT, "import java.util.Map"), "imports"), "the imports: " + ranges);
        assertTrue(hasFold(ranges, commentStart, commentStart + 2, "comment"), "the comment: " + ranges);
    }

    private static boolean hasFold(JsonNode ranges, int startLine, int endLine, String kind) {
        for (JsonNode range : ranges) {
            if (range.path("startLine").asInt() == startLine && range.path("endLine").asInt() == endLine && kind.equals(range.path("kind").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("selection ranges widen from the name under the cursor through each enclosing node to the whole file, each inside the next")
    void selectionWidensThroughTheEnclosingNodes() throws Exception {
        start();
        int line = lineOf(TEXT, "return total + base;");
        int column = columnOf(TEXT, "return total + base;", "total", 1);
        JsonNode chain = session.ask("textDocument/selectionRange",
            Map.of("textDocument", Map.of("uri", file.toUri().toString()), "positions", List.of(Map.of("line", line, "character", column)))).get(0);
        List<JsonNode> ranges = new ArrayList<>();
        for (JsonNode link = chain; link != null && !link.isMissingNode(); link = link.get("parent")) {
            ranges.add(link.path("range"));
        }
        JsonNode innermost = ranges.get(0);
        assertEquals(line, innermost.path("start").path("line").asInt(), chain.toString());
        assertEquals("total".length(), innermost.path("end").path("character").asInt() - innermost.path("start").path("character").asInt(),
            "the innermost range is the name: " + chain);
        boolean nested = true;
        for (int i = 1; i < ranges.size(); i++) {
            nested &= startsNoLaterThan(ranges.get(i), ranges.get(i - 1)) && endsNoEarlierThan(ranges.get(i), ranges.get(i - 1));
        }
        assertTrue(nested, "each range holds the one inside it: " + chain);
        assertTrue(ranges.size() >= 4, "the name, the expression, the statement, the body, the class, the file: " + chain);
        assertEquals(0, ranges.get(ranges.size() - 1).path("start").path("line").asInt(), "the outermost is the whole file: " + chain);
    }

    private static boolean startsNoLaterThan(JsonNode outer, JsonNode inner) {
        int outerLine = outer.path("start").path("line").asInt();
        int innerLine = inner.path("start").path("line").asInt();
        return outerLine < innerLine || outerLine == innerLine && outer.path("start").path("character").asInt() <= inner.path("start").path("character").asInt();
    }

    private static boolean endsNoEarlierThan(JsonNode outer, JsonNode inner) {
        int outerLine = outer.path("end").path("line").asInt();
        int innerLine = inner.path("end").path("line").asInt();
        return outerLine > innerLine || outerLine == innerLine && outer.path("end").path("character").asInt() >= inner.path("end").path("character").asInt();
    }

    /** The tokens of an answer as {@code text:type:modifiers}, decoded from the protocol's relative numbers. */
    private static Set<String> tokensOf(JsonNode answer) {
        String[] lines = TEXT.split("\n", -1);
        List<String> types = List.of("namespace", "class", "interface", "enum", "typeParameter", "parameter", "variable", "property",
            "enumMember", "method", "decorator");
        List<String> modifiers = List.of("declaration", "static", "readonly", "deprecated", "abstract");
        Set<String> tokens = new HashSet<>();
        JsonNode data = answer.path("data");
        int line = 0;
        int column = 0;
        for (int i = 0; i + 4 < data.size(); i += 5) {
            line += data.get(i).asInt();
            column = data.get(i).asInt() == 0 ? column + data.get(i + 1).asInt() : data.get(i + 1).asInt();
            List<String> set = new ArrayList<>();
            for (int bit = 0; bit < modifiers.size(); bit++) {
                if ((data.get(i + 4).asInt() & (1 << bit)) != 0) {
                    set.add(modifiers.get(bit));
                }
            }
            tokens.add(lines[line].substring(column, column + data.get(i + 2).asInt()) + ":" + types.get(data.get(i + 3).asInt()) + ":" + String.join(",", set));
        }
        return tokens;
    }

    @Test
    @DisplayName("semantic tokens are non-empty and give each name of the text the type and modifiers the compiler binds it to")
    void tokensClassifyTheNames() throws Exception {
        start();
        Set<String> tokens = tokensOf(session.ask("textDocument/semanticTokens/full", document()));
        assertTrue(tokens.contains("Calculator:class:declaration"), tokens.toString());
        assertTrue(tokens.contains("add:method:declaration"), "the declaration of add: " + tokens);
        assertTrue(tokens.contains("add:method:"), "the call of add: " + tokens);
        assertTrue(tokens.contains("LIMIT:property:declaration,static,readonly"), tokens.toString());
        assertTrue(tokens.contains("base:property:declaration,readonly"), tokens.toString());
        assertTrue(tokens.contains("first:parameter:declaration"), tokens.toString());
        assertTrue(tokens.contains("total:variable:declaration"), tokens.toString());
        assertTrue(tokens.contains("Colour:enum:declaration,static"), tokens.toString());
        assertTrue(!tokens.contains("var:class:"), "the keyword var is not a class: " + tokens);
        assertTrue(tokens.contains("List:interface:"), tokens.toString());
    }

    @Test
    @DisplayName("semantic tokens of a range hold only the names of its lines")
    void tokensOfARange() throws Exception {
        start();
        int twiceLine = lineOf(TEXT, "public int twice(");
        Set<String> tokens = tokensOf(session.ask("textDocument/semanticTokens/range", lines(twiceLine, twiceLine + 2)));
        assertTrue(tokens.contains("twice:method:declaration") && tokens.contains("add:method:"), tokens.toString());
        assertTrue(!tokens.contains("LIMIT:property:declaration,static,readonly") && !tokens.contains("Calculator:class:declaration"), tokens.toString());
    }

    private static List<String> labelsOf(JsonNode hints) {
        List<String> labels = new ArrayList<>();
        for (JsonNode hint : hints) {
            labels.add(hint.path("label").asText());
        }
        return labels;
    }

    @Test
    @DisplayName("inlay hints name the parameters an argument fills, skip a call that says so itself, and give a var its type")
    void inlayHintsNameParametersAndVarTypes() throws Exception {
        start();
        List<String> labels = labelsOf(session.ask("textDocument/inlayHint", lines(0, TEXT.split("\n", -1).length)));
        assertTrue(labels.containsAll(List.of("first:", "second:", ": int", ": String")), labels.toString());
        int twiceLine = lineOf(TEXT, "public int twice(");
        JsonNode inRange = session.ask("textDocument/inlayHint", lines(twiceLine, twiceLine + 2));
        assertEquals(List.of("first:", "second:"), labelsOf(inRange), "only the hints of the lines asked for: " + inRange);
        assertEquals(columnOf(TEXT, "return add(value, 2);", "value", 0), inRange.get(0).path("position").path("character").asInt(),
            "a parameter hint sits before its argument: " + inRange);
    }

    @Test
    @DisplayName("code lenses sit on each type and method, and a resolved lens counts the places the engine's reference search finds")
    void lensesCountReferences() throws Exception {
        start();
        JsonNode lenses = session.ask("textDocument/codeLens", document());
        assertEquals(4, lenses.size(), "Calculator, Colour, add and twice: " + lenses);
        JsonNode addLens = lensAt(lenses, lineOf(TEXT, "public int add("));
        JsonNode twiceLens = lensAt(lenses, lineOf(TEXT, "public int twice("));
        assertTrue(addLens.path("command").isMissingNode(), "a lens is counted only when the editor shows it: " + addLens);

        int expected = engineCount(lineOf(TEXT, "public int add("), columnOf(TEXT, "public int add(", "add", 1));
        assertTrue(expected >= 1, "twice calls add");
        JsonNode resolved = session.ask("codeLens/resolve", JSON.convertValue(addLens, Map.class));
        assertEquals(titleOf(expected), resolved.path("command").path("title").asText(), resolved.toString());
        assertEquals("jawata.showReferences", resolved.path("command").path("command").asText());

        int none = engineCount(lineOf(TEXT, "public int twice("), columnOf(TEXT, "public int twice(", "twice", 1));
        JsonNode resolvedTwice = session.ask("codeLens/resolve", JSON.convertValue(twiceLens, Map.class));
        assertEquals(titleOf(none), resolvedTwice.path("command").path("title").asText(), resolvedTwice.toString());
        assertTrue(!resolved.path("command").path("title").asText().equals(resolvedTwice.path("command").path("title").asText()),
            "two names with different uses get different counts: " + resolved + " " + resolvedTwice);
    }

    private static String titleOf(int count) {
        return count == 1 ? "1 reference" : count + " references";
    }

    private static JsonNode lensAt(JsonNode lenses, int line) {
        for (JsonNode lens : lenses) {
            if (lens.path("range").path("start").path("line").asInt() == line) {
                return lens;
            }
        }
        throw new AssertionError("no lens on line " + line + ": " + lenses);
    }

    /** The count the engine's own reference search gives for the name at the position. */
    @SuppressWarnings("unchecked")
    private int engineCount(int line, int column) {
        ObjectNode arguments = JSON.createObjectNode().put("kind", "references").put("filePath", file.toString())
            .put("line", line).put("column", column);
        ToolResponse response = new FindRefsTool(() -> service).execute(arguments);
        return ((Number) ((Map<String, Object>) response.getData()).get("totalReferences")).intValue();
    }

    @Test
    @DisplayName("a file the model does not hold is answered with nothing, not an error, by the four requests that read a file")
    void anUnknownFileAnswersNothing() throws Exception {
        start();
        Map<String, Object> unknown = Map.of("textDocument", Map.of("uri", file.resolveSibling("Nowhere.java").toUri().toString()));
        assertEquals(0, session.ask("textDocument/foldingRange", unknown).size());
        assertEquals(0, session.ask("textDocument/semanticTokens/full", unknown).path("data").size());
        assertEquals(0, session.ask("textDocument/codeLens", unknown).size());
        assertEquals(0, session.ask("textDocument/inlayHint", Map.of("textDocument", unknown.get("textDocument"),
            "range", Map.of("start", Map.of("line", 0, "character", 0), "end", Map.of("line", 5, "character", 0)))).size());
    }
}
