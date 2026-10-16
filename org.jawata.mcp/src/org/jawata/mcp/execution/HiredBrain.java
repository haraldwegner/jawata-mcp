package org.jawata.mcp.execution;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcesses;

/**
 * Hire a brain, drive it, bound it, reap it — and PROPOSE (Sprint 28g S9a M5a).
 *
 * <p>jawata has hired a child process before: {@code ForkedTestRunner} spawns a JVM and reads our
 * own event format back. This hires a coding-agent CLI, reads a shape somebody else chose through a
 * {@link BrainDialect}, and enforces the ceilings the process is ours to enforce. It never applies
 * anything: a hired run PROPOSES, because nobody is watching it.</p>
 *
 * <h2>The four defects this is written NOT to carry</h2>
 *
 * <p>A complete Rust implementation of this loop exists as reference
 * ({@code jawata-studio/src-tauri/src/runner.rs}) and has never had a production caller. Its own
 * module doc lists four defects, and each one is a design constraint here rather than a note:</p>
 *
 * <ol>
 *   <li><b>Its step ceiling is inert at every configured value.</b> The loop breaks on its first
 *       pass, so the iteration check can only fire when the ceiling is zero. Here the ceiling is
 *       tested as each line arrives, and {@link Outcome#stoppedBy()} names it when it fires — with
 *       a mutation that sets it to 1 and asserts the run stops.</li>
 *   <li><b>A reaped run books $0.00.</b> Cost arrives only on the terminal event, so a run killed
 *       on its wall clock never sees one — and reading that as zero means the cost ceiling cannot
 *       fire on exactly the expensive runs. {@link Outcome#costUsd()} is ABSENT in that case and
 *       {@link Outcome#sawTerminalCost()} says which it was. <b>A caller must not treat absent as
 *       zero</b>, and nothing here does it for them.</li>
 *   <li><b>A crashed adapter reads as "the model said nothing."</b> The reference sends stderr to
 *       the void and never inspects the exit status, so an adapter that died returns empty text and
 *       looks like silence. Here stderr is kept SEPARATE and reported, the exit code is reported,
 *       and {@link Outcome#saidNothing()} is deliberately false when the child failed — emptiness
 *       and failure are different answers.</li>
 *   <li><b>A malformed schedule is swallowed.</b> That one belongs to M5b, which is not this.</li>
 * </ol>
 *
 * <h2>Why stderr is SEPARATE here</h2>
 *
 * <p>Not from caution — {@code ForkedTestRunner} already learned it the expensive way, and its own
 * comment records that merging moved an {@code OutOfMemoryError}'s evidence out of the reporting
 * stream and turned it into an unexplained exit 2. A brain's stderr is where its crash goes, and a
 * dialect reads stdout; merging them would feed the dialect a stack trace and have it report
 * unreadable lines instead of a failure.</p>
 */
public final class HiredBrain {

    /** How much stderr is kept. Enough to name a crash, bounded so a chatty child cannot grow it. */
    private static final int STDERR_TAIL_LIMIT = 8_192;

    private HiredBrain() {
    }

    /**
     * What a hired run is allowed to consume.
     *
     * @param wallClock    how long the run may take before it is reaped
     * @param maxToolSteps the tool-call ceiling, or a non-positive value for none. NOT the CLI's own
     *     turn limit: one turn may hold several tool calls, which is exactly what a turn limit
     *     cannot see
     * @param maxCostUsd   the cost ceiling, or null for none. Only enforceable against a cost the
     *     brain actually reported — see defect 2 above
     */
    public record Ceilings(Duration wallClock, int maxToolSteps, Double maxCostUsd) {

        public static Ceilings of(Duration wallClock) {
            return new Ceilings(wallClock, 0, null);
        }
    }

