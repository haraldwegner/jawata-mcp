package org.jawata.mcp.java.lsp;

import static org.jawata.mcp.java.lsp.LspSession.lineOf;
import static org.jawata.mcp.java.lsp.LspSession.onName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 28h S16c: the rename requests, one row per request, over the plain Maven tree. The edit set is the one the engine stages for
 * {@code rename_symbol}, so each row checks the set against what the engine finds, and applies it to the text to see the result.
 */
class JavaLanguageServerRenameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private LspSession session;

    private void start() throws Exception {
        service = helper.loadProject("simple-maven");
        session = new LspSession(service, "rename-test");
    }

    private Path source(String name) {
        return helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/" + name);
    }

    /** A rename at the name {@code name} on the line of {@code text} that holds {@code lineMarker}, to {@code newName}. */
    private static Map<String, Object> renameAt(Path file, String text, String lineMarker, String name, String newName) {
        Map<String, Object> params = new HashMap<>(onName(file, text, lineMarker, name));
        params.put("newName", newName);
        return params;
    }

    /** The edits of one file, applied to its text: the last edit first, so the earlier positions still hold. */
    private static String applied(String text, JsonNode edits) {
        String[] lines = text.split("\n", -1);
        int[] starts = new int[lines.length];
        for (int i = 1; i < lines.length; i++) {
            starts[i] = starts[i - 1] + lines[i - 1].length() + 1;
        }
        List<JsonNode> ordered = new ArrayList<>();
        edits.forEach(ordered::add);
        ordered.sort(Comparator.<JsonNode>comparingInt(e -> e.path("range").path("start").path("line").asInt())
            .thenComparingInt(e -> e.path("range").path("start").path("character").asInt()).reversed());
        StringBuilder result = new StringBuilder(text);
        for (JsonNode edit : ordered) {
            JsonNode range = edit.path("range");
            int from = starts[range.path("start").path("line").asInt()] + range.path("start").path("character").asInt();
            int to = starts[range.path("end").path("line").asInt()] + range.path("end").path("character").asInt();
            result.replace(from, to, edit.path("newText").asText());
        }
        return result.toString();
    }

    private static int countEdits(JsonNode changes) {
        int count = 0;
        for (JsonNode file : changes) {
            count += file.size();
        }
        return count;
    }

    @Test
    @DisplayName("prepareRename answers the name under the cursor, and what it is called")
    void prepareRenameAnswersTheName() throws Exception {
        start();
        Path shared = source("SharedHelper.java");
        String text = Files.readString(shared);

        JsonNode prepared = session.ask("textDocument/prepareRename", onName(shared, text, "public int value()", "value"));

        assertEquals("value", prepared.path("placeholder").asText(), prepared.toString());
        assertEquals(lineOf(text, "public int value()"), prepared.path("range").path("start").path("line").asInt(), prepared.toString());
        assertEquals("value".length(), prepared.path("range").path("end").path("character").asInt()
            - prepared.path("range").path("start").path("character").asInt(), prepared.toString());
    }

    @Test
    @DisplayName("rename answers one edit per place the method is named, and applied, the callers say the new name")
    void renameAnswersTheEditsOfTheEngine() throws Exception {
        start();
        Path shared = source("SharedHelper.java");
        String helperText = Files.readString(shared);
        Path one = source("SharedUserOne.java");
        Path two = source("SharedUserTwo.java");
        String oneText = Files.readString(one);
        String twoText = Files.readString(two);

        JsonNode edit = session.ask("textDocument/rename", renameAt(shared, helperText, "public int value()", "value", "magnitude"));

        JsonNode changes = edit.path("changes");
        assertEquals(3, changes.size(), "the declaration and the two users are three files: " + edit);
        Map<String, Object> asking = new HashMap<>(onName(shared, helperText, "public int value()", "value"));
        asking.put("context", Map.of("includeDeclaration", false));
        int uses = session.ask("textDocument/references", asking).size();
        assertEquals(uses + 1, countEdits(changes), "one edit per reference, and one for the declaration: " + edit);

        String helperAfter = applied(helperText, changes.get(shared.toUri().toString()));
        String oneAfter = applied(oneText, changes.get(one.toUri().toString()));
        String twoAfter = applied(twoText, changes.get(two.toUri().toString()));
        assertTrue(helperAfter.contains("public int magnitude()") && !helperAfter.contains("value()"), helperAfter);
        assertTrue(oneAfter.contains("helper.magnitude()") && !oneAfter.contains("helper.value()"), oneAfter);
        assertTrue(twoAfter.contains("helper.magnitude()") && !twoAfter.contains("helper.value()"), twoAfter);
    }

    @Test
    @DisplayName("a rename that would change a file the editor has unsaved text in is refused, naming that file, and changes nothing")
    void aRenameOverUnsavedTextIsRefused() throws Exception {
        start();
        Path calculator = source("Calculator.java");
        String saved = Files.readString(calculator);
        String unsaved = saved + "\n// typed and not saved\n";
        session.open(calculator, unsaved);

        String message = session.refusalOf("textDocument/rename", renameAt(calculator, unsaved, "public int add(", "add", "sum"));

        assertTrue(message.contains("Calculator.java") && message.contains("unsaved"), message);
        assertEquals(saved, Files.readString(calculator), "the file on disk is as it was");
    }

    @Test
    @DisplayName("a rename of a type answers the rename of its file too, as a document change the editor applies in order")
    void aTypeRenameAnswersTheFileRename() throws Exception {
        start();
        Path hello = source("HelloWorld.java");
        String text = Files.readString(hello);

        JsonNode edit = session.ask("textDocument/rename", renameAt(hello, text, "public class HelloWorld", "HelloWorld", "Greeting"));

        JsonNode documents = edit.path("documentChanges");
        assertTrue(documents.isArray(), "a file rename is a document change: " + edit);
        JsonNode rename = null;
        for (JsonNode change : documents) {
            if ("rename".equals(change.path("kind").asText())) {
                rename = change;
            }
        }
        assertTrue(rename != null, "the rename operation is among the document changes: " + edit);
        assertTrue(rename.path("oldUri").asText().endsWith("HelloWorld.java"), rename.toString());
        assertTrue(rename.path("newUri").asText().endsWith("Greeting.java"), rename.toString());
    }
}
