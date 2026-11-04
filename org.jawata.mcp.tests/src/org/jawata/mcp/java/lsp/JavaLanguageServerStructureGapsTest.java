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
 * 28h S16d carry-over (the eleven gaps of the C16d report's decision 4): folding of the blocks of {@code if}, {@code for}, {@code while}
 * and {@code try} and of a {@code switch} expression, a scripted row for the lambda, {@code switch}, initializer and nested-type folds,
 * inlay hints for lambda parameters and for the result of a chained call, and code lenses on fields, on constructors and for
 * implementations. The text is held unsaved, in the file Calculator.java of simple-maven, as in the structure rows.
 */
class JavaLanguageServerStructureGapsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TEXT = String.join("\n",
        "package com.example;",
        "",
        "import java.util.List;",
        "",
        "public class Calculator {",
        "    private final int sides;",
        "    static int made;",
        "",
        "    static {",
        "        made = 81;",
        "        made += 82;",
        "    }",
        "",
        "    public Calculator(int sides) {",
        "        this.sides = sides;",
        "    }",
        "",
        "    static Calculator triangle() {",
        "        return new Calculator(3);",
        "    }",
        "",
        "    interface Figure {",
        "        double area();",
        "    }",
        "",
        "    abstract static class Base implements Figure {",
        "        abstract String label();",
        "    }",
        "",
        "    static class Square extends Base {",
        "        public double area() {",
        "            return 1;",
        "        }",
        "",
        "        String label() {",
        "            return \"square\";",
        "        }",
        "    }",
        "",
        "    int describe(List<String> names, int mode) {",
        "        int total = 0;",
        "        if (mode > 0) {",
        "            total += 11;",
        "            total += 12;",
        "        } else {",
        "            total -= 21;",
        "            total -= 22;",
        "        }",
        "        for (String each : names) {",
        "            total += each.length();",
        "            total += 31;",
        "        }",
        "        while (total > 100) {",
        "            total -= 41;",
        "            total -= 42;",
        "        }",
        "        try {",
        "            total += 51;",
        "            total += 52;",
        "        } catch (RuntimeException e) {",
        "            total -= 61;",
        "            total -= 62;",
        "        } finally {",
        "            total += 71;",
        "            total += 72;",
        "        }",
        "        String word = switch (mode) {",
        "            case 0 -> \"zero\";",
        "            case 1 -> \"one\";",
        "            default -> \"many\";",
        "        };",
        "        switch (mode) {",
        "            case 0:",
        "                total += 91;",
        "                break;",
        "            default:",
        "                total -= 92;",
        "        }",
        "        Runnable run = () -> {",
        "            names.clear();",
        "            names.isEmpty();",
        "        };",
        "        run.run();",
        "        return total + word.length();",
        "    }",
        "",
        "    int chained(List<String> names) {",
        "        return names.stream()",
        "            .filter(each -> each.length() > 1)",
        "            .map(each -> each.length())",
        "            .reduce(0, Integer::sum);",
        "    }",
        "}",
        "");

    private JdtServiceImpl service;
    private LspSession session;
    private Path file;

    private void start() throws Exception {
        service = helper.loadProject("simple-maven");
        file = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        session = new LspSession(service, "structure-gaps-test");
        session.open(file, TEXT);
    }

    private Map<String, Object> document() {
        return Map.of("textDocument", Map.of("uri", file.toUri().toString()));
    }

    private Map<String, Object> wholeFile() {
        return Map.of("textDocument", Map.of("uri", file.toUri().toString()),
            "range", Map.of("start", Map.of("line", 0, "character", 0), "end", Map.of("line", TEXT.split("\n", -1).length, "character", 0)));
    }

    private static boolean hasFold(JsonNode ranges, int startLine, int endLine) {
        for (JsonNode range : ranges) {
            if (range.path("startLine").asInt() == startLine && range.path("endLine").asInt() == endLine && "region".equals(range.path("kind").asText())) {
                return true;
            }
        }
        return false;
    }

    /** One fold: from the line that holds {@code opener} to the line that holds {@code lastStatement}, the one before the closing brace. */
    private static void assertFold(JsonNode ranges, String what, String opener, String lastStatement) {
        assertTrue(hasFold(ranges, lineOf(TEXT, opener), lineOf(TEXT, lastStatement)),
            what + " folds from " + opener.strip() + " to " + lastStatement.strip() + ": " + ranges);
    }

    @Test
    @DisplayName("folding covers the block of an if, an else, a for, a while, a try, a catch and a finally")
    void foldingCoversTheBlocksOfStatements() throws Exception {
        start();
        JsonNode ranges = session.ask("textDocument/foldingRange", document());
        assertFold(ranges, "the if block", "if (mode > 0) {", "total += 12;");
        assertFold(ranges, "the else block", "} else {", "total -= 22;");
        assertFold(ranges, "the for block", "for (String each : names) {", "total += 31;");
        assertFold(ranges, "the while block", "while (total > 100) {", "total -= 42;");
        assertFold(ranges, "the try block", "        try {", "total += 52;");
        assertFold(ranges, "the catch block", "} catch (RuntimeException e) {", "total -= 62;");
        assertFold(ranges, "the finally block", "} finally {", "total += 72;");
    }

    @Test
    @DisplayName("folding covers a switch expression, a switch statement, a lambda body, an initializer and the bodies of nested types")
    void foldingCoversSwitchesLambdasInitializersAndNestedTypes() throws Exception {
        start();
        JsonNode ranges = session.ask("textDocument/foldingRange", document());
        assertFold(ranges, "the switch expression", "String word = switch (mode) {", "default -> \"many\";");
        assertFold(ranges, "the switch statement", "        switch (mode) {", "total -= 92;");
        assertFold(ranges, "the lambda body", "Runnable run = () -> {", "names.isEmpty();");
        assertFold(ranges, "the static initializer", "static {", "made += 82;");
        assertFold(ranges, "the nested interface", "interface Figure {", "double area();");
        assertFold(ranges, "the nested abstract class", "abstract static class Base implements Figure {", "abstract String label();");
        assertTrue(hasFold(ranges, lineOf(TEXT, "static class Square extends Base {"), lineOf(TEXT, "return \"square\";") + 1),
            "the nested class folds to the closing brace of its last method: " + ranges);
    }

    @Test
    @DisplayName("an inlay hint gives a lambda parameter written without its type the type the compiler infers")
    void lambdaParametersGetTheirInferredType() throws Exception {
        start();
        JsonNode hints = session.ask("textDocument/inlayHint", wholeFile());
        int filterLine = lineOf(TEXT, ".filter(each");
        List<JsonNode> onTheFilterLine = new ArrayList<>();
        for (JsonNode hint : hints) {
            if (hint.path("position").path("line").asInt() == filterLine && ": String".equals(hint.path("label").asText())) {
                onTheFilterLine.add(hint);
            }
        }
        assertEquals(1, onTheFilterLine.size(), "the parameter of the filter lambda is hinted once: " + hints);
        assertEquals(columnOf(TEXT, ".filter(each", "each", 4), onTheFilterLine.get(0).path("position").path("character").asInt(),
            "the hint sits right after the parameter name: " + onTheFilterLine.get(0));
    }

    private static String lineText(String marker) {
        return TEXT.split("\n", -1)[lineOf(TEXT, marker)];
    }

    @Test
    @DisplayName("an inlay hint gives the result type of a call that ends its line in a chain, and not of the last call or of a chain on one line")
    void chainedCallsShowTheirResultType() throws Exception {
        start();
        JsonNode hints = session.ask("textDocument/inlayHint", wholeFile());
        assertEquals(": Stream<String>", hintAt(hints, lineOf(TEXT, "return names.stream()"), lineText("return names.stream()").length()), "after names.stream()");
        assertEquals(": Stream<String>", hintAt(hints, lineOf(TEXT, ".filter(each"), lineText(".filter(each").length()), "after .filter(...)");
        assertEquals(": Stream<Integer>", hintAt(hints, lineOf(TEXT, ".map(each"), lineText(".map(each").length()), "after .map(...)");
        int reduceLine = lineOf(TEXT, ".reduce(0");
        for (JsonNode hint : hints) {
            assertTrue(hint.path("position").path("line").asInt() != reduceLine || !hint.path("label").asText().startsWith(": "),
                "the last call of the chain gets no result hint: " + hint);
        }
        int oneLine = lineOf(TEXT, "return total + word.length();");
        for (JsonNode hint : hints) {
            assertTrue(hint.path("position").path("line").asInt() != oneLine || !hint.path("label").asText().startsWith(": "),
                "a call chain on one line gets no result hint: " + hint);
        }
    }

    /** The label of the hint at the position; fails when there is none. */
    private static String hintAt(JsonNode hints, int line, int column) {
        for (JsonNode hint : hints) {
            if (hint.path("position").path("line").asInt() == line && hint.path("position").path("character").asInt() == column) {
                return hint.path("label").asText();
            }
        }
        throw new AssertionError("no hint at " + line + ":" + column + " in " + hints);
    }

    private static JsonNode lensOn(JsonNode lenses, int line, String kind) {
        for (JsonNode lens : lenses) {
            if (lens.path("range").path("start").path("line").asInt() == line && kind.equals(lens.path("data").path("kind").asText())) {
                return lens;
            }
        }
        throw new AssertionError("no " + kind + " lens on line " + line + ": " + lenses);
    }

    /** The count the engine's own search gives for the name at the position, for the kind of search a lens makes. */
    @SuppressWarnings("unchecked")
    private int engineCount(String kind, String field, int line, int column) {
        ObjectNode arguments = JSON.createObjectNode().put("kind", kind).put("filePath", file.toString()).put("line", line).put("column", column);
        ToolResponse response = new FindRefsTool(() -> service).execute(arguments);
        return ((Number) ((Map<String, Object>) response.getData()).get(field)).intValue();
    }

    private String titleOf(JsonNode lens) throws Exception {
        return session.ask("codeLens/resolve", JSON.convertValue(lens, Map.class)).path("command").path("title").asText();
    }

    private static String titleFor(int count, String noun) {
        return count == 1 ? "1 " + noun : count + " " + noun + "s";
    }

    @Test
    @DisplayName("a field and a constructor each have a references lens that counts what the engine's reference search counts")
    void fieldsAndConstructorsHaveReferenceLenses() throws Exception {
        start();
        JsonNode lenses = session.ask("textDocument/codeLens", document());
        int sidesLine = lineOf(TEXT, "private final int sides;");
        int constructorLine = lineOf(TEXT, "public Calculator(int sides)");
        int sidesCount = engineCount("references", "totalReferences", sidesLine, columnOf(TEXT, "private final int sides;", "sides", 1));
        int constructorCount = engineCount("references", "totalReferences", constructorLine, columnOf(TEXT, "public Calculator(int sides)", "Calculator", 1));
        assertTrue(sidesCount >= 1, "the constructor assigns the field: " + sidesCount);
        assertTrue(constructorCount >= 1, "triangle() constructs a Calculator: " + constructorCount);
        assertEquals(titleFor(sidesCount, "reference"), titleOf(lensOn(lenses, sidesLine, "references")));
        assertEquals(titleFor(constructorCount, "reference"), titleOf(lensOn(lenses, constructorLine, "references")));
        lensOn(lenses, lineOf(TEXT, "static int made;"), "references");
    }

    @Test
    @DisplayName("an interface, an abstract class and an abstract method each have an implementations lens that counts what the engine finds; a concrete one has none")
    void interfacesAndAbstractMembersHaveImplementationLenses() throws Exception {
        start();
        JsonNode lenses = session.ask("textDocument/codeLens", document());
        int figureLine = lineOf(TEXT, "interface Figure {");
        int areaLine = lineOf(TEXT, "double area();");
        int baseLine = lineOf(TEXT, "abstract static class Base");
        int labelLine = lineOf(TEXT, "abstract String label();");
        int figure = engineCount("implementations", "totalImplementations", figureLine, columnOf(TEXT, "interface Figure {", "Figure", 1));
        int area = engineCount("implementations", "totalImplementations", areaLine, columnOf(TEXT, "double area();", "area", 1));
        int label = engineCount("implementations", "totalImplementations", labelLine, columnOf(TEXT, "abstract String label();", "label", 1));
        assertTrue(figure >= 1 && area >= 1 && label >= 1, "Square implements all three: " + figure + " " + area + " " + label);
        assertEquals(titleFor(figure, "implementation"), titleOf(lensOn(lenses, figureLine, "implementations")));
        assertEquals(titleFor(area, "implementation"), titleOf(lensOn(lenses, areaLine, "implementations")));
        assertEquals(titleFor(label, "implementation"), titleOf(lensOn(lenses, labelLine, "implementations")));
        lensOn(lenses, baseLine, "implementations");
        JsonNode resolved = session.ask("codeLens/resolve", JSON.convertValue(lensOn(lenses, figureLine, "implementations"), Map.class));
        assertEquals("jawata.showImplementations", resolved.path("command").path("command").asText(), resolved.toString());
        assertEquals("jawata.showReferences",
            session.ask("codeLens/resolve", JSON.convertValue(lensOn(lenses, figureLine, "references"), Map.class)).path("command").path("command").asText());
        Set<Integer> implementationLines = new HashSet<>();
        for (JsonNode lens : lenses) {
            if ("implementations".equals(lens.path("data").path("kind").asText())) {
                implementationLines.add(lens.path("range").path("start").path("line").asInt());
            }
        }
        assertEquals(Set.of(figureLine, areaLine, baseLine, labelLine), implementationLines,
            "only interfaces, abstract classes and abstract methods have an implementations lens: " + lenses);
    }
}
