package org.jawata.core.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sprint 28a (1b, step M6) — {@link HostProcesses} against the real operating
 * system. The only class below the boundary that constructs a
 * {@link ProcessBuilder}.
 */
final class SystemHostProcesses implements HostProcesses {

    static final SystemHostProcesses INSTANCE = new SystemHostProcesses(HostOS.current());

    /**
     * The operating system whose SPELLINGS this instance answers for.
     *
     * <p>A value, not {@code HostOS.current()} read inline — for the same reason
     * {@link HostOS#of(String)} takes a string: it makes the Windows naming
     * branch reachable from a Linux runner. Naming is pure logic and deserves
     * to be tested on every host; LAUNCHING is not, and is proven per
     * environment by the boundary's contract test.</p>
     */
    private final HostOS os;

    SystemHostProcesses(HostOS os) {
        this.os = os;
    }

    @Override
    public List<String> executableCandidates(String base) {
        if (!os.isWindows()) {
            return List.of(base);
        }
        // .cmd first: a tool shipped as both a wrapper script and a binary should
        // be reached through the wrapper, which is what sets up its environment.
        // The bare name last, so an extensionless executable on PATH still wins
        // over nothing.
        return List.of(base + ".cmd", base + ".exe", base + ".bat", base);
    }

    @Override
    public List<String> wrapperNames(String base) {
        return os.isWindows() ? List.of(base + ".cmd", base) : List.of(base);
    }

    @Override
    public HostProcessOutcome run(HostCommand command) throws InterruptedException {
        Process process;
        try {
            process = start(command);
        } catch (IOException e) {
            // The spawn itself failed — the executable is missing, or is not a
            // valid application here. NOT the same as a tool that ran and
            // disagreed with us, and the caller is told which.
            return new HostProcessOutcome.CannotLaunch(command.executable(), reasonFor(e));
        }

        // The output is drained on its OWN thread, and this is not a style
        // choice. InputStream.readAllBytes() returns at end of stream — which
        // for a pipe means when the process exits — so reading inline BEFORE
        // waitFor makes the timeout decorative: the call blocks for as long as
        // the child feels like living and then reports a tidy completion. The
        // boundary's own contract test caught exactly that: a child told to
        // sleep 120s under a 3s timeout came back Completed, 120 seconds later.
        // Draining concurrently also keeps a chatty child from filling the pipe
        // buffer and deadlocking against a parent that is not reading yet.
        //
        // StringBuffer, not StringBuilder: on the timeout path the partial
        // output is read while the drain is still running, and a bounded join
        // that EXPIRES establishes no happens-before edge. Synchronised appends
        // are what make that read safe at all.
        StringBuffer captured = new StringBuffer();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    captured.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // The stream dies when we kill the process — expected on timeout,
                // and the partial output collected so far is still returned.
                captured.append("\n[output truncated: ").append(e.getMessage()).append(']');
            }
        }, "jawata-host-drain");
        drain.setDaemon(true);
        drain.start();

        // run() owns both streams, so an unmerged stderr gets drained too — not
        // to report it (this caller asked for stdout alone) but because a full
        // pipe blocks the child, and a hang presents as a slow tool.
        if (!command.mergeStderr()) {
            Thread stderrDrain = new Thread(() -> {
                try (InputStream err = process.getErrorStream()) {
                    while (err.read() != -1) {
                        // discard: kept out of the parsed stream by request
                    }
                } catch (IOException ignored) {
                    // The stream dies with the process — expected on teardown.
                }
            }, "jawata-host-drain-err");
            stderrDrain.setDaemon(true);
            stderrDrain.start();
        }

        if (!process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
            // Kill the tree, not just the parent: a wrapper script that spawned
            // the real tool leaves it running otherwise.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            drain.join(1000);
            return new HostProcessOutcome.TimedOut(captured.toString(), command.timeout());
        }
        // The child has exited, so the drain is at end of stream and about to
        // finish; a bounded join keeps a wedged reader from holding the caller.
        drain.join(5000);
        return new HostProcessOutcome.Completed(process.exitValue(), captured.toString());
    }

    @Override
    public Process start(HostCommand command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command.argv());
        if (command.workingDirectory() != null) {
            builder.directory(command.workingDirectory().toFile());
        }
        if (command.cleanEnvironment()) {
            builder.environment().clear();
        }
        if (!command.environment().isEmpty()) {
            Map<String, String> env = builder.environment();
            env.putAll(command.environment());
        }
        // start() hands the live process to its caller, so it must NOT touch
        // the streams beyond the merge choice. An earlier version discarded a
        // separate stderr here to stop an undrained pipe blocking the child —
        // and silently threw away the evidence of callers who READ stderr
        // (ForkedTestRunner's OutOfMemoryError became an unexplained exit 2).
        // Draining an unread stderr is run()'s job, where this class owns the
        // streams; it is never start()'s.
        builder.redirectErrorStream(command.mergeStderr());
        return builder.start();
    }

    /**
     * A reason a human can act on.
     *
     * <p>The JDK's message for a Windows spawn refusal is
     * {@code CreateProcess error=193, %1 is not a valid Win32 application} —
     * accurate and opaque. It almost always means a {@code .cmd} was executed
     * as if it were a binary, which is a spelling problem, not a maven
     * problem.</p>
     */
    private static String reasonFor(IOException e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        if (message.contains("error=193")) {
            return message + " — on Windows this usually means a .cmd/.bat was "
                + "launched as if it were a native executable";
        }
        return message;
    }
}
