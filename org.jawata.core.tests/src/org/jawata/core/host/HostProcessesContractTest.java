package org.jawata.core.host;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28a (1b, step M6) — the boundary's process contract, checked against
 * the REAL adapter (ring 2: runs on every matrix operating system).
 *
 * <p>The executable used throughout is the JVM running this test. It is the one
 * program guaranteed present on every runner, and reaching it through
 * {@code java.home} rather than {@code PATH} means the test asserts our
 * behaviour rather than the runner's environment.</p>
 */
class HostProcessesContractTest {

    private final HostProcesses host = HostProcesses.system();

    /** The JVM running this test — present on every runner, by construction. */
    private static String javaExecutable() {
        String name = HostOS.current().isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    @Test
    @DisplayName("executable candidates carry the host's spellings, wrapper first on Windows")
    void candidatesAreSpelledForThisHost() {
        List<String> candidates = host.executableCandidates("mvn");

        if (HostOS.current().isWindows()) {
            assertEquals("mvn.cmd", candidates.get(0),
                "the wrapper script comes first — it is what sets up the tool's environment");
            assertTrue(candidates.contains("mvn.exe"), candidates.toString());
            assertTrue(candidates.contains("mvn"), "the bare name stays as a last resort");
        } else {
            assertEquals(List.of("mvn"), candidates,
                "POSIX executables carry no extension; offering .cmd here would be noise");
        }
        assertFalse(candidates.isEmpty());
    }

    @Test
    @DisplayName("a build wrapper is mvnw.cmd on Windows and mvnw everywhere else")
    void wrapperNamesFollowTheHost() {
        List<String> names = host.wrapperNames("mvnw");

        assertEquals(HostOS.current().isWindows() ? "mvnw.cmd" : "mvnw", names.get(0));
        assertTrue(names.contains("mvnw"), "the POSIX spelling is always a candidate: " + names);
    }

    @Test
    @DisplayName("a process that runs and succeeds reports Completed(0) with its output")
    void aSuccessfulRunIsCompletedZero() throws Exception {
        HostProcessOutcome outcome = host.run(HostCommand.of(javaExecutable(), "-version"));

        HostProcessOutcome.Completed completed =
            assertInstanceOf(HostProcessOutcome.Completed.class, outcome, outcome.describe());
        assertEquals(0, completed.exitCode(), completed.output());
        assertTrue(outcome.succeeded());
        // -version writes to stderr; the merged stream is what makes that visible
        // without every caller re-deciding how to drain two pipes.
        assertFalse(outcome.output().isBlank(),
            "merged stderr must reach the caller, or diagnostics vanish");
    }

    @Test
    @DisplayName("a process that runs and FAILS is Completed with a non-zero code — not a launch failure")
    void aFailedRunIsStillCompleted() throws Exception {
        HostProcessOutcome outcome =
            host.run(HostCommand.of(javaExecutable(), "--no-such-flag-exists"));

        HostProcessOutcome.Completed completed =
            assertInstanceOf(HostProcessOutcome.Completed.class, outcome,
                "the JVM started and rejected the flag — that is a RUN failure");
        assertFalse(completed.exitCode() == 0, "a rejected flag must not report success");
        assertFalse(outcome.succeeded());
    }

    @Test
    @DisplayName("a process that cannot START is CannotLaunch, naming the executable — the distinction the old code lost")
    void aMissingExecutableIsCannotLaunch() throws Exception {
        HostProcessOutcome outcome =
            host.run(HostCommand.of("jawata-no-such-executable-anywhere", "--help"));

        HostProcessOutcome.CannotLaunch failed =
            assertInstanceOf(HostProcessOutcome.CannotLaunch.class, outcome,
                "a missing executable is not a tool that disagreed with us");
        assertEquals("jawata-no-such-executable-anywhere", failed.executable());
        assertFalse(failed.reason().isBlank(), "a refusal must say why");
        assertTrue(failed.describe().contains("could not start"), failed.describe());
        assertEquals("", outcome.output());
        assertFalse(outcome.succeeded());
    }

    @Test
    @DisplayName("start() surfaces a spawn failure as IOException rather than a half-live handle")
    void startThrowsWhenTheSpawnFails() {
        assertThrows(IOException.class,
            () -> host.start(HostCommand.of("jawata-no-such-executable-anywhere")));
    }

    @Test
    @DisplayName("a run honours its working directory")
    void theWorkingDirectoryIsUsed() throws Exception {
        Path dir = Files.createTempDirectory("jawata-host-cwd-");
        try {
            HostProcessOutcome outcome = host.run(
                HostCommand.of(javaExecutable(), "-XshowSettings:properties", "-version")
                    .in(dir));

            assertTrue(outcome.succeeded(), outcome.describe());
            // toRealPath on both sides: macOS reports /private/var for a /var temp
            // dir, which is the same asymmetry HostPaths exists to absorb.
            assertTrue(outcome.output().contains(dir.toRealPath().toString())
                    || outcome.output().contains(dir.toString()),
                "user.dir should be the directory we asked for: " + dir);
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @DisplayName("a process that overruns its timeout is TimedOut and is killed, not left behind")
    void anOverrunIsTimedOutAndReaped() throws Exception {
        // A JVM that sleeps far longer than we are willing to wait. Source-file
        // mode (JEP 330) keeps this to one file and no build step.
        Path source = Files.createTempDirectory("jawata-host-sleep-");
        Path program = source.resolve("Sleeper.java");
        Files.writeString(program, """
            public class Sleeper {
                public static void main(String[] args) throws Exception {
                    System.out.println("started");
                    System.out.flush();
                    Thread.sleep(120_000);
                }
            }
            """);
        try {
            HostProcessOutcome outcome = host.run(
                HostCommand.of(javaExecutable(), program.toString())
                    .waitingAtMost(Duration.ofSeconds(3)));

            assertInstanceOf(HostProcessOutcome.TimedOut.class, outcome,
                "a process that outlives our patience must say so: " + outcome.describe());
            assertFalse(outcome.succeeded());
        } finally {
            Files.deleteIfExists(program);
            Files.deleteIfExists(source);
        }
    }

    @Test
    @DisplayName("a command is a value: built and inspected without spawning anything")
    void aCommandIsInspectableBeforeItRuns() {
        HostCommand command = HostCommand.of("git", "-C", "/repo", "diff")
            .waitingAtMost(Duration.ofSeconds(5));

        assertEquals("git", command.executable());
        assertEquals("git -C /repo diff", command.describe());
        assertEquals(Duration.ofSeconds(5), command.timeout());
        assertTrue(command.mergeStderr(), "merged by default — one stream to read");
        assertFalse(command.withSeparateStderr().mergeStderr());
        assertThrows(IllegalArgumentException.class, () -> HostCommand.of(List.of()),
            "an empty command is a programming error, caught at construction");
    }
}
