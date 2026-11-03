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
 * 28h S16c: the editing requests, one row per request, over the plain Maven tree: rename, formatting of a whole document and of a
 * range, and the code actions (quick fixes and organize imports). A rename's edit set is the one the engine stages for {@code rename_symbol}, so each rename row checks the set against what the
 * engine finds; every row applies the answered edits to the text to see the result.
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
        java.util.Collections.reverse(ordered); // edits at one position: the later one first, so an insert and a delete there land in the order sent
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
        String declared = null;
        for (JsonNode change : documents) {
            if (change.path("textDocument").path("uri").asText().equals(hello.toUri().toString())) {
                declared = applied(text, change.path("edits"));
            }
        }
        assertTrue(declared != null && declared.contains("public class Greeting") && declared.contains("public Greeting()"),
            "the type and its constructors take the new name in the file the editor then renames: " + declared);
    }

    private static final String ADD_AS_TYPED = "lastResult   =   a+b ;";

    /** The fixture file's text with the statements of add and multiply written badly, as an editor holds it after typing and before saving. */
    private static String typedBadly(String saved) {
        return saved.replace("lastResult = a + b;", ADD_AS_TYPED).replace("lastResult = a * b;", "lastResult=a*b;");
    }

    private static Map<String, Object> formattingOf(Path file) {
        return Map.of("textDocument", Map.of("uri", file.toUri().toString()), "options", Map.of("tabSize", 4, "insertSpaces", true));
    }

    @Test
    @DisplayName("formatting lays out the text the editor holds, saved or not, and answers nothing for text that is laid out already")
    void formattingLaysOutTheUnsavedText() throws Exception {
        start();
        Path calculator = source("Calculator.java");
        String saved = Files.readString(calculator);
        String typed = typedBadly(saved);
        session.open(calculator, typed);

        JsonNode edits = session.ask("textDocument/formatting", formattingOf(calculator));

        assertTrue(edits.size() > 0, "the typed text is not laid out: " + edits);
        String after = applied(typed, edits);
        assertTrue(after.contains("lastResult = a + b;") && after.contains("lastResult = a * b;"), after);
        assertTrue(!after.contains("a+b") && !after.contains("a*b"), after);
        assertEquals(saved, Files.readString(calculator), "the file on disk is as it was");
        start();
        session.open(calculator, after);
        assertEquals(0, session.ask("textDocument/formatting", formattingOf(calculator)).size(), "text that is laid out asks for no edit");
    }

    @Test
    @DisplayName("range formatting lays out the lines asked for and leaves the rest as typed")
    void rangeFormattingTouchesOnlyTheRange() throws Exception {
        start();
        Path calculator = source("Calculator.java");
        String typed = typedBadly(Files.readString(calculator));
        session.open(calculator, typed);
        int line = lineOf(typed, ADD_AS_TYPED);
        Map<String, Object> params = new HashMap<>(formattingOf(calculator));
        params.put("range", Map.of("start", Map.of("line", line, "character", 0), "end", Map.of("line", line + 1, "character", 0)));

        JsonNode edits = session.ask("textDocument/rangeFormatting", params);

        String after = applied(typed, edits);
        assertTrue(after.contains("lastResult = a + b;"), after);
        assertTrue(after.contains("lastResult=a*b;"), "the multiply line is outside the range and stays as typed: " + after);
    }

    @Test
    @DisplayName("on-type formatting lays out the line just typed and leaves the other lines as typed")
    void onTypeFormattingTouchesOnlyTheLineJustTyped() throws Exception {
        start();
        Path calculator = source("Calculator.java");
        String typed = typedBadly(Files.readString(calculator));
        session.open(calculator, typed);
        int line = lineOf(typed, ADD_AS_TYPED);
        Map<String, Object> params = new HashMap<>(formattingOf(calculator));
        params.put("position", Map.of("line", line, "character", typed.split("\n", -1)[line].length()));
        params.put("ch", ";");

        JsonNode edits = session.ask("textDocument/onTypeFormatting", params);

        String after = applied(typed, edits);
        assertTrue(after.contains("lastResult = a + b;"), after);
        assertTrue(after.contains("lastResult=a*b;"), "the multiply line was not just typed and stays as typed: " + after);
    }

    private static final String PROBLEM_FILE = """
        package com.example;

        import java.util.List;

        public class HasAProblem {
            Object made = new ArrayList<String>();
        }
        """;

    /** A copy of the tree with a file that has an import nothing uses and a name nothing imports. */
    private Path withAProblemFile() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        session = new LspSession(service, "code-action-test");
        Path file = service.getProjectRoot().resolve("src/main/java/com/example/HasAProblem.java");
        Files.writeString(file, PROBLEM_FILE);
        new org.jawata.resident.workspace.OwnedReconciler(() -> service).syncBeforeCall();
        return file;
    }

    private static Map<String, Object> codeActionsAt(Path file, int line, List<String> only) {
        Map<String, Object> context = new HashMap<>();
        context.put("diagnostics", List.of());
        if (!only.isEmpty()) {
            context.put("only", only);
        }
        return Map.<String, Object>of("textDocument", Map.of("uri", file.toUri().toString()),
            "range", Map.of("start", Map.of("line", line, "character", 0), "end", Map.of("line", line, "character", 0)),
            "context", context);
    }

    /** The first action of the answer of this kind whose title holds {@code titled}; null when there is none. */
    private static JsonNode actionOfKind(JsonNode answer, String kind, String titled) {
        for (JsonNode candidate : answer) {
            if (kind.equals(candidate.path("kind").asText()) && candidate.path("title").asText().contains(titled)) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    @DisplayName("code actions offer the import for a name nothing imports, as the edit that adds it, and organize imports as the edit that tidies the block")
    void codeActionsOfferTheFixesOfTheLine() throws Exception {
        Path file = withAProblemFile();

        JsonNode actions = session.ask("textDocument/codeAction", codeActionsAt(file, lineOf(PROBLEM_FILE, "new ArrayList"), List.of()));

        JsonNode fix = actionOfKind(actions, "quickfix", "java.util");
        assertTrue(fix != null, "the unresolved name has an import fix: " + actions);
        String fixed = applied(PROBLEM_FILE, fix.path("edit").path("changes").get(file.toUri().toString()));
        assertTrue(fixed.contains("import java.util.ArrayList;"), fixed);
        JsonNode organize = actionOfKind(actions, "source.organizeImports", "Organize");
        assertTrue(organize != null, "imports that are not organized have an action: " + actions);
        String organized = applied(PROBLEM_FILE, organize.path("edit").path("changes").get(file.toUri().toString()));
        assertTrue(!organized.contains("import java.util.List;") && organized.contains("import java.util.ArrayList;"), organized);
        Files.writeString(file, fixed);
        new org.jawata.resident.workspace.OwnedReconciler(() -> service).syncBeforeCall();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode compiled = mapper.valueToTree(new org.jawata.mcp.tools.CompileWorkspaceTool(() -> service,
            () -> new org.jawata.mcp.java.JavaBuildVerdict(service), () -> new org.jawata.resident.workspace.OwnedReconciler(() -> service))
            .execute(mapper.createObjectNode()).getData());
        assertEquals(0, compiled.path("errorCount").asInt(), "the file with the fix compiles: " + compiled);
    }

    @Test
    @DisplayName("code actions answer only the kinds the editor names")
    void codeActionsAnswerTheKindsAskedFor() throws Exception {
        Path file = withAProblemFile();
        int line = lineOf(PROBLEM_FILE, "new ArrayList");

        JsonNode quickOnly = session.ask("textDocument/codeAction", codeActionsAt(file, line, List.of("quickfix")));
        JsonNode sourceOnly = session.ask("textDocument/codeAction", codeActionsAt(file, line, List.of("source")));

        assertTrue(actionOfKind(quickOnly, "quickfix", "") != null && actionOfKind(quickOnly, "source.organizeImports", "") == null, quickOnly.toString());
        assertTrue(actionOfKind(sourceOnly, "source.organizeImports", "") != null && actionOfKind(sourceOnly, "quickfix", "") == null, sourceOnly.toString());
    }

    @Test
    @DisplayName("a file with unsaved editor text is offered no code action, because the gate reads the saved text")
    void noCodeActionOverUnsavedText() throws Exception {
        Path file = withAProblemFile();
        session.open(file, PROBLEM_FILE + "\n// typed and not saved\n");

        JsonNode actions = session.ask("textDocument/codeAction", codeActionsAt(file, lineOf(PROBLEM_FILE, "new ArrayList"), List.of()));

        assertEquals(0, actions.size(), actions.toString());
    }

    /** A copy of the tree this test is free to change, with a session over it; the folder of the example package. */
    private Path copiedTree() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        session = new LspSession(service, "file-operation-test");
        return service.getProjectRoot().resolve("src/main/java/com/example");
    }

    private static Map<String, Object> willRename(Path from, Path to) {
        return Map.of("files", List.of(Map.of("oldUri", from.toUri().toString(), "newUri", to.toUri().toString())));
    }

    @Test
    @DisplayName("renaming a Java file in its folder answers the rename of its type, and no rename of the file, which the editor does itself")
    void renamingAFileRenamesItsType() throws Exception {
        Path folder = copiedTree();
        Path hello = folder.resolve("HelloWorld.java");

        JsonNode edit = session.ask("workspace/willRenameFiles", willRename(hello, folder.resolve("Greeting.java")));

        String after = applied(Files.readString(hello), edit.path("changes").get(hello.toUri().toString()));
        assertTrue(after.contains("class Greeting"), after);
        assertTrue(!edit.has("documentChanges"), "the editor renames the file itself: " + edit);
    }

    @Test
    @DisplayName("moving a Java file to another folder of its source root answers the package change, and the imports of the files that use it")
    void movingAFileMovesItsType() throws Exception {
        Path folder = copiedTree();
        Path calculator = folder.resolve("Calculator.java");

        JsonNode edit = session.ask("workspace/willRenameFiles", willRename(calculator, folder.resolve("util").resolve("Calculator.java")));

        JsonNode changes = edit.path("changes");
        String moved = applied(Files.readString(calculator), changes.get(calculator.toUri().toString()));
        assertTrue(moved.contains("package com.example.util;"), moved);
        assertTrue(changes.size() > 1, "the files that use Calculator change too, or the loop below checks nothing: " + edit);
        List<String> others = new ArrayList<>();
        changes.fieldNames().forEachRemaining(others::add);
        others.remove(calculator.toUri().toString());
        for (String uri : others) {
            assertTrue(applied(Files.readString(Path.of(java.net.URI.create(uri))), changes.get(uri)).contains("com.example.util.Calculator"), uri);
        }
    }

    @Test
    @DisplayName("a file operation that would change a file with unsaved editor text is refused, naming that file")
    void aFileOperationOverUnsavedTextIsRefused() throws Exception {
        Path folder = copiedTree();
        Path calculator = folder.resolve("Calculator.java");
        session.open(calculator, Files.readString(calculator) + "\n// typed and not saved\n");

        String message = session.refusalOf("workspace/willRenameFiles", willRename(calculator, folder.resolve("util").resolve("Calculator.java")));

        assertTrue(message.contains("Calculator.java") && message.contains("unsaved"), message);
    }

    @Test
    @DisplayName("a file that is not Java changes no code")
    void aFileThatIsNotJavaChangesNoCode() throws Exception {
        Path folder = copiedTree();

        JsonNode edit = session.ask("workspace/willRenameFiles", willRename(folder.resolve("notes.txt"), folder.resolve("memo.txt")));

        assertTrue(edit.isNull(), edit.toString());
    }

    @Test
    @DisplayName("formatting answers what the format tool writes: the editor's formatted text is the file the tool formats from the same text")
    void formattingEqualsWhatTheFormatToolWrites() throws Exception {
        Path folder = copiedTree();
        Path calculator = folder.resolve("Calculator.java");
        String typed = typedBadly(Files.readString(calculator));
        Files.writeString(calculator, typed);
        new org.jawata.resident.workspace.OwnedReconciler(() -> service).syncBeforeCall();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode arguments = mapper.createObjectNode();
        arguments.putObject("scope").put("kind", "file").put("filePath", calculator.toString());
        assertTrue(new org.jawata.mcp.tools.workflow.FormatTool(() -> service).execute(arguments).isSuccess(), "the format tool formats the file");
        String byTool = Files.readString(calculator);
        assertTrue(!byTool.equals(typed), "the tool changed the file");
        Files.writeString(calculator, typed);
        new org.jawata.resident.workspace.OwnedReconciler(() -> service).syncBeforeCall();
        session.open(calculator, typed);

        JsonNode edits = session.ask("textDocument/formatting", formattingOf(calculator));

        assertEquals(byTool, applied(typed, edits), "the editor's text is the tool's text");
    }

    @Test
    @DisplayName("renaming a Java file in its folder over unsaved text is refused, naming the file")
    void aFileRenameOverUnsavedTextIsRefused() throws Exception {
        Path folder = copiedTree();
        Path hello = folder.resolve("HelloWorld.java");
        session.open(hello, Files.readString(hello) + "\n// typed and not saved\n");

        String message = session.refusalOf("workspace/willRenameFiles", willRename(hello, folder.resolve("Greeting.java")));

        assertTrue(message.contains("HelloWorld.java") && message.contains("unsaved"), message);
    }

    @Test
    @DisplayName("a selection is offered the extractions of the engine, as the edits that make them")
    void aSelectionIsOfferedTheExtractions() throws Exception {
        Path folder = copiedTree();
        Path calculator = folder.resolve("Calculator.java");
        String text = Files.readString(calculator);
        int line = lineOf(text, "lastResult = a + b;");
        String lineText = text.split("\n", -1)[line];
        int expression = lineText.indexOf("a + b");
        int statement = lineText.indexOf("lastResult");

        JsonNode variableActions = session.ask("textDocument/codeAction", selection(calculator, line, expression, line, expression + "a + b".length()));
        JsonNode methodActions = session.ask("textDocument/codeAction", selection(calculator, line, statement, line, lineText.length()));

        JsonNode variable = actionOfKind(variableActions, "refactor.extract", "local variable");
        assertTrue(variable != null, "the expression can be extracted to a variable: " + variableActions);
        String withVariable = applied(text, variable.path("edit").path("changes").get(calculator.toUri().toString()));
        assertTrue(!withVariable.contains("lastResult = a + b;") && withVariable.lines().anyMatch(l -> l.contains("int ") && l.contains("= a + b;")), withVariable);
        JsonNode method = actionOfKind(methodActions, "refactor.extract", "method");
        assertTrue(method != null, "the statement can be extracted to a method: " + methodActions);
        String withMethod = applied(text, method.path("edit").path("changes").get(calculator.toUri().toString()));
        assertTrue(withMethod.split("extracted\\(", -1).length >= 3, "the call and the new method: " + withMethod);
    }

    private static Map<String, Object> selection(Path file, int line, int from, int endLine, int to) {
        return Map.of("textDocument", Map.of("uri", file.toUri().toString()),
            "range", Map.of("start", Map.of("line", line, "character", from), "end", Map.of("line", endLine, "character", to)),
            "context", Map.of("diagnostics", List.of(), "only", List.of("refactor.extract")));
    }
}
