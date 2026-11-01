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
 * 28h S16b, the plug-in half of the scripted exit clause: the navigation requests over a tree of two Eclipse plug-in bundles. The editor
 * has {@code Caller.java} of {@code pde-bundle-a} open with unsaved text that uses {@code Greeter} of {@code pde-bundle-b} in new ways,
 * so a row that answers about it proves the answer crosses the bundles and is about the person's text.
 */
class JavaLanguageServerPluginNavigationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String TYPED = "    public String typedHello() { return greeter.greet(\"x\"); }\n"
        + "    void typedHalf() { greeter.gre }\n"
        + "    static class Friendly extends Greeter { }\n";

    private LspSession session;
    private Path caller;
    private Path greeter;
    private String greeterText;
    private String unsaved;

    private void start() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-b", "pde-bundle-a");
        caller = helper.getTempDirectory().resolve("pde-bundle-a/src/com/example/a/Caller.java");
        greeter = helper.getTempDirectory().resolve("pde-bundle-b/src/com/example/b/Greeter.java");
        greeterText = Files.readString(greeter);
        String saved = Files.readString(caller);
        unsaved = saved.substring(0, saved.lastIndexOf('}')) + TYPED + "}\n";
        session = new LspSession(service, "plugin-navigation-test");
        session.open(caller, unsaved);
    }

    /** A request at a name of the unsaved text of Caller. */
    private Map<String, Object> on(String lineMarker, String name) {
        return onName(caller, unsaved, lineMarker, name);
    }

    /** A request at the declaration of {@code greet}, in the other bundle, which nobody has open. */
    private Map<String, Object> onGreetDeclaration() {
        return onName(greeter, greeterText, "public String greet(", "greet");
    }

    @Test
    @DisplayName("hover on a call of the other bundle's method, in unsaved text, shows its signature")
    void hover() throws Exception {
        start();
        assertTrue(session.ask("textDocument/hover", on("typedHello", ".greet")).path("contents").path("value").asText().contains("greet("));
    }

    @Test
    @DisplayName("definition and declaration of that call are the method's name in the other bundle's file")
    void definitionAndDeclaration() throws Exception {
        start();
        for (String method : List.of("textDocument/definition", "textDocument/declaration")) {
            JsonNode definition = session.ask(method, on("typedHello", ".greet"));
            assertTrue(definition.path("uri").asText().endsWith("pde-bundle-b/src/com/example/b/Greeter.java"), method + " " + definition);
            assertEquals(lineOf(greeterText, "public String greet("), definition.path("range").path("start").path("line").asInt(), method);
            assertEquals("greet".length(), definition.path("range").path("end").path("character").asInt()
                - definition.path("range").path("start").path("character").asInt(), method + " names the method: " + definition);
        }
    }

    @Test
    @DisplayName("references of the other bundle's method include its call in the unsaved text of this bundle")
    void references() throws Exception {
        start();
        Map<String, Object> params = new HashMap<>(onGreetDeclaration());
        params.put("context", Map.of("includeDeclaration", false));
        JsonNode references = session.ask("textDocument/references", params);
        boolean found = false;
        for (JsonNode reference : references) {
            found |= reference.path("uri").asText().endsWith("Caller.java")
                && reference.path("range").path("start").path("line").asInt() == lineOf(unsaved, "typedHello");
        }
        assertTrue(found, "references " + references);
    }

    @Test
    @DisplayName("highlights of the field mark every use in the file, the unsaved ones too")
    void highlights() throws Exception {
        start();
        List<Integer> lines = startLinesOf(session.ask("textDocument/documentHighlight", on("typedHello", "greeter")));
        assertTrue(lines.contains(lineOf(unsaved, "typedHello")) && lines.size() >= 3, "highlight lines " + lines);
    }

    @Test
    @DisplayName("implementations of the other bundle's class include the unsaved class of this bundle that extends it")
    void implementations() throws Exception {
        start();
        List<Integer> lines = startLinesOf(session.ask("textDocument/implementation", onName(greeter, greeterText, "public class Greeter", "Greeter")));
        assertTrue(lines.contains(lineOf(unsaved, "class Friendly")), "implementation lines " + lines);
    }

    @Test
    @DisplayName("the outline of the unsaved file lists the new method, and workspace symbols find the other bundle's class")
    void symbols() throws Exception {
        start();
        JsonNode outline = session.ask("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", caller.toUri().toString())));
        assertTrue(namesOf(outline.get(0).path("children"), "").contains("typedHello"), outline.toString());
        assertTrue(namesOf(session.ask("workspace/symbol", Map.of("query", "Greeter")), "").contains("Greeter"));
    }

    @Test
    @DisplayName("signature help inside the call of the other bundle's method names it")
    void signatureHelp() throws Exception {
        start();
        JsonNode help = session.ask("textDocument/signatureHelp",
            at(caller, lineOf(unsaved, "typedHello"), columnOf(unsaved, "typedHello", "greet(", "greet(".length())));
        assertTrue(help.path("signatures").get(0).path("label").asText().contains("greet"), help.toString());
        assertEquals(0, help.path("activeParameter").asInt(), help.toString());
    }

    @Test
    @DisplayName("completion after 'greeter.gre' in the unsaved text proposes the other bundle's method 'greet'")
    void completion() throws Exception {
        start();
        JsonNode items = session.ask("textDocument/completion",
            at(caller, lineOf(unsaved, "typedHalf"), columnOf(unsaved, "typedHalf", "greeter.gre", "greeter.gre".length()))).path("items");
        List<String> labels = new ArrayList<>();
        for (JsonNode item : items) {
            labels.add(item.path("label").asText());
        }
        assertTrue(labels.contains("greet"), "proposals " + labels);
    }

    @Test
    @DisplayName("the type of the field is the other bundle's class")
    void typeDefinition() throws Exception {
        start();
        JsonNode definition = session.ask("textDocument/typeDefinition", on("typedHello", "greeter"));
        assertTrue(definition.path("uri").asText().endsWith("pde-bundle-b/src/com/example/b/Greeter.java"), definition.toString());
        assertEquals(lineOf(greeterText, "public class Greeter"), definition.path("range").path("start").path("line").asInt());
    }

    @Test
    @DisplayName("the callers of the other bundle's method include the unsaved method of this bundle that calls it")
    void callHierarchy() throws Exception {
        start();
        JsonNode greet = session.ask("textDocument/prepareCallHierarchy", onGreetDeclaration());
        assertEquals("greet", greet.get(0).path("name").asText(), greet.toString());
        JsonNode incoming = session.ask("callHierarchy/incomingCalls", asParams(greet.get(0)));
        assertTrue(namesOf(incoming, "from").contains("typedHello"), "callers " + incoming);
        JsonNode typed = session.ask("textDocument/prepareCallHierarchy", on("typedHello", "typedHello"));
        assertTrue(namesOf(session.ask("callHierarchy/outgoingCalls", asParams(typed.get(0))), "to").contains("greet"));
    }

    @Test
    @DisplayName("the supertypes of the unsaved class include the other bundle's class, and its subtypes include the unsaved class")
    void typeHierarchy() throws Exception {
        start();
        JsonNode friendly = session.ask("textDocument/prepareTypeHierarchy", on("class Friendly", "Friendly"));
        assertEquals("Friendly", friendly.get(0).path("name").asText(), friendly.toString());
        assertTrue(namesOf(session.ask("typeHierarchy/supertypes", asParams(friendly.get(0))), "").contains("Greeter"));
        JsonNode greeterItem = session.ask("textDocument/prepareTypeHierarchy", onName(greeter, greeterText, "public class Greeter", "Greeter"));
        assertTrue(namesOf(session.ask("typeHierarchy/subtypes", asParams(greeterItem.get(0))), "").contains("Friendly"));
    }

    @Test
    @DisplayName("a command runs a tool of the gate over both bundles, and a library class's source is asked at the address its definition gave")
    void commandAndLibraryClass() throws Exception {
        start();
        JsonNode result = session.ask("workspace/executeCommand",
            Map.of("command", "search_symbols", "arguments", List.of(Map.of("query", "Greeter"))));
        List<String> names = new ArrayList<>();
        for (JsonNode hit : result.path("results")) {
            names.add(hit.path("name").asText());
        }
        assertTrue(names.contains("Greeter"), "found " + names);
        JsonNode definition = session.ask("textDocument/definition", on("public String hello", "String"));
        String address = definition.path("uri").asText();
        assertTrue(address.startsWith("jdt://"), definition.toString());
        assertTrue(session.ask("java/classFileContents", Map.of("uri", address)).asText().contains("class String"));
    }
}
