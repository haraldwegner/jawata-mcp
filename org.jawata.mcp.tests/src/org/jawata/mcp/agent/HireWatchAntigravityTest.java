package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, z64 and z67: a hire made by an Antigravity principal draws a card. Measured against agy 1.5.15 on 2026-11-24: its agent titles a call of
 * a tool of the engine's stdio server {@code Run mcp_jawata-engine_hire} and puts the arguments themselves in the raw input; for a server of its own
 * configuration it titles the call {@code MCP Tool hire} and puts {@code ServerName}, {@code ToolName} and {@code Arguments} in the raw input. The card
 * watch knew only Claude's and Cursor's forms, so with Antigravity as the principal no hire had a card, refused or not.
 */
class HireWatchAntigravityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode update(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void aHireTitledTheWayTheEnginesStdioServerIsTitledDrawsACard() throws Exception {
        Optional<HireEntry> card = new HireWatch(() -> 0L).observe(update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"c1\",\"title\":\"Run mcp_jawata-engine_hire\","
            + "\"status\":\"in_progress\",\"rawInput\":{\"hirer\":\"main\",\"seat\":\"pm\",\"brief\":\"plan it\",\"seatFile\":\"/x/pm.md\",\"steps\":[],\"workingDirectory\":\"/tmp\"}}"));

        assertTrue(card.isPresent(), "a hire by an Antigravity principal has a card");
        assertEquals("pm", card.get().seat());
        assertEquals(HireEntry.RUNNING, card.get().state());
    }

    @Test
    void aHireOfAServerInAgysOwnConfigurationDrawsACardFromItsRawInput() throws Exception {
        Optional<HireEntry> card = new HireWatch(() -> 0L).observe(update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"c2\",\"title\":\"MCP Tool hire\","
            + "\"status\":\"in_progress\",\"rawInput\":{\"ServerName\":\"jawata-javata-dev\",\"ToolName\":\"hire\","
            + "\"Arguments\":{\"hirer\":\"main\",\"seat\":\"pm\",\"brief\":\"plan it\",\"seatFile\":\"/x/pm.md\",\"steps\":[],\"workingDirectory\":\"/tmp\"}}}"));

        assertTrue(card.isPresent(), "the call is the resident's hire, read from its raw input");
        assertEquals("pm", card.get().seat(), "the arguments are the ones inside Arguments");
    }

    @Test
    void aHireOfAnotherServerIsNotTheEnginesAndDrawsNoCard() throws Exception {
        HireWatch watch = new HireWatch(() -> 0L);

        assertTrue(watch.observe(update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"c3\",\"title\":\"MCP Tool hire\",\"status\":\"in_progress\","
            + "\"rawInput\":{\"ServerName\":\"some-other-server\",\"ToolName\":\"hire\",\"Arguments\":{\"seat\":\"pm\"}}}")).isEmpty());
        assertTrue(watch.observe(update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"c4\",\"title\":\"Run mcp_other_hire\",\"status\":\"in_progress\","
            + "\"rawInput\":{\"seat\":\"pm\"}}")).isEmpty());
    }
}
