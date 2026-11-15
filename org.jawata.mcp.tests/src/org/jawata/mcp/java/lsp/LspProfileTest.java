package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jawata.core.IJdtService;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28i, Stage 25, step A6 (ARCHITECTURE-28i-s25.md): what the editor's profile page asks of the engine, answered over the engine's {@code profile}
 * tool as a script of canned answers stands in for it, and the resolution of a hot method to its source over a real project.
 */
class LspProfileTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** Answers the profile tool's sample and hotspots actions from canned answers and records every call. */
    private static final class Fake implements ToolCalls {
        final List<ObjectNode> calls = new CopyOnWriteArrayList<>();
        ToolResponse sampled = ToolResponse.success(Map.of("artifactId", "jfr-sample-1", "sessionId", "s1"), ResponseMeta.builder().build());
        ToolResponse ranked = ToolResponse.success(Map.of("totalSamples", 40, "totalMethods", 2, "rows", List.of(
            Map.of("symbol", "com.example.debug.DebugTarget#computeSignal", "samples", 30, "sharePercent", 75.0),
            Map.of("symbol", "java.lang.Thread#sleep", "samples", 10, "sharePercent", 25.0))), ResponseMeta.builder().build());

        @Override
        public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
            calls.add(arguments.deepCopy());
            return "sample".equals(arguments.path("action").asText()) ? sampled : ranked;
        }

        @Override
        public List<Map<String, Object>> getToolDefinitions() {
            return List.of();
        }
    }

    private static LspProfile over(Fake fake, IJdtService model) {
        return new LspProfile(fake, CallerIdentity.editor("lsp-test"), () -> model);
    }

    private static ObjectNode ask(String session) {
        return JSON.createObjectNode().put("sessionId", session);
    }

    @Test
    @DisplayName("a profile records the session, ranks the recording and answers the rows as the engine ranked them, with the totals")
    void aProfileRecordsRanksAndAnswersTheRows() throws Exception {
        Fake fake = new Fake();
        ObjectNode params = ask("s1").put("seconds", 3).put("dimension", "wall").put("limit", 5);

        JsonNode result = JSON.valueToTree(over(fake, null).profile(params));

        assertEquals("jfr-sample-1", result.path("artifactId").asText());
        assertEquals(40, result.path("totalSamples").asInt());
        assertEquals(2, result.path("rows").size());
        assertEquals("com.example.debug.DebugTarget#computeSignal", result.path("rows").path(0).path("symbol").asText());
        assertEquals(30, result.path("rows").path(0).path("samples").asInt());
        assertFalse(result.path("rows").path(0).has("location"), "with no model there is no place to open");
        assertEquals("s1", fake.calls.get(0).path("sessionId").asText());
        assertEquals(3, fake.calls.get(0).path("durationSeconds").asInt(), "the person's seconds reach the recording");
        assertEquals("jfr-sample-1", fake.calls.get(1).path("artifactId").asText());
        assertEquals("wall", fake.calls.get(1).path("dimension").asText());
        assertEquals(5, fake.calls.get(1).path("limit").asInt());
    }

    @Test
    @DisplayName("a profile is refused with a reason when no session is named, the program cannot be recorded, or the recording names nothing")
    void aProfileIsRefusedWithAReason() {
        Fake unrecorded = new Fake();
        unrecorded.sampled = ToolResponse.error("SESSION_NOT_FOUND", "no such session", "");
        Fake empty = new Fake();
        empty.sampled = ToolResponse.success(Map.of("enabled", false, "why", "Flight Recorder is off"), ResponseMeta.builder().build());

        assertTrue(assertThrows(IllegalArgumentException.class, () -> over(new Fake(), null).profile(JSON.createObjectNode())).getMessage().contains("sessionId"));
        assertTrue(assertThrows(IllegalStateException.class, () -> over(unrecorded, null).profile(ask("s1"))).getMessage().contains("no such session"));
        assertTrue(assertThrows(IllegalStateException.class, () -> over(empty, null).profile(ask("s1"))).getMessage().contains("Flight Recorder is off"),
            "a capability that is absent says why instead of answering an empty profile");
    }

    @Test
    @DisplayName("a hot method that is in a loaded project's source comes back with its file and the range of its name; one in the JDK has none")
    void aHotMethodIsResolvedToItsSource() throws Exception {
        IJdtService model = helper.loadProjectCopy("debug-target");

        JsonNode rows = JSON.valueToTree(over(new Fake(), model).profile(ask("s1"))).path("rows");

        JsonNode where = rows.path(0).path("location");
        assertTrue(where.path("uri").asText().endsWith("/com/example/debug/DebugTarget.java"), where.toString());
        assertEquals(63, where.path("range").path("start").path("line").asInt(), "computeSignal is declared on line 64, which is 63 zero-based");
        assertFalse(rows.path(1).has("location"), "a JDK method has no source in the workspace");
    }
}
