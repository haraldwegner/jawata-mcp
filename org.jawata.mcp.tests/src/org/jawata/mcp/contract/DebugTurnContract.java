package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicInteger;

import org.jawata.spi.host.DebugTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every host promises about whose turn it is to debug (Sprint 28k, Stage 9; contract §4.2, A3-2): the person has not started
 * debugging before they do, asking to be told never fails, and the person wins: when they start, the engine is told exactly once and
 * the agent's turn is over. A host that cannot see the person debugging, such as the headless one, is always the agent's turn and has
 * nothing to start; the second clause then only shows that the engine is never told.
 */
public abstract class DebugTurnContract {

    /**
     * @return the debug turn under test, fresh for each test
     * @throws Exception when the implementation cannot be built
     */
    protected abstract DebugTurn turn() throws Exception;

    /**
     * Makes the person start a debug launch of their own, as the host would see it.
     *
     * @return whether this host has a person who can debug at all; false for a host that cannot see them
     * @throws Exception when the person cannot be made to start
     */
    protected abstract boolean personStartsDebugging() throws Exception;

    @Test
    @DisplayName("before the person starts debugging it is the agent's turn, and asking to be told does not change that")
    void itIsTheAgentsTurnUntilThePersonStarts() throws Exception {
        DebugTurn turn = turn();

        assertFalse(turn.personIsDebugging());
        assertDoesNotThrow(() -> turn.onPersonStartsDebugging(() -> { }));
        assertFalse(turn.personIsDebugging());
    }

    @Test
    @DisplayName("when the person starts debugging the engine is told once, and the agent's turn is over")
    void thePersonWins() throws Exception {
        DebugTurn turn = turn();
        AtomicInteger told = new AtomicInteger();
        turn.onPersonStartsDebugging(told::incrementAndGet);

        boolean seen = personStartsDebugging();

        assertEquals(seen ? 1 : 0, told.get(), "told once when the host sees the person, never when it cannot");
        assertEquals(seen, turn.personIsDebugging(), "the agent's turn is over exactly when the host saw the person start");
    }
}
