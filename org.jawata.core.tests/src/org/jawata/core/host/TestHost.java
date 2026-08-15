// The design named this package org.jawata.core.host.test. It lives in
// org.jawata.core.host instead, for a reason the runner enforced: the test
// bundle is a FRAGMENT, and the framework resolves a test class through a
// package its HOST bundle exports. A brand-new .test subpackage is exported by
// nobody, so the class loaded from nowhere — "cannot be found by
// org.jawata.mcp_3.8.0". Every existing test package mirrors an exported
// production one; this now does too, rather than exporting a test-only package
// from a shipped bundle.
package org.jawata.core.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 28a (1b, step M11) — host fixtures for tests, in one place.
 *
 * <p>Tests need stand-in tools: a fake {@code mvnw}, a fake crash-triage
 * adapter, a fake RCP launcher. Until now each wrote its own shell script, and
 * a shell script is not a thing Windows can execute — so those tests carry
 * per-OS branches, or honest skips, or both.</p>
 *
 * <p><b>The problem this kit exists to solve is ARGV.</b> A Windows stand-in has
 * to be a {@code .cmd} or a {@code .bat}, because those are the only scripts
 * {@link ProcessBuilder} will spawn — and the evidence on record says arguments
 * do not survive that path intact (a batch fake scanned its whole argv for a
 * flag it was definitely given, and exited 3 on the miss; CI run 31804158929).
 * The design's answer is to put a JVM in the middle: {@code java} is the one
 * executable guaranteed present on every runner AND faithful with its
 * arguments, so the launcher's only job is to forward.</p>
 *
 * <p><b>Whether that forwarding works on Windows is exactly what nobody could
 * verify from a Linux machine</b>, which is why this kit's own contract test
 * asserts argv fidelity rather than assuming it. The three-OS matrix answers the
 * question; if Windows still loses arguments, the test says so on the platform
 * that decides, and the honest skip it replaces goes back with a reason that is
 * now measured rather than inferred.</p>
 */
public final class TestHost {

    /** Single-file source (JEP 330) — no compilation step, no classpath. */
    private static final String FAKE_TOOL_SOURCE = """
        import java.nio.file.*;
        import java.util.*;

        /**
         * Records the arguments it was handed, one per line, then exits with the
         * code baked into its name. The recording IS the assertion surface: a
         * launcher that loses arguments produces a short file, not a wrong one.
         */
        public class FakeTool {
            public static void main(String[] args) throws Exception {
                Path log = Paths.get(System.getProperty("jawata.fake.argv"));
                List<String> lines = new ArrayList<>(Arrays.asList(args));
                Files.write(log, lines);
                String out = System.getProperty("jawata.fake.stdout", "");
                if (!out.isEmpty()) {
                    System.out.println(out);
                }
                System.exit(Integer.getInteger("jawata.fake.exit", 0));
            }
        }
        """;

    private TestHost() {
    }

    /**
     * A fake executable that records its argv to {@code argvLog} and exits with
     * {@code exitCode}.
     *
     * <p>Returned path is directly spawnable: a {@code .cmd} on Windows, an
     * extensionless script elsewhere. Both forward to the same JVM program, so
     * the two platforms differ only in the four lines of launcher syntax.</p>
     *
     * @param dir      where to place the launcher and its program
     * @param baseName the executable's name WITHOUT extension — the caller gets
     *                 back the real path, because only this method knows what
     *                 this host requires
     * @param argvLog  the file the fake writes its arguments to, one per line
     * @param exitCode what the fake exits with
     * @param stdout   a line to print, or empty for none
     */
    public static Path fakeExecutable(Path dir, String baseName, Path argvLog,
                                      int exitCode, String stdout) throws IOException {
        Files.createDirectories(dir);
        Path program = dir.resolve("FakeTool.java");
        Files.writeString(program, FAKE_TOOL_SOURCE, StandardCharsets.UTF_8);

        String java = Path.of(System.getProperty("java.home"), "bin",
            HostOS.current().isWindows() ? "java.exe" : "java").toString();
        String props = " -Djawata.fake.argv=" + quote(argvLog.toString())
            + " -Djawata.fake.exit=" + exitCode
            + " -Djawata.fake.stdout=" + quote(stdout);

        Path launcher;
        if (HostOS.current().isWindows()) {
            // %* forwards the caller's arguments verbatim — no `for` loop, which
            // is where a previous batch fake shredded them (cmd's for-in splits
            // on '=' as well as spaces, and the arguments under test are
            // -Dkey=value shaped).
            launcher = dir.resolve(baseName + ".cmd");
            Files.writeString(launcher,
                "@echo off\r\n\"" + java + "\"" + props + " \"" + program + "\" %*\r\n",
                StandardCharsets.UTF_8);
        } else {
            launcher = dir.resolve(baseName);
            Files.writeString(launcher,
                "#!/bin/sh\nexec \"" + java + "\"" + props + " \"" + program + "\" \"$@\"\n",
                StandardCharsets.UTF_8);
        }
        // Not setPosixFilePermissions: that THROWS on Windows, and a fixture
        // that learns the platform by catching an exception has already failed
        // in a way that reads like a product bug.
        HostFs.setExecutable(launcher);
        return launcher;
    }

    /** The arguments the fake recorded, in order; empty if it never ran. */
    public static List<String> recordedArgv(Path argvLog) throws IOException {
        return Files.exists(argvLog) ? Files.readAllLines(argvLog) : List.of();
    }

    private static String quote(String value) {
        return value.indexOf(' ') >= 0 ? "\"" + value + "\"" : value;
    }
}
