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
 *   <li><b>A jawata tool</b> (the {@code mcp__<jawata server>__<tool>} form, or the bare name).
 *       The ENGINE's own flag decides: {@code Tool.changesCode()} — "a call can change source or
 *       build files", which defaults to the revertible flag and is overridden by the tools that
 *       write without an undo ({@code format}, {@code quick_fix}, {@code dependency}) — passed in
 *       as a function, so this class holds no copy of the tool list. A tool of a FOREIGN MCP
 *       server is never matched to a jawata tool; it is unknown. The sub-agent tool ({@code
 *       Task}) is unknown too, because what a sub-agent does does not appear in this stream.</li>
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
        "Read", "Grep", "Glob", "LS", "WebFetch", "WebSearch", "TodoWrite", "ToolSearch",
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
                                 List<String> shell, List<String> unknown, int unnamed, java.util.List<String> notOffered) {

        /** M11's measure, and only M11's: zero calls the engine classifies as changing code. */
        public boolean performedNoCodeChangingOperation() {
            return changesCode.isEmpty();
        }

        /**
         * Why the zero above cannot be trusted as "the run changed no code" — empty only when it
         * can. A shell, a call nobody could name and a name nobody could classify are the calls
         * the measure could not see; a code-changing call is the zero's own negation.
         */
        public List<String> incompleteBecause() {
            List<String> reasons = new ArrayList<>();
            if (!changesCode.isEmpty()) {
                reasons.add("the run made " + changesCode.size() + " code-changing call(s)");
            }
            if (!shell.isEmpty()) {
                reasons.add("a shell ran; what it did is not in its name");
            }
            if (!unknown.isEmpty()) {
                reasons.add("calls nobody here can classify: " + unknown);
            }
            if (unnamed > 0) {
                reasons.add(unnamed + " call(s) were counted and never named");
            }
            return List.copyOf(reasons);
        }

        public Classification(List<String> changesCode, List<String> readOnly, List<String> shell, List<String> unknown, int unnamed) {
            this(changesCode, readOnly, shell, unknown, unnamed, java.util.List.of());
        }
    }

    /**
     * As above, given what the brain's own CLI said it OFFERED when it started (claude's init event).
     *
     * <p>A call to a tool the CLI did not offer cannot have run: the CLI answers it with an error and
     * does nothing. Counting it as a call overstated what the brain DID — measured 2026-10-13, a PM
     * hired with no built-in tools attempted Write, Bash and Read by name and every one was refused,
     * yet the record read "the run made 1 code-changing call(s)". Those names are set aside in
     * {@link Classification#notOffered()}, listed rather than dropped. An ABSENT offer list (a brain
     * whose dialect does not read one) sets nothing aside: the old counting stands.</p>
     */
    public static Classification classify(List<String> names, int unnamedSteps,
            Function<String, Optional<Boolean>> jawataChanges, Optional<List<String>> offered) {
        List<String> changes = new ArrayList<>();
        List<String> readOnly = new ArrayList<>();
        List<String> shell = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> notOffered = new ArrayList<>();
        for (String raw : names) {
            String name = raw == null ? "" : raw;
            if (!name.isEmpty() && offered.isPresent() && !offered.get().contains(name)) {
                notOffered.add(name);
                continue;
            }
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
            List.copyOf(unknown), Math.max(0, unnamedSteps), List.copyOf(notOffered));
    }

    /**
     * {@code mcp__jawata-javata-dev__search_symbols} -> {@code search_symbols}.
     *
     * <p>Only a JAWATA server's prefix is stripped. A foreign server's tool keeps its full
     * qualified name, so it can never be matched to a jawata tool of the same bare name and
     * inherit that tool's flag — it falls through to {@code unknown}, which is where a tool
     * nobody here has classified belongs.</p>
     */
    static String bareName(String name) {
        if (!name.startsWith("mcp__")) {
            return name;
        }
        int serverEnd = name.indexOf("__", "mcp__".length());
        if (serverEnd < 0) {
            return name;
        }
        String server = name.substring("mcp__".length(), serverEnd);
        return server.startsWith("jawata") ? name.substring(serverEnd + 2) : name;
    }
}