    /**
     * What came back, with failure and silence kept apart.
     *
     * @param text            everything the brain said, joined
     * @param costUsd         what it reported spending. ABSENT means nothing reported a cost, which
     *     is NOT zero
     * @param toolSteps       tool calls counted as blocks across the whole run
     * @param unreadableLines lines the dialect could not read at all — non-zero means the child is
     *     probably not speaking the dialect we hired it under, which no other field would show
     * @param sawTerminalCost whether a terminal cost event arrived, so a caller can tell a genuine
     *     zero from a run that ended before reporting
     * @param timedOut        whether the wall clock reaped it
     * @param exitCode        the child's exit status, absent when it was reaped before exiting
     * @param stderrTail      the tail of its stderr, which is where a crash goes
     * @param stoppedBy       the ceiling that ended it, or absent when it ended on its own
     * @param toolNames       the names of the tools the brain called, in order. SHORTER than
     *     {@code toolSteps} when the dialect counts calls it cannot name (codex, copilot); the
     *     difference is calls nobody classified, and {@link #unnamedToolSteps()} says how many
     */
    public record Outcome(String text, OptionalDouble costUsd, int toolSteps, int unreadableLines,
                          boolean sawTerminalCost, boolean timedOut, OptionalInt exitCode,
                          String stderrTail, Optional<String> stoppedBy,
                          List<String> toolNames, java.util.Optional<java.util.List<String>> toolsOffered) {

        /** The shape every caller used before names were recorded. */
        public Outcome(String text, OptionalDouble costUsd, int toolSteps, int unreadableLines,
                       boolean sawTerminalCost, boolean timedOut, OptionalInt exitCode,
                       String stderrTail, Optional<String> stoppedBy) {
            this(text, costUsd, toolSteps, unreadableLines, sawTerminalCost, timedOut, exitCode,
                stderrTail, stoppedBy, List.of());
        }

        /**
         * Calls that were counted and never named. A classification built on {@link #toolNames}
         * alone would read these as harmless; a reader must be told they are UNKNOWN.
         */
        public int unnamedToolSteps() {
            return Math.max(0, toolSteps - toolNames.size());
        }

        /** Did it exit cleanly, on its own, inside every ceiling? */
        public boolean clean() {
            return !timedOut && stoppedBy.isEmpty()
                && exitCode.isPresent() && exitCode.getAsInt() == 0;
        }

        /**
         * Did the brain genuinely produce nothing — as opposed to failing?
         *
         * <p>The distinction the reference loses. An adapter that crashed returns empty text too,
         * and calling that "the model said nothing" sends a reader looking for a prompt problem
         * when the problem is that the binary died. Empty text with a failure is NOT silence.</p>
         */
        public boolean saidNothing() {
            return text.isEmpty() && clean();
        }

        public Outcome(String text, OptionalDouble costUsd, int toolSteps, int unreadableLines, boolean sawTerminalCost, boolean timedOut, OptionalInt exitCode, String stderrTail, Optional<String> stoppedBy, List<String> toolNames) {
            this(text, costUsd, toolSteps, unreadableLines, sawTerminalCost, timedOut, exitCode, stderrTail, stoppedBy, toolNames, java.util.Optional.empty());
        }
    }

