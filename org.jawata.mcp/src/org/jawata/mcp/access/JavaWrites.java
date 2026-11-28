package org.jawata.mcp.access;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 28h z31 piece 4: a command that writes a Java file is refused, as the guest hook refuses it in a brain's own shell, because a text write of
 * Java misses what the compiler-aware tools see. The command names a {@code .java} file and takes a way to write: a redirect into one, a
 * program that writes, an in-place flag, code handed to a shell, or {@code git apply}. Only {@code jawata-author:} lets it through. It judges
 * the whole command and so refuses some that would only have read; it reads what is written, and a script it is handed can write anything.
 */
public final class JavaWrites {

    private static final String RULE = "Writing a Java file from the shell is refused. Change it with jawata's refactoring tools or your own "
        + "editor. If neither can do this case, say why with jawata-author: in the command.";

    private static final Pattern REDIRECT_INTO_JAVA = Pattern.compile(">>?\\s*[\"']?[^\\s\"';|&<>]*\\.java(?![A-Za-z0-9_.])");

    private static final Set<String> WRITERS = Set.of("python", "python2", "python3", "perl", "ruby", "node", "php", "tee", "dd", "ex", "ed",
        "patch", "mv", "cp", "ln", "install", "rsync", "truncate");

    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "dash", "ksh");

    private JavaWrites() {
    }

    /** The sentence that says why this command is refused, or empty when it writes no Java file or declares that no tool can. */
    public static Optional<String> refusal(String command) {
        if (!command.contains(".java") || command.contains("jawata-author:")) {
            return Optional.empty();
        }
        boolean writes = REDIRECT_INTO_JAVA.matcher(command).find() || Shell.commands(command).stream().anyMatch(JavaWrites::writer);
        return writes ? Optional.of(RULE) : Optional.empty();
    }

    private static boolean writer(List<String> words) {
        int at = TestCommands.programAt(words);
        if (at >= words.size()) {
            return false;
        }
        String program = TestCommands.name(words.get(at));
        List<String> rest = words.subList(at + 1, words.size());
        return switch (program) {
            case "sed" -> rest.stream().anyMatch(word -> word.startsWith("--in-place") || word.startsWith("-i"));
            case "awk", "gawk" -> rest.stream().anyMatch(word -> word.equals("inplace") || word.equals("-iinplace"));
            case "git" -> !rest.isEmpty() && rest.get(0).equals("apply");
            default -> WRITERS.contains(program) || SHELLS.contains(program) && rest.contains("-c");
        };
    }
}
