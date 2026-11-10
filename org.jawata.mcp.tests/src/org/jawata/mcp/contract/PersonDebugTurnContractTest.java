package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.jawata.spi.host.DebugTurn;
import org.jawata.spi.host.PersonDebugTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28i, Stage 25, step A2 (ARCHITECTURE-28i-s25.md, F3): the turn state of a headless engine whose debugger route serves the person. It
 * keeps every promise of {@link DebugTurnContract}, and it is the person's turn from the first debug launch begun until the last one ends.
 */
class PersonDebugTurnContractTest extends DebugTurnContract {

    private PersonDebugTurn turn;

    @Override
    protected DebugTurn turn() {
        turn = new PersonDebugTurn();
        return turn;
    }

    @Override
    protected boolean personStartsDebugging() {
        turn.begin();
        return true;
    }

    @Test
    @DisplayName("two debug launches overlap: the engine is told once, at the first, and the turn is the person's until the last one ends")
    void theTurnIsThePersonsUntilTheLastLaunchEnds() {
        PersonDebugTurn own = new PersonDebugTurn();
        AtomicInteger told = new AtomicInteger();
        own.onPersonStartsDebugging(told::incrementAndGet);

        PersonDebugTurn.Launch first = own.begin();
        PersonDebugTurn.Launch second = own.begin();
        first.end();

        assertEquals(1, told.get());
        assertTrue(own.personIsDebugging(), "one launch is still running");

        second.end();
        assertFalse(own.personIsDebugging());
    }

    @Test
    @DisplayName("ending a launch twice ends it once, so a later launch is not counted away")
    void endingALaunchTwiceEndsItOnce() {
        PersonDebugTurn own = new PersonDebugTurn();
        PersonDebugTurn.Launch first = own.begin();
        PersonDebugTurn.Launch second = own.begin();

        first.end();
        first.end();

        assertTrue(own.personIsDebugging(), "the second launch is still running");
        second.end();
        assertFalse(own.personIsDebugging());
    }

    @Test
    @DisplayName("a launch begun after all earlier ones ended tells the engine again")
    void theEngineIsToldAgainAtTheNextFirstLaunch() {
        PersonDebugTurn own = new PersonDebugTurn();
        AtomicInteger told = new AtomicInteger();
        own.onPersonStartsDebugging(told::incrementAndGet);

        own.begin().end();
        own.begin();

        assertEquals(2, told.get());
    }
}
