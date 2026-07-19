package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolRegistry.
 * Tests tool registration, lookup, and execution.
 */
class ToolRegistryTest {

    private ToolRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        objectMapper = new ObjectMapper();
    }

    // ========== Sprint 26: session threading + event tap ==========

    @Test
    @DisplayName("callTool threads the session id into the event tap; errors are tapped too")
    void callTool_threadsSessionIntoTap() throws Exception {
        org.jawata.mcp.learn.SessionLedger ledger = new org.jawata.mcp.learn.SessionLedger();
        registry.setEventTap(new org.jawata.mcp.learn.EventTap(ledger, null));
        registry.register(new MockTool("ok_tool", "answers"));
        registry.callTool("ok_tool", objectMapper.readTree("{}"), "session-A");
        assertEquals(1, ledger.calls("session-A").size(), "the call is ledgered under its session");
        assertTrue(ledger.calls("session-A").get(0).ok());
        // The 2-arg overload keys the "local" session (in-process callers).
        registry.callTool("ok_tool", objectMapper.readTree("{}"));
        assertEquals(1, ledger.calls("local").size());
        // A tap failure never fails the tool call.
        registry.setEventTap(new org.jawata.mcp.learn.EventTap(ledger, null) {
            @Override
            public void onCall(String s, String n, JsonNode a,
                    org.jawata.mcp.models.ToolResponse r) {
                throw new IllegalStateException("tap boom");
            }
        });
        assertTrue(registry.callTool("ok_tool", objectMapper.readTree("{}"), "s").isSuccess(),
            "a tap failure is the tap's problem, never the caller's");
    }

    @Test
    @DisplayName("Sprint 26 D1: watch findings COMPOSE with the tool's steering, never replace it")
    void watchFindings_composeWithSteering() throws Exception {
        registry.setWatchEngine(new org.jawata.mcp.learn.WatchEngine(
            (kind, path) -> "bugs".equals(kind)
                ? org.jawata.mcp.models.ToolResponse.success(java.util.Map.of("findings",
                    java.util.List.of(java.util.Map.of(
                        "kind", "bugs", "filePath", path, "line", 4, "message", "seeded smell"))))
                : org.jawata.mcp.models.ToolResponse.success(
                    java.util.Map.of("findings", java.util.List.of())),
            null));
        // A mutating mock whose response carries filesModified — the delta source.
        registry.register(new MockTool("rename_symbol", "mutates") {
            @Override
            public org.jawata.mcp.models.ToolResponse execute(JsonNode arguments) {
                return org.jawata.mcp.models.ToolResponse.success(
                    java.util.Map.of("filesModified", java.util.List.of("Seeded.java")));
            }
        });
        var response = registry.callTool("rename_symbol", objectMapper.readTree("{}"), "s1");
        String steering = response.getMeta().getSteering();
        assertNotNull(steering);
        assertTrue(steering.contains("Grounded next step"), "the tool's own steering survives");
        assertTrue(steering.contains("ARCHITECT WATCH"), "the watch block is appended");
        assertTrue(steering.contains("design fix or bandage?"), "the architect's question rides");
        assertTrue(steering.contains("seeded smell"));
    }

    @Test
    @DisplayName("v3.2.1: a degraded store's notice rides EVERY answer, and clears on recovery")
    void storeNotice_ridesEveryAnswerWhileDegraded() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> notice =
            new java.util.concurrent.atomic.AtomicReference<>("STORE DEGRADED: fallback");
        registry.setStoreNotice(notice::get);
        registry.register(new MockTool("ok_tool", "answers"));
        var r1 = registry.callTool("ok_tool", objectMapper.readTree("{}"), "s");
        assertTrue(r1.getMeta().getSteering().contains("STORE DEGRADED"),
            "while degraded, every answer says so");
        notice.set(null); // recovery
        var r2 = registry.callTool("ok_tool", objectMapper.readTree("{}"), "s");
        assertTrue(r2.getMeta() == null || r2.getMeta().getSteering() == null
                || !r2.getMeta().getSteering().contains("STORE DEGRADED"),
            "after recovery the notice is gone");
    }

    // ========== Registration Tests ==========

    @Test
    @DisplayName("register should add tool to registry")
    void register_addsTool() {
        Tool tool = new MockTool("test_tool", "Test tool description");

        registry.register(tool);

        assertTrue(registry.hasTool("test_tool"));
        assertEquals(1, registry.getToolCount());
    }

    @Test
    @DisplayName("register should overwrite existing tool with same name")
    void register_overwritesExisting() {
        Tool tool1 = new MockTool("same_name", "First");
        Tool tool2 = new MockTool("same_name", "Second");

        registry.register(tool1);
        registry.register(tool2);

        assertEquals(1, registry.getToolCount());
        Optional<Tool> retrieved = registry.getTool("same_name");
        assertTrue(retrieved.isPresent());
        assertEquals("Second", retrieved.get().getDescription());
    }

    @Test
    @DisplayName("registerAll should add multiple tools")
    void registerAll_addsMultipleTools() {
        Tool tool1 = new MockTool("tool1", "First");
        Tool tool2 = new MockTool("tool2", "Second");
        Tool tool3 = new MockTool("tool3", "Third");

        registry.registerAll(tool1, tool2, tool3);

        assertEquals(3, registry.getToolCount());
        assertTrue(registry.hasTool("tool1"));
        assertTrue(registry.hasTool("tool2"));
        assertTrue(registry.hasTool("tool3"));
    }

    // ========== Lookup Tests ==========

    @Test
    @DisplayName("getTool should return registered tool")
    void getTool_returnsRegisteredTool() {
        Tool tool = new MockTool("my_tool", "My tool");
        registry.register(tool);

        Optional<Tool> result = registry.getTool("my_tool");

        assertTrue(result.isPresent());
        assertEquals("my_tool", result.get().getName());
    }

    @Test
    @DisplayName("getTool should return empty for missing tool")
    void getTool_returnsEmptyForMissing() {
        Optional<Tool> result = registry.getTool("nonexistent");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("hasTool should return true for registered tool")
    void hasTool_returnsTrueForRegistered() {
        registry.register(new MockTool("exists", "Exists"));

        assertTrue(registry.hasTool("exists"));
    }

    @Test
    @DisplayName("hasTool should return false for missing tool")
    void hasTool_returnsFalseForMissing() {
        assertFalse(registry.hasTool("missing"));
    }

    // ========== Enumeration Tests ==========

    @Test
    @DisplayName("getToolNames should return all registered names")
    void getToolNames_returnsAllNames() {
        registry.register(new MockTool("alpha", "Alpha"));
        registry.register(new MockTool("beta", "Beta"));
        registry.register(new MockTool("gamma", "Gamma"));

        Set<String> names = registry.getToolNames();

        assertEquals(3, names.size());
        assertTrue(names.contains("alpha"));
        assertTrue(names.contains("beta"));
        assertTrue(names.contains("gamma"));
    }

    @Test
    @DisplayName("getToolNames should return empty set for empty registry")
    void getToolNames_returnsEmptyForEmpty() {
        Set<String> names = registry.getToolNames();

        assertTrue(names.isEmpty());
    }

    @Test
    @DisplayName("getToolCount should return correct count")
    void getToolCount_returnsCorrectCount() {
        assertEquals(0, registry.getToolCount());

        registry.register(new MockTool("one", "One"));
        assertEquals(1, registry.getToolCount());

        registry.register(new MockTool("two", "Two"));
        assertEquals(2, registry.getToolCount());
    }

    // ========== Tool Definitions Tests ==========

    @Test
    @DisplayName("getToolDefinitions should return MCP format")
    void getToolDefinitions_returnsMcpFormat() {
        registry.register(new MockTool("test_tool", "Test description"));

        List<Map<String, Object>> definitions = registry.getToolDefinitions();

        assertEquals(1, definitions.size());
        Map<String, Object> def = definitions.get(0);
        assertEquals("test_tool", def.get("name"));
        assertEquals("Test description", def.get("description"));
        assertNotNull(def.get("inputSchema"));
    }

    @Test
    @DisplayName("getToolDefinitions should include all tools")
    void getToolDefinitions_includesAllTools() {
        registry.register(new MockTool("tool1", "First"));
        registry.register(new MockTool("tool2", "Second"));

        List<Map<String, Object>> definitions = registry.getToolDefinitions();

        assertEquals(2, definitions.size());
    }

    // ========== Tool Execution Tests ==========

    @Test
    @DisplayName("callTool should execute registered tool")
    void callTool_executesTool() throws Exception {
        Tool tool = new MockTool("executor", "Executor");
        registry.register(tool);
        JsonNode args = objectMapper.createObjectNode();

        ToolResponse response = registry.callTool("executor", args);

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
    }

    @Test
    @DisplayName("callTool should throw ToolNotFoundException for missing tool")
    void callTool_throwsForMissing() {
        JsonNode args = objectMapper.createObjectNode();

        assertThrows(ToolRegistry.ToolNotFoundException.class,
            () -> registry.callTool("missing_tool", args));
    }

    @Test
    @DisplayName("callTool should handle tool exceptions gracefully")
    void callTool_handlesExceptions() throws Exception {
        Tool failingTool = new FailingTool();
        registry.register(failingTool);
        JsonNode args = objectMapper.createObjectNode();

        ToolResponse response = registry.callTool("failing_tool", args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("v2.7.1: callTool turns a JVM Error (StackOverflowError) into a structured INTERNAL_ERROR instead of killing the worker")
    void callTool_handlesErrors_notJustExceptions() throws Exception {
        // Dogfood find 2026-07-10: find_duplicate_code's tokenizer threw
        // StackOverflowError on a giant string literal; the Error escaped every
        // catch(Exception) and the client saw a dropped socket. The registry is
        // the per-request boundary — it must answer structurally for ANY Throwable.
        registry.register(new ErrorThrowingTool());
        JsonNode args = objectMapper.createObjectNode();

        ToolResponse response = registry.callTool("error_tool", args);

        assertFalse(response.isSuccess(),
            "an Error inside a tool must surface as a structured failure");
        assertNotNull(response.getError(),
            "the client must receive a diagnosable error, never a dropped connection");
    }

    // ========== Steering Injection Tests (Sprint 22 POST layer) ==========

    @Test
    @DisplayName("callTool steers a read-only navigate tool toward a JAWATA refactor tool")
    void callTool_steersReadOnlyToChange() throws Exception {
        registry.register(new MockTool("search_symbols", "read-only navigate"));

        ToolResponse r = registry.callTool("search_symbols", objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        assertNotNull(r.getMeta());
        String steering = r.getMeta().getSteering();
        assertNotNull(steering, "read-only success carries steering");
        assertTrue(steering.contains("rename_symbol") && steering.contains("refactoring(action=plan)"),
            "steering names JAWATA refactor tools");
        assertTrue(steering.contains("not a hand-edit"), "steering discourages hand-editing");
    }

    @Test
    @DisplayName("callTool steers a mutating tool toward verification")
    void callTool_steersMutatorToVerify() throws Exception {
        registry.register(new MockTool("rename_symbol", "mutator"));

        ToolResponse r = registry.callTool("rename_symbol", objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        assertNotNull(r.getMeta());
        assertTrue(r.getMeta().getSteering().contains("compile_workspace"),
            "mutator steering points at verification");
    }

    @Test
    @DisplayName("callTool adds no steering for session/build/verify tools")
    void callTool_noSteeringForNoSteerTools() throws Exception {
        registry.register(new MockTool("health_check", "session"));

        ToolResponse r = registry.callTool("health_check", objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        assertTrue(r.getMeta() == null || r.getMeta().getSteering() == null,
            "no-steer tools carry no steering");
    }

    @Test
    @DisplayName("a failed tool call carries no steering")
    void callTool_noSteeringOnFailure() throws Exception {
        registry.register(new FailingTool());

        ToolResponse r = registry.callTool("failing_tool", objectMapper.createObjectNode());

        assertFalse(r.isSuccess());
        assertNull(r.getMeta(), "error responses have no steering meta");
    }

    // ========== Mock Tool Implementation ==========

    /**
     * Simple mock tool for testing.
     */
    private static class MockTool implements Tool {
        private final String name;
        private final String description;

        MockTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of()
            );
        }

        @Override
        public ToolResponse execute(JsonNode arguments) {
            return ToolResponse.success(Map.of("executed", true, "tool", name));
        }
    }

    /**
     * Tool that throws a JVM Error (not an Exception) — models the v2.7.1
     * StackOverflowError dogfood find. Must NOT escape callTool.
     */
    private static class ErrorThrowingTool implements Tool {
        @Override
        public String getName() {
            return "error_tool";
        }

        @Override
        public String getDescription() {
            return "A tool that dies with a JVM Error";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of();
        }

        @Override
        public ToolResponse execute(JsonNode arguments) {
            throw new StackOverflowError("simulated pathological-scan stack overflow");
        }
    }

    /**
     * Tool that throws an exception for testing error handling.
     */
    private static class FailingTool implements Tool {
        @Override
        public String getName() {
            return "failing_tool";
        }

        @Override
        public String getDescription() {
            return "A tool that always fails";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of();
        }

        @Override
        public ToolResponse execute(JsonNode arguments) {
            throw new RuntimeException("Intentional failure");
        }
    }
}
