package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.jawata.mcp.execution.HireRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 28h Stage 28 (i) M1: a hire the brain starts is first recorded under the brain's tool-session key and only later bound to the chat session
 * id, so main's own hire is found under either. Each test registers its hires in the shared registry and closes every one, leaving it as found.
 */
class AgentFaceOwnHireTest {

    private static final PrincipalSession SESSION = new PrincipalSession("chat-session-1", "/w", false);

    private static HireRegistry.Registration hireUnder(String leadId) {
        return HireRegistry.shared().begin("developer", "a brief of " + leadId, leadId);
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: a hire running under the brain's key makes ownHireRuns true although no hire runs under the session id")
    void aHireUnderTheBrainsKeyCounts() {
        try (HireRegistry.Registration hire = hireUnder("brain-key-1")) {
            assertTrue(AgentFace.ownHireRuns(Optional.of("brain-key-1"), SESSION));
        }
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: a hire running under the session id makes ownHireRuns true")
    void aHireUnderTheSessionIdCounts() {
        try (HireRegistry.Registration hire = hireUnder(SESSION.id())) {
            assertTrue(AgentFace.ownHireRuns(Optional.of("brain-key-1"), SESSION));
            assertTrue(AgentFace.ownHireRuns(Optional.empty(), SESSION), "a brain with no key is asked about as the session alone");
        }
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: with no hire under the brain's key or the session id ownHireRuns is false")
    void noHireMeansNoOwnHire() {
        assertFalse(AgentFace.ownHireRuns(Optional.of("brain-key-1"), SESSION));
        assertFalse(AgentFace.ownHireRuns(Optional.empty(), SESSION));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M1: a hire running under another key and another session does not make ownHireRuns true")
    void aHireOfSomeoneElseDoesNotCount() {
        try (HireRegistry.Registration hire = hireUnder("another-brain-key")) {
            assertFalse(AgentFace.ownHireRuns(Optional.of("brain-key-1"), SESSION));
        }
    }
}
