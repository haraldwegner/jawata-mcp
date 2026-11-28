package org.jawata.mcp.access;

import java.util.List;
import java.util.Optional;

/**
 * 28h z216: a command that commits or pushes is refused in a hired worker's shell. The rules for it (the checks passed, only the changed
 * files, the person's own order to release) live in jawata's git tool, which the leads hold; a shell would pass them by. Each command of the
 * line is judged by the program it starts, so a line that only mentions a commit is not one. A script it is handed can still do anything.
 */
public final class GitCommands {

    private static final String RULE = "git commit and git push are not run in a worker's shell: a lead commits through jawata's git tool once "
        + "the checks have passed, and pushing is the person's order. Report what you changed.";

    /** Options of git itself that take their value as the next word. */
    private static final List<String> VALUE_OPTIONS = List.of("-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path");

    private GitCommands() {
    }

    /** The sentence that says why this command is refused, or empty when it neither commits nor pushes. */
    public static Optional<String> refusal(String command) {
        return Shell.commands(command).stream().anyMatch(GitCommands::records) ? Optional.of(RULE) : Optional.empty();
    }

    private static boolean records(List<String> words) {
        int at = TestCommands.programAt(words);
        if (at >= words.size()) {
            return false;
        }
        String program = TestCommands.name(words.get(at));
        List<String> rest = words.subList(at + 1, words.size());
        if (program.equals("git") || program.equals("git.exe")) {
            int i = 0;
            while (i < rest.size() && rest.get(i).startsWith("-")) {
                i += VALUE_OPTIONS.contains(rest.get(i)) ? 2 : 1;
            }
            return i < rest.size() && (rest.get(i).equals("commit") || rest.get(i).equals("push"));
        }
        // a shell handed a line of its own runs that line
        int line = rest.indexOf("-c");
        return List.of("sh", "bash", "zsh", "dash", "ksh").contains(program) && line >= 0 && line + 1 < rest.size()
            && refusal(rest.get(line + 1)).isPresent();
    }
}
