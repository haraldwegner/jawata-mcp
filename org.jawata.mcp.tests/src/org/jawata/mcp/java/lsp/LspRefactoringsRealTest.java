package org.jawata.mcp.java.lsp;

import static org.jawata.mcp.java.lsp.LspSession.onName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 28i, Stage 25, step A5 (ARCHITECTURE-28i-s25.md), the exit measure: for one refactoring of each family the engine's own tools stage the
 * change, the language server offers it, and applying the answered edits to the text gives the file the refactoring is meant to give. Over the plain
 * Maven tree and the real tool gate; the edit is the editor's to apply and to undo, so each row applies it to the text and reads the result.
 */
class LspRefactoringsRealTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private LspSession session;

    private Path fixtureFile(String name) throws Exception {
        session = session == null ? new LspSession(helper.loadProject("simple-maven"), "refactor-real-test") : session;
        return helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/" + name);
    }

    /** The code actions of one kind offered at the name {@code name} on the line holding {@code marker}. */
    private JsonNode offeredAt(Path file, String text, String marker, String name, String kind) throws Exception {
        Map<String, Object> params = new HashMap<>(onName(file, text, marker, name));
        Object position = params.get("position");
        params.put("range", Map.of("start", position, "end", position));
        params.put("context", Map.of("only", List.of(kind), "diagnostics", List.of()));
        return session.ask("textDocument/codeAction", params);
    }

    private static JsonNode actionTitled(JsonNode actions, String title) {
        for (JsonNode action : actions) {
            if (title.equals(action.path("title").asText())) {
                return action;
            }
        }
        throw new AssertionError("no action titled '" + title + "' in " + actions);
    }

    private String afterApplying(JsonNode changes, Path file) throws Exception {
        return JavaLanguageServerRenameTest.applied(Files.readString(file), changes.path(file.toUri().toString()));
    }

    @Test
    @DisplayName("inline family: inlining a method is offered at its name, and applied the callers hold the value instead of the call")
    void inlineAMethod() throws Exception {
        Path shared = fixtureFile("SharedHelper.java");
        String text = Files.readString(shared);

        JsonNode inline = actionTitled(offeredAt(shared, text, "public int value()", "value", "refactor.inline"), "Inline method");

        JsonNode changes = inline.path("edit").path("changes");
        assertEquals("refactor.inline", inline.path("kind").asText());
        for (String user : List.of("SharedUserOne.java", "SharedUserTwo.java")) {
            String after = afterApplying(changes, fixtureFile(user));
            assertFalse(after.contains("value()"), user + " still calls the method:\n" + after);
        }
    }

    @Test
    @DisplayName("change-signature family: a visibility is asked of the person, and staged with their answer it makes the method package-private")
    void changeAMethodsVisibility() throws Exception {
        Path shared = fixtureFile("SharedHelper.java");
        String text = Files.readString(shared);

        JsonNode offered = actionTitled(offeredAt(shared, text, "public int value()", "value", "refactor.rewrite"), "Change method visibility");
        JsonNode ask = offered.path("command").path("arguments").path(0);
        assertEquals("signature.visibility", ask.path("id").asText());
        assertFalse(offered.has("edit"), "nothing is staged before the person answers");

        Map<String, Object> answered = new HashMap<>();
        answered.put("id", ask.path("id").asText());
        answered.put("uri", ask.path("uri").asText());
        answered.put("line", ask.path("line").asInt());
        answered.put("column", ask.path("column").asInt());
        answered.put("inputs", Map.of("visibility", "package"));
        JsonNode edit = session.ask("jawata/refactor/stage", answered);

        String after = afterApplying(edit.path("changes"), shared);
        assertTrue(after.contains("int value()") && !after.contains("public int value()"), after);
    }

    @Test
    @DisplayName("move family: pulling a method up is offered at its name, and applied the superclass declares it and the subclass no longer does")
    void pullAMethodUp() throws Exception {
        Path sub = fixtureFile("PlainCharge.java");
        String text = Files.readString(sub);

        JsonNode pull = actionTitled(offeredAt(sub, text, "public int doubled()", "doubled", "refactor.move"), "Pull member up");

        JsonNode changes = pull.path("edit").path("changes");
        String subAfter = afterApplying(changes, sub);
        String superAfter = afterApplying(changes, fixtureFile("Charge.java"));
        assertFalse(subAfter.contains("doubled()"), subAfter);
        assertTrue(superAfter.contains("doubled()"), superAfter);
    }

    @Test
    @DisplayName("clean-up family: making unchanged variables final is offered at a method, and applied its parameters that are never reassigned are final")
    void makeUnchangedVariablesFinal() throws Exception {
        Path targets = fixtureFile("GuardClauseTargets.java");
        String text = Files.readString(targets);

        JsonNode cleanup = actionTitled(offeredAt(targets, text, "public int nestedReturns(", "nestedReturns", "refactor.rewrite"),
            "Make variables that are never reassigned final");

        String after = afterApplying(cleanup.path("edit").path("changes"), targets);
        assertFalse(text.contains("final boolean dead"), "the fixture starts without it");
        assertTrue(after.contains("final boolean dead"), "the parameter was not made final:\n" + after);
    }

    @Test
    @DisplayName("pattern family: a factory method's name is asked of the person, and staged with it the callers use the factory instead of the constructor")
    void replaceAConstructorWithAFactory() throws Exception {
        Path charge = fixtureFile("PlainCharge.java");
        String text = Files.readString(charge);

        JsonNode offered = actionTitled(offeredAt(charge, text, "public PlainCharge(int amount)", "PlainCharge", "refactor.rewrite"),
            "Replace constructor with factory method");
        JsonNode ask = offered.path("command").path("arguments").path(0);

        Map<String, Object> answered = new HashMap<>();
        answered.put("id", ask.path("id").asText());
        answered.put("uri", ask.path("uri").asText());
        answered.put("line", ask.path("line").asInt());
        answered.put("column", ask.path("column").asInt());
        answered.put("inputs", Map.of("factoryMethodName", "of"));
        JsonNode edit = session.ask("jawata/refactor/stage", answered);

        String user = afterApplying(edit.path("changes"), fixtureFile("PlainChargeUser.java"));
        assertTrue(user.contains(".of(amount)") && !user.contains("new PlainCharge("), user);
    }
}
