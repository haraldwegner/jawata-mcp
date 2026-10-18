package org.jawata.mcp.execution;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A seat definition's own declaration of WHICH BRAIN it is hired as, and under what bounds
 * (Sprint 28g S16, D37).
 *
 * <p>Until this existed, the hire path could bound a run and could not learn those bounds from
 * anywhere: {@code HiredRun} passed an EMPTY tool list with a comment saying so, and a seat's
 * {@code tools:}, {@code cost_budget_usd:} and {@code max_tool_steps:} were read by nothing. This
 * reads the frontmatter a seat already carries and answers with the three things a hire needs — a
 * dialect, a tool list, and ceilings.</p>
 *
 * <h2>{@code brain:} IS ITS OWN FIELD, and deriving it from {@code model:} would be WRONG</h2>
 *
 * <p>Every seat shipped until studio 4.4.1 said {@code model: claude-sonnet-5} or
 * {@code claude-haiku-4-5}, which made "read the brain off the model's prefix" look obviously right. <b>It is measurably wrong.</b>
 * Running copilot on 2026-10-08 produced {@code session.auto_mode_resolved} with
 * {@code "chosenModel":"claude-haiku-4.5"} — a CLAUDE MODEL running on the COPILOT BRAIN, chosen by
 * copilot's own router from its {@code availableModels}. A seat naming that model would have been
 * routed to {@link ClaudeCodeDialect} and spawned the wrong CLI. The model is which weights answer;
 * the brain is whose process we start, and they are not the same question.</p>
 *
 * <p>Absent, the brain defaults to Claude Code, because that is what every seat in the tree means
 * today — they were written for it and name its flags nowhere else. An UNKNOWN brain is REFUSED
 * rather than defaulted: a seat asking for a brain this product has no dialect for must not quietly
 * be hired as a different one, which is the failure the default is otherwise one typo away from.</p>
 *
 * <h2>A DECLARED COST BUDGET IS NOT NECESSARILY AN ENFORCEABLE ONE, and this says which</h2>
 *
 * <p>Measured by running all four brains: <b>only Claude Code publishes a dollar cost.</b> cursor
 * and codex publish token counts; copilot publishes {@code usage.premiumRequests}, a fractional
 * count of subscription requests. So a seat declaring {@code cost_budget_usd: 2.0} and
 * {@code brain: cursor} has written down a bound that can never fire — the ceiling is carried
 * faithfully into {@link HiredBrain.Ceilings} and nothing will ever compare against it, because
 * nothing reports a cost to compare.</p>
 *
 * <p>{@link #budgetReport()} is what keeps that from being silent. It is not a refusal: the seat's
 * declaration is legitimate and may become enforceable the day a vendor publishes a cost, and
 * refusing the hire would make this product's reading of a vendor's output into a veto over a
 * human's written intent. It is a REPORT, for the same reason {@link ToolAttachment} reports a brain
 * that cannot be told which tools rather than dropping it from the count.</p>
 *
 * @param seat     the seat's name, which is what a journal row and a proposal are attributed to
 * @param brain    which CLI to spawn
 * @param model    which weights to ask that CLI for, or null to take its own default
 * @param tools    the tools this seat may use — handed to the dialect, which attaches them if it CAN
 * @param ceilings what the run may consume, assembled from the seat's own declared limits
 * @param stance   the seat's own rules — the markdown body below its frontmatter — which
 *     {@link #prompt(String)} sends ahead of the brief. Empty when the seat has none.
 * @param answerGates the checks the seat declares over its OWN answer ({@code answer_gates:} in
 *     the frontmatter), each run as a step of the plan whose result lands in the run's record.
 *     Today one is known: {@code upward_message}. Empty when the seat declares none.
 * @param enabled whether the seat is switched ON — false when its file says {@code enabled: false},
 *     which studio writes when the user turns the seat off. A hire of an off seat is refused by the
 *     hire tool; this record only carries the fact.
 * @param script the executable a {@link Brain#SCRIPT} seat runs, from its {@code script:} line;
 *     null for every other brain, which {@link #read(String)} enforces in both directions.
 * @param acceptsUncontainedWrites the seat's own word, {@code accepts_uncontained_writes: true},
 *     that it may be hired on a brain whose writes no mechanism refuses (S16-38). Carried on the
 *     hire so that the refusal lives where every path that hands the hire a seat passes:
 *     {@link HiredRun#issue(SeatHire, HireSite, String, java.util.List,
 *     HiredRun.GateRunner, java.util.List)} enforces it, whichever caller — the hire tool, a
 *     scheduler — reaches it with a seat.
 */
public record SeatHire(String seat, Brain brain, String model, List<String> tools,
                       HiredBrain.Ceilings ceilings, String stance, List<String> answerGates, boolean enabled, String script,
                       boolean acceptsUncontainedWrites, boolean changesCode, boolean readsFiles) {

    /** The brains this product has a dialect for. A seat may name no other. */
    public enum Brain {
        CLAUDE_CODE("claude-code", "claude"),
        CURSOR("cursor", "cursor-agent"),
        COPILOT("copilot"),
        CODEX("codex"),
        /**
         * Any executable, named by the seat's {@code script:} line — {@link ScriptDialect}, the
         * escape hatch for a CLI with no dialect of its own (S16-6).
         */
        SCRIPT("script");

        private final List<String> spellings;

        Brain(String... spellings) {
            this.spellings = List.of(spellings);
        }

        /**
         * The brain a seat means by this word, or absent.
         *
         * <p>Several spellings per brain because a seat is written by a human who will reasonably
         * write either the product's name or its binary's — {@code cursor} and
         * {@code cursor-agent} are the same brain, and refusing one of them would be a rule about
         * our vocabulary rather than about theirs. Case and surrounding space are ignored for the
         * same reason.</p>
         */
        public static Optional<Brain> named(String word) {
            if (word == null || word.isBlank()) {
                return Optional.empty();
            }
            String wanted = word.strip().toLowerCase(Locale.ROOT);
            for (Brain brain : values()) {
                if (brain.spellings.contains(wanted)) {
                    return Optional.of(brain);
                }
            }
            return Optional.empty();
        }
    }

    /** How long a seat may run when it declares no {@code ttl_secs}. */
    private static final Duration DEFAULT_WALL_CLOCK = Duration.ofMinutes(10);

    public SeatHire {
        if (seat == null || seat.isBlank()) {
            throw new IllegalArgumentException("a seat with no name cannot be reported on");
        }
        if (brain == null) {
            throw new IllegalArgumentException("a hire must say which brain it starts: a run whose"
                + " brain is unknown cannot be given a dialect, and a dialect guessed from the"
                + " model name is how a copilot seat gets spawned as claude");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        stance = stance == null ? "" : stance.strip();
        answerGates = answerGates == null ? List.of() : List.copyOf(answerGates);
        if (ceilings == null) {
            throw new IllegalArgumentException("a hire with no ceilings is an unbounded run, which"
                + " is the thing this whole path exists to prevent");
        }
    }

    /**
     * Read a seat definition's frontmatter.
     *
     * @param markdown the seat file's whole text
     * @throws IllegalArgumentException if the file declares a brain this product cannot start, or
     *     declares no name
     */
    public static SeatHire read(String markdown) {
        Map<String, String> front = Frontmatter.frontmatter(markdown);

        String declared = front.get("brain");
        Brain brain = declared == null || declared.isBlank()
            ? Brain.CLAUDE_CODE
            : Brain.named(declared).orElseThrow(() -> new IllegalArgumentException(
                "seat declares brain '" + declared + "', which this product has no dialect for."
                    + " Known: claude-code, cursor, copilot, codex, script. NOT defaulted on purpose"
                    + " — a seat hired as a brain it did not ask for is worse than one that refuses"
                    + " to start"));

        // S16-6: the script escape hatch names its executable here, the one place an executable
        // may be named (S16-12 removed the hire tool's). Both mismatches are refused: a script
        // brain with nothing to run cannot start, and a script line on another brain would be
        // IGNORED — the inert-input shape that ran the real claude when a stand-in was meant.
        String script = Frontmatter.emptyToNull(front.get("script"));
        if (brain == Brain.SCRIPT && script == null) {
            throw new IllegalArgumentException("seat declares brain 'script' and no 'script:' line"
                + " naming the executable to run, so there is nothing to start");
        }
        if (brain != Brain.SCRIPT && script != null) {
            throw new IllegalArgumentException("seat declares script '" + script + "' on brain '"
                + brain.spellings.get(0) + "', which would ignore it — set brain: script to run it,"
                + " or remove the line");
        }

        // A switch a dialect cannot carry is refused, as a script line on another brain is: the seat
        // would be told its job is to change code and meet a brain that never heard of it.
        boolean changesCode = Frontmatter.readSwitch("changes_code", front.get("changes_code"), false);
        if (changesCode && brain != Brain.CLAUDE_CODE && brain != Brain.CURSOR) {
            throw new IllegalArgumentException("seat declares changes_code: true on brain '"
                + brain.spellings.get(0) + "', whose dialect has no write-capable mode to select, so the"
                + " declaration would be IGNORED. Only claude-code and cursor carry it");
        }
        // The same refusal for the same reason: the read-only built-ins are a claude start-up
        // choice, and a seat on another brain would be told it can read and never be offered it.
        boolean readsFiles = Frontmatter.readSwitch("reads_files", front.get("reads_files"), false);
        if (readsFiles && brain != Brain.CLAUDE_CODE) {
            throw new IllegalArgumentException("seat declares reads_files: true on brain '"
                + brain.spellings.get(0) + "', whose dialect has no built-in file reader to offer, so"
                + " the declaration would be IGNORED. Only claude-code carries it");
        }
        if (readsFiles && changesCode) {
            throw new IllegalArgumentException("seat declares both reads_files and changes_code: a"
                + " seat that changes code does so through the engine's tools, and offering it the"
                + " CLI's own file tools as well would open the editors the hire keeps closed");
        }

        return new SeatHire(
            front.getOrDefault("name", ""),
            brain,
            Frontmatter.emptyToNull(front.get("model")),
            Frontmatter.list(front.get("tools")),
            new HiredBrain.Ceilings(
                Frontmatter.seconds(front.get("ttl_secs")).orElse(DEFAULT_WALL_CLOCK),
                Frontmatter.integer(front.get("max_tool_steps")).orElse(0),
                // null, not 0.0 — an undeclared budget is NO ceiling, and zero would be a ceiling
                // of nothing that fired on the first reported cent.
                Frontmatter.decimal(front.get("cost_budget_usd")).orElse(null)),
            Frontmatter.body(markdown),
            Frontmatter.list(front.get("answer_gates")),
            Frontmatter.readSwitch("enabled", front.get("enabled"), true),
            script,
            // The same strict reader as every other switch: this one used to read loosely, so a
            // value of yes was silently no. Absent means NO — the default is the refusal.
            Frontmatter.readSwitch("accepts_uncontained_writes", front.get("accepts_uncontained_writes"), false),
            changesCode,
            readsFiles);
    }

    /**
     * The resident's code-changing tools this seat's brain must not be offered (REL44-6): all of them
     * when the seat does not change code, even one its tool list names, and only the ones it did not
     * name when it does. The seat answers for itself, so the hire does not weigh its tool list.
     *
     * @param engineCodeChangingTools the names of every tool the engine flags as changing code
     * @return those names, sorted
     */
    public List<String> toolsToHide(Collection<String> engineCodeChangingTools) {
        return engineCodeChangingTools.stream()
            .filter(name -> !changesCode || !tools.contains(name))
            .sorted()
            .toList();
    }

    /**
     * What the hired brain is asked: the seat's own rules, then the brief.
     *
     * <p>Until 4.4.1 a hire sent the brief ALONE. The seat file was read for its frontmatter — brain,
     * model, tools, ceilings — and its body, the stance that says what the seat is and what it must
     * refuse, never reached the brain: a hired cataloguer ran as a generic agent holding the PM's
     * brief. The seat's file IS the hire, so its rules are part of what is asked.</p>
     */
    public String prompt(String brief) {
        String asked = brief == null ? "" : brief.strip();
        if (stance.isEmpty()) {
            return asked;
        }
        return stance + "\n\n---\n\n## Your brief\n\n" + asked;
    }

    /**
     * The dialect for this seat's brain, given the hiring resident's endpoint when it has one — see
     * {@link SelfEndpoint} for what a Claude hire does with it. Null is the stdio case.
     *
     * @param hiddenTools the resident's tools to take away from a brain that attaches the resident
     *     (REL44-6) — the code-changing ones the seat did not name; empty for none. A brain that
     *     does not attach the resident has nothing to hide from and ignores it.
     */
    public BrainDialect dialect(SelfEndpoint self, List<String> hiddenTools) {
        return switch (brain) {
            case CLAUDE_CODE -> new ClaudeCodeDialect("claude", maxTurns(), model, !changesCode, self,
                hiddenTools, readsFiles ? ClaudeCodeDialect.READ_ONLY_BUILT_INS : List.of());
            case CURSOR -> new CursorDialect("cursor-agent", !changesCode, model);
            // Neither of these takes a model from this product: copilot ROUTES its own (its
            // auto_mode_resolved event reports which it chose and why), and codex's is set in its
            // config, out of band. Passing one would be a flag invented rather than measured.
            case COPILOT -> new CopilotDialect();
            case CODEX -> new CodexDialect();
            case SCRIPT -> new ScriptDialect(script, List.of());
        };
    }

    /**
     * How many turns the CLI may take. The default is twelve, which is right for a seat that calls
     * a few jawata tools and wrong for one that reads: measured 2026-10-18, the first real UX hire
     * with the read-only built-ins made 24 reads in twelve turns and was cut off by the CLI (exit 1)
     * before it wrote its census. A seat that reads files is given as many turns as the step
     * ceiling it declared, so the ceiling the engine enforces is the bound and the CLI's own turn
     * count is not a second, tighter, undeclared one. Every other seat keeps the default.
     */
    int maxTurns() {
        int steps = ceilings.maxToolSteps();
        return readsFiles && steps > ClaudeCodeDialect.DEFAULT_MAX_TURNS
            ? steps : ClaudeCodeDialect.DEFAULT_MAX_TURNS;
    }

    /**
     * What this seat's declared cost budget actually BUYS on the brain it chose — see the type
     * javadoc. Absent when the seat declared no budget, because there is then nothing to report.
     */
    public Optional<String> budgetReport() {
        Double budget = ceilings.maxCostUsd();
        if (budget == null) {
            return Optional.empty();
        }
        if (brain == Brain.SCRIPT) {
            // A script publishes a dollar cost only if it prints one — ScriptDialect reads a
            // `COST-USD: <n>` line — so the budget's force is the script's to give, not ours.
            return Optional.of("cost budget $" + budget + " fires only on the COST-USD: lines the"
                + " script prints; one that prints none is still bounded by its wall clock and step"
                + " ceiling");
        }
        boolean publishesDollars = brain == Brain.CLAUDE_CODE;
        return Optional.of(publishesDollars
            ? "cost budget $" + budget + " is ENFORCEABLE: " + brain + " reports a dollar cost,"
                + " but only when the run ends, so the ceiling STOPS a run that has passed it and"
                + " CANNOT PREVENT THE SPEND — a single-turn hire measured at $2.26 against a"
                + " $0.25 ceiling was stopped correctly and still cost $2.26"
            : "cost budget $" + budget + " CANNOT FIRE on " + brain + ", which publishes no dollar"
                + " cost — the run is still bounded by its wall clock and step ceiling");
    }

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance) {
        this(seat, brain, model, tools, ceilings, stance, List.of());
    }

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance, List<String> answerGates) {
        this(seat, brain, model, tools, ceilings, stance, answerGates, true);
    }

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance, List<String> answerGates, boolean enabled) {
        this(seat, brain, model, tools, ceilings, stance, answerGates, enabled, null, false);
    }

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance, List<String> answerGates, boolean enabled, String script, boolean acceptsUncontainedWrites) {
        this(seat, brain, model, tools, ceilings, stance, answerGates, enabled, script, acceptsUncontainedWrites, false);
    }

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance, List<String> answerGates, boolean enabled, String script, boolean acceptsUncontainedWrites, boolean changesCode) {
        this(seat, brain, model, tools, ceilings, stance, answerGates, enabled, script, acceptsUncontainedWrites, changesCode, false);
    }
}
