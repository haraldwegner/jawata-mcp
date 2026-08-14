package org.jawata.core.host;

import java.time.Duration;

/**
 * Sprint 28a (1b, step M6) — what happened when we tried to run something.
 *
 * <p>The distinction this type exists to make is between <b>could not start</b>
 * and <b>started and failed</b>. Before the boundary, every launch site
 * collapsed the two: a caught {@link java.io.IOException} and a non-zero exit
 * were both reported as "the tool failed". On Windows those are wildly
 * different diagnoses — {@code CreateProcess error=193} means the executable
 * was found but is not a valid application (typically: we tried to exec a
 * {@code .cmd} the way you exec a binary), while {@code ENOENT} means the
 * spelling was wrong (we looked for {@code mvn} where only {@code mvn.cmd}
 * exists). Telling a user "maven failed" when maven was never started sends
 * them to debug the wrong thing.</p>
 *
 * <p>Sealed, so a caller that adds a branch for one outcome is told by the
 * compiler about the others.</p>
 */
public sealed interface HostProcessOutcome {

    /** Whether the process ran to completion with exit code zero. */
    default boolean succeeded() {
        return this instanceof Completed c && c.exitCode() == 0;
    }

    /** The output captured so far, whatever the outcome — empty, never null. */
    String output();

    /** A sentence naming what happened, for a message a human will read. */
    String describe();

    /** The process ran to completion. {@code exitCode} may still be non-zero. */
    record Completed(int exitCode, String output) implements HostProcessOutcome {
        public Completed {
            output = output == null ? "" : output;
        }

        @Override
        public String describe() {
            return exitCode == 0 ? "completed" : "exited " + exitCode;
        }
    }

    /**
     * The process was never started — the executable could not be found, is not
     * executable here, or the OS refused the spawn.
     */
    record CannotLaunch(String executable, String reason) implements HostProcessOutcome {
        @Override
        public String output() {
            return "";
        }

        @Override
        public String describe() {
            return "could not start '" + executable + "': " + reason;
        }
    }

    /** The process started but had not finished when patience ran out; it was killed. */
    record TimedOut(String output, Duration waited) implements HostProcessOutcome {
        public TimedOut {
            output = output == null ? "" : output;
        }

        @Override
        public String describe() {
            return "did not finish within " + waited.toSeconds() + "s";
        }
    }
}
