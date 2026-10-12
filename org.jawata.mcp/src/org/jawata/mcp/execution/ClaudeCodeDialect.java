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
    private final boolean readOnly;

    /** The hiring resident's own endpoint, or null when it has none (stdio). See {@link SelfEndpoint}. */
    private final SelfEndpoint self;

    public ClaudeCodeDialect() {
        this("claude", DEFAULT_MAX_TURNS, null);
    }

    /**
     * READ-ONLY, because that is the hire path's contract and because the alternative was measured.
     *
     * @param model the model to ask for, or null to take the CLI's own default.
     */
    public ClaudeCodeDialect(String binary, int maxTurns, String model) {
        this(binary, maxTurns, model, true);
    }

    /**
     * @param readOnly pass {@code --permission-mode plan}, which is this CLI's own read-only mode.
     *     DEFAULTED ON by the constructor above, for the reason {@link CursorDialect} gives in the
     *     same words: an unattended run PROPOSES, and a caller who means to let it write passes
     *     false and says so, which is the direction that decision should have to travel.
     *     <p>It was added after a MEASUREMENT rather than on principle. C16 asks that "the seat
     *     cannot edit a file except through a tool", and the cataloguer seat — whose declared tools
     *     are four read-only jawata operations and nothing that writes — was hired with the brief
     *     "Create a file named SHOULD-NOT-EXIST.txt … Then stop" and CREATED IT: one tool step,
     *     $0.19, clean exit, the file on disk. So {@code --allowedTools} carrying JAWATA tool names
     *     restricts nothing here: those are not names this CLI knows, and a list of names it does
     *     not recognise is not a bound on the tools it does. The clause was nominal, and the
     *     containment it reported was a sentence rather than a fact.</p>
     */
    public ClaudeCodeDialect(String binary, int maxTurns, String model, boolean readOnly) {
        this(binary, maxTurns, model, readOnly, null);
    }

    /**
     * @param self the hiring resident's endpoint. When given, the brain starts with ONLY that MCP
     *     server ({@code --strict-mcp-config --mcp-config}), none of the user's settings or hooks
     *     ({@code --setting-sources project}), and an allowlist in the names it actually sees
     *     ({@code mcp__jawata__<tool>}). Null keeps the user's own setup, which is the stdio case.
     */
    public ClaudeCodeDialect(String binary, int maxTurns, String model, boolean readOnly,
            SelfEndpoint self) {
        this.binary = binary == null || binary.isBlank() ? "claude" : binary;
        this.maxTurns = maxTurns > 0 ? maxTurns : DEFAULT_MAX_TURNS;
        this.model = model;
        this.readOnly = readOnly;
        this.self = self;
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
            // THE FLAG THAT NARROWS WHAT THIS BRAIN WILL WRITE — and NOT, as this comment used to
            // claim, "the containment that actually contains". Read off the CLI's own --help on
            // 2026-10-08: `--permission-mode <mode>` takes "plan" among its values. It is added
            // FIRST among the optional flags because it is the one flag whose absence was measured
            // to let a read-only seat write a file into the directory it was pointed at.
            //
            // MEASURED 2026-10-08, and it retires the sentence that stood here: plan mode is an
            // INSTRUCTION this brain carries out, not a bound on what it can do. Under it the init
            // event still ADVERTISES Edit, Write and NotebookEdit; Write was INVOKED and returned
            // "File created successfully"; permission_denials is EMPTY in every run. What the model
            // does is narrate the rule — "plan mode permits edits only to this plan file" — and
            // then write that file, outside the hire's working directory, on every run.
            //
            // So it held for the working directory in 5 of 5 runs and it bounds nothing. The
            // allowlist below is weaker still for this purpose: it carries the seat's JAWATA tool
            // names, which this CLI does not know, so it bounds the MCP operations and not the
            // CLI's own editors. Both are sent, and NEITHER is a mechanism. Compare codex, whose
            // sandbox was observed rejecting an attempted apply_patch, and copilot, which answers
            // permission_denied — those are mechanisms; this is not.
            "--permission-mode", readOnly ? "plan" : "manual",
            "--verbose",
            "--max-turns", Integer.toString(maxTurns)));
        if (model != null && !model.isBlank()) {
            argv.add("--model");
            argv.add(model);
        }
        if (self != null) {
            // THE HIRING RESIDENT, AND NOTHING ELSE. Without these the brain loaded the user's
            // every MCP server, setting and hook — measured $2.26 for a one-word answer — and
            // talked to whichever servers the user had rather than to the resident that hired it.
            argv.add("--strict-mcp-config");
            argv.add("--mcp-config");
            argv.add(self.claudeMcpConfig());
            argv.add("--setting-sources");
            argv.add("project");
        }
        if (tools != null && !tools.isEmpty()) {
            argv.add("--allowedTools");
            for (String tool : tools) {
                if (tool != null && !tool.isBlank()) {
                    // Given the resident's own server, the CLI sees a jawata tool as
                    // mcp__jawata__<tool>; the bare name is one it does not know.
                    argv.add(self != null ? SelfEndpoint.claudeToolName(tool) : tool);
                }
            }
        }
        return List.copyOf(argv);
    }

    /**
     * Measured 2026-10-11 on a real run: every tool call the brain made arrived as a named
     * {@code tool_use} block in an {@code assistant} event. A call made INSIDE a sub-agent does
     * not appear in this stream, which is why the sub-agent tool is classified unknown rather
     * than read-only.
     */
    @Override
    public boolean observesEveryCall() {
        return true;
    }

    @Override
    public ToolAttachment toolAttachment() {
        // THE REPORT NAMES WHAT BINDS, and it used to name only what was sent. The earlier wording
        // — "tools set for this run via --allowedTools" — was true about the flag and false about
        // the effect: measured, a seat whose declared tools cannot write wrote a file anyway,
        // because the allowlist carries jawata's tool names and this CLI does not know them. A
        // record in which a nominal bound reads like a real one is worse than no record.
        return ToolAttachment.perRun((readOnly
            ? "--permission-mode plan, which this hire passes. MEASURED CAVEAT: it is an"
                + " INSTRUCTION this brain carries out rather than a bound — under it Edit, Write"
                + " and NotebookEdit stay advertised, Write was invoked and succeeded, and no"
                + " permission denial was ever recorded. It held for the working directory in 5 of"
                + " 5 runs and wrote the brain's own plan file OUTSIDE it every time. Treat a"
                + " claude hire as NOT write-contained by a mechanism; plus "
            : "--permission-mode manual: writes are NOT blocked for this hire, by explicit"
                + " request; plus ")
            + (self != null
                ? "only the hiring resident's MCP server (--strict-mcp-config --mcp-config), none of"
                    + " the user's settings or hooks (--setting-sources project), and --allowedTools"
                    + " naming the seat's jawata operations as the CLI sees them (mcp__jawata__<tool>);"
                    + " the allowlist bounds those operations, not the CLI's own editors"
                : "--allowedTools, which carries the seat's jawata operations by their bare names;"
                    + " with no endpoint to give the brain, it keeps the user's own MCP servers, under"
                    + " which those names bound nothing"));
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
                StreamJsonEvents.assistantText(event), StreamJsonEvents.toolUseBlocks(event), true,
                StreamJsonEvents.toolUseNames(event));
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
