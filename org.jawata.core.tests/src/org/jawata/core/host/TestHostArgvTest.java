package org.jawata.core.host;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28a (1b, step M11) — does a generated fake executable receive its
 * arguments intact, on THIS operating system?
 *
 * <p>This test exists to settle a question the design could not: a Windows
 * stand-in must be a {@code .cmd}, and the evidence on record said arguments do
 * not survive that path (a batch fake scanned its argv for a flag it had
 * definitely been given and exited 3 on the miss — CI run 31804158929). The
 * cure was to forward through a JVM, and whether THAT survives could not be
 * verified from a Linux machine.</p>
 *
 * <p>So the question becomes an assertion, and the three-OS matrix answers it.
 * If Windows still loses arguments this fails there and says exactly which ones
 * arrived — which is strictly better than the honest skip it replaces, because
 * a skip records that we did not know and this records what is true.</p>
 */
class TestHostArgvTest {

    /** The shapes that actually broke: -Dkey=value, spaces, and a bare flag. */
    private static final List<String> ARGV = List.of(
        "dependency:build-classpath",
        "-Dmdep.outputFile=target/jawata-classpath-1234.txt",
        "-q");

    @Test
    @DisplayName("a generated fake receives every argument, in order, unmangled")
    void argvSurvivesTheLauncher(@TempDir Path dir) throws Exception {
        Path argvLog = dir.resolve("argv.txt");
        Path fake = TestHost.fakeExecutable(dir, "mvnw", argvLog, 0, "");

        HostProcessOutcome outcome = HostProcesses.system().run(
            HostCommand.of(concat(fake.toString(), ARGV)));

        assertTrue(outcome.succeeded(), () -> "the fake did not run: " + outcome.describe()
            + " — output: " + outcome.output());
        assertEquals(ARGV, TestHost.recordedArgv(argvLog),
            "every argument must arrive, in order and unsplit. A -Dkey=value pair "
                + "shredded here is the exact failure that forced the Windows skip.");
    }

    @Test
    @DisplayName("a fake can fail on demand, and its exit code reaches the caller")
    void theFakeCanFail(@TempDir Path dir) throws Exception {
        Path argvLog = dir.resolve("argv.txt");
        Path fake = TestHost.fakeExecutable(dir, "failing-tool", argvLog, 3, "");

        HostProcessOutcome outcome =
            HostProcesses.system().run(HostCommand.of(fake.toString(), "--whatever"));

        HostProcessOutcome.Completed completed = (HostProcessOutcome.Completed) outcome;
        assertEquals(3, completed.exitCode(), "a fixture must be able to fail deliberately");
        assertEquals(List.of("--whatever"), TestHost.recordedArgv(argvLog));
    }

    @Test
    @DisplayName("a fake can speak, so a caller that parses stdout has something to parse")
    void theFakeCanPrint(@TempDir Path dir) throws Exception {
        Path argvLog = dir.resolve("argv.txt");
        Path fake = TestHost.fakeExecutable(dir, "chatty-tool", argvLog, 0, "hello-from-the-fake");

        HostProcessOutcome outcome = HostProcesses.system().run(HostCommand.of(fake.toString()));

        assertTrue(outcome.succeeded(), outcome.describe());
        assertTrue(outcome.output().contains("hello-from-the-fake"), outcome.output());
    }

    private static List<String> concat(String head, List<String> tail) {
        java.util.List<String> all = new java.util.ArrayList<>();
        all.add(head);
        all.addAll(tail);
        return all;
    }
}
