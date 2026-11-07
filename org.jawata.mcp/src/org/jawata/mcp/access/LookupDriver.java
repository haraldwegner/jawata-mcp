package org.jawata.mcp.access;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The driver of the loop for lookups (Sprint 28h S14, D22): a brain that searches Java source with its own shell (grep, rg, find, sed, awk)
 * is refused where the engine answers its permission requests, because the compiler-aware tools answer the same question and the brain's
 * text search misses what a symbol search finds. It judges the call's input only; it knows no seat and no brain, so the one reading holds
 * for every brain that asks permission. A brain that never asks permission is bounded by the choke, as the plan says.
 *
 * <p>The command is read as words the way a shell reads them ({@link Shell#words}), the one reader of a shell command this package has, so a
 * sentence inside quotes is one word and a commit message that mentions {@code grep} and a file name is not a search. The declaration
 * grammar is the guest hook's: {@code jawata-fallback:} in the command lets a READ through, and only {@code jawata-author:} lets a command
 * that rewrites a file in place ({@code sed -i}) through.</p>
 *
 * <p>The refusal travels as a rejected permission and nothing more: an ACP permission reply has no message field. The sentence that says
 * which tools to use is {@link #RULE}, which the hire prompt carries, because the prompt is the only channel the brain reads.</p>
 */
public final class LookupDriver {

    /** What a brain is told once, in the prompt it is hired with, so that the rejected permission has an explanation it has already read. */
    public static final String RULE = "A text search over Java source with your own shell (grep, rg, find, sed or awk on a .java file) is refused. "
        + "Ask the compiler-aware tools instead: search_symbols finds a symbol by name, find_references finds who uses it, inspect lists a type's "
        + "members, analyze answers the rest. If they cannot answer this one case, say why with jawata-fallback: in the command.";

    private static final Set<String> SEARCH_VERBS = Set.of("grep", "egrep", "fgrep", "rg", "ag", "ack", "find", "sed", "awk", "gawk");

    /** The programs for which {@code -i} means rewrite the file in place; for grep, rg and their kin it means ignore case, and opens like any read. */
    private static final Set<String> IN_PLACE_VERBS = Set.of("sed", "awk", "gawk");

    /** A Java source file as a word on its own; {@code Foo.java.bak} is not one, and a quoted sentence that ends in a file name is not one word. */
    private static final Pattern JAVA_FILE = Pattern.compile("\\S*\\.java");

    private static final Pattern FALLBACK = Pattern.compile("jawata-fallback:");
    private static final Pattern AUTHOR = Pattern.compile("jawata-author:");

    private LookupDriver() {
    }

    /**
     * The sentence that says why this call is refused, or empty when the call is not a shell search over Java source or declares why the
     * tools cannot answer it.
     *
     * @param rawInput the input the brain sent with its tool call; a {@code command} that is a string or a list of words
     */
    public static Optional<String> refusal(JsonNode rawInput) {
        String command = commandIn(rawInput);
        if (command.isEmpty()) {
            return Optional.empty();
        }
        List<String> words = Shell.words(command);
        if (words.stream().noneMatch(LookupDriver::isSearchVerb) || !namesJava(words)) {
            return Optional.empty();
        }
        boolean author = AUTHOR.matcher(command).find();
        boolean declared = author || FALLBACK.matcher(command).find();
        boolean rewrites = words.stream().anyMatch(word -> IN_PLACE_VERBS.contains(word.substring(word.lastIndexOf('/') + 1)))
            && words.stream().anyMatch(word -> word.equals("--in-place") || word.equals("-i") || word.startsWith("-i."));
        if (rewrites ? author : declared) {
            return Optional.empty();
        }
        return Optional.of(RULE);
    }

    private static boolean isSearchVerb(String word) {
        return SEARCH_VERBS.contains(word.substring(word.lastIndexOf('/') + 1));
    }

    /** Whether the command names a Java file, or restricts a search to Java by its flags. */
    private static boolean namesJava(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (JAVA_FILE.matcher(word).matches() || word.equals("--type=java") || word.equals("-tjava") || word.equals("--include=*.java")) {
                return true;
            }
            if ((word.equals("--type") || word.equals("-t")) && i + 1 < words.size() && words.get(i + 1).equals("java")) {
                return true;
            }
        }
        return false;
    }

    private static String commandIn(JsonNode rawInput) {
        if (rawInput == null) {
            return "";
        }
        JsonNode command = rawInput.path("command");
        if (command.isTextual()) {
            return command.asText();
        }
        if (command.isArray()) {
            StringBuilder words = new StringBuilder();
            for (JsonNode word : command) {
                words.append(words.length() == 0 ? "" : " ").append(word.asText());
            }
            return words.toString();
        }
        return "";
    }
}
