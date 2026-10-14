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

    // ---- S16-18: the collected answer DUPLICATED a short reply. Both claude and cursor returned
    // "READY\nREADY" for a brief asking for one word: the assistant event and the closing result
    // event each contributed, and a one-turn reply is in both. The closing line restates what the
    // earlier lines said; it is the answer only when nothing else was.

    private HiredBrain.Outcome hireStreamJson(String body, boolean cursor) throws Exception {
        HostCommand command = script(body);
        BrainDialect dialect = cursor
            ? new CursorDialect(command.argv().get(0), true, null)
            : new ClaudeCodeDialect(command.argv().get(0), 12, null, false);
        return HiredBrain.hire(host, dialect, command, "brief", List.of(),
            HiredBrain.Ceilings.of(Duration.ofSeconds(20)));
    }

    private static final String ONE_WORD_REPLY = """
        echo '{"type":"assistant","message":{"content":[{"type":"text","text":"READY"}]}}'
        echo '{"type":"result","result":"READY","total_cost_usd":0.01}'
        """;

    @Test
    @DisplayName("S16-18: a one-word reply is the answer ONCE (claude)")
    void aShortReplyIsNotDuplicatedByTheClosingResult() throws Exception {
        assertEquals("READY", hireStreamJson(ONE_WORD_REPLY, false).text(),
            "the assistant line and the closing result say the same thing; the answer is one of them");
    }

    @Test
    @DisplayName("S16-18: a one-word reply is the answer ONCE (cursor, the other dialect that returned both)")
    void aShortReplyIsNotDuplicatedByTheClosingResultCursor() throws Exception {
        assertEquals("READY", hireStreamJson(ONE_WORD_REPLY, true).text());
    }

    @Test
    @DisplayName("S16-18: a multi-turn answer keeps every turn and still does not repeat the last")
    void everyTurnIsKeptAndTheLastIsNotRepeated() throws Exception {
        HiredBrain.Outcome outcome = hireStreamJson("""
            echo '{"type":"assistant","message":{"content":[{"type":"text","text":"first"}]}}'
            echo '{"type":"assistant","message":{"content":[{"type":"text","text":"second"}]}}'
            echo '{"type":"result","result":"second","total_cost_usd":0.01}'
            """, false);
        assertEquals("first\nsecond", outcome.text());
    }

    @Test
    @DisplayName("S16-18: when nothing else was said, the closing result IS the answer — an error must not vanish")
    void theClosingResultIsTheAnswerWhenNothingElseWasSaid() throws Exception {
        assertEquals("the brain stopped: out of turns", hireStreamJson("""
            echo '{"type":"result","result":"the brain stopped: out of turns","total_cost_usd":0.01}'
            """, false).text(),
            "dropping the closing result outright would lose the only text a failed run produced");
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
            "the brief", List.of(), HiredBrain.Ceilings.of(Duration.ofSeconds(20)));

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
    @DisplayName("M11: the outcome carries the NAMES of the calls, and a dialect that only counts reports the rest as unnamed")
    void theOutcomeCarriesWhichToolsWereCalled() throws Exception {
        // A real stream-json dialect reading a script that speaks it: two turns, three calls.
        HostCommand named = script("""
            echo '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"search_symbols"},{"type":"tool_use","name":"Write"}]}}'
            echo '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash"},{"type":"text","text":"done"}]}}'
            echo '{"type":"result","result":"done","total_cost_usd":0.01}'
            """);
        HiredBrain.Outcome seen = HiredBrain.hire(host,
            new ClaudeCodeDialect(named.argv().get(0), 12, null, false), named, "brief",
            List.of(), HiredBrain.Ceilings.of(Duration.ofSeconds(20)));

        // The plain-text dialect COUNTS what it is told and names nothing.
        HostCommand counted = script("""
            echo "TOOL-STEPS: 3"
            """);
        HiredBrain.Outcome unnamed = HiredBrain.hire(host, dialectFor(counted), counted, "brief",
            List.of(), HiredBrain.Ceilings.of(Duration.ofSeconds(20)));

        assertAll(
            () -> assertEquals(List.of("search_symbols", "Write", "Bash"), seen.toolNames(),
                "every call, in the order the brain made them, across turns"),
            () -> assertEquals(3, seen.toolSteps()),
            () -> assertEquals(0, seen.unnamedToolSteps(), "every counted call was named"),
            () -> assertTrue(unnamed.toolNames().isEmpty()),
            () -> assertEquals(3, unnamed.unnamedToolSteps(),
                "three calls counted and none named — an empty list beside that count must not"
                    + " read as 'nothing was called'"));
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
            "the brief", List.of(), HiredBrain.Ceilings.of(Duration.ofSeconds(20)));

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
            "the brief", List.of(), HiredBrain.Ceilings.of(Duration.ofSeconds(2)));

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
            "the brief", List.of(), new HiredBrain.Ceilings(Duration.ofSeconds(25), 1, null));

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
            "the brief", List.of(), new HiredBrain.Ceilings(Duration.ofSeconds(25), 0, 1.00));

        assertAll(
            () -> assertTrue(outcome.stoppedBy().orElse("").startsWith("cost"),
                "the cost ceiling: " + outcome.stoppedBy().orElse("(none)")),
            () -> assertFalse(outcome.text().contains("never reached")),
            () -> assertFalse(outcome.timedOut(), "the ceiling, not the clock"));
    }
}
