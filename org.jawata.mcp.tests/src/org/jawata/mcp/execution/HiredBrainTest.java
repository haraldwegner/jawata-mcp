package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostOS;
import org.jawata.core.host.HostProcesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28g S9a M5a — the hire, driven against a shell script so no model, API key or network is
 * involved.
 *
 * <p>Each case below pins one of the four defects the Rust reference records against itself, because
 * the whole value of porting the loop rather than copying it is that those four do not travel. The
 * script dialect exists for exactly this: a plain-text brain DECLARES its cost and tool steps, so a
 * fixture can produce any shape a real brain could.</p>
 */
class HiredBrainTest {

    private Path dir;
    private final HostProcesses host = HostProcesses.system();

    @BeforeEach
    void posixOnly() {
        // The fixtures are shell scripts; the Windows path needs its own and has no brain to hire
        // yet either.
        assumeTrue(!HostOS.current().isWindows(), "the fixtures are POSIX shell scripts");
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best effort; the temp dir is the OS's problem after this
                    }
                });
            }
        }
    }

    /** A script the hire can run: the dialect passes the prompt as the last argument. */
    private HostCommand script(String body) throws Exception {
        dir = Files.createTempDirectory("jawata-hire-");
        Path file = dir.resolve("brain.sh");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    private BrainDialect dialectFor(HostCommand command) {
        return new ScriptDialect(command.argv().get(0), List.of());
    }

    @Test
    @DisplayName("a clean run carries what the brain declared")
    void aCleanRunCarriesWhatTheBrainDeclared() throws Exception {
        HostCommand command = script("""
            echo "looking at it"
            echo "TOOL-STEPS: 3"
            echo "COST-USD: 0.17"
            echo "done"
            """);
        HiredBrain.Outcome outcome = HiredBrain.hire(host, dialectFor(command), command,
            "the brief", HiredBrain.Ceilings.of(Duration.ofSeconds(20)));

        assertAll(
            () -> assertTrue(outcome.clean(), "it exited 0, on its own, inside every ceiling"),
            () -> assertTrue(outcome.text().contains("looking at it")),
            () -> assertTrue(outcome.text().contains("done")),
            () -> assertEquals(3, outcome.toolSteps()),
            () -> assertEquals(0.17, outcome.costUsd().orElseThrow(), 1e-9),
            () -> assertTrue(outcome.sawTerminalCost(), "a cost WAS reported"),
            () -> assertEquals(0, outcome.unreadableLines(),
                "a plain-text dialect has no unreadable line; a non-zero count here would mean"
                    + " the driver is confused rather than the child"),
            () -> assertTrue(outcome.stoppedBy().isEmpty(), "no ceiling fired"));
    }

    @Test
    @DisplayName("DEFECT 3: a crashed brain is a FAILURE, not 'the model said nothing'")
    void aCrashedBrainIsNotSilence() throws Exception {
        // The reference sends stderr to the void and never reads the exit status, so a dead
        // adapter returns empty text and reads exactly like a model that chose not to answer.
        // Those are different diagnoses and send a reader to different places.
        HostCommand command = script("""
            echo "Traceback: the adapter died" >&2
            exit 9
            """);
        HiredBrain.Outcome outcome = HiredBrain.hire(host, dialectFor(command), command,
            "the brief", HiredBrain.Ceilings.of(Duration.ofSeconds(20)));

        assertAll(
            () -> assertTrue(outcome.text().isEmpty(), "it really did say nothing on stdout"),
            () -> assertFalse(outcome.saidNothing(),
                "but that is NOT silence — the distinction this whole record exists for"),
            () -> assertEquals(9, outcome.exitCode().orElseThrow(),
                "the exit status is REPORTED, which the reference discards"),
            () -> assertTrue(outcome.stderrTail().contains("the adapter died"),
                "and its crash is carried, because stderr is where a crash goes"),
            () -> assertFalse(outcome.clean()));
    }

    @Test
    @DisplayName("DEFECT 2: a reaped run's cost is ABSENT, never zero")
    void aReapedRunReportsNoCostRatherThanZero() throws Exception {
        // Cost arrives at the end, so a run killed on its wall clock never reports one. Booking
        // that as 0.00 is what stops a cost ceiling firing on exactly the expensive runs.
        HostCommand command = script("""
            echo "starting"
            sleep 60
            echo "COST-USD: 99.99"
            """);
        HiredBrain.Outcome outcome = HiredBrain.hire(host, dialectFor(command), command,
            "the brief", HiredBrain.Ceilings.of(Duration.ofSeconds(2)));

        assertAll(
            () -> assertTrue(outcome.timedOut(), "the wall clock reaped it"),
            () -> assertTrue(outcome.costUsd().isEmpty(),
                "NOTHING reported a cost — absent, and a caller must not round it to zero"),
            () -> assertFalse(outcome.sawTerminalCost(),
                "and the flag says which case this is, so absent is never guessed at"),
            () -> assertTrue(outcome.text().contains("starting"),
                "proof of life: it really ran before being reaped, or this checks nothing"),
            () -> assertFalse(outcome.clean()));
    }

    @Test
    @DisplayName("DEFECT 1: the step ceiling actually fires — set it to 1 and the run stops")
    void theStepCeilingIsABoundRatherThanADecoration() throws Exception {
        // The reference's loop breaks on its first pass, so its iteration ceiling can only fire
        // when set to zero: inert at every value a caller would configure. This is the mutation
        // its own module doc prescribes, run as a test.
        HostCommand command = script("""
            echo "TOOL-STEPS: 2"
            echo "TOOL-STEPS: 2"
            sleep 30
            echo "never reached"
            """);
        HiredBrain.Outcome outcome = HiredBrain.hire(host, dialectFor(command), command,
            "the brief", new HiredBrain.Ceilings(Duration.ofSeconds(25), 1, null));

        assertAll(
            () -> assertTrue(outcome.stoppedBy().isPresent(), "a ceiling fired"),
            () -> assertTrue(outcome.stoppedBy().orElseThrow().startsWith("tool steps"),
                "and it was the STEP ceiling: " + outcome.stoppedBy().orElse("(none)")),
            () -> assertEquals(2, outcome.toolSteps(),
                "it stopped at the first line that crossed 1, not after draining everything"),
            () -> assertFalse(outcome.text().contains("never reached"),
                "the run really was cut short rather than merely labelled"),
            () -> assertFalse(outcome.timedOut(),
                "the CONTROL: the ceiling stopped it, not the 25-second wall clock — without this"
                    + " a driver that only ever times out would pass the assertions above"));
    }

    @Test
    @DisplayName("the cost ceiling fires on a cost the brain DID report")
    void theCostCeilingFiresOnAReportedCost() throws Exception {
        HostCommand command = script("""
            echo "COST-USD: 5.00"
            sleep 30
            echo "never reached"
            """);
        HiredBrain.Outcome outcome = HiredBrain.hire(host, dialectFor(command), command,
            "the brief", new HiredBrain.Ceilings(Duration.ofSeconds(25), 0, 1.00));

        assertAll(
            () -> assertTrue(outcome.stoppedBy().orElse("").startsWith("cost"),
                "the cost ceiling: " + outcome.stoppedBy().orElse("(none)")),
            () -> assertFalse(outcome.text().contains("never reached")),
            () -> assertFalse(outcome.timedOut(), "the ceiling, not the clock"));
    }
}
