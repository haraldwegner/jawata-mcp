package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.ProfileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Sprint 28b — <b>the two profile actions that read the model, with no model.</b>
 *
 * <p>{@code profile} declares {@link ProfileTool#requiresLoadedProject()}
 * {@code false}, and its javadoc promises that the only two actions resolving
 * symbols against the model — {@code call_counts} and {@code latency_seam} —
 * "answer with their own error when the model is empty". They did not: both
 * called {@code service.findType(...)} unguarded, so a null service threw an
 * NPE out of {@code executeWithService}, where the catch-all turned it into
 * INTERNAL_ERROR "this may be a bug".
 *
 * <p>That is the worst shape this codebase has: it blames US for a state the
 * USER owns, and hands the agent nothing to act on — at exactly the moment an
 * agent reaches for the runtime tools, which is when the model is unavailable.
 *
 * <p>The service supplier here is {@code () -> null} — the real shape, not a
 * mock: no workspace loaded at all, which is precisely what the other 21
 * actions are built to keep working through.
 *
 * <p><b>What this test does and does not reach, measured.</b> Run against the
 * unguarded code, both cases failed with JCMD_FAILED ("This action needs a
 * sessionId"), NOT with INTERNAL_ERROR — because {@code sessionOf(arguments)}
 * ran before {@code findType}. So the NPE needed a LIVE SESSION as well as an
 * empty workspace; with no session the session refusal fired first and hid it.
 * What this pins is therefore the reachable half: with no model, both actions
 * answer PROJECT_NOT_LOADED rather than any other code. The unreachable half
 * is closed structurally — the guard is now the FIRST statement of each
 * method, ahead of the session lookup, so {@code findType} cannot be reached
 * with a null service at all. That placement is deliberate: guarding at the
 * {@code findType} call site instead would leave the branch unreachable
 * without launching a JVM, and a branch no test can reach is exactly how the
 * defect shipped.
 */
class ProfileWithoutModelTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** No workspace, no session — the state the two actions must survive. */
    private static ProfileTool profileWithNoModel() {
        return new ProfileTool(() -> null, new RuntimeSessionRegistry());
    }

    private static ObjectNode args(String action) {
        ObjectNode node = OM.createObjectNode();
        node.put("action", action);
        node.put("className", "com.example.Whatever");
        node.put("method", "whatever");
        return node;
    }

    @Test
    @DisplayName("call_counts with no model answers PROJECT_NOT_LOADED, never INTERNAL_ERROR")
    void callCountsWithoutAModelRefusesInItsOwnWords() {
        assertTypedRefusal("call_counts");
    }

    @Test
    @DisplayName("latency_seam with no model answers PROJECT_NOT_LOADED, never INTERNAL_ERROR")
    void latencySeamWithoutAModelRefusesInItsOwnWords() {
        assertTypedRefusal("latency_seam");
    }

    private static void assertTypedRefusal(String action) {
        ProfileTool profile = profileWithNoModel();

        ToolResponse response = assertDoesNotThrow(
            () -> profile.execute(args(action)),
            action + " must not throw: the seam does not catch it, and the "
                + "dispatcher would report our NullPointerException as a bug in us");

        assertFalse(response.isSuccess(), action + " cannot resolve a symbol with no model");
        assertNotEquals("INTERNAL_ERROR", response.getError().getCode(),
            "an empty workspace is the USER's state, not our defect — reporting it as "
                + "INTERNAL_ERROR sends the agent looking for a bug that is not there");
        assertEquals("PROJECT_NOT_LOADED", response.getError().getCode(),
            "and it is the code an agent already knows how to recover from: " + action
                + " answered " + response.getError());
    }
}
