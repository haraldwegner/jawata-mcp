package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.jawata.mcp.execution.HireRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S15 (D6, D40): the card of a hire whose brain is compacting its own context says {@code compacting} while it does, goes back to
 * {@code running} when the brain says it has finished, and is the hire's own final state once the hire has answered.
 */
class HireCompactingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicLong now = new AtomicLong(1_000);
    private final HireWatch watch = new HireWatch(now::get);

    private static ObjectNode call(String kind, String id, String status) {
        ObjectNode update = JSON.createObjectNode().put("sessionUpdate", kind).put("toolCallId", id).put("status", status);
        update.put("title", "mcp__jawata-javata-dev__hire");
        update.putObject("rawInput").put("hirer", "pm").put("seat", "chief-engineer").put("brief", "a long task");
        return update;
    }

    @Test
    @DisplayName("running, then compacting while the brain compacts, then running again, then the hire's own state once it has answered")
    void theCardFollowsTheBrainsCompaction() {
        watch.observe(call("tool_call", "c1", "pending")).orElseThrow();
        try (HireRegistry.Registration ignored = HireRegistry.shared().begin("chief-engineer", "a long task", "")) {
            assertEquals(HireEntry.RUNNING, watch.entry("c1").orElseThrow().state());

            HireRegistry.shared().noteCompaction(true);
            assertEquals(HireEntry.COMPACTING, watch.entry("c1").orElseThrow().state());
            assertEquals(HireEntry.COMPACTING, watch.entries().get(0).state(), "the list the panel polls says so too");

            HireRegistry.shared().noteCompaction(false);
            assertEquals(HireEntry.RUNNING, watch.entry("c1").orElseThrow().state());

            HireRegistry.shared().noteCompaction(true);
            watch.observe(call("tool_call_update", "c1", "failed")).orElseThrow();
            assertEquals(HireEntry.FAILED, watch.entry("c1").orElseThrow().state(), "an ended hire is never shown as compacting");
        }
    }
}
