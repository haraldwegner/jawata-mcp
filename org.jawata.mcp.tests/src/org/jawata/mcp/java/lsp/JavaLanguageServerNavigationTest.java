package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.tools.FindRefsTool;
import org.jawata.mcp.tools.GetAtPositionTool;
import org.jawata.mcp.tools.GoToDefinitionTool;
import org.jawata.mcp.tools.InspectTool;
import org.jawata.mcp.tools.SearchSymbolsTool;
import org.jawata.mcp.tools.Tool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16b: the navigation requests, one row per request. The engine's published doors answer them through a stand-in gate made of the
 * real door tools. The editor's unsaved text adds two methods to {@code Calculator.java} of simple-maven, so a row that answers about
 * them proves the answer is about the person's text and not the saved file.
 */
class JavaLanguageServerNavigationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lsp-navigation-test-scheduler");
        t.setDaemon(true);
        return t;
    });
    private static final String SUM_LINE = "    int typedSum() { int s = add(1, 2); return s + lastResult; }\n";
    private static final String SHAPE_LINES = "    interface Shape { }\n    static class Square implements Shape { }\n"
        + "    Shape typedShape;\n    String typedName;\n";
    private static final String CALL_LINE ="    int typedCall() { return add(1, 2); }\n";

    private final List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
    private JavaLanguageServer server;
    private Path calculator;
    private String saved;
    private String unsaved;

    private static ToolCalls doors(JdtServiceImpl service) {
        Map<String, Tool> byName = Map.of(
            "get_at_position", new GetAtPositionTool(() -> service),
            "go_to_definition", new GoToDefinitionTool(() -> service),
            "find_references", new FindRefsTool(() -> service),
            "inspect", new InspectTool(() -> service),
            "get_call_hierarchy", new org.jawata.mcp.tools.GetCallHierarchyTool(() -> service),
            "search_symbols", new SearchSymbolsTool(() -> service));
        return new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                Tool tool = byName.get(name);
                if (tool == null) {
                    throw new IllegalArgumentException("this test gate publishes no door " + name);
                }
                return tool.execute(arguments);
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                List<Map<String, Object>> definitions = new ArrayList<>();
                byName.keySet().forEach(name -> definitions.add(Map.of("name", name)));
                return definitions;
            }
        };
    }

    private void startWithUnsavedText() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        calculator = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        saved = Files.readString(calculator);
        unsaved = saved.substring(0, saved.lastIndexOf('}')) + SUM_LINE + CALL_LINE + SHAPE_LINES + "}\n";
        server = new JavaLanguageServer(() -> service, doors(service), CallerIdentity.editor("navigation-test"), sent::add, SCHEDULER, 0);
        server.receive(JsonRpcMessage.notification("textDocument/didOpen", JSON.valueToTree(Map.of("textDocument",
            Map.of("uri", uri(), "languageId", "java", "version", 1, "text", unsaved)))));
    }

    private String uri() {
        return calculator.toUri().toString();
    }

    /** Zero-based line of the first line of {@code text} that holds {@code marker}. */
    private static int lineOf(String text, String marker) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("no line holds " + marker);
    }

    private static Map<String, Object> position(int line, int character) {
        return Map.of("line", line, "character", character);
    }

    /** The column {@code insideMarker} characters into the first occurrence of {@code marker} on the line of {@code lineMarker}. */
    private int at(String lineMarker, String marker, int insideMarker) {
        return unsaved.split("\n", -1)[lineOf(unsaved, lineMarker)].indexOf(marker) + insideMarker;
    }

    private JsonNode ask(int id, String method, Map<String, Object> params) throws Exception {
        server.receive(JsonRpcMessage.request(id, method, JSON.valueToTree(params)));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            for (JsonRpcMessage message : sent) {
                if (message.getMethod() == null && Integer.valueOf(id).equals(message.getId())) {
                    JsonNode answer = JSON.valueToTree(message);
                    assertTrue(answer.path("error").isMissingNode() || answer.path("error").isNull(), answer.toString());
                    return answer.path("result");
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no answer to " + method + ": " + sent);
    }

    private Map<String, Object> onTypedAdd(String lineMarker) {
        return Map.of("textDocument", Map.of("uri", uri()), "position", position(lineOf(unsaved, lineMarker), at(lineMarker, "add(", 1)));
    }

    @Test
    @DisplayName("initialize tells the editor it may ask for every navigation request the server answers")
    void navigationIsAdvertised() throws Exception {
        startWithUnsavedText();
        JsonNode capabilities = ask(1, "initialize", Map.of()).path("capabilities");
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
        JsonNode hover = ask(2, "textDocument/hover", onTypedAdd("typedSum"));
        assertTrue(hover.path("contents").path("value").asText().contains("add"), hover.toString());
    }

    @Test
    @DisplayName("definition of a call in the unsaved text is the method's declaration, in the name's range")
    void definitionOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode definition = ask(3, "textDocument/definition", onTypedAdd("typedSum"));
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
        JsonNode declaration = ask(4, "textDocument/declaration", onTypedAdd("typedSum"));
        assertEquals(lineOf(saved, "public int add("), declaration.path("range").path("start").path("line").asInt(), declaration.toString());
    }

    @Test
    @DisplayName("references of a field include the use in the unsaved text, at a line only the unsaved text has")
    void referencesOnUnsavedText() throws Exception {
        startWithUnsavedText();
        Map<String, Object> at = Map.of("textDocument", Map.of("uri", uri()),
            "position", position(lineOf(unsaved, "typedSum"), at("typedSum", "lastResult", 2)), "context", Map.of("includeDeclaration", true));
        JsonNode references = ask(5, "textDocument/references", at);
        List<Integer> lines = new ArrayList<>();
        for (JsonNode reference : references) {
            lines.add(reference.path("range").path("start").path("line").asInt());
        }
        assertTrue(lines.contains(lineOf(unsaved, "typedSum")), "reference lines " + lines);
    }

    @Test
    @DisplayName("highlights of a field mark every use in the file, the unsaved one too")
    void highlightsOnUnsavedText() throws Exception {
        startWithUnsavedText();
        Map<String, Object> at = Map.of("textDocument", Map.of("uri", uri()),
            "position", position(lineOf(unsaved, "typedSum"), at("typedSum", "lastResult", 2)));
        JsonNode highlights = ask(6, "textDocument/documentHighlight", at);
        List<Integer> lines = new ArrayList<>();
        for (JsonNode highlight : highlights) {
            lines.add(highlight.path("range").path("start").path("line").asInt());
        }
        assertTrue(lines.size() >= 2 && lines.contains(lineOf(unsaved, "typedSum")), "highlight lines " + lines);
    }

    @Test
    @DisplayName("the outline lists the methods of the unsaved text, nested under their class")
    void documentSymbolsOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode symbols = ask(7, "textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", uri())));
        JsonNode calculatorSymbol = symbols.get(0);
        assertEquals("Calculator", calculatorSymbol.path("name").asText());
        List<String> children = new ArrayList<>();
        for (JsonNode child : calculatorSymbol.path("children")) {
            children.add(child.path("name").asText());
        }
        assertTrue(children.contains("typedSum") && children.contains("add"), "children " + children);
    }

    @Test
    @DisplayName("workspace symbols finds a type by name across the project")
    void workspaceSymbols() throws Exception {
        startWithUnsavedText();
        JsonNode found = ask(8, "workspace/symbol", Map.of("query", "Calculator"));
        List<String> names = new ArrayList<>();
        for (JsonNode symbol : found) {
            names.add(symbol.path("name").asText());
        }
        assertTrue(names.contains("Calculator"), "found " + names);
    }

    @Test
    @DisplayName("implementations of an interface declared in the unsaved text include the class that implements it, found only there")
    void implementationsOnUnsavedText() throws Exception {
        startWithUnsavedText();
        Map<String, Object> at = Map.of("textDocument", Map.of("uri", uri()),
            "position", position(lineOf(unsaved, "interface Shape"), at("interface Shape", "Shape", 1)));
        JsonNode implementations = ask(10, "textDocument/implementation", at);
        List<Integer> lines = new ArrayList<>();
        for (JsonNode implementation : implementations) {
            lines.add(implementation.path("range").path("start").path("line").asInt());
        }
        assertTrue(lines.contains(lineOf(unsaved, "class Square")), "implementation lines " + lines);
    }

    /** A request at the name of the declaration that holds {@code lineMarker}. */
    private Map<String, Object> onDeclaration(String lineMarker, String name) {
        return Map.of("textDocument", Map.of("uri", uri()), "position", position(lineOf(unsaved, lineMarker), at(lineMarker, name, 1)));
    }

    /** The names of the items of an answer; {@code side} names the member that holds the item when each answer wraps one. */
    private static List<String> namesOf(JsonNode answers, String side) {
        List<String> names = new ArrayList<>();
        for (JsonNode answer : answers) {
            names.add((side.isEmpty() ? answer : answer.path(side)).path("name").asText());
        }
        return names;
    }

    @Test
    @DisplayName("type definition of a field declared with a type of the unsaved text is that type's declaration, found only there")
    void typeDefinitionOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode definition = ask(11, "textDocument/typeDefinition", onDeclaration("Shape typedShape", "typedShape"));
        assertEquals(lineOf(unsaved, "interface Shape"), definition.path("range").path("start").path("line").asInt(), definition.toString());
    }

    @Test
    @DisplayName("the callers of a method include the unsaved method that calls it, and the callees of that method include it")
    void callHierarchyOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode added = ask(12, "textDocument/prepareCallHierarchy", onDeclaration("public int add(", "add"));
        assertEquals("add", added.get(0).path("name").asText(), added.toString());
        JsonNode incoming = ask(13, "callHierarchy/incomingCalls", Map.of("item", JSON.convertValue(added.get(0), Map.class)));
        assertTrue(namesOf(incoming, "from").contains("typedSum"), "callers " + incoming);
        JsonNode typed = ask(14, "textDocument/prepareCallHierarchy", onDeclaration("int typedSum", "typedSum"));
        JsonNode outgoing = ask(15, "callHierarchy/outgoingCalls", Map.of("item", JSON.convertValue(typed.get(0), Map.class)));
        assertTrue(namesOf(outgoing, "to").contains("add"), "callees " + outgoing);
    }

    @Test
    @DisplayName("the supertypes of a class of the unsaved text include its interface, and the subtypes of the interface include the class")
    void typeHierarchyOnUnsavedText() throws Exception {
        startWithUnsavedText();
        JsonNode square = ask(16, "textDocument/prepareTypeHierarchy", onDeclaration("static class Square", "Square"));
        assertEquals("Square", square.get(0).path("name").asText(), square.toString());
        JsonNode supertypes = ask(17, "typeHierarchy/supertypes", Map.of("item", JSON.convertValue(square.get(0), Map.class)));
        assertTrue(namesOf(supertypes, "").contains("Shape"), "supertypes " + supertypes);
        JsonNode shape = ask(18, "textDocument/prepareTypeHierarchy", onDeclaration("interface Shape", "Shape"));
        JsonNode subtypes = ask(19, "typeHierarchy/subtypes", Map.of("item", JSON.convertValue(shape.get(0), Map.class)));
        assertTrue(namesOf(subtypes, "").contains("Square"), "subtypes " + subtypes);
    }

    @Test
    @DisplayName("a command is a tool of the gate: running search_symbols answers the symbols it finds")
    void executeCommandRunsATool() throws Exception {
        startWithUnsavedText();
        JsonNode capabilities = ask(20, "initialize", Map.of()).path("capabilities");
        assertTrue(capabilities.path("executeCommandProvider").path("commands").toString().contains("search_symbols"), capabilities.toString());
        JsonNode result = ask(21, "workspace/executeCommand", Map.of("command", "search_symbols", "arguments", List.of(Map.of("query", "Calculator"))));
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
        JsonNode definition = ask(22, "textDocument/definition", onDeclaration("String typedName", "String"));
        String address = definition.path("uri").asText();
        assertTrue(address.startsWith("jdt://"), definition.toString());
        String source = ask(23, "java/classFileContents", Map.of("uri", address)).asText();
        assertTrue(source.contains("class String"), "the source of " + address + " starts " + source.substring(0, Math.min(200, source.length())));
    }

    @Test
    @DisplayName("signature help with the cursor at the second argument of a call in the unsaved text names the method called and that argument")
    void signatureHelpOnUnsavedText() throws Exception {
        startWithUnsavedText();
        Map<String, Object> at = Map.of("textDocument", Map.of("uri", uri()),
            "position", position(lineOf(unsaved, "typedCall"), at("typedCall", "add(1, ", "add(1, ".length())));
        JsonNode help = ask(9, "textDocument/signatureHelp", at);
        assertTrue(help.path("signatures").get(0).path("label").asText().contains("add"), help.toString());
        assertEquals(1, help.path("activeParameter").asInt(), "the second argument is being typed: " + help);
    }
}
