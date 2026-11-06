package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S15 (D6, D40): a hired brain that is compacting its own context shows as compacting while it does. The signal is read off the
 * brain's stream, from updates measured on 2026-11-06 with {@code @agentclientprotocol/claude-agent-acp} 0.101.0 answering {@code /compact}.
 */
class CompactionSignalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode update(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("the three updates the Claude adapter sent for a compaction: it began on the first, ended on the second, and the third says nothing")
    void theMeasuredUpdatesAreReadAsBeganEndedAndNothing() throws Exception {
        JsonNode began = update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"19b2\",\"title\":\"Compact conversation\",\"kind\":\"think\","
            + "\"status\":\"in_progress\",\"_meta\":{\"claudeCode\":{\"toolName\":\"compact\"}}}");
        JsonNode ended = update("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"19b2\",\"status\":\"completed\","
            + "\"_meta\":{\"claudeCode\":{\"toolName\":\"compact\"}}}");
        JsonNode sizes = update("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"19b2\","
            + "\"rawOutput\":{\"trigger\":\"manual\",\"preTokens\":265250,\"postTokens\":12154,\"durationMs\":11438},"
            + "\"_meta\":{\"claudeCode\":{\"toolName\":\"compact\"}}}");

        assertEquals(Optional.of(true), AcpBrainSession.compactionIn(began));
        assertEquals(Optional.of(false), AcpBrainSession.compactionIn(ended));
        assertEquals(Optional.empty(), AcpBrainSession.compactionIn(sizes));
    }

    @Test
    @DisplayName("the controls: an ordinary tool call, another brain's update and a message chunk are none of them a compaction")
    void nothingElseIsACompaction() throws Exception {
        assertEquals(Optional.empty(), AcpBrainSession.compactionIn(update(
            "{\"sessionUpdate\":\"tool_call\",\"status\":\"in_progress\",\"_meta\":{\"claudeCode\":{\"toolName\":\"Bash\"}}}")));
        assertEquals(Optional.empty(), AcpBrainSession.compactionIn(update(
            "{\"sessionUpdate\":\"tool_call\",\"title\":\"Compact conversation\",\"status\":\"in_progress\"}")));
        assertEquals(Optional.empty(), AcpBrainSession.compactionIn(update(
            "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"text\":\"compact\"},\"_meta\":{\"claudeCode\":{\"toolName\":\"compact\"}}}")));
    }

    @Test
    @DisplayName("a running hire is compacting from the moment its brain says so until it says it has finished, and not after the hire is closed")
    void aRunningHireKnowsWhenItsBrainIsCompacting() {
        RunningHires hires = RunningHires.shared();
        try (RunningHires.Registration ignored = hires.begin("compaction-probe", "a task")) {
            assertFalse(hires.compacting("compaction-probe", "a task"));

            hires.noteCompaction(true);
            assertTrue(hires.compacting("compaction-probe", "a task"));
            assertFalse(hires.compacting("another-seat", "a task"), "only the hire whose brain said so");

            hires.noteCompaction(false);
            assertFalse(hires.compacting("compaction-probe", "a task"));

            hires.noteCompaction(true);
        }
        assertFalse(hires.compacting("compaction-probe", "a task"), "a closed hire is not running, so it is not compacting");
    }
}
