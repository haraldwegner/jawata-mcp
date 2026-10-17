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
