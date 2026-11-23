package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code agy}, Google's Antigravity command-line agent, in print mode as a dialect (28h z54).
 *
 * <p>Everything below was measured on 2026-11-23 with {@code agy} 1.5.15 on a Google AI Pro account, by running it in an empty folder, and not
 * inferred from another vendor's shape.</p>
 *
 * <p><b>Its events.</b> {@code --output-format stream-json} prints one JSON object per line, each named by an {@code event} key: {@code init}
 * (the tools it offers and its permission mode), {@code step_update} (a step of the turn, the answer arriving as {@code text_delta} pieces) and
 * {@code result} (a {@code status} such as {@code SUCCESS}, the whole {@code response} and a {@code usage} block with snake_case counts). The
 * answer is taken from {@code result.response}; the deltas say the same thing in pieces and are not added to it.</p>
 *
 * <p><b>The prompt travels as {@code -p=<prompt>}.</b> Measured: {@code -p <prompt>} with the flags after it takes the next flag for the prompt
 * and ignores the real one, so the prompt is attached to the flag.</p>
 *
 * <p><b>It reports no dollar cost</b>, so {@link BrainLine#costUsd()} is always absent and a cost ceiling can never fire on this brain.</p>
 *
 * <p><b>Writes are not contained, by a mechanism or usably by an instruction.</b> Told to create a file, it did in 5 of 5 print-mode runs and in
 * 3 of 3 runs over ACP in its default mode, which asked no permission. {@code --mode plan} held in 8 of 8 runs but made no tool call in 2 of 2
 * ACP runs with a tool attached, as Claude's plan mode does, so a hire that must call the resident's tools cannot run in it.</p>
 *
 * <p><b>Tools.</b> It reads MCP servers only from config files, so a hire started on the command line cannot attach the resident. A hire that
 * needs the resident's tools runs over ACP, where the session names its servers and each tool is asked permission for (measured:
 * {@code Run mcp_<server>_<tool>}), which the seat's list answers.</p>
 */
public final class AntigravityDialect implements BrainDialect {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String binary;
    private final String model;

    /**
     * @param binary the program to run; {@code agy} when blank
     * @param model  the model to ask for, or null to take the program's own default
     */
    public AntigravityDialect(String binary, String model) {
        this.binary = binary == null || binary.isBlank() ? "agy" : binary;
        this.model = model;
    }

    @Override
    public String name() {
        return "agy";
    }

    @Override
    public List<String> command(String prompt, List<String> tools) {
        List<String> argv = new ArrayList<>(List.of(binary, "--output-format", "stream-json"));
        if (model != null && !model.isBlank()) {
            argv.add("--model");
            argv.add(model);
        }
        // Attached to its flag: the form that is not mistaken for a flag's value, so a brief with newlines or quotes needs no escaping.
        argv.add("-p=" + (prompt == null ? "" : prompt));
        return List.copyOf(argv);
    }

    @Override
    public WriteContainment writeContainment() {
        return WriteContainment.none("agy was measured on 2026-11-23 to write a file when told to: 5 of 5 print-mode runs and 3 of 3 ACP runs in its"
            + " default mode, which asked no permission. --mode plan held in 8 of 8 runs but made no tool call in 2 of 2 ACP runs with a tool"
            + " attached, so a hire that must call tools cannot run in it");
    }

    @Override
    public ToolAttachment toolAttachment() {
        return ToolAttachment.cannotBeTold("agy reads MCP servers only from config files (its global mcp_config.json and a workspace's"
            + " .agents/mcp_config.json), so a hire started on the command line cannot attach the resident. Hire it over ACP, where the session"
            + " names its servers and each tool is asked permission for, which the seat's list answers");
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
            return BrainLine.unreadable();
        }
        if (event == null || !event.isObject()) {
            return BrainLine.unreadable();
        }
        return switch (event.path("event").asText("")) {
            case "result" -> {
                JsonNode result = event.path("result");
                yield new BrainLine(OptionalDouble.empty(), StreamJsonEvents.textOf(result.path("response")), 0, true, List.of(),
                    Optional.empty(), true,
                    TokenUsage.read(result.path("usage"), "input_tokens", false, new String[] {"cache_read_tokens"}, "output_tokens",
                        "thinking_tokens", "agy: input_tokens counted without the cached part, which is added; whether agy's own figure includes"
                            + " it is not established"));
            }
            case "init" -> {
                List<String> offered = new ArrayList<>();
                event.path("init").path("tools").forEach(tool -> offered.add(tool.asText()));
                yield new BrainLine(OptionalDouble.empty(), Optional.empty(), 0, true, List.of(), Optional.of(List.copyOf(offered)));
            }
            // The answer arrives again, whole, in the result; the deltas are not added to it.
            default -> BrainLine.nothing();
        };
    }
}
