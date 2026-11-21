package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, Stage 27, row (z29): a test run is a JVM of its own, so the forked runner refuses one when the machine has less memory available than
 * the floor, saying how much there is, as the hire tool does for a hire; a machine that cannot say how much it has is admitted, not guessed at.
 */
class ForkedTestRunnerMemoryTest {

    @Test
    @DisplayName("28h z29: a test run is refused when the machine has less memory available than the floor, naming the figure and the floor; at the floor, above it, and with no figure it is admitted")
    void aTestRunIsRefusedWhenTheMachineIsOutOfMemory() {
        Optional<String> below = ForkedTestRunner.refuseForMemory(OptionalLong.of(200), 512);

        assertAll(
            () -> assertTrue(below.isPresent(), "a test run was let start on a machine with 200 MB available against a floor of 512"),
            () -> assertTrue(below.orElse("").contains("200") && below.orElse("").contains("512"), "the refusal names neither the figure nor the floor: " + below),
            () -> assertEquals(Optional.empty(), ForkedTestRunner.refuseForMemory(OptionalLong.of(512), 512), "CONTROL: a machine with exactly the floor was refused"),
            () -> assertEquals(Optional.empty(), ForkedTestRunner.refuseForMemory(OptionalLong.of(4000), 512), "CONTROL: a machine with plenty was refused"),
            () -> assertEquals(Optional.empty(), ForkedTestRunner.refuseForMemory(OptionalLong.empty(), 512), "CONTROL: a machine that cannot say how much it has was refused"));
    }
}
