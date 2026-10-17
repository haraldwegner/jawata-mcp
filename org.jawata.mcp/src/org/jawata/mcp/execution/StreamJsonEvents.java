package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code stream-json} event shape, read in ONE place (Sprint 28g S16).
 *
 * <p>Two brains emit it: Claude Code under {@code --output-format stream-json}, and cursor-agent
 * under the same flag. Measured 2026-10-08 by running both — cursor's assistant event is
 * structurally identical to claude's, down to the content array and its block types:</p>
 *
 * <pre>
 * {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}
 * </pre>
 *
 * <h2>Why this is a class and not a second copy in CursorDialect</h2>
 *
 * <p>Because the architect seat's standing rule refuses a second implementation of a job the system
 * already does, and reading a content array of typed blocks is one job however many vendors emit it.
 * The two dialects differ in what they do with the TERMINAL event — claude publishes
 * {@code total_cost_usd}, cursor publishes token counts and no dollar figure at all — and that
 * difference stays in each dialect, where it is a fact about a vendor rather than a shared
 * algorithm.</p>
 *
 * <h2>The two traps it holds, so neither dialect can reintroduce one</h2>
 *
 * <p><b>The content array is not its first element.</b> A turn may open with a {@code thinking}
 * block, so reading {@code content[0]} silently loses the answer. Every {@code text} block is
 * collected and joined.</p>
 *
 * <p><b>Tool calls are BLOCKS, not events.</b> One turn may carry several {@code tool_use} blocks,
 * which is exactly what a {@code --max-turns} ceiling cannot see: twelve turns can be a hundred tool
 * calls. Counting blocks is what makes a step ceiling mean anything.</p>
 */
final class StreamJsonEvents {

    private StreamJsonEvents() {
    }

    /** A non-empty textual node, or absent — so an empty string never reads as an answer. */
    static Optional<String> textOf(JsonNode node) {
        return node.isTextual() && !node.asText().isEmpty()
            ? Optional.of(node.asText())
            : Optional.empty();
    }

    /** Every {@code text} block of this turn, joined — not {@code content[0]}. */
    static Optional<String> assistantText(JsonNode event) {
        JsonNode content = event.path("message").path("content");
        if (!content.isArray()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText(""))) {
                textOf(block.path("text")).ifPresent(parts::add);
            }
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", parts));
    }

    /**
     * The NAMES of this turn's tool calls, in order, one per {@code tool_use} block.
     *
     * <p>A count cannot be classified. "The PM made no code-changing call" is a claim about which
     * calls were made, and the step ceiling this file's {@code toolUseBlocks} feeds knows only how
     * many. A block with no name is recorded as {@code ""} so the list stays the same length as
     * the count and an unnamed call cannot quietly vanish from it.</p>
     */
    static List<String> toolUseNames(JsonNode event) {
        // ONE reader of the blocks: the names are the calls' names, so the two lists cannot disagree.
        List<String> names = new ArrayList<>();
        for (ToolCall call : toolUseCalls(event)) {
            names.add(call.name());
        }
        return names;
    }

    /**
     * This turn's tool calls with the arguments each passed, in order, one per {@code tool_use} block.
     *
     * <p>The same blocks {@link #toolUseNames} reads, so the two lists are the same length and agree on
     * order. A block with no {@code input} yields an empty object, never null, so a caller comparing
     * arguments never meets a missing node.</p>
     */
    static List<ToolCall> toolUseCalls(JsonNode event) {
        JsonNode content = event.path("message").path("content");
        List<ToolCall> calls = new ArrayList<>();
        if (!content.isArray()) {
            return calls;
        }
        for (JsonNode block : content) {
            if ("tool_use".equals(block.path("type").asText(""))) {
                JsonNode input = block.path("input");
                calls.add(new ToolCall(block.path("name").asText(""),
                    input.isObject() ? input : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
            }
        }
        return calls;
    }

    /** Tool calls in this turn, counted as BLOCKS rather than as turns. */
    static int toolUseBlocks(JsonNode event) {
        // The count IS the length of the names list — one reader of the blocks, so the count and
        // the names cannot disagree about how many calls the turn made.
        return toolUseNames(event).size();
    }
}
