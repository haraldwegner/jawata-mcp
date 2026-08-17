package org.jawata.mcp.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.learn.EventTap;
import org.jawata.mcp.learn.SessionLedger;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28b D1: the recorder rides the PRODUCTION tap — a ToolRegistry call
 * with the EventTap installed produces a pile event without any test-side
 * wiring of the recording itself (the wired-not-just-built discipline: the
 * same setFieldRecorder call the application makes is the one under test).
 */
class FieldRecorderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Tool failingTool() {
        return new Tool() {
            @Override public String getName() {
                return "probe_tool";
            }
            @Override public String getDescription() {
                return "fails structurally";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.error("PROBE_FAILED",
                    "message with /a/path/that/must/not/leak.java", null);
            }
        };
    }

    @Test
    void a_registry_call_becomes_one_sanitized_pile_event(@TempDir Path dir) throws Exception {
        FieldPile pile = new FieldPile(dir);
        ClientDirectory clients = new ClientDirectory();
        clients.record("session-9", "claude-code");
        FieldRecorder recorder = new FieldRecorder(pile, clients, "3.11.0");

        ToolRegistry registry = new ToolRegistry();
        EventTap tap = new EventTap(new SessionLedger(), null);
        tap.setFieldRecorder(recorder);
        registry.setEventTap(tap);
        registry.register(failingTool());

        registry.callTool("probe_tool",
            MAPPER.createObjectNode().put("kind", "probe"), "session-9");

        List<FieldEvent> events = pile.fold();
        assertEquals(1, events.size());
        FieldEvent event = events.get(0);
        assertEquals("probe_tool/probe/PROBE_FAILED", event.shapeKey());
        assertFalse(event.ok());
        assertEquals("claude_code", event.client().value());
        assertEquals("3_11_0", event.version().token());
        assertTrue(event.latencyBucket() >= 0 && event.latencyBucket() <= 6);
    }

    @Test
    void the_registry_plumbs_the_real_call_duration(@TempDir Path dir) throws Exception {
        // A tool that takes >100ms must land in bucket >= 1: reverting the
        // duration plumbing (passing 0) turns this red (C1 audit F5a).
        FieldPile pile = new FieldPile(dir);
        FieldRecorder recorder = new FieldRecorder(pile, new ClientDirectory(), "3.11.0");
        ToolRegistry registry = new ToolRegistry();
        EventTap tap = new EventTap(new SessionLedger(), null);
        tap.setFieldRecorder(recorder);
        registry.setEventTap(tap);
        registry.register(new Tool() {
            @Override public String getName() {
                return "slow_tool";
            }
            @Override public String getDescription() {
                return "sleeps past the first bucket boundary";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResponse.success(Map.of());
            }
        });
        registry.callTool("slow_tool", MAPPER.createObjectNode(), "s");
        assertTrue(pile.fold().get(0).latencyBucket() >= 1,
            "a 120ms call cannot be in the <10ms bucket unless the plumbing passes 0");
    }

    @Test
    void initialize_attributes_the_session_through_the_protocol_handler(@TempDir Path dir)
            throws Exception {
        // The REAL protocol path (C1 audit F5b): an initialize message with a
        // clientInfo must land in the directory under its session id.
        ClientDirectory clients = new ClientDirectory();
        org.jawata.mcp.protocol.McpProtocolHandler handler =
            new org.jawata.mcp.protocol.McpProtocolHandler(new ToolRegistry());
        handler.setClientDirectory(clients);
        handler.processMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"2.1\"},"
            + "\"protocolVersion\":\"2025-06-18\"}}", "session-init-7");
        assertEquals("claude_code", clients.clientOf("session-init-7").value());
        assertEquals(Token.UNKNOWN, clients.clientOf("other-session"));
    }

    @Test
    void an_unattributed_session_records_as_unknown_client(@TempDir Path dir) throws Exception {
        FieldPile pile = new FieldPile(dir);
        FieldRecorder recorder = new FieldRecorder(pile, new ClientDirectory(), null);
        recorder.onCall("never-initialized", "search_symbols",
            MAPPER.createObjectNode(), ToolResponse.success(Map.of()), 3);
        FieldEvent event = pile.fold().get(0);
        assertEquals(Token.UNKNOWN, event.client());
        assertEquals(Token.UNKNOWN, event.version());
        assertTrue(event.ok());
    }

    @Test
    void a_recorder_failure_never_fails_the_tool_call(@TempDir Path dir) throws Exception {
        FieldRecorder exploding = new FieldRecorder(new FieldPile(dir), null, null);
        // null ClientDirectory makes onCall throw inside the recorder…
        ToolRegistry registry = new ToolRegistry();
        EventTap tap = new EventTap(new SessionLedger(), null);
        tap.setFieldRecorder(exploding);
        registry.setEventTap(tap);
        registry.register(failingTool());
        // …and the call still answers structurally.
        assertFalse(registry.callTool("probe_tool", MAPPER.createObjectNode(), "s").isSuccess());
    }
}
