package org.jawata.mcp.execution;

import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One tool call a brain made: its name and the arguments it passed.
 *
 * <p>The names alone say WHICH tools a run called, which is what "no code-changing call" needs. They
 * cannot say whether a worker did what it was told, and that is the PM's score: tokens per instruction
 * that RAN AS WRITTEN, which is a comparison of the instruction's tool and arguments with the call that
 * was made. This carries the arguments so that comparison is made on what the brain sent and not on
 * what it reported having sent.</p>
 *
 * @param name      the tool's name as the brain's CLI wrote it, possibly with an MCP prefix
 * @param arguments the call's input object; never null, an empty object when the block had none
 */
public record ToolCall(String name, JsonNode arguments) {

    /** The longest line a card shows for one call. */
    private static final int LINE_LIMIT = 300;

    /**
     * What the call looked at, as its input names it: what was searched for, then where; empty when it names neither. Cursor's agent wraps
     * the input of a call of a server's tool in {@code args} beside its {@code toolName}, and that wrapped input is the one read.
     */
    public String target() {
        JsonNode input = arguments.has("toolName") && arguments.path("args").isObject() ? arguments.get("args") : arguments;
        return java.util.stream.Stream.of("pattern", "file_path", "filePath", "path", "glob", "include")
            .map(key -> input.path(key).asText("")).filter(value -> !value.isEmpty())
            .collect(java.util.stream.Collectors.joining(" "));
    }

    /**
     * The call's line on a hire's card: the name it is shown under and, unless that name already says it, what it looked at, cut at
     * {@value #LINE_LIMIT} characters (28h z129).
     */
    public String line(String shown) {
        String target = target();
        String line = target.isEmpty() || shown.contains(target) ? shown : shown + " " + target;
        return line.length() > LINE_LIMIT ? line.substring(0, LINE_LIMIT) + "…" : line;
    }

    /**
     * One card line for each name a brain's stream listed: the call's own line where the stream carried a call for every name, and the names
     * alone where it did not, since a call could then be given another call's target.
     */
    public static java.util.List<String> lines(java.util.List<String> names, java.util.List<ToolCall> calls) {
        return calls.size() == names.size() ? calls.stream().map(call -> call.line(call.name())).toList() : names;
    }

    /** The name without the prefix of a jawata MCP server, which is how the engine knows its own tools. */
    public String bareName() {
        return RunEffects.bareName(name);
    }

    /**
     * Whether this call changes code: a jawata tool the engine flags, or one of the brain CLI's own
     * editors. The one reading of a name that the instruction score and the run's classification share.
     *
     * @param jawataChanges whether the engine flags a bare tool name as code-changing, empty for a name
     *                      that is not one of its tools
     */
    public boolean changesCode(Function<String, Optional<Boolean>> jawataChanges) {
        String bare = bareName();
        return jawataChanges.apply(bare).orElse(false) || RunEffects.isCliEditor(bare);
    }
}
