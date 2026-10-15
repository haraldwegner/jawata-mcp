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

    /**
     * The tool names this CLI's OWN HELP uses for writing and for running commands.
     *
     * <p>Taken from its examples rather than guessed: {@code copilot --allow-tool='write'} is
     * documented as "Allow all file editing", and {@code --allow-tool='shell(git:*)'} shows that
     * {@code shell} is the command-running tool. Denying both is the symmetric instruction, and
     * {@code --deny-tool} says of itself that the CLI "does not have permission to use" them and
     * "will not prompt".</p>
     */
    private static final List<String> WRITING_TOOLS = List.of("write", "shell");

    private final String binary;
    private final boolean disableBuiltinMcps;
    private final List<String> disableMcpServers;
    private final boolean readOnly;

    /** Built-in MCP servers off, no server disabled by name, READ-ONLY — see {@link #CopilotDialect}. */
    public CopilotDialect() {
        this("copilot", true, List.of(), true);
    }

    /** The three-argument form keeps its callers and is READ-ONLY, like every other brain's. */
    public CopilotDialect(String binary, boolean disableBuiltinMcps,
            List<String> disableMcpServers) {
        this(binary, disableBuiltinMcps, disableMcpServers, true);
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
    /**
     * @param readOnly pass {@code --deny-tool write --deny-tool shell}, which is this CLI's own
     *     way of saying a run may not edit or execute. DEFAULTED ON, for the reason every other
     *     dialect gives: an unattended run PROPOSES, and a caller who means to let it write passes
     *     false and says so.
     *     <p>It was added at C16 after a FALSE CLAIM OF MINE — the architect report for that
     *     checkpoint said this brain could not be told to stay read-only at all, "nothing", "can
     *     it be said? no", while its own {@code --help} offers {@code --deny-tool},
     *     {@code --excluded-tools} and {@code --available-tools} and names {@code write} as the
     *     file-editing tool.</p>
     *     <p><b>AND THE CONTROL THEN SHOWED THESE FLAGS ARE NOT WHAT CONTAINS IT.</b> Two runs of
     *     the same brief in scratch directories, one with these flags and one without: NEITHER
     *     wrote. Without them the model called {@code apply_patch}, was refused, reasoned about
     *     "needing approval due to security measures", routed around to {@code bash} with
     *     {@code printf 'no' > …}, was refused again, and answered <i>"I couldn't create the file
     *     because write permission was denied."</i> So this CLI's HEADLESS DEFAULT denies writes,
     *     and the containment a hire gets from it is the vendor's rather than ours.</p>
     *     <p>The flags stay because they state the intent explicitly and would still read as the
     *     hire's decision if that default changed or if a configuration elsewhere turned on
     *     {@code --allow-all-tools}. What is NOT claimed: that deny takes precedence over allow
     *     for TOOLS — the help documents that precedence for {@code --deny-url} only, and
     *     inferring it for tools from a URL example would be exactly the kind of inference in
     *     measurement language this checkpoint spent the day removing.</p>
     */
    public CopilotDialect(String binary, boolean disableBuiltinMcps,
            List<String> disableMcpServers, boolean readOnly) {
        this.binary = binary == null || binary.isBlank() ? "copilot" : binary;
        this.disableBuiltinMcps = disableBuiltinMcps;
        this.disableMcpServers = disableMcpServers == null ? List.of()
            : List.copyOf(disableMcpServers);
        this.readOnly = readOnly;
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
        // THE WRITE BOUND, FIRST among the optional flags because it is the one whose absence
        // means an unattended run can edit the directory it was pointed at. `--deny-tool` is
        // stronger than an allowlist for this purpose: it says the CLI "does not have permission
        // to use" the tool and "will not prompt", so there is no approval channel to leave open.
        if (readOnly) {
            for (String tool : WRITING_TOOLS) {
                argv.add("--deny-tool");
                argv.add(tool);
            }
        }
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

    /**
     * The CLI's own headless default denial is what refuses a write: measured 6 of 6 with this
     * hire's {@code --deny-tool} flags and 3 of 3 without them. The flags state the intent and do
     * not create the bound, and the answer says so.
     */
    @Override
    public WriteContainment writeContainment() {
        if (readOnly) {
            return WriteContainment.mechanism("the CLI's own headless default denial of write"
                + " (measured 6 of 6 with this hire's --deny-tool flags and 3 of 3 without them,"
                + " so the flags state the intent rather than create the bound)");
        }
        return WriteContainment.none("--deny-tool is not passed, by explicit request; the CLI's"
            + " headless default denied writes in 3 of 3 measured runs, which this hire does not"
            + " rely on");
    }

    @Override
    public ToolAttachment toolAttachment() {
        return ToolAttachment.perRun((readOnly
            ? "--deny-tool write --deny-tool shell, which this hire passes — though MEASURED, this"
                + " CLI's headless default already denies writes and that is what contains a run;"
                + " the flags state the intent rather than create it; plus "
            : "writes are not blocked BY THIS HIRE, by explicit request — note the CLI's own"
                + " headless default may still deny them; plus ")
            + "--available-tools (\"only these tools will be available to"
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
