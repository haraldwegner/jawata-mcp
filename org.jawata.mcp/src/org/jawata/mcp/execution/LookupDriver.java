package org.jawata.mcp.execution;

import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The driver of the loop for lookups (Sprint 28h S14, D22): a brain that searches Java source with its own shell (grep, rg, find, sed, awk)
 * is refused where the engine answers its permission requests, because the compiler-aware tools answer the same question and the brain's
 * text search misses what a symbol search finds. It judges the call's input only; it knows no seat and no brain, so the one reading holds
 * for every brain that asks permission. A brain that never asks permission is bounded by the choke, as the plan says.
 *
 * <p>A search that declares why the tools cannot answer it ({@code jawata-fallback:} or {@code jawata-author:} in the command) is let
 * through, as the guest hook lets it through; a search over anything that is not Java source is not this driver's business.</p>
 */
final class LookupDriver {

    private static final Pattern SEARCH_TOOL = Pattern.compile("(?<![\\w./-])(?:grep|egrep|fgrep|rg|ag|ack|find|sed|awk)(?![\\w-])");

    /** A Java source file, or the flags that restrict a search to Java; {@code Foo.java.bak} is not one. */
    private static final Pattern JAVA = Pattern.compile("\\.java(?![\\w]|\\.\\w)|--type[= ]java\\b|(?<![\\w-])-t[= ]?java\\b");

    private static final Pattern DECLARATION = Pattern.compile("jawata-(?:fallback|author):");

    private LookupDriver() {
    }

    /**
     * The sentence that says why this call is refused and which tools to use, or empty when the call is not a shell search over Java source or
     * declares why the tools cannot answer it.
     *
     * @param rawInput the input the brain sent with its tool call; a {@code command} that is a string or a list of words
     */
    static Optional<String> refusal(JsonNode rawInput) {
        String command = commandIn(rawInput);
        if (command.isEmpty() || DECLARATION.matcher(command).find()) {
            return Optional.empty();
        }
        if (SEARCH_TOOL.matcher(command).find() && JAVA.matcher(command).find()) {
            return Optional.of("A text search over Java source is refused: search_symbols finds a symbol by name, find_references finds who uses it, "
                + "inspect lists a type's members, and analyze answers the rest. If they cannot answer this case, say why with jawata-fallback: in the command.");
        }
        return Optional.empty();
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
