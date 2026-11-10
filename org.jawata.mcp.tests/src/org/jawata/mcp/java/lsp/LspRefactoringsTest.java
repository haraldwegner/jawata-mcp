package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28i, Stage 25, step A5 (ARCHITECTURE-28i-s25.md): the refactorings the editor is offered at the cursor and the staging of one that needed the
 * person's answers, over the engine's tools as a script of canned answers stands in for them.
 */
class LspRefactoringsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URI = "file:///work/src/Foo.java";

    /** Answers get_at_position with the element kind it was given and each refactoring tool from a table, recording every call. */
    private static final class Fake implements ToolCalls {
        String element = "Method";
        final Map<String, ToolResponse> staged = new HashMap<>();
        final List<String> tools = new CopyOnWriteArrayList<>();
        final List<ObjectNode> calls = new CopyOnWriteArrayList<>();

        @Override
        public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
            tools.add(name);
            calls.add(arguments.deepCopy());
            if ("get_at_position".equals(name)) {
                return element == null ? ToolResponse.error("NOT_FOUND", "nothing here", "") : ToolResponse.success(Map.of("kind", element), ResponseMeta.builder().build());
            }
            return staged.getOrDefault(name, ToolResponse.error("REFUSED", "not here", ""));
        }

        @Override
        public List<Map<String, Object>> getToolDefinitions() {
            return List.of();
        }
    }

    private static ToolResponse editing(String newText) {
        return ToolResponse.success(Map.of("applied", false, "edits", List.of(Map.of("filePath", "/work/src/Foo.java",
            "edits", List.of(Map.of("line", 3, "column", 4, "endLine", 3, "endColumn", 9, "newText", newText))))), ResponseMeta.builder().build());
    }

    private static LspRefactorings over(Fake fake, Predicate<String> unsaved) {
        return new LspRefactorings(fake, CallerIdentity.editor("lsp-test"), new LspEdits(uri -> null, unsaved));
    }

    private static JsonNode cursor() {
        return JSON.createObjectNode().put("line", 3).put("character", 4);
    }

    private static List<JsonNode> offeredAt(Fake fake, Predicate<String> asked) throws Exception {
        List<Object> actions = new ArrayList<>();
        over(fake, uri -> false).offered(URI, "/work/src/Foo.java", cursor(), asked, actions);
        return actions.stream().map(a -> (JsonNode) JSON.valueToTree(a)).toList();
    }

    @Test
    @DisplayName("at a method the refactorings that apply to methods are offered: those that need nothing as the edit they make, those that need answers as a command that says what to ask")
    void theRefactoringsThatApplyAtAMethodAreOffered() throws Exception {
        Fake fake = new Fake();
        fake.staged.put("inline", editing("inlined"));
        fake.staged.put("hierarchy", editing("pulled up"));

        List<JsonNode> actions = offeredAt(fake, kind -> true);

        List<String> titles = actions.stream().map(a -> a.path("title").asText()).toList();
        assertTrue(titles.contains("Inline method"), titles.toString());
        assertTrue(titles.contains("Pull member up"), titles.toString());
        assertFalse(titles.contains("Inline variable"), "a variable's refactoring is not offered on a method: " + titles);
        JsonNode inline = actions.stream().filter(a -> "Inline method".equals(a.path("title").asText())).findFirst().orElseThrow();
        assertEquals("refactor.inline", inline.path("kind").asText());
        assertEquals("inlined", inline.path("edit").path("changes").path("file:///work/src/Foo.java").path(0).path("newText").asText());
        JsonNode visibility = actions.stream().filter(a -> "Change method visibility".equals(a.path("title").asText())).findFirst().orElseThrow();
        JsonNode ask = visibility.path("command").path("arguments").path(0);
        assertEquals(LspRefactorings.COMMAND, visibility.path("command").path("command").asText());
        assertEquals("signature.visibility", ask.path("id").asText());
        assertEquals("visibility", ask.path("inputs").path(0).path("name").asText());
        assertEquals(4, ask.path("inputs").path(0).path("choices").size(), "the four visibilities are the answers to choose from");
        assertFalse(visibility.has("edit"), "nothing is staged until the person has answered");
    }

    @Test
    @DisplayName("a refactoring the engine refuses at this place is not offered, and only the kinds the editor asked for are looked at")
    void aRefusedRefactoringIsNotOfferedAndOnlyAskedKindsAreTried() throws Exception {
        Fake fake = new Fake();
        fake.staged.put("inline", editing("inlined")); // hierarchy and the rest are refused

        List<JsonNode> actions = offeredAt(fake, kind -> kind.equals("refactor.inline") || kind.equals("refactor.move"));

        assertEquals(List.of("Inline method"), actions.stream().map(a -> a.path("title").asText()).toList());
        assertFalse(fake.tools.contains("apply_cleanup"), "a kind the editor did not ask for costs no call");
        ObjectNode asked = fake.calls.get(fake.tools.indexOf("inline"));
        assertEquals("method", asked.path("kind").asText());
        assertFalse(asked.path("auto_apply").asBoolean(true), "it is staged, never applied");
        assertTrue(asked.path("edits").asBoolean(), "as edits the editor applies itself");
        assertEquals("/work/src/Foo.java", asked.path("filePath").asText());
    }

    @Test
    @DisplayName("when the engine cannot say what is at the cursor nothing is offered and no refactoring is staged")
    void nothingIsOfferedWhereTheEngineSaysNothing() throws Exception {
        Fake fake = new Fake();
        fake.element = null;

        assertTrue(offeredAt(fake, kind -> true).isEmpty());
        assertEquals(List.of("get_at_position"), fake.tools);
    }

    @Test
    @DisplayName("a refactoring that needed answers is staged with them and comes back as the workspace edit")
    void aPromptedRefactoringIsStagedWithTheAnswersGiven() throws Exception {
        Fake fake = new Fake();
        fake.staged.put("change_method_signature", editing("private"));
        ObjectNode params = JSON.createObjectNode().put("id", "signature.visibility").put("uri", URI).put("line", 3).put("column", 4);
        params.putObject("inputs").put("visibility", "private");

        JsonNode edit = JSON.valueToTree(over(fake, uri -> false).stage(params));

        assertEquals("private", edit.path("changes").path("file:///work/src/Foo.java").path(0).path("newText").asText());
        ObjectNode sent = fake.calls.get(0);
        assertEquals("private", sent.path("visibility").asText(), "the person's answer reaches the tool under the asked name");
        assertEquals("change_signature", sent.path("kind").asText());
        assertFalse(sent.path("auto_apply").asBoolean(true));
    }

    @Test
    @DisplayName("staging is refused, with the reason, when an answer is missing, the id is unknown, the engine refuses, or the file has unsaved text")
    void stagingIsRefusedWithAReason() {
        Fake fake = new Fake();
        ObjectNode missing = JSON.createObjectNode().put("id", "signature.visibility").put("uri", URI);
        missing.putObject("inputs");
        ObjectNode unknown = JSON.createObjectNode().put("id", "no.such").put("uri", URI);
        ObjectNode answered = JSON.createObjectNode().put("id", "signature.visibility").put("uri", URI);
        answered.putObject("inputs").put("visibility", "public");

        assertTrue(assertThrows(IllegalArgumentException.class, () -> over(fake, u -> false).stage(missing)).getMessage().contains("Make the method which visibility?"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> over(fake, u -> false).stage(unknown)).getMessage().contains("no.such"));
        assertTrue(assertThrows(IllegalStateException.class, () -> over(fake, u -> false).stage(answered)).getMessage().contains("not here"),
            "the engine's own reason is carried");
        assertTrue(assertThrows(IllegalStateException.class, () -> over(fake, u -> true).stage(answered)).getMessage().contains("unsaved"));
        assertEquals(List.of("change_method_signature"), fake.tools, "an answer-less, unknown or unsaved request never reaches a tool");
    }
}
