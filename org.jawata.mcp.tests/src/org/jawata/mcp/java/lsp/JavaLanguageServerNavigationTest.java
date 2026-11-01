package org.jawata.mcp.java.lsp;

import static org.jawata.mcp.java.lsp.LspSession.asParams;
import static org.jawata.mcp.java.lsp.LspSession.at;
import static org.jawata.mcp.java.lsp.LspSession.columnOf;
import static org.jawata.mcp.java.lsp.LspSession.lineOf;
import static org.jawata.mcp.java.lsp.LspSession.namesOf;
import static org.jawata.mcp.java.lsp.LspSession.onName;
import static org.jawata.mcp.java.lsp.LspSession.startLinesOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 28h S16b: the navigation requests, one row per request, over the plain Maven tree. The engine's published doors answer them through a
 * stand-in gate made of the real door tools. The editor's unsaved text adds members to {@code Calculator.java} of simple-maven that the
 * saved file does not have, so a row that answers about them proves the answer is about the person's text and not the saved file.
 */
class JavaLanguageServerNavigationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String SUM_LINE = "    int typedSum() { int s = add(1, 2); return s + lastResult; }\n";
    private static final String CALL_LINE = "    int typedCall() { return add(1, 2); }\n";
    private static final String SHAPE_LINES = "    interface Shape { }\n    static class Square implements Shape { }\n"
        + "    Shape typedShape;\n    String typedName;\n";

    private LspSession session;
    private Path calculator;
    private String saved;
    private String unsaved;

    private void startWithUnsavedText() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        calculator = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        saved = Files.readString(calculator);
        unsaved = saved.substring(0, saved.lastIndexOf('}')) + SUM_LINE + CALL_LINE + SHAPE_LINES + "}\n";
        session = new LspSession(service, "navigation-test");
        session.open(calculator, unsaved);
    }

    /** A request at the name {@code name} of the line of the unsaved text that holds {@code lineMarker}. */
    private Map<String, Object> on(String lineMarker, String name) {
        return onName(calculator, unsaved, lineMarker, name);
    }

    @Test
    @DisplayName("initialize tells the editor it may ask for every navigation request the server answers")
    void navigationIsAdvertised() throws Exception {
        startWithUnsavedText();
        JsonNode capabilities = session.ask("initialize", Map.of()).path("capabilities");
        for (String provider : List.of("hoverProvider", "definitionProvider", "declarationProvider", "referencesProvider",
                "implementationProvider", "documentHighlightProvider", "documentSymbolProvider", "workspaceSymbolProvider",
                "signatureHelpProvider", "completionProvider", "typeDefinitionProvider", "callHierarchyProvider", "typeHierarchyProvider",
                "executeCommandProvider")) {
            assertTrue(capabilities.has(provider), provider + " in " + capabilities);
        }
    }

    @Test
    @DisplayName("hover on a call in the unsaved text shows the called method's signature")
    void hoverOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode hover = session.ask("textDocument/hover", on("typedSum", "add"));
        assertTrue(hover.path("contents").path("value").asText().contains("add"), hover.toString());
    }

    @Test
    @DisplayName("definition of a call in the unsaved text is the method's declaration, in the name's range")
    void definitionOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode definition = session.ask("textDocument/definition", on("typedSum", "add"));
        assertTrue(definition.path("uri").asText().endsWith("Calculator.java"), definition.toString());
        assertEquals(lineOf(saved, "public int add("), definition.path("range").path("start").path("line").asInt());
        int from = definition.path("range").path("start").path("character").asInt();
        int to = definition.path("range").path("end").path("character").asInt();
        assertEquals("add".length(), to - from, "the range is the name: " + definition);
    }

    @Test
    @DisplayName("declaration answers as definition does")
    void declarationIsDefinition() throws Exception {
        startWithUnsavedText();
        JsonNode declaration = session.ask("textDocument/declaration", on("typedSum", "add"));
        assertEquals(lineOf(saved, "public int add("), declaration.path("range").path("start").path("line").asInt(), declaration.toString());
    }

    @Test
    @DisplayName("references of a field include the use in the unsaved text, at a line only the unsaved text has")
    void referencesOnUnsavedText() throws Exception {
        startWithUnsavedText();
        Map<String, Object> params = new HashMap<>(at(calculator, lineOf(unsaved, "typedSum"), columnOf(unsaved, "typedSum", "lastResult", 2)));
        params.put("context", Map.of("includeDeclaration", true));
        List<Integer> lines = startLinesOf(session.ask("textDocument/references", params));
        assertTrue(lines.contains(lineOf(unsaved, "typedSum")), "reference lines " + lines);
    }

    @Test
    @DisplayName("highlights of a field mark every use in the file, the unsaved one too")
    void highlightsOnUnsavedText() throws Exception {
        startWithUnsavedText();
        List<Integer> lines = startLinesOf(session.ask("textDocument/documentHighlight",
            at(calculator, lineOf(unsaved, "typedSum"), columnOf(unsaved, "typedSum", "lastResult", 2))));
        assertTrue(lines.size() >= 2 && lines.contains(lineOf(unsaved, "typedSum")), "highlight lines " + lines);
    }

    @Test
    @DisplayName("the outline lists the methods of the unsaved text, nested under their class")
    void documentSymbolsOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode symbols = session.ask("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", calculator.toUri().toString())));
        assertEquals("Calculator", symbols.get(0).path("name").asText());
        List<String> children = namesOf(symbols.get(0).path("children"), "");
        assertTrue(children.contains("typedSum") && children.contains("add"), "children " + children);
    }

    @Test
    @DisplayName("workspace symbols finds a type by name across the project")
    void workspaceSymbols() throws Exception {
        startWithUnsavedText();
        List<String> names = namesOf(session.ask("workspace/symbol", Map.of("query", "Calculator")), "");
        assertTrue(names.contains("Calculator"), "found " + names);
    }

    @Test
    @DisplayName("implementations of an interface declared in the unsaved text include the class that implements it, found only there")
    void implementationsOnUnsavedText() throws Exception {
        startWithUnsavedText();
        List<Integer> lines = startLinesOf(session.ask("textDocument/implementation", on("interface Shape", "Shape")));
        assertTrue(lines.contains(lineOf(unsaved, "class Square")), "implementation lines " + lines);
    }

    @Test
    @DisplayName("type definition of a field declared with a type of the unsaved text is that type's declaration, found only there")
    void typeDefinitionOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode definition = session.ask("textDocument/typeDefinition", on("Shape typedShape", "typedShape"));
        assertEquals(lineOf(unsaved, "interface Shape"), definition.path("range").path("start").path("line").asInt(), definition.toString());
    }

    @Test
    @DisplayName("the callers of a method include the unsaved method that calls it, and the callees of that method include it")
    void callHierarchyOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode added = session.ask("textDocument/prepareCallHierarchy", on("public int add(", "add"));
        assertEquals("add", added.get(0).path("name").asText(), added.toString());
        JsonNode incoming = session.ask("callHierarchy/incomingCalls", asParams(added.get(0)));
        assertTrue(namesOf(incoming, "from").contains("typedSum"), "callers " + incoming);
        JsonNode typed = session.ask("textDocument/prepareCallHierarchy", on("int typedSum", "typedSum"));
        JsonNode outgoing = session.ask("callHierarchy/outgoingCalls", asParams(typed.get(0)));
        assertTrue(namesOf(outgoing, "to").contains("add"), "callees " + outgoing);
    }

    @Test
    @DisplayName("the supertypes of a class of the unsaved text include its interface, and the subtypes of the interface include the class")
    void typeHierarchyOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode square = session.ask("textDocument/prepareTypeHierarchy", on("static class Square", "Square"));
        assertEquals("Square", square.get(0).path("name").asText(), square.toString());
        JsonNode supertypes = session.ask("typeHierarchy/supertypes", asParams(square.get(0)));
        assertTrue(namesOf(supertypes, "").contains("Shape"), "supertypes " + supertypes);
        JsonNode shape = session.ask("textDocument/prepareTypeHierarchy", on("interface Shape", "Shape"));
        JsonNode subtypes = session.ask("typeHierarchy/subtypes", asParams(shape.get(0)));
        assertTrue(namesOf(subtypes, "").contains("Square"), "subtypes " + subtypes);
    }

    @Test
    @DisplayName("a command is a tool of the gate: running search_symbols answers the symbols it finds")
    void executeCommandRunsATool() throws Exception {
        startWithUnsavedText();
        JsonNode capabilities = session.ask("initialize", Map.of()).path("capabilities");
        assertTrue(capabilities.path("executeCommandProvider").path("commands").toString().contains("search_symbols"), capabilities.toString());
        JsonNode result = session.ask("workspace/executeCommand",
            Map.of("command", "search_symbols", "arguments", List.of(Map.of("query", "Calculator"))));
        List<String> names = new ArrayList<>();
        for (JsonNode hit : result.path("results")) {
            names.add(hit.path("name").asText());
        }
        assertTrue(names.contains("Calculator"), "found " + names);
    }

    @Test
    @DisplayName("the definition of a library class is an address that names no file, and its source is asked at that address")
    void libraryClassContent() throws Exception {
        startWithUnsavedText();
        JsonNode definition = session.ask("textDocument/definition", on("String typedName", "String"));
        String address = definition.path("uri").asText();
        assertTrue(address.startsWith("jdt://"), definition.toString());
        String source = session.ask("java/classFileContents", Map.of("uri", address)).asText();
        assertTrue(source.contains("class String"), "the source of " + address + " starts " + source.substring(0, Math.min(200, source.length())));
    }

    @Test
    @DisplayName("signature help with the cursor at the second argument of a call in the unsaved text names the method called and that argument")
    void signatureHelpOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode help = session.ask("textDocument/signatureHelp",
            at(calculator, lineOf(unsaved, "typedCall"), columnOf(unsaved, "typedCall", "add(1, ", "add(1, ".length())));
        assertTrue(help.path("signatures").get(0).path("label").asText().contains("add"), help.toString());
        assertEquals(1, help.path("activeParameter").asInt(), "the second argument is being typed: " + help);
    }
}
