package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.GateOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage 27 (s), his words 2026-11-18 (the PM hires' search gates passed over a plan that was never changed): a gate step states what it must find, and
 * a search that matched nothing is not a pass. A step that states nothing is held to the weakest reading that still proves something: at least one match.
 * Each case has the control that must not trigger it, so a runner that fails every search, or passes every one, is caught.
 */
class ToolGateRunnerExpectsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A registry that answers every call with the same data. */
    private static ToolCalls answering(Map<String, Object> data) {
        return new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                return ToolResponse.success(data);
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
    }

    private static GateOutcome run(Map<String, Object> answer, String expect) throws Exception {
        JsonNode expected = expect == null ? null : JSON.readTree(expect);
        return new ToolGateRunner(answering(answer)).run(new GatedStep(0, "files", JSON.createObjectNode().put("action", "search"), expected));
    }

    private static Map<String, Object> matches(int count) {
        return Map.of("matches", java.util.Collections.nCopies(count, Map.of("path", "plan.md", "line", 1, "text", "x")), "truncated", false);
    }

    @Test
    @DisplayName("Stage 27 (s): a search that matched nothing is not a pass when the step states nothing, and one that matched is")
    void aSearchWithNoMatchIsNotAPass() throws Exception {
        GateOutcome none = run(matches(0), null);
        GateOutcome some = run(matches(1), null);

        assertAll(
            () -> assertTrue(none.succeeded(), "the search ran and answered, so this is a failed check and not an unanswered gate"),
            () -> assertTrue(none.errorCount() > 0, "THE CLAIM: a search over a document that found nothing passed"),
            () -> assertTrue(some.succeeded() && some.errorCount() == 0, "CONTROL: a search that found its text was refused: " + some));
    }

    @Test
    @DisplayName("Stage 27 (s): a step that states the count it must find passes on that count and no other, zero included")
    void aStatedCountIsTheOnlyPass() throws Exception {
        assertAll(
            () -> assertEquals(0, run(matches(2), "{\"matches\":2}").errorCount(), "the stated count was not accepted"),
            () -> assertTrue(run(matches(1), "{\"matches\":2}").errorCount() > 0, "one match passed a step that stated two"),
            () -> assertTrue(run(matches(3), "{\"matches\":2}").errorCount() > 0, "three matches passed a step that stated two"),
            () -> assertEquals(0, run(matches(0), "{\"matches\":0}").errorCount(), "a step that states it must find nothing was refused when it found nothing"),
            () -> assertTrue(run(matches(1), "{\"matches\":0}").errorCount() > 0, "a step that states it must find nothing passed over a match"));
    }

    @Test
    @DisplayName("Stage 27 (s): a step that states the tree must have changed passes on a diff with text and fails on an empty one, and the reverse for a step that states it must not")
    void aStatedChangeIsReadFromTheDiffText() throws Exception {
        Map<String, Object> diff = Map.of("output", "diff --git a/plan.md b/plan.md\n+a line\n", "truncated", false);
        Map<String, Object> nothing = Map.of("output", "", "truncated", false);
        JsonNode changed = JSON.createObjectNode().put("changed", true);
        JsonNode unchanged = JSON.createObjectNode().put("changed", false);
        JsonNode lookup = JSON.createObjectNode().put("kind", "git_diff");

        assertAll(
            () -> assertEquals(0, new ToolGateRunner(answering(diff)).run(new GatedStep(0, "lookup", lookup, changed)).errorCount(), "a changed tree was refused for a step that said it must change"),
            () -> assertTrue(new ToolGateRunner(answering(nothing)).run(new GatedStep(0, "lookup", lookup, changed)).errorCount() > 0,
                "THE CLAIM: an unchanged tree passed a step that said the file must change"),
            () -> assertEquals(0, new ToolGateRunner(answering(nothing)).run(new GatedStep(0, "lookup", lookup, unchanged)).errorCount(), "an unchanged tree was refused for a step that said nothing may change"),
            () -> assertTrue(new ToolGateRunner(answering(diff)).run(new GatedStep(0, "lookup", lookup, unchanged)).errorCount() > 0, "a changed tree passed a step that said nothing may change"),
            () -> assertFalse(new ToolGateRunner(answering(Map.of("errorCount", 0))).run(new GatedStep(0, "lookup", lookup, changed)).succeeded(),
                "an answer with no text to read a change from was taken as an answer to whether the tree changed"),
            () -> assertFalse(new ToolGateRunner(answering(Map.of("output", "## main...origin/main\n", "truncated", false)))
                    .run(new GatedStep(0, "lookup", JSON.createObjectNode().put("kind", "git_status"), changed)).succeeded(),
                "THE CLAIM: a git_status, whose text always has its branch line, was read as a change"),
            () -> assertFalse(new ToolGateRunner(answering(diff)).run(new GatedStep(0, "files", lookup, changed)).succeeded(),
                "a tool that is not the lookup tool was read as telling whether the tree changed"));
    }

    @Test
    @DisplayName("Stage 27 (s): a gate that publishes an error count is judged by it as before, whether or not it states an expectation")
    void anErrorCountGateIsUnchanged() throws Exception {
        GateOutcome clean = new ToolGateRunner(answering(Map.of("errorCount", 0))).run(new GatedStep(0, "compile_workspace", JSON.createObjectNode()));
        GateOutcome broken = new ToolGateRunner(answering(Map.of("errorCount", 2))).run(new GatedStep(0, "compile_workspace", JSON.createObjectNode()));

        assertAll(
            () -> assertTrue(clean.succeeded() && clean.errorCount() == 0),
            () -> assertEquals(2, broken.errorCount()),
            () -> assertFalse(new ToolGateRunner(answering(Map.of("somethingElse", 1))).run(new GatedStep(0, "files", JSON.createObjectNode())).succeeded(),
                "an answer in a shape nothing here reads was still taken as an answer"));
    }
}
