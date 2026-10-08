package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Claude Code headless, as a dialect (Sprint 28g S9a M5a) — the first hired brain.
 *
 * <p>{@code claude -p <prompt> --output-format stream-json --verbose --max-turns N [--model m]}
 * emits one JSON object per line. Three event shapes matter:</p>
 *
 * <ul>
 *   <li>{@code {"type":"result","result":"…","total_cost_usd":0.0421}} — the terminal event, which
 *       carries the whole answer and the run's cost;</li>
 *   <li>{@code {"type":"assistant","message":{"content":[ … ]}}} — a turn, whose content is an
 *       ARRAY of blocks: {@code text}, {@code tool_use}, and possibly {@code thinking} first;</li>
 *   <li>anything else, which this dialect reads and has nothing to say about.</li>
 * </ul>
 *
 * <h2>Two things the reference gets wrong here, and this does not</h2>
 *
 * <p><b>The content array is not its first element.</b> A turn may open with a {@code thinking}
 * block, so taking {@code content[0]} silently loses the answer. Every {@code text} block is
 * collected and joined — the Rust reference gets this right and its own comment says why, which is
 * why the behaviour is kept rather than simplified.</p>
 *
 * <p><b>Tool calls are BLOCKS, not events.</b> One assistant turn may carry several
 * {@code tool_use} blocks, which is precisely what a {@code --max-turns} ceiling cannot see: twelve
 * turns can be a hundred tool calls. Counting blocks is what makes a step ceiling mean anything.</p>
 *
 * <h2>The cost reading is DELIBERATELY the reference's, and its defect is pushed up</h2>
 *
 * <p>Cost arrives only on the terminal event, because that is the only place this CLI reports it.
 * The reference's own defect list names the consequence: a run reaped on its wall clock never emits
 * that event, so the cost reads as 0.00 and the cost ceiling cannot fire on exactly the runs that
 * are expensive. This dialect does not paper over that — it reports ABSENT rather than zero, and
 * the driver above it is where "we never saw a cost" has to stay distinct from "the cost was zero".
 * Writing a guessed per-turn figure here would make the ceiling fire on a number nobody published.</p>
 */
public final class ClaudeCodeDialect implements BrainDialect {

    /** The step ceiling's companion on the CLI's own axis; turns are not tool calls. */
    public static final int DEFAULT_MAX_TURNS = 12;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String binary;
    private final int maxTurns;
    private final String model;

    public ClaudeCodeDialect() {
        this("claude", DEFAULT_MAX_TURNS, null);
    }

    /** @param model the model to ask for, or null to take the CLI's own default. */
    public ClaudeCodeDialect(String binary, int maxTurns, String model) {
        this.binary = binary == null || binary.isBlank() ? "claude" : binary;
        this.maxTurns = maxTurns > 0 ? maxTurns : DEFAULT_MAX_TURNS;
        this.model = model;
    }

    @Override
    public String name() {
        return "claude-code";
    }

    /**
     * {@code --allowedTools} is this CLI's own flag, read off its {@code --help} on 2026-10-08
     * rather than assumed: {@code --allowedTools, --allowed-tools <tools...>}. It takes the names
     * as separate arguments, which is why they are added individually and not joined — a
     * comma-joined single argument is the shape that silently attaches one tool named
     * "a,b,c".
     *
     * <p>An EMPTY list adds no flag at all, and the difference is not cosmetic: passing
     * {@code --allowedTools} with nothing after it would hand the CLI a flag with a missing value,
     * and asking for nothing in particular is not the same instruction as asking for an empty
     * set.</p>
     */
    @Override
    public List<String> command(String prompt, List<String> tools) {
        List<String> argv = new ArrayList<>(List.of(
            binary, "-p", prompt == null ? "" : prompt,
            "--output-format", "stream-json",
            "--verbose",
            "--max-turns", Integer.toString(maxTurns)));
        if (model != null && !model.isBlank()) {
            argv.add("--model");
            argv.add(model);
        }
        if (tools != null && !tools.isEmpty()) {
            argv.add("--allowedTools");
            for (String tool : tools) {
                if (tool != null && !tool.isBlank()) {
                    argv.add(tool);
                }
            }
        }
        return List.copyOf(argv);
    }

    @Override
    public ToolAttachment toolAttachment() {
        return ToolAttachment.perRun("--allowedTools (with --mcp-config / --strict-mcp-config"
            + " available to bound which servers are reachable at all)");
    }

    @Override
    public BrainLine read(String line) {
        if (line == null || line.isBlank()) {
            return BrainLine.nothing();
        }
        JsonNode event;
        try {
            event = MAPPER.readTree(line);
        } catch (Exception e) {
            // NOT an error and NOT silence: the line is a fact the driver can count.
            return BrainLine.unreadable();
        }
        if (event == null || !event.isObject()) {
            return BrainLine.unreadable();
        }
        String type = event.path("type").asText("");
        return switch (type) {
            case "result" -> new BrainLine(costOf(event),
                StreamJsonEvents.textOf(event.path("result")), 0, true);
            case "assistant" -> new BrainLine(OptionalDouble.empty(),
                StreamJsonEvents.assistantText(event), StreamJsonEvents.toolUseBlocks(event), true);
            // An event shape this dialect does not act on. Read, understood, nothing to say —
            // which is why it is `nothing()` and not `unreadable()`.
            default -> BrainLine.nothing();
        };
    }

    private static OptionalDouble costOf(JsonNode resultEvent) {
        JsonNode cost = resultEvent.path("total_cost_usd");
        return cost.isNumber() ? OptionalDouble.of(cost.asDouble()) : OptionalDouble.empty();
    }

}
