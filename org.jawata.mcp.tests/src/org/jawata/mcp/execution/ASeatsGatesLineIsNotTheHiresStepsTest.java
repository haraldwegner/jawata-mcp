package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S16-8: the steps of a hire come from the HIRER, and a seat's {@code gates:} line is not read as
 * steps.
 *
 * <p>The key looked unread and was proposed for deletion. It is not unread: jawata-studio's seat
 * parser reads {@code gates:} as the seat's gate CLASSES (always, behavior, tests, docs), a
 * different concept that shares the key. The engine ignores it, and the choice made here is that
 * it keeps ignoring it — a hired seat does not gate its own work, the hirer declares the steps
 * (the hire tool refuses a hire with none) — so reading it as steps would let the same file be
 * answered two ways by two programs. This holds that choice: adding a {@code gates:} line to a seat
 * changes nothing about the hire the engine derives from it.</p>
 */
class ASeatsGatesLineIsNotTheHiresStepsTest {

    private static final String SEAT = "---\nname: cataloguer\nttl_secs: 30\n%s---\n\nbody\n";

    @Test
    @DisplayName("a seat with a gates: line is hired exactly as the same seat without one")
    void aGatesLineChangesNothingTheEngineDerives() {
        SeatHire without = SeatHire.read(SEAT.formatted(""));
        SeatHire with = SeatHire.read(SEAT.formatted("gates: [always, tests]\n"));

        assertEquals(without, with,
            "the engine does not read gates: — studio's gate classes — as the hire's steps");
        assertEquals(java.util.List.of(), with.answerGates(),
            "and it is not mistaken for the seat's answer gates either");
    }
}
