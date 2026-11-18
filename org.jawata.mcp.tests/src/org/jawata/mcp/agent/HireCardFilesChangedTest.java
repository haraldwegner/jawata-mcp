package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stage 27 (u), his words 2026-11-18 (after a fold Agent Control said "8 finished" and showed none, so "done · 0 files changed" was visible
 * nowhere): the card of an ended hire carries how many files the run changed, so the panel can say it. A count the engine could not take is
 * left out and never sent as zero, because a zero reads as "changed nothing".
 */
class HireCardFilesChangedTest {

    private static HireEntry card(OptionalInt changed) {
        return new HireEntry("plan-1", "developer", Optional.of("claude-code"), Optional.empty(), HireEntry.DONE, 1000, 10, OptionalDouble.empty(), "task",
            Optional.empty(), Optional.empty(), Optional.empty(), 0, 0, OptionalLong.empty(), Optional.empty(), Optional.empty(), Optional.empty(), changed);
    }

    @Test
    @DisplayName("Stage 27 (u): the card says how many files the run changed, zero included, and says nothing when the engine could not count")
    void theCardCarriesTheCountOnlyWhenItIsKnown() {
        ObjectNode none = card(OptionalInt.of(0)).toMeta();
        ObjectNode some = card(OptionalInt.of(3)).toMeta();
        ObjectNode unknown = card(OptionalInt.empty()).toMeta();

        assertEquals(0, none.path("changedFileCount").asInt(-1), "a run that changed nothing did not say so: " + none);
        assertEquals(3, some.path("changedFileCount").asInt(-1));
        assertFalse(unknown.has("changedFileCount"), "a count nobody took was sent as a number: " + unknown);
    }
}