    /**
     * Run one hired call to completion, a ceiling breach, or the wall clock.
     *
     * @param host    the process boundary — injected rather than reached for, so a test supplies it
     * @param dialect how this brain speaks
     * @param base    where the brain runs and with what environment. It carries no argv: what to run
     *     is the dialect's answer alone, and where to run it is the caller's
     * @param prompt  the brief
     * @param tools   the tools this run may use. Handed to the dialect, which attaches them if it
     *     CAN — and a dialect whose {@link BrainDialect#toolAttachment()} is not
     *     {@code PER_RUN} will ignore them. <b>That is why this method does not report the tools
     *     as attached:</b> what reaches the brain is the dialect's answer, so the hirer reads the
     *     attachment and says which of the three it got. Passing a list to a brain that cannot be
     *     told is not an error and must not read as success.
     */
    public static Outcome hire(HostProcesses host, BrainDialect dialect, HireSite base,
            String prompt, List<String> tools, Ceilings ceilings)
            throws IOException, InterruptedException {
        // The dialect owns argv (the prompt travels as one argument, unquoted); the caller owns
        // where and with what environment. Stderr is forced APART whatever the caller asked for:
        // a dialect reads stdout, and a merged stream would feed it a crash to misread.
        HostCommand command = new HostCommand(
            dialect.command(prompt, tools == null ? List.of() : tools), base.workingDirectory(),
            base.environment(), ceilings.wallClock(), false, base.cleanEnvironment());

        Process child = host.start(command);
        StringBuilder stderrTail = new StringBuilder();
        Thread stderrDrain = drainStderr(child, stderrTail);

        // The wall clock is a WATCHDOG rather than a read timeout: when it reaps, the child's
        // stdout reaches end-of-stream and the read loop below ends on its own. A bounded
        // readLine does not exist, and a reader thread would need its own interrupt dance.
        boolean[] reapedByClock = {false};
        Thread watchdog = new Thread(() -> {
            try {
                if (!child.waitFor(ceilings.wallClock().toMillis(), TimeUnit.MILLISECONDS)) {
                    reapedByClock[0] = true;
                    HostProcesses.reap(child);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "jawata-hire-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        List<String> said = new ArrayList<>();
        OptionalDouble cost = OptionalDouble.empty();
        boolean sawTerminalCost = false;
        int toolSteps = 0;
        List<String> toolNames = new ArrayList<>();
        // What the brain's own CLI said it offers, when its dialect reads that (claude's init event).
        Optional<List<String>> toolsOffered = Optional.empty();
        int unreadable = 0;
        String stoppedBy = null;

        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                BrainLine read = dialect.read(line);
                if (!read.parsed()) {
                    unreadable++;
                    continue;
                }
                // S16-18: a brain's CLOSING line restates the answer its earlier lines already gave —
                // a one-turn reply is in both, and collecting both made "READY" read "READY\nREADY".
                // It is the answer only when nothing else was said, so a run that produced no turn
                // text still reports the one thing it did say, an error message included.
                if (!(read.terminal() && !said.isEmpty())) {
                    read.text().ifPresent(said::add);
                }
                toolSteps += read.toolSteps();
                toolNames.addAll(read.toolNames());
                if (read.toolsOffered().isPresent()) {
                    toolsOffered = read.toolsOffered();
                }
                if (read.costUsd().isPresent()) {
                    cost = read.costUsd();
                    sawTerminalCost = true;
                }

                // THE CEILINGS, CHECKED AS THE RUN HAPPENS. The reference checks its step ceiling
                // once per loop pass and its loop always breaks on the first pass, so the ceiling
                // is inert at every value but zero. Checking here is what makes it a bound.
                if (ceilings.maxToolSteps() > 0 && toolSteps > ceilings.maxToolSteps()) {
                    stoppedBy = "tool steps: " + toolSteps + " over the ceiling of "
                        + ceilings.maxToolSteps();
                } else if (ceilings.maxCostUsd() != null && cost.isPresent()
                        && cost.getAsDouble() > ceilings.maxCostUsd()) {
                    stoppedBy = "cost: " + cost.getAsDouble() + " over the ceiling of "
                        + ceilings.maxCostUsd();
                }
                if (stoppedBy != null) {
                    HostProcesses.reap(child);
                    break;
                }
            }
        } catch (IOException e) {
            // The stream dies with the process — expected on a reap, and not a failure of the run.
        }

        watchdog.interrupt();
        boolean exited = child.waitFor(5, TimeUnit.SECONDS);
        // Belt: a ceiling breach reaped above, the clock reaped in the watchdog, and anything else
        // that left the child alive is reaped here rather than leaked.
        if (!exited) {
            HostProcesses.reap(child);
            child.waitFor(2, TimeUnit.SECONDS);
        }
        stderrDrain.join(2_000);

        OptionalInt exitCode = child.isAlive() ? OptionalInt.empty()
            : OptionalInt.of(child.exitValue());

        return new Outcome(String.join("\n", said), cost, toolSteps, unreadable, sawTerminalCost,
            reapedByClock[0], exitCode, stderrTail.toString(), Optional.ofNullable(stoppedBy),
            List.copyOf(toolNames), toolsOffered);
    }

    /**
     * Drain stderr into a bounded tail on its own thread.
     *
     * <p>Two jobs, and only one of them is reporting. A pipe holds about 64KB: a child that writes
     * past it with nobody reading blocks in {@code write()} for ever, so this must run whether or
     * not anyone reads the result. {@code SystemHostProcesses#run} drains for exactly that reason
     * and throws the bytes away; this keeps the tail, because a hired brain's crash goes here and
     * the reference's habit of discarding it is what makes a dead adapter look silent.</p>
     */
    private static Thread drainStderr(Process child, StringBuilder tail) {
        Thread drain = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    synchronized (tail) {
                        if (tail.length() < STDERR_TAIL_LIMIT) {
                            tail.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
                // The stream dies with the process — expected on teardown.
            }
        }, "jawata-hire-drain-err");
        drain.setDaemon(true);
        drain.start();
        return drain;
    }
}
