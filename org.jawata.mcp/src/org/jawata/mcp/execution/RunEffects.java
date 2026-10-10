package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What a hired run's tool calls DID, sorted by whether they change code (Sprint 28g M11).
 *
 * <p>M11 reads: <i>the PM seat performs 0 operations the engine classifies as changing code</i>,
 * measured by <i>operation classification over a PM run</i>. Until this existed no run could be
 * classified, because a run recorded how many tools were called and never which — and a count
 * answers none of "which of them changed code". The seat file's tool list makes the claim
 * structurally true on a brain that honours it; this is the measure, read off what the brain
 * actually did.</p>
 *
 * <h2>Three classes of call, because there are three kinds of name</h2>
 *
 * <ul>
 *   <li><b>A jawata tool</b> (any {@code mcp__<server>__<tool>} form, or the bare name). The
 *       ENGINE's own flag decides: {@code Tool.isRevertible()} — "this changes the workspace and
 *       has an undo" — passed in as a function, so this class holds no copy of the tool list.</li>
 *   <li><b>The brain CLI's own editors</b> ({@code Write}, {@code Edit}, {@code apply_patch}, …).
 *       These are not jawata's and the engine knows nothing of them, so they are a fixed set here
 *       and say plainly that they are.</li>
 *   <li><b>A shell.</b> Whether {@code Bash} changed code depends on the command, which the name
 *       does not carry. It is NEITHER code-changing NOR harmless: it is reported on its own line,
 *       because folding it into either hides the one call a reader most needs to look at.</li>
 * </ul>
 *
 * <p>Anything else is {@code unknown}, and so is every call a dialect counted and could not name.
 * The result is never read as "zero code-changing" unless the unknown and shell counts are also
 * visible beside it: a classification that silently drops what it could not classify reports a
 * clean bill for exactly the runs it understood least.</p>
 */
public final class RunEffects {

    /** The brain CLIs' own file editors, by the names the measured dialects emit. */
    private static final Set<String> CLI_EDITORS = Set.of(
        "Write", "Edit", "MultiEdit", "NotebookEdit", "apply_patch", "str_replace_editor",
        "str_replace_based_edit_tool", "create", "edit", "write_file", "replace");

    /** The brain CLIs' own shells. */
    private static final Set<String> CLI_SHELLS = Set.of(
        "Bash", "shell", "exec_command", "run_shell_command", "bash", "Shell");

    /** The brain CLIs' own readers and bookkeeping — they change nothing in the tree. */
    private static final Set<String> CLI_READERS = Set.of(
        "Read", "Grep", "Glob", "LS", "WebFetch", "WebSearch", "TodoWrite", "ToolSearch", "Task",
        "ExitPlanMode", "read_file", "view", "grep", "glob", "list_dir", "search", "ReadFile");

    private RunEffects() {
    }

    /**
     * @param changesCode  calls that change code: a jawata tool the engine flags revertible, or a
     *                     brain CLI's own editor
     * @param readOnly     calls known not to: jawata tools the engine does not flag, and a CLI's
     *                     readers
     * @param shell        calls to a shell, whose effect depends on a command this class cannot see
     * @param unknown      named calls matching none of the above
     * @param unnamed      calls a dialect counted and could not name
     */
    public record Classification(List<String> changesCode, List<String> readOnly,
                                 List<String> shell, List<String> unknown, int unnamed) {

        /** M11's measure, and only M11's: zero calls the engine classifies as changing code. */
        public boolean performedNoCodeChangingOperation() {
            return changesCode.isEmpty();
        }

        /**
         * Whether the zero above can be trusted as "the run changed no code". It cannot while a
         * shell was used, a call was unnamed, or a name was unclassifiable — those are the calls
         * the measure could not see.
         */
        public boolean zeroIsComplete() {
            return changesCode.isEmpty() && shell.isEmpty() && unknown.isEmpty() && unnamed == 0;
        }
    }

    /**
     * @param names         the names of the calls the run made, in order
     * @param unnamedSteps  calls counted and not named ({@code Outcome.unnamedToolSteps()})
     * @param jawataChanges for a name that is a jawata tool, whether the ENGINE flags it as
     *                      changing the workspace; empty when the name is not a jawata tool
     */
    public static Classification classify(List<String> names, int unnamedSteps,
            Function<String, Optional<Boolean>> jawataChanges) {
        List<String> changes = new ArrayList<>();
        List<String> readOnly = new ArrayList<>();
        List<String> shell = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : names) {
            String name = raw == null ? "" : raw;
            String bare = bareName(name);
            Optional<Boolean> engine = name.isEmpty() ? Optional.empty() : jawataChanges.apply(bare);
            if (engine.isPresent()) {
                (engine.get() ? changes : readOnly).add(name);
            } else if (CLI_EDITORS.contains(bare)) {
                changes.add(name);
            } else if (CLI_SHELLS.contains(bare)) {
                shell.add(name);
            } else if (CLI_READERS.contains(bare)) {
                readOnly.add(name);
            } else {
                unknown.add(name.isEmpty() ? "(unnamed block)" : name);
            }
        }
        return new Classification(List.copyOf(changes), List.copyOf(readOnly), List.copyOf(shell),
            List.copyOf(unknown), Math.max(0, unnamedSteps));
    }

    /** {@code mcp__jawata-javata-dev__search_symbols} -> {@code search_symbols}. */
    static String bareName(String name) {
        int at = name.lastIndexOf("__");
        return at >= 0 && name.startsWith("mcp__") ? name.substring(at + 2) : name;
    }
}
