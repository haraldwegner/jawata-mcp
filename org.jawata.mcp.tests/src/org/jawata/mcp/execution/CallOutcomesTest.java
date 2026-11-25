package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage 27 (o): Agent Control shows each jawata call of a run that failed or was refused. The stream a hire's brain sends records a call and
 * not its result, so the engine, which answers every call, keeps the failed and refused ones by the caller's id, which for a hire is its plan id.
 */
class CallOutcomesTest {

    private static ToolResponse refused(String code) {
        return ToolResponse.error(code, "message", "hint");
    }

    @Test
    @DisplayName("a failed or refused call is kept under its caller with the tool and the error code, and handed over once")
    void aFailedCallIsKeptAndTakenOnce() {
        CallOutcomes outcomes = new CallOutcomes();
        outcomes.record("plan-1", "rename_symbol", refused("REFACTORING_FAILED"));
        outcomes.record("plan-1", "files", refused("ACCESS_DENIED"));

        List<CallOutcomes.Failed> taken = outcomes.take("plan-1");

        assertAll(
            () -> assertEquals(List.of(new CallOutcomes.Failed("rename_symbol", "REFACTORING_FAILED", "message"), new CallOutcomes.Failed("files", "ACCESS_DENIED", "message")), taken),
            () -> assertTrue(outcomes.take("plan-1").isEmpty(), "CONTROL: what was handed over was handed over again"));
    }

    @Test
    @DisplayName("what the engine answered is kept on one line and cut, so the card can say which call it was without carrying a whole listing")
    void whatTheEngineAnsweredIsKeptShort() {
        CallOutcomes outcomes = new CallOutcomes();
        outcomes.record("plan-1", "files", ToolResponse.error("ACCESS_DENIED", "Access denied:\n  /home/x/rules.md", "hint"));
        outcomes.record("plan-1", "lookup", ToolResponse.error("INTERNAL_ERROR", "y".repeat(1000), "hint"));

        List<CallOutcomes.Failed> taken = outcomes.take("plan-1");

        assertAll(
            () -> assertEquals("Access denied: /home/x/rules.md", taken.get(0).said()),
            () -> assertEquals("y".repeat(200) + "…", taken.get(1).said()));
    }

    @Test
    @DisplayName("a call that succeeded is not kept, and another caller's failures are not handed to this one")
    void aSuccessIsNotKeptAndCallersAreSeparate() {
        CallOutcomes outcomes = new CallOutcomes();
        outcomes.record("plan-1", "search_symbols", ToolResponse.success("ok"));
        outcomes.record("plan-2", "extract", refused("REFACTORING_FAILED"));

        assertAll(
            () -> assertTrue(outcomes.take("plan-1").isEmpty(), "a success was counted as a failure"),
            () -> assertEquals(1, outcomes.take("plan-2").size(), "CONTROL: the other caller's failure was lost"));
    }

    @Test
    @DisplayName("a caller that fails without end keeps only the latest few, so the store cannot grow with it")
    void aCallerThatKeepsFailingIsBounded() {
        CallOutcomes outcomes = new CallOutcomes();
        for (int i = 0; i < CallOutcomes.PER_CALLER + 50; i++) {
            outcomes.record("plan-1", "tool-" + i, refused("X"));
        }

        List<CallOutcomes.Failed> taken = outcomes.take("plan-1");

        assertAll(
            () -> assertEquals(CallOutcomes.PER_CALLER, taken.size()),
            () -> assertEquals("tool-" + (CallOutcomes.PER_CALLER + 49), taken.get(taken.size() - 1).tool(), "the latest was dropped, not the oldest"));
    }
}
