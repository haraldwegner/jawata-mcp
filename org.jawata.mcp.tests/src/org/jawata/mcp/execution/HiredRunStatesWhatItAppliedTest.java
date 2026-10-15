package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.jawata.mcp.execution.TreeAttribution.Attribution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A proposal says what it applied by READING it, not by asserting it (the architect's F1, S16-35).
 *
 * <p>{@code HiredRun} used to put {@code applied: false} in the manifest and "NOTHING HERE WAS
 * APPLIED" at the top of the record for EVERY run, while the changed-file census sat in the same
 * method — so a run that wrote files said, in its own first line, that it had applied nothing. A
 * property asserted about a mechanism rather than read out of the log is the failure shape of the
 * whole containment cluster; this is the one place it was visible to the human deciding whether to
 * accept a proposal.</p>
 */
class HiredRunStatesWhatItAppliedTest {

    private static final String BASIS = "a census before and after the run, scoped to the working directory";

    private static Attribution changed(String... files) {
        return new Attribution(Optional.of(List.of(files)), BASIS);
    }

    private static Attribution noCensus() {
        return new Attribution(Optional.empty(), "NOT ATTRIBUTABLE: /x is not a git working tree");
    }

    @Test
    @DisplayName("applied is true when the census names files, false when it names none, and UNKNOWN when nobody looked")
    void appliedIsReadFromTheCensus() {
        assertAll(
            () -> assertEquals(Boolean.TRUE, HiredRun.applied(changed("A.java"))),
            () -> assertEquals(Boolean.FALSE, HiredRun.applied(changed())),
            () -> assertNull(HiredRun.applied(noCensus()),
                "no census is not an empty one: 'applied: false' for a tree nobody looked at is"
                    + " the one thing a reader must not be told"));
    }

    @Test
    @DisplayName("the record's first line follows the same three states, and 'nothing applied' is only said when it was read")
    void theHeadlineFollowsTheCensus() {
        String some = HiredRun.headline(changed("A.java", "B.java"));
        String none = HiredRun.headline(changed());
        String unknown = HiredRun.headline(noCensus());

        assertAll(
            () -> assertTrue(some.contains("CHANGED 2 FILE(S)"), some),
            () -> assertFalse(some.contains("NOTHING HERE WAS APPLIED"),
                "a run that wrote files must not open by saying it applied nothing: " + some),
            () -> assertTrue(none.contains("NOTHING HERE WAS APPLIED"), none),
            () -> assertTrue(none.contains("working directory"),
                "and it says what the census could see, so off-tree writes are not denied: " + none),
            () -> assertTrue(unknown.contains("NOT ESTABLISHED"), unknown),
            () -> assertFalse(unknown.contains("NOTHING HERE WAS APPLIED"), unknown),
            () -> assertTrue(unknown.contains("not a git working tree"),
                "the reason no census was taken is beside the claim: " + unknown));
    }

    @Test
    @DisplayName("a mechanism is WITNESSED only when the run's own init list offered no editor")
    void aMechanismIsWitnessedFromTheLog() {
        WriteContainment mechanism = WriteContainment.mechanism("--tools \"\"");
        WriteContainment instruction = WriteContainment.instruction("--mode plan", "x");

        assertAll(
            () -> assertTrue(HiredRun.writeWitness(mechanism, Optional.of(List.of())).startsWith("WITNESSED"),
                "an empty init list: nothing offered, nothing to refuse"),
            () -> assertTrue(HiredRun.writeWitness(mechanism,
                    Optional.of(List.of("mcp__jawata__inspect"))).startsWith("WITNESSED"),
                "tools offered, none of them an editor"),
            () -> assertTrue(HiredRun.writeWitness(mechanism,
                    Optional.of(List.of("Read", "Write"))).startsWith("CONTRADICTED"),
                "the mechanism says no editors and the run's own init list offered Write"),
            () -> assertTrue(HiredRun.writeWitness(mechanism, Optional.empty())
                    .startsWith("declared, not witnessed"),
                "a brain that publishes no init list can only be believed, and the record says so"),
            () -> assertTrue(HiredRun.writeWitness(instruction, Optional.of(List.of()))
                    .startsWith("not applicable"),
                "an instruction has no refusal point to witness"));
    }
}
