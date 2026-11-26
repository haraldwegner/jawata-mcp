package org.jawata.mcp.execution;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jawata.mcp.access.LookupDriver;

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
 * <p>Absent or blank, the brain is none: the hire reads the stored choice for the seat and does not
 * fill a brain in. An UNKNOWN brain is REFUSED rather than replaced: a seat asking for a brain this
 * product has no dialect for must not quietly be hired as a different one.</p>
 *
 * <h2>A hire has no limit of time, cost or number of tool uses (28h z132)</h2>
 *
 * <p>A seat file's {@code ttl_secs}, {@code cost_budget_usd} and {@code max_tool_steps} lines limit nothing: every hire runs under
 * {@link HiredBrain.Ceilings#none()}. What a hire spends is counted and reported to the seat that hired it, which controls it; a hire
 * ends when its brain does, when it is stopped, or when it shows no sign of life.</p>
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
 * @param writesStore the seat's own word, {@code writes_store: true}, that its job is to read and
 *     write the knowledge store through its tool. It is hired outside plan mode, which refuses the
 *     {@code experience} tool wholesale, and keeps every code-changing tool hidden and every
 *     built-in tool of the CLI unoffered. Not the same as {@code changes_code}, and not combinable
 *     with it.
 */
public record SeatHire(String seat, Brain brain, String model, List<String> tools,
                       HiredBrain.Ceilings ceilings, String stance, List<String> answerGates, boolean enabled, String script,
                       boolean acceptsUncontainedWrites, boolean changesCode, boolean readsFiles, List<String> activities, boolean writesStore, String command, String launchModel) {

    /**
     * The brains this product has a dialect for, each with the facts of its program (28h step 13): what a person calls it, how it is started
     * and logged in, which programs speak ACP for it, and what a seat may ask of it. Every list of agent programs is read from here, so the
     * order of the constants is the order those lists show. A seat may name no other.
     */
    public enum Brain {
        CLAUDE_CODE(new Program("Claude Code", "claude", "claude auth login", Set.of(Can.TAKE_A_MODEL, Can.CHANGE_CODE, Can.READ_FILES),
            List.of(new Start("claude-agent-acp", List.of(), "npm install -g @agentclientprotocol/claude-agent-acp"),
                new Start("claude-code-acp", List.of(), "npm install -g @zed-industries/claude-code-acp")),
            List.of()), "claude-code", "claude"),
        CODEX(new Program("Codex", "codex", "codex login", Set.of(),
            List.of(new Start("codex-acp", List.of(), "npm install -g @zed-industries/codex-acp")), List.of()), "codex"),
        CURSOR(new Program("Cursor-Grok", "cursor-agent", "cursor-agent login", Set.of(Can.TAKE_A_MODEL, Can.CHANGE_CODE, Can.WRITE_UNHELD),
            List.of(), List.of("agent")), "cursor", "cursor-agent"),
        COPILOT(new Program("Copilot", "copilot", "copilot login", Set.of(),
            List.of(new Start("copilot", List.of("--acp"), "npm install -g @github/copilot")), List.of()), "copilot"),
        /** Google's Antigravity command-line agent, started over ACP as {@code agy acp}, or in print mode through {@link AntigravityDialect} (28h z54). */
        ANTIGRAVITY(new Program("Antigravity", "agy", "agy acp --login", Set.of(Can.TAKE_A_MODEL, Can.WRITE_UNHELD),
            List.of(new Start("agy", List.of("acp"), "curl -fsSL https://antigravity.google/cli/install.sh | bash")), List.of()), "antigravity", "agy"),
        /**
         * Any executable, named by the seat's {@code script:} line — {@link ScriptDialect}, the
         * escape hatch for a CLI with no dialect of its own (S16-6). No program of its own, so no facts.
         */
        SCRIPT(new Program("Script", "", "", Set.of(), List.of(), List.of()), "script");

        /** What a seat may ask of a brain, or what is known to be true of it. */
        public enum Can {
            /** A hire passes it a model on its command line; the others route or configure their own. */
            TAKE_A_MODEL,
            /** It has a mode that changes code and writes the store ({@code changes_code}, {@code writes_store}). */
            CHANGE_CODE,
            /** It has a built-in file reader that can be offered alone ({@code reads_files}). */
            READ_FILES,
            /** No mechanism refuses its writes: Cursor's are held by an instruction to the model, Antigravity's by nothing usable (measured 2026-11-23). */
            WRITE_UNHELD
        }

        /** A program that speaks ACP for a brain, with its arguments and the command that installs it. */
        public record Start(String program, List<String> args, String install) {
        }

        /**
         * The facts of one agent program.
         *
         * @param title      the name a person reads
         * @param command    the program asked for its version, login and models
         * @param login      what a person types to log in
         * @param can        what may be asked of it
         * @param acp        the programs that speak ACP for it, in the order they are tried
         * @param otherNames the other binaries it installs as, beside its spellings and those programs
         */
        public record Program(String title, String command, String login, Set<Can> can, List<Start> acp, List<String> otherNames) {
        }

        private final Program program;
        private final List<String> spellings;

        Brain(Program program, String... spellings) {
            this.program = program;
            this.spellings = List.of(spellings);
        }

        /** The facts of its program. */
        public Program program() {
            return program;
        }

        /** The brains a hire can start: every one but the script seat's own executable. */
        public static List<Brain> programs() {
            return Arrays.stream(values()).filter(brain -> brain != SCRIPT).toList();
        }

        /** Whether this may be asked of it, or is true of it. */
        public boolean can(Can what) {
            return program.can().contains(what);
        }

        /** The name the host's registry settings know this brain by: the first spelling, which is also how its dialect names itself. */
        public String label() {
            return spellings.get(0);
        }

        /**
         * Whether this product passes the brain a model: claude and cursor take one on their command line, while copilot routes its own and
         * codex's is set in its config out of band, so a model named for either would be a flag invented and is not applied.
         */
        public boolean takesAModel() {
            return can(Can.TAKE_A_MODEL);
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

    public SeatHire {
        if (seat == null || seat.isBlank()) {
            throw new IllegalArgumentException("a seat with no name cannot be reported on");
        }
        // Null is "no brain yet". It is not Claude and not any other dialect. A hire binds the
        // stored choice before it asks for a dialect; guessing one here is the default this path refuses.

        tools = tools == null ? List.of() : List.copyOf(tools);
        stance = stance == null ? "" : stance.strip();
        answerGates = answerGates == null ? List.of() : List.copyOf(answerGates);
        activities = activities == null ? List.of() : List.copyOf(activities);
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
        // A missing or blank line is no brain yet. It is not Claude Code and not any other dialect.
        Brain brain = declared == null || declared.isBlank()
            ? null
            : Brain.named(declared).orElseThrow(() -> new IllegalArgumentException(
                "seat declares brain '" + declared + "', which this product has no dialect for."
                    + " Known: claude-code, cursor, copilot, codex, antigravity, script. NOT defaulted on purpose"
                    + " — a seat hired as a brain it did not ask for is worse than one that refuses"
                    + " to start"));

        // S16-6: the script escape hatch names its executable here, the one place an executable
        // may be named (S16-12 removed the hire tool's). Both mismatches are refused: a script
        // brain with nothing to run cannot start, and a script line on another brain would be
        // IGNORED — the inert-input shape that ran the real claude when a stand-in was meant.
        String script = Frontmatter.emptyToNull(front.get("script"));
        if (brain == null && script != null) {
            throw new IllegalArgumentException("seat declares script '" + script + "' and names no brain,"
                + " which would ignore it — set brain: script to run it, or remove the line");
        }
        if (brain == Brain.SCRIPT && script == null) {
            throw new IllegalArgumentException("seat declares brain 'script' and no 'script:' line"
                + " naming the executable to run, so there is nothing to start");
        }
        if (brain != null && brain != Brain.SCRIPT && script != null) {
            throw new IllegalArgumentException("seat declares script '" + script + "' on brain '"
                + brain.spellings.get(0) + "', which would ignore it — set brain: script to run it,"
                + " or remove the line");
        }

        // A switch a dialect cannot carry is refused, as a script line on another brain is: the seat
        // would be told its job is to change code and meet a brain that never heard of it. A seat
        // that names no brain yet is not checked here: the stored choice is not known, and treating
        // the blank as Claude would accept a switch that brain cannot carry.
        boolean changesCode = Frontmatter.readSwitch("changes_code", front.get("changes_code"), false);
        if (brain != null && changesCode && !brain.can(Brain.Can.CHANGE_CODE)) {
            throw new IllegalArgumentException("seat declares changes_code: true on brain '"
                + brain.spellings.get(0) + "', whose dialect has no write-capable mode to select, so the"
                + " declaration would be IGNORED. Only claude-code and cursor carry it");
        }
        // The same refusal for the same reason: the read-only built-ins are a claude start-up
        // choice, and a seat on another brain would be told it can read and never be offered it.
        boolean readsFiles = Frontmatter.readSwitch("reads_files", front.get("reads_files"), false);
        if (brain != null && readsFiles && !brain.can(Brain.Can.READ_FILES)) {
            throw new IllegalArgumentException("seat declares reads_files: true on brain '"
                + brain.spellings.get(0) + "', whose dialect has no built-in file reader to offer, so"
                + " the declaration would be IGNORED. Only claude-code carries it");
        }
        if (readsFiles && changesCode) {
            throw new IllegalArgumentException("seat declares both reads_files and changes_code: a"
                + " seat that changes code does so through the engine's tools, and offering it the"
                + " CLI's own file tools as well would open the editors the hire keeps closed");
        }

        // A seat whose whole job is to read and write the knowledge store through its own tool. Plan
        // mode refuses every `experience` call, reads included (measured 2026-10-19: a real rottenmeier
        // hire could read none of his turns and wrote no rule), and it bounds nothing else — the
        // bound on the CLI's own file tools is that none are offered. So this seat is hired outside
        // plan mode, with every code-changing tool still hidden and no built-in tool offered.
        boolean writesStore = Frontmatter.readSwitch("writes_store", front.get("writes_store"), false);
        if (brain != null && writesStore && !brain.can(Brain.Can.CHANGE_CODE)) {
            throw new IllegalArgumentException("seat declares writes_store: true on brain '"
                + brain.spellings.get(0) + "', whose dialect has no mode outside plan mode to select, so"
                + " the declaration would be IGNORED. Only claude-code and cursor carry it");
        }
        if (writesStore && changesCode) {
            throw new IllegalArgumentException("seat declares both writes_store and changes_code: a seat"
                + " that changes code through the engine already writes the store, so the second"
                + " declaration says nothing and hides which of the two the seat means");
        }

        return new SeatHire(
            front.getOrDefault("name", ""),
            brain,
            Frontmatter.emptyToNull(front.get("model")),
            Frontmatter.list(front.get("tools")),
            // 28h z132: a hire has no limit of time, cost or calls, whatever its seat file still says. What it spends is counted and reported
            // to the lead, who controls it.
            HiredBrain.Ceilings.none(),
            Frontmatter.body(markdown),
            Frontmatter.list(front.get("answer_gates")),
            Frontmatter.readSwitch("enabled", front.get("enabled"), true),
            script,
            // The same strict reader as every other switch: this one used to read loosely, so a
            // value of yes was silently no. Absent means NO — the default is the refusal.
            Frontmatter.readSwitch("accepts_uncontained_writes", front.get("accepts_uncontained_writes"), false),
            changesCode,
            readsFiles,
            // The activity labels this seat carries beyond its own name (D45): a rule anchored to
            // one of them binds this seat. Declared here, once, so a label is asked for by a file
            // a person can read rather than by code that has to be searched.
            Frontmatter.list(front.get("activities")),
            writesStore,
            // The slash command a person types for this seat (architect is /refactor), written once in the seat's own file so that the
            // studio's command table and the Agent Control panel read the same word. A seat with no command of its own leaves it out.
            Frontmatter.emptyToNull(front.get("command")),
            null);
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
     * What the hired brain is asked, with the accepted rules that bind this seat (Sprint 28g D45):
     * the seat's own rules, then the standing rules he has accepted for it, then the brief.
     *
     * <p>The rules are read by the caller at the moment of the hire ({@link
     * org.jawata.mcp.knowledge.SeatRules#bindingOn}), so a rule recorded after the build is in the
     * next hire's text. A seat with no rules is asked exactly what it was before.</p>
     *
     * @param brief the instruction
     * @param rules the accepted rules that bind this seat, as text; none is a valid answer
     * @param ground the catalogue's map of the area and its jobs with their live locations, set between the rules and the brief
     *     (Sprint 28h, S14, D16); empty or null is no ground
     * @return the text the brain is asked
     */
    public String prompt(String brief, List<String> rules, String ground) {
        String asked = brief == null ? "" : brief.strip();
        String known = ground == null ? "" : ground.strip();
        StringBuilder text = new StringBuilder();
        if (!stance.isEmpty()) {
            text.append(stance).append("\n\n---\n\n");
        }
        if (!rules.isEmpty()) {
            text.append("## Rules that bind you\n\nHe accepted these; they hold until he retires them.\n\n");
            for (String rule : rules) {
                text.append("- ").append(rule.strip()).append('\n');
            }
            text.append("\n---\n\n");
        }
        // THE GROUND (Sprint 28h, S14, D16): the catalogue's description of the area and its jobs with their live locations, set after
        // the rules and before the brief so the seat reads what the code is before what it is asked to do. It is burned into the prompt
        // rather than offered as a tool, so the seat has no other option than to have read it.
        if (!known.isEmpty()) {
            text.append("## The ground you work on\n\n").append(known).append("\n\n---\n\n");
        }
        // HOW A BRAIN LOOKS THINGS UP (Sprint 28h, S14, D22): a seat that changes code is refused a shell text search over Java source where the
        // engine answers its permission requests, and a refused permission carries no words, so the rule is said here, once, in the one channel the
        // brain reads.
        if (changesCode) {
            text.append("## How you look things up\n\n").append(LookupDriver.RULE).append("\n\n---\n\n");
        }
        if (stance.isEmpty() && rules.isEmpty() && known.isEmpty() && !changesCode) {
            return asked;
        }
        return text.append("## Your brief\n\n").append(asked).toString();
    }

    /**
     * The model the registry setting offers for this seat's brain, used only where the seat file names none: a model the seat file writes is
     * the seat's own word and wins over the studio's default for the brain.
     */

    /**
     * The same seat on the stored brain and model. {@code shown} is what the record and the journal say;
     * {@code launched} is what the program's command line is given (the engine's existing name-to-value translation).
     */
    public SeatHire apply(Brain picked, String shown, String launched) {
        if (picked == null) {
            throw new IllegalArgumentException("a hire has no brain");
        }
        if (picked == Brain.SCRIPT && (script == null || script.isBlank())) {
            throw new IllegalArgumentException("seat '" + seat + "' is set to run on 'script' and names no script to run");
        }
        if (changesCode && !picked.can(Brain.Can.CHANGE_CODE)) {
            throw new IllegalArgumentException("seat '" + seat + "' is set to run on '" + picked.label()
                + "', which cannot carry changes_code");
        }
        if (readsFiles && !picked.can(Brain.Can.READ_FILES)) {
            throw new IllegalArgumentException("seat '" + seat + "' is set to run on '" + picked.label()
                + "', which cannot carry reads_files");
        }
        if (writesStore && !picked.can(Brain.Can.CHANGE_CODE)) {
            throw new IllegalArgumentException("seat '" + seat + "' is set to run on '" + picked.label()
                + "', which cannot carry writes_store");
        }
        // The stored choice is the person's own word, and it is also the consent a brain held only by an instruction needs: the Seats page used
        // to write accepts_uncontained_writes into the seat file when a person chose such a brain, and a seat file is no longer where the brain
        // is chosen. Without this, a person who chose Cursor for a seat was refused at the hire, by a rule they had just answered.
        return new SeatHire(seat, picked, shown, tools, ceilings, stance, answerGates, enabled, script, true,
            changesCode, readsFiles, activities, writesStore, command, launched);
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
        if (brain == null) {
            throw new IllegalStateException("this seat has no brain yet, so there is no dialect to start");
        }
        String flag = launchModel != null && !launchModel.isBlank() ? launchModel : model;
        return switch (brain) {
            case CLAUDE_CODE -> new ClaudeCodeDialect("claude", 0, flag, !(changesCode || writesStore), self,
                hiddenTools, readsFiles ? ClaudeCodeDialect.READ_ONLY_BUILT_INS : List.of());
            case CURSOR -> new CursorDialect("cursor-agent", !(changesCode || writesStore), flag);
            // Neither of these takes a model from this product: copilot ROUTES its own (its
            // auto_mode_resolved event reports which it chose and why), and codex's is set in its
            // config, out of band. Passing one would be a flag invented rather than measured.
            case COPILOT -> new CopilotDialect();
            case CODEX -> new CodexDialect();
            case ANTIGRAVITY -> new AntigravityDialect("agy", flag);
            case SCRIPT -> new ScriptDialect(script, List.of());
        };
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

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance, List<String> answerGates, boolean enabled, String script, boolean acceptsUncontainedWrites, boolean changesCode, boolean readsFiles) {
        this(seat, brain, model, tools, ceilings, stance, answerGates, enabled, script, acceptsUncontainedWrites, changesCode, readsFiles, List.of());
    }

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance, List<String> answerGates, boolean enabled, String script, boolean acceptsUncontainedWrites, boolean changesCode, boolean readsFiles, List<String> activities) {
        this(seat, brain, model, tools, ceilings, stance, answerGates, enabled, script, acceptsUncontainedWrites, changesCode, readsFiles, activities, false);
    }

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance, List<String> answerGates, boolean enabled, String script, boolean acceptsUncontainedWrites, boolean changesCode, boolean readsFiles, List<String> activities, boolean writesStore) {
        this(seat, brain, model, tools, ceilings, stance, answerGates, enabled, script, acceptsUncontainedWrites, changesCode, readsFiles, activities, writesStore, null, null);
    }
}
