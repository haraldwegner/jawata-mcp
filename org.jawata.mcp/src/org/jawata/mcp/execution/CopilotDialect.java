package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GitHub Copilot CLI headless, as a dialect (Sprint 28g S16, D36) — <b>the brain that could not run
 * at all until it was told which tools to attach.</b>
 *
 * <p>Measured 2026-10-08 from this CLI's own {@code --help} and then by RUNNING it. Its event stream
 * is NOT the {@code stream-json} shape claude and cursor share, so nothing here is borrowed from
 * {@link StreamJsonEvents}: {@code --output-format json} means <i>JSONL, one JSON object per
 * line</i>, its text arrives as a plain STRING rather than a content array, and its tool calls are a
 * named array on the message rather than typed blocks inside it.</p>
 *
 * <h2>The measurement that justifies D36, rather than illustrating it</h2>
 *
 * <p>The first run of this brain on the development machine <b>never reached the model</b>. It
 * loaded the machine's three jawata MCP servers, then emitted
 * {@code session.warning / compaction_static_context_blocked}: <i>"Static context is using 126% of
 * available input tokens, leaving too little room for the conversation."</i> A turn opened and closed
 * in three milliseconds, {@code usage.premiumRequests} was 0, {@code totalApiDurationMs} was 0, and
 * it exited 1 having said nothing. With the servers disabled by name it answered in one turn.</p>
 *
 * <p>So for this brain the tool set is not a nicety: <b>a hirer who cannot bound what loads cannot
 * hire it.</b> That is D36's requirement measured rather than argued, and it is why both levers are
 * exposed here — {@code --available-tools}, whose own help reads <i>"Only these tools will be
 * available to the model"</i>, and the MCP-server disable list.</p>
 *
 * <h2>Why {@code --available-tools} and not {@code --allow-tool}</h2>
 *
 * <p>Both are per-run flags and they are different promises. {@code --allow-tool} suppresses the
 * permission PROMPT for a tool the model still has; {@code --available-tools} bounds what the model
 * HAS. A report naming the first while the caller meant the second would overstate the containment,
 * so {@link #toolAttachment()} names the one that actually restricts availability. The variadic form
 * is confirmed by a run rather than read off the help's notation: {@code --available-tools shell
 * view} was accepted, exit 0, with the model reached.</p>
 *
 * <h2>IT PUBLISHES NO DOLLAR COST — and what it publishes instead is not one</h2>
 *
 * <p>Its terminal event carries {@code usage.premiumRequests}, measured at {@code 0.33} for a
 * one-word answer: a FRACTIONAL COUNT OF REQUESTS against a subscription quota. That is not a
 * currency amount and must never be reported as one — publishing it through
 * {@link BrainLine#costUsd()} would be a unit error wearing the contracted measure's name, and a
 * {@code maxCostUsd} ceiling would then fire on a number nobody charged. So the cost is always
 * ABSENT from this dialect, which {@link HiredBrain.Outcome#sawTerminalCost()} already keeps
 * distinct from zero, and {@link HiredBrain.Ceilings#maxCostUsd()} cannot fire on this brain.</p>
 */
public final class CopilotDialect implements BrainDialect {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String binary;
    private final boolean disableBuiltinMcps;
    private final List<String> disableMcpServers;

    /** Built-in MCP servers off, no server disabled by name — see {@link #CopilotDialect}. */
    public CopilotDialect() {
        this("copilot", true, List.of());
    }

    /**
     * @param disableBuiltinMcps whether to pass {@code --disable-builtin-mcps}. Default true: the
     *     built-in set is a cost in context this hire did not ask for, and context is the measured
     *     reason this brain can fail to start at all.
     * @param disableMcpServers MCP servers to disable BY NAME. Empty by default and deliberately
     *     not guessed: which servers a machine configures is not a fact this class can know, and the
     *     one that broke the first run was a local server nothing in this product could enumerate.
     *     A caller whose run reports {@code compaction_static_context_blocked} fills this in.
     */
    public CopilotDialect(String binary, boolean disableBuiltinMcps, List<String> disableMcpServers) {
        this.binary = binary == null || binary.isBlank() ? "copilot" : binary;
        this.disableBuiltinMcps = disableBuiltinMcps;
        this.disableMcpServers = disableMcpServers == null ? List.of()
            : List.copyOf(disableMcpServers);
    }

    @Override
    public String name() {
        return "copilot";
    }

    /**
     * {@code --available-tools} takes the names as separate arguments — confirmed by a run, not
     * inferred — so they are added individually and never joined. An EMPTY list adds no flag at
     * all: asking for nothing in particular leaves the brain its configured tools, whereas
     * {@code --available-tools} with nothing after it is a flag with a missing value.
     */
    @Override
    public List<String> command(String prompt, List<String> tools) {
        List<String> argv = new ArrayList<>(List.of(
            binary, "-p", prompt == null ? "" : prompt,
            // Measured: 'json' here means JSONL, one object per line — NOT a single JSON document.
            "--output-format", "json"));
        if (disableBuiltinMcps) {
            argv.add("--disable-builtin-mcps");
        }
        for (String server : disableMcpServers) {
            if (server != null && !server.isBlank()) {
                argv.add("--disable-mcp-server");
                argv.add(server);
            }
        }
        if (tools != null && !tools.isEmpty()) {
            argv.add("--available-tools");
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
        return ToolAttachment.perRun("--available-tools (\"only these tools will be available to"
            + " the model\"), with --disable-builtin-mcps and --disable-mcp-server bounding which"
            + " servers load at all — which on this machine decided whether the brain could run");
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
        JsonNode data = event.path("data");
        return switch (event.path("type").asText("")) {
            // THE COMPLETED MESSAGE, and only it. `assistant.message_delta` carries the same answer
            // in fragments: collecting both would report the text twice.
            case "assistant.message" -> new BrainLine(OptionalDouble.empty(),
                StreamJsonEvents.textOf(data.path("content")), toolRequests(data), true);
            // THE TERMINAL EVENT, carrying NO dollar cost — see the class javadoc. Its `usage`
            // is read for nothing on purpose: premiumRequests is a request count, and the one
            // honest thing to do with a figure in the wrong unit is to not publish it.
            case "result" -> BrainLine.nothing();
            // THE WARNING THAT DECIDED THE FIRST RUN, carried into the record rather than dropped.
            // It is NOT the brain's answer and is labelled so a reader cannot mistake it for one —
            // but a run whose model was never called must not render as a brain that chose to say
            // nothing, and this line is the only place that distinction exists on this dialect.
            case "session.warning" -> new BrainLine(OptionalDouble.empty(),
                warning(data), 0, true);
            default -> BrainLine.nothing();
        };
    }

    /** Tool calls this message asked for, counted from the array the message itself carries. */
    private static int toolRequests(JsonNode data) {
        JsonNode requests = data.path("toolRequests");
        return requests.isArray() ? requests.size() : 0;
    }

    private static Optional<String> warning(JsonNode data) {
        String kind = data.path("warningType").asText("");
        String message = data.path("message").asText("");
        if (kind.isEmpty() && message.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("[copilot warning" + (kind.isEmpty() ? "" : ": " + kind) + "] "
            + message);
    }
}
