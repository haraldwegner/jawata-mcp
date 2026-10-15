package org.jawata.mcp.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
 */
public record SeatHire(String seat, Brain brain, String model, List<String> tools,
                       HiredBrain.Ceilings ceilings, String stance, List<String> answerGates, boolean enabled) {

    /** The brains this product has a dialect for. A seat may name no other. */
    public enum Brain {
        CLAUDE_CODE("claude-code", "claude"),
        CURSOR("cursor", "cursor-agent"),
        COPILOT("copilot"),
        CODEX("codex");

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
        Map<String, String> front = frontmatter(markdown);

        String declared = front.get("brain");
        Brain brain = declared == null || declared.isBlank()
            ? Brain.CLAUDE_CODE
            : Brain.named(declared).orElseThrow(() -> new IllegalArgumentException(
                "seat declares brain '" + declared + "', which this product has no dialect for."
                    + " Known: claude-code, cursor, copilot, codex. NOT defaulted on purpose — a"
                    + " seat hired as a brain it did not ask for is worse than one that refuses"
                    + " to start"));

        return new SeatHire(
            front.getOrDefault("name", ""),
            brain,
            emptyToNull(front.get("model")),
            list(front.get("tools")),
            new HiredBrain.Ceilings(
                seconds(front.get("ttl_secs")).orElse(DEFAULT_WALL_CLOCK),
                integer(front.get("max_tool_steps")).orElse(0),
                // null, not 0.0 — an undeclared budget is NO ceiling, and zero would be a ceiling
                // of nothing that fired on the first reported cent.
                decimal(front.get("cost_budget_usd")).orElse(null)),
            body(markdown),
            list(front.get("answer_gates")),
            enabled(front.get("enabled")));
    }

    /**
     * The seat's on/off switch, read STRICTLY — C11-6.
     *
     * <p>studio writes {@code enabled: false} into a seat's file when the user switches the seat
     * off, because the file is the one thing every reader opens; the settings sidecar was read by
     * the deploy alone, so a switched-off seat could still be hired. Absent means on. Anything but
     * {@code true} or {@code false} is refused, the same as studio's own parser refuses it: a
     * switch read as on because it was misspelled would hire the seat the user turned off.</p>
     */
    private static boolean enabled(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.strip()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("seat declares enabled '" + value.strip()
                + "': expected true or false. NOT read as on — a misspelled switch would hire a seat"
                + " its owner turned off");
        };
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
     */
    public BrainDialect dialect(SelfEndpoint self) {
        return switch (brain) {
            case CLAUDE_CODE -> new ClaudeCodeDialect("claude",
                ClaudeCodeDialect.DEFAULT_MAX_TURNS, model, true, self);
            case CURSOR -> new CursorDialect("cursor-agent", true, model);
            // Neither of these takes a model from this product: copilot ROUTES its own (its
            // auto_mode_resolved event reports which it chose and why), and codex's is set in its
            // config, out of band. Passing one would be a flag invented rather than measured.
            case COPILOT -> new CopilotDialect();
            case CODEX -> new CodexDialect();
        };
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
        boolean publishesDollars = brain == Brain.CLAUDE_CODE;
        return Optional.of(publishesDollars
            ? "cost budget $" + budget + " is ENFORCEABLE: " + brain + " reports a dollar cost"
            : "cost budget $" + budget + " CANNOT FIRE on " + brain + ", which publishes no dollar"
                + " cost — the run is still bounded by its wall clock and step ceiling");
    }

    /**
     * The frontmatter's scalar fields.
     *
     * <p><b>A deliberate second implementation, and the reason is that the shared part is smaller
     * than it looks.</b> {@code ExperienceMaintenance} also reads markdown frontmatter, and the
     * architect seat's standing rule refuses a second implementation of a job the system already
     * does. What those two readers share is finding the fences and splitting on the first colon —
     * six lines. What they do NOT share is the value shapes: that one needs a YAML BLOCK list for
     * its cues, this one needs an inline bracket list for its tools, and a common helper handing
     * each a {@code Map<String,String>} would leave both re-parsing their own values anyway while
     * adding a type neither owns. Extracting it would also mean editing a shipped reader in the
     * knowledge package to serve a new feature in the execution package, for a six-line saving.</p>
     *
     * <p>Recorded as a declared duplicate rather than taken silently: if a THIRD reader ever wants
     * frontmatter, that is the moment the shared type pays for itself, and this paragraph is the
     * evidence for it.</p>
     */
    private static Map<String, String> frontmatter(String markdown) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (markdown == null) {
            return fields;
        }
        String[] lines = markdown.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) {
            // No frontmatter at all. An empty map rather than a throw: the CALLER decides which
            // absent field is fatal, and only the brain and the name are.
            return fields;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.equals("---")) {
                break;
            }
            // A seat's frontmatter carries COMMENTS — every shipped seat has a multi-line tier
            // justification in one, and three of those lines contain a colon. Skipped so they do
            // not become junk keys like "# /main's, and wrong here costs a whole stage rather than
            // one finding. No schedule".
            //
            // WHAT THIS SKIP DOES NOT BUY, stated because the first version of this comment
            // claimed it did: a comment CANNOT shadow a real field. `# model: x` splits at its own
            // colon into the key "# model", which is not "model" — so without this skip the junk
            // key is stored and the real field still wins. A mutation deleting the skip left every
            // test green, which is how the false claim was found. The skip is kept because junk
            // keys in a map a caller may iterate are worth not having, not because anything
            // downstream would otherwise break.
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            fields.put(line.substring(0, colon).strip(), unquote(line.substring(colon + 1).strip()));
        }
        return fields;
    }

    /**
     * Everything below the frontmatter's closing fence — the seat's stance. A file with no
     * frontmatter is ALL stance; a file whose fence never closes has none, because what follows the
     * opening fence is then frontmatter that was never finished, not rules anybody wrote.
     */
    private static String body(String markdown) {
        if (markdown == null) {
            return "";
        }
        String[] lines = markdown.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) {
            return markdown;
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                return String.join("\n", Arrays.copyOfRange(lines, i + 1, lines.length));
            }
        }
        return "";
    }

    /** An inline {@code [a, b, c]} list, which is how every shipped seat writes its tools. */
    private static List<String> list(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String inner = value.strip();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        List<String> items = new ArrayList<>();
        for (String part : inner.split(",")) {
            String item = unquote(part.strip());
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    private static String unquote(String value) {
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * A declared number, or ABSENT — never a substituted default.
     *
     * <p>A seat that writes {@code max_tool_steps: banana} has declared NO ceiling, and reading it
     * as zero or as some default would silently bound a run by a number nobody wrote. The same
     * reasoning the script dialect gives for a malformed cost line.</p>
     */
    private static Optional<Integer> integer(String value) {
        try {
            return value == null || value.isBlank()
                ? Optional.empty()
                : Optional.of(Integer.valueOf(value.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Double> decimal(String value) {
        try {
            return value == null || value.isBlank()
                ? Optional.empty()
                : Optional.of(Double.valueOf(value.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> seconds(String value) {
        return integer(value).filter(s -> s > 0).map(Duration::ofSeconds);
    }

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance) {
        this(seat, brain, model, tools, ceilings, stance, List.of());
    }

    public SeatHire(String seat, Brain brain, String model, List<String> tools, HiredBrain.Ceilings ceilings, String stance, List<String> answerGates) {
        this(seat, brain, model, tools, ceilings, stance, answerGates, true);
    }
}
