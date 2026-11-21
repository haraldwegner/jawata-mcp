package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, Stage 27, row (z27): the reason the test manager gave for a full run is kept on that hire's record, so the pm that reads the hire
 * sees what the run was argued for. The registry keeps each reason on the running hire whose plan the caller is, and keeps none for a caller that
 * is no running hire or for a reason that says nothing.
 */
class HireFullRunReasonTest {

    @Test
    @DisplayName("28h z27: a reason is kept on the running hire whose plan the caller is, in the order given; a blank reason, a caller that is no hire and another hire's plan keep nothing")
    void aFullRunReasonIsKeptOnTheHireThatGaveIt() {
        HireRegistry hires = HireRegistry.shared();
        try (HireRegistry.Registration manager = hires.begin("test-manager", "z27 record reasons", "")) {
            hires.planOpened("plan-z27-reasons");
            hires.noteFullRunReason("plan-z27-reasons", "the checkpoint C27 gate");
            hires.noteFullRunReason("plan-z27-reasons", "  ");
            hires.noteFullRunReason("plan-z27-reasons", "after the dist was rebuilt");
            hires.noteFullRunReason("z27-nobody-registered", "no hire has this id");

            assertAll(
                () -> assertEquals(List.of("the checkpoint C27 gate", "after the dist was rebuilt"), hires.fullRunReasonsOf("plan-z27-reasons"),
                    "the reasons were not kept in order"),
                () -> assertEquals(List.of(), hires.fullRunReasonsOf("z27-nobody-registered"), "CONTROL: a reason was kept for a caller that is no hire"),
                () -> assertEquals(List.of(), hires.fullRunReasonsOf(""), "CONTROL: a reason was kept for no plan"));
        }
    }
}
