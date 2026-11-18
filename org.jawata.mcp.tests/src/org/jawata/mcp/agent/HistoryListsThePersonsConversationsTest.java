package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 28h, D57 (a): his words, <i>"I do not want the history of hiring the workers."</i> The transcript lane holds every session any client
 * wrote to it (the hook's captures of Claude Code sessions, among them an observer's runs and a hired brain's own conversation). The chat's
 * History lists only the conversations the chat itself opened, which its recorder writes under its own client name when they open; nothing is
 * read from a session's first words to decide it.
 */
class HistoryListsThePersonsConversationsTest {

    private static Map<String, Object> row(String id, String client, String title) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", id);
        row.put("client", client);
        row.put("title", title);
        row.put("updatedAt", "2026-11-17T10:00:00Z");
        return row;
    }

    @Test
    @DisplayName("28h D57 (a), widened by his ruling 2026-11-18: History lists the person's conversations from every client, titled by his first words, and no brain's own session")
    void everyClientsConversationsAreListedAndNoBrainsOwn() {
        List<Map<String, Object>> lane = List.of(
            row("chat-1", AgentRecorder.CLIENT, "Fix the dogfood bugs"),
            row("observer-run", "claude-code", "You are the observer seat"),
            row("hired-pm", "claude-code", "You are the PM seat"),
            row("marked", "claude-code-machine", "a brief written by the engine"),
            row("chat-2", AgentRecorder.CLIENT, "Release it"),
            row("cursor-own", "cursor", "something he typed in Cursor"));
        ToolCalls store = new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                return ToolResponse.success(Map.of("sessions", lane));
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };

        List<AgentRecorder.SessionInfo> listed = new AgentRecorder(store, AgentRecorder.Depth.ALL).sessions(10, null).sessions();

        assertEquals(List.of("chat-1", "chat-2", "cursor-own"), listed.stream().map(AgentRecorder.SessionInfo::sessionId).toList());
        assertEquals("Fix the dogfood bugs", listed.get(0).title(), "the title is not his first words");
    }

    @Test
    @DisplayName("28h D57 (a): the list is still as long as asked when machine-made sessions come first in the lane")
    void machineMadeSessionsDoNotCrowdThePersonsOut() {
        List<Map<String, Object>> lane = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lane.add(row("hook-" + i, "claude-code-machine", "a hired brain"));
        }
        for (int i = 0; i < 5; i++) {
            lane.add(row("chat-" + i, AgentRecorder.CLIENT, "his words " + i));
        }
        int[] asked = {0};
        ToolCalls store = new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                asked[0] = arguments.path("sessions").asInt();
                return ToolResponse.success(Map.of("sessions", lane.subList(0, Math.min(lane.size(), asked[0]))));
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };

        List<AgentRecorder.SessionInfo> listed = new AgentRecorder(store, AgentRecorder.Depth.ALL).sessions(3, null).sessions();

        assertEquals(3, listed.size(), "asked " + asked[0] + " rows of the lane: " + listed);
    }
}
