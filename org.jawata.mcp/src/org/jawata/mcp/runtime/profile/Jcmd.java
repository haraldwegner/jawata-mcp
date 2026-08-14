package org.jawata.mcp.runtime.profile;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 24 (D10) — the profiling floor's channel to a target JVM: the {@code jcmd}
 * binary that ships beside {@code java}. Unlike JDI (which needs the target to have
 * been STARTED with a debug agent, structurally and permanently), {@code jcmd}
 * attaches to any same-user JVM through the JDK's Dynamic Attach mechanism —
 * threads, heap, GC and native-memory diagnostics do not require debuggability.
 *
 * <p>Shelling out (rather than the internal {@code sun.tools.attach} attach-and-
 * execute API) mirrors how {@link org.jawata.mcp.runtime.JvmTargets#launch} already
 * starts {@code ${java.home}/bin/java} — a supported, exported command-line tool
 * instead of an unexported internal class.</p>
 */
public final class Jcmd {

    private static final Logger log = LoggerFactory.getLogger(Jcmd.class);

    private static final Path BINARY =
        Path.of(System.getProperty("java.home"), "bin", "jcmd");

    private Jcmd() {
    }

    /**
     * The raw diagnostic-command output — the process's own words, not
     * reinterpreted.
     */
    public static String run(long pid, String... command) throws JcmdException {
        List<String> full = new ArrayList<>();
        full.add(BINARY.toString());
        full.add(String.valueOf(pid));
        full.addAll(List.of(command));

        try {
            // The drain-then-wait discipline this method pioneered (Sprint-24
            // audit: reading to EOF first blocks until the child exits, so the
            // timeout could only be reached by a jcmd that had already
            // finished) now lives in the boundary, where every launch site gets
            // it. Three git sites and HeapHistogram still carried the original
            // bug because a careful fix in one file does not travel.
            HostProcessOutcome outcome = HostProcesses.system()
                .run(HostCommand.of(full).waitingAtMost(Duration.ofSeconds(30)));

            if (outcome instanceof HostProcessOutcome.TimedOut) {
                throw new JcmdException("jcmd " + pid + " " + String.join(" ", command)
                    + " timed out after 30s and was killed — the target is not answering the "
                    + "attach listener (stopped, wedged, or gone).");
            }
            if (outcome instanceof HostProcessOutcome.CannotLaunch cannotLaunch) {
                throw new JcmdException("Could not run jcmd against pid " + pid + ": "
                    + cannotLaunch.reason());
            }
            HostProcessOutcome.Completed completed = (HostProcessOutcome.Completed) outcome;
            String output = completed.output();
            if (completed.exitCode() != 0) {
                throw new JcmdException("jcmd " + pid + " " + String.join(" ", command)
                    + " failed (exit " + completed.exitCode() + "): " + output.strip());
            }
            log.debug("jcmd {} {} -> {} bytes", pid, String.join(" ", command), output.length());
            return output;
        } catch (JcmdException e) {
            throw e;
        } catch (Exception e) {
            throw new JcmdException("Could not run jcmd against pid " + pid + ": "
                + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        }
    }

    /** A jcmd invocation that could not be run, or that the target refused. */
    public static final class JcmdException extends Exception {
        private static final long serialVersionUID = 1L;

        public JcmdException(String message) {
            super(message);
        }

        public JcmdException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
