package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, Stage 27, row (z28), the second ceiling as the hire tool carries it: a watchdog the hire tool starts stops a hire that has shown no
 * sign of life past the ceiling the host sets, and the answer of a hire stopped that way says it was a stall and for how long, where a person's
 * stop is still answered as the person's.
 */
class StallWatchTest {

    @Test
    @DisplayName("28h z28: the watchdog tick stops a hire quiet past the ceiling the host set and answers it as a stall with the seconds; with the ceiling off nothing is stopped")
    void aQuietHireIsStoppedByTheTickAndAnsweredAsAStall() throws Exception {
        HireRegistry hires = HireRegistry.shared();
        try (HireRegistry.Registration quiet = hires.begin("developer", "z28 watch quiet", "")) {
            hires.planOpened("plan-z28-watch-quiet");
            Thread.sleep(1_300);

            HireTool.watchForStalls(0);
            List<String> whenOff = HireTool.stopQuietHires();
            HireTool.watchForStalls(1);
            List<String> stopped = HireTool.stopQuietHires();
            Thread.interrupted();
            ToolResponse answer = HireTool.stoppedAnswer(quiet);

            assertAll(
                () -> assertEquals(List.of(), whenOff, "CONTROL: a hire was stopped with the ceiling off"),
                () -> assertEquals(1, stopped.size(), "the quiet hire was not stopped by the tick: " + stopped),
                () -> assertFalse(answer.isSuccess()),
                () -> assertEquals("HIRE_STALLED", answer.getError().getCode()),
                () -> assertTrue(answer.getError().getMessage().contains("no sign of life"), "the answer does not say it was a stall: " + answer.getError().getMessage()));
        } finally {
            HireTool.watchForStalls(0);
        }
    }

    @Test
    @DisplayName("28h z28: the watchdog tick tells that a quiet hire is stalled, before the second ceiling stops it; a hire quiet for less than the first ceiling is told of nothing")
    void theTickTellsAQuietHireIsStalled() throws Exception {
        HireRegistry hires = HireRegistry.shared();
        List<HireRegistry.Event> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable unsubscribe = hires.subscribe(heard::add);
        try (HireRegistry.Registration quiet = hires.begin("developer", "z28 watch tells", "")) {
            hires.planOpened("plan-z28-watch-tells");
            Thread.sleep(1_300);

            HireTool.watchForStalls(0);
            HireTool.stopQuietHires();
            long whenTheCeilingIsOff = heard.stream().filter(event -> "stalled".equals(event.kind()) && "plan-z28-watch-tells".equals(event.planId())).count();
            HireTool.watchForStalls(1);
            HireTool.stopQuietHires();
            Thread.interrupted();
            long afterTheTick = heard.stream().filter(event -> "stalled".equals(event.kind()) && "plan-z28-watch-tells".equals(event.planId())).count();

            assertAll(
                () -> assertEquals(0, whenTheCeilingIsOff, "CONTROL: a hire quiet for 1 s was told as stalled at the first ceiling of five minutes"),
                () -> assertEquals(1, afterTheTick, "the tick did not tell the quiet hire was stalled"));
        } finally {
            HireTool.watchForStalls(0);
            unsubscribe.run();
        }
    }

    @Test
    @DisplayName("28h z28: a hire stopped by the person is answered as the person's stop, not as a stall")
    void aPersonsStopIsStillThePersons() throws Exception {
        HireRegistry hires = HireRegistry.shared();
        try (HireRegistry.Registration stoppedByPerson = hires.begin("developer", "z28 watch person", "")) {
            hires.planOpened("plan-z28-watch-person");
            hires.stopTree("plan-z28-watch-person");
            Thread.interrupted();

            ToolResponse answer = HireTool.stoppedAnswer(stoppedByPerson);

            assertAll(
                () -> assertEquals("HIRE_STOPPED", answer.getError().getCode()),
                () -> assertTrue(answer.getError().getMessage().contains("Agent Control"), "the person's stop lost its wording: " + answer.getError().getMessage()));
        }
    }
}
