package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.jawata.mcp.execution.HireRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, Stage 27, row (z28): the card of a hire that has shown no sign of life for the first ceiling says {@code stalled} while it has not,
 * goes back to {@code running} the moment the hire acts, and is the hire's own final state once it has answered, so the person looking at Agent
 * Control sees a hire gone quiet before the engine stops it.
 */
class HireStalledCardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicLong now = new AtomicLong(1_000);
    private final HireWatch watch = new HireWatch(now::get, () -> "", 1);

    private static ObjectNode call(String kind, String id, String status) {
        ObjectNode update = JSON.createObjectNode().put("sessionUpdate", kind).put("toolCallId", id).put("status", status);
        update.put("title", "mcp__jawata-javata-dev__hire");
        update.putObject("rawInput").put("hirer", "pm").put("seat", "chief-engineer").put("brief", "a quiet task");
        return update;
    }

    @Test
    @DisplayName("28h z28: running, then stalled once it has shown no sign of life for the ceiling, running again when it acts, and the hire's own state once it has answered")
    void theCardSaysStalledWhileTheHireIsQuiet() throws Exception {
        watch.observe(call("tool_call", "c1", "pending")).orElseThrow();
        try (HireRegistry.Registration ignored = HireRegistry.shared().begin("chief-engineer", "a quiet task", "")) {
            HireRegistry.shared().planOpened("plan-z28-card-quiet");
            HireEntry justStarted = watch.entry("c1").orElseThrow();
            Thread.sleep(1_300);
            HireEntry quiet = watch.entry("c1").orElseThrow();
            HireRegistry.shared().noteToolCall("a call the hire made");
            HireEntry acted = watch.entry("c1").orElseThrow();
            Thread.sleep(1_300);
            ObjectNode ended = call("tool_call_update", "c1", "completed");
            ended.put("rawOutput", "{\"data\":{\"cleanExit\":false,\"timedOut\":true}}");
            watch.observe(ended).orElseThrow();
            HireEntry over = watch.entry("c1").orElseThrow();

            assertAll(
                () -> assertEquals(HireEntry.RUNNING, justStarted.state(), "a hire that has just opened its plan is not stalled"),
                () -> assertEquals(HireEntry.STALLED, quiet.state(), "a hire quiet past the ceiling was not shown as stalled"),
                () -> assertEquals(HireEntry.RUNNING, acted.state(), "a hire that has just made a call is still shown as stalled"),
                () -> assertEquals(HireEntry.FAILED, over.state(), "an ended hire is never shown as stalled"));
        }
    }
}
