package org.jawata.core.host;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sprint 28a (1b, step M6) — a command to run, as a VALUE.
 *
 * <p>Every launch site in this codebase used to assemble a {@link ProcessBuilder}
 * inline and then repeat the same four decisions with slightly different
 * answers: where to run, whether to merge stderr, how long to wait, and what a
 * failure means. A command that is a value can be built, inspected, logged and
 * asserted on without spawning anything — which is what lets the boundary's
 * contract test check argv fidelity instead of hoping.</p>
 *
 * @param argv             the executable and its arguments; never empty
 * @param workingDirectory where to run, or {@code null} for the JVM's own
 * @param environment      extra environment entries; never null, often empty
 * @param timeout          how long to wait for completion before giving up
 * @param mergeStderr      whether stderr joins stdout (the common case: one
 *                         readable stream for diagnostics)
 */
public record HostCommand(
    List<String> argv,
    Path workingDirectory,
    Map<String, String> environment,
    Duration timeout,
    boolean mergeStderr) {

    /** The default patience for a short-lived tool invocation. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public HostCommand {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("a command needs at least an executable");
        }
        argv = List.copyOf(argv);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /** A command run in the JVM's own directory, stderr merged, default timeout. */
    public static HostCommand of(String... argv) {
        return new HostCommand(List.of(argv), null, Map.of(), DEFAULT_TIMEOUT, true);
    }

    /** A command run in the JVM's own directory, stderr merged, default timeout. */
    public static HostCommand of(List<String> argv) {
        return new HostCommand(argv, null, Map.of(), DEFAULT_TIMEOUT, true);
    }

    public HostCommand in(Path directory) {
        return new HostCommand(argv, directory, environment, timeout, mergeStderr);
    }

    public HostCommand waitingAtMost(Duration newTimeout) {
        return new HostCommand(argv, workingDirectory, environment, newTimeout, mergeStderr);
    }

    public HostCommand withEnvironment(Map<String, String> extra) {
        return new HostCommand(argv, workingDirectory, extra, timeout, mergeStderr);
    }

    /** Keep stderr separate — for a caller that reports the two streams apart. */
    public HostCommand withSeparateStderr() {
        return new HostCommand(argv, workingDirectory, environment, timeout, false);
    }

    /** The executable, for diagnostics that name what could not be started. */
    public String executable() {
        return argv.get(0);
    }

    /** A readable rendering for logs and error messages — never for a shell to parse. */
    public String describe() {
        return String.join(" ", argv);
    }
}
