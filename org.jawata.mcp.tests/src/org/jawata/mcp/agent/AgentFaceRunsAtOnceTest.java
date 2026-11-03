package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S9: what must act while a prompt is running. A hire runs inside the principal's prompt, so the panel's stop queued behind that
 * prompt would act only when the hire it was meant to end had ended; the tests of the stop call the server directly and cannot see this, so
 * the route's own rule is held here.
 */
class AgentFaceRunsAtOnceTest {

    @Test
    @DisplayName("the person's switch and cancel, the panel's stop, pick, transcript and brains list, and the host's answers (which have no method) act at once")
    void whatTheDoorMustNotQueue() {
        for (String method : new String[] {"session/set_config_option", "session/cancel", "_jawata/hire/stop", "_jawata/hire/brain",
            "_jawata/hire/transcript", "_jawata/brains"}) {
            assertTrue(AgentFace.runsAtOnce(method), method + " was queued behind the running prompt");
        }
        assertTrue(AgentFace.runsAtOnce(null), "the host's answer to a question was queued behind the running prompt");
    }

    @Test
    @DisplayName("the control: a prompt and the other requests are worked in order on the connection's one thread")
    void whatIsWorkedInOrder() {
        for (String method : new String[] {"session/prompt", "session/new", "session/list", "session/load", "initialize"}) {
            assertFalse(AgentFace.runsAtOnce(method), method + " was run at once and may overtake the prompt before it");
        }
    }
}
