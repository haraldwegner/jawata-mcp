package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * How one hired run used jawata, as Agent Control shows it beside the run (Stage 27 (o)): the run's calls split into jawata calls,
 * other calls and shell calls on Java, and each shell call on Java kept with the reason it declared.
 *
 * <p>A run whose calls were counted and not named has no figures at all. {@link #observed()} is false then and the map carries no
 * counts, because a zero would read as a run that never used jawata. A call to a tool of another MCP server is an other call even when
 * its bare name is a jawata tool's.</p>
 *
 * @param observed    whether the run's calls were named; false when some were counted and none named
 * @param jawata      calls to a jawata tool
 * @param other       every call that is neither a jawata call nor a call on Java outside jawata
 * @param shellOnJava the shell calls whose command names a Java file, and the brain's own reads and searches of Java files, each with the
 *                    reason it declared
 */
public record ToolUse(boolean observed, int jawata, int other, List<ShellOnJava> shellOnJava, java.util.List<CallOutcomes.Failed> failed) {

    /** The longest command kept on a card; the full text is in the run's own stream. */
    private static final int COMMAND_LIMIT = 300;

    private static final Pattern JAVA_FILE = Pattern.compile("\\.java\\b");
    private static final Pattern DECLARED = Pattern.compile("jawata-(?:fallback|author):\\s*([^\\n\"']*)");

    /**
     * One shell call on Java.
     *
     * @param command  what it ran, cut at 300 characters
     * @param declared the reason it carried as {@code jawata-fallback:} or {@code jawata-author:}, empty when it carried none
     */
    public record ShellOnJava(String command, Optional<String> declared) {
    }

    /**
     * @param calls       the calls the run's stream named, with their arguments
     * @param toolSteps   how many calls the run counted, named or not
     * @param isJawataTool whether a bare name is a tool of this engine
     */
    public static ToolUse of(List<ToolCall> calls, int toolSteps, Predicate<String> isJawataTool) {
        return of(calls, toolSteps, isJawataTool, List.of());
    }

    /**
     * As above, with the jawata calls the engine itself answered with an error (see {@link CallOutcomes}), which it knows whatever the run's
     * stream named: an unobserved run still lists them.
     */
    public static ToolUse of(List<ToolCall> calls, int toolSteps, Predicate<String> isJawataTool, List<CallOutcomes.Failed> failed) {
        if (calls.isEmpty() && toolSteps > 0) {
            return new ToolUse(false, 0, 0, List.of(), List.copyOf(failed));
        }
        int jawata = 0;
        int other = 0;
        List<ShellOnJava> shell = new ArrayList<>();
        for (ToolCall call : calls) {
            String bare = call.bareName();
            if (isJawata(call.name(), bare, isJawataTool)) {
                jawata++;
            } else if (RunEffects.isCliShell(bare) && JAVA_FILE.matcher(commandOf(call.arguments())).find()) {
                shell.add(shellOnJava(commandOf(call.arguments())));
            } else if (RunEffects.isCliFileReader(bare) && readsJava(bare, call.arguments())) {
                shell.add(shellOnJava(java.util.stream.Stream.of(bare, call.target(), call.arguments().path("type").asText(""))
                    .filter(part -> !part.isEmpty()).collect(java.util.stream.Collectors.joining(" "))));
            } else {
                other++;
            }
        }
        return new ToolUse(true, jawata, other, List.copyOf(shell), List.copyOf(failed));
    }

    /** What the card carries: the three counts and the shell calls, or only {@code observed:false} for a run nobody could read. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("observed", observed);
        List<Map<String, Object>> failedRows = new ArrayList<>();
        for (CallOutcomes.Failed f : failed) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tool", f.tool());
            row.put("code", f.code());
            if (!f.said().isEmpty()) {
                row.put("said", f.said());
            }
            failedRows.add(row);
        }
        if (!observed) {
            // the engine answered these calls itself, so an unreadable stream does not hide them
            if (!failedRows.isEmpty()) {
                map.put("failed", failedRows);
            }
            return map;
        }
        map.put("failed", failedRows);
        map.put("jawata", jawata);
        map.put("other", other);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ShellOnJava s : shellOnJava) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("command", s.command());
            row.put("declared", s.declared().orElse(null));
            rows.add(row);
        }
        map.put("shellOnJava", rows);
        return map;
    }

    private static boolean isJawata(String name, String bare, Predicate<String> isJawataTool) {
        if (name.startsWith("mcp__")) {
            return !name.equals(bare) && isJawataTool.test(bare);
        }
        return isJawataTool.test(bare);
    }

    private static ShellOnJava shellOnJava(String command) {
        Matcher declared = DECLARED.matcher(command);
        Optional<String> reason = declared.find() ? Optional.of(declared.group(1).strip()).filter(r -> !r.isEmpty()) : Optional.empty();
        String shown = command.length() > COMMAND_LIMIT ? command.substring(0, COMMAND_LIMIT) + "…" : command;
        return new ShellOnJava(shown, reason);
    }

    /**
     * Whether a native read or search looked at Java: a file, folder or file filter it names ends in {@code .java}, its type is java, or a
     * file-name search's pattern ends in {@code .java}. A content search's pattern is the text it looked for and says nothing about the files.
     */
    private static boolean readsJava(String tool, JsonNode arguments) {
        boolean named = java.util.stream.Stream.of("file_path", "filePath", "path", "glob", "include")
            .anyMatch(key -> JAVA_FILE.matcher(arguments.path(key).asText("")).find());
        return named || "java".equals(arguments.path("type").asText(""))
            || "glob".equalsIgnoreCase(tool) && JAVA_FILE.matcher(arguments.path("pattern").asText("")).find();
    }

    /** The shell command a call carried: the {@code command} or {@code cmd} argument, a list joined by spaces. */
    private static String commandOf(JsonNode arguments) {
        JsonNode command = arguments.has("command") ? arguments.get("command") : arguments.path("cmd");
        if (command.isArray()) {
            List<String> parts = new ArrayList<>();
            command.forEach(part -> parts.add(part.asText()));
            return String.join(" ", parts);
        }
        return command.asText("");
    }

    public ToolUse(boolean observed, int jawata, int other, List<ShellOnJava> shellOnJava) {
        this(observed, jawata, other, shellOnJava, java.util.List.of());
    }
}
