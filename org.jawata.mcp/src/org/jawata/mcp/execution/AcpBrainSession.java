package org.jawata.mcp.execution;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.access.LookupDriver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The engine as an ACP client (Sprint 28h, S6, D1, D4, D28): one prompt run in a brain that speaks the Agent Client Protocol, over the
 * brain's stdio. It is the second way a brain is hired, beside {@link HiredBrain}'s per-vendor command lines, and it answers the same
 * {@link HiredBrain.Outcome}, so what reads an outcome does not care which way the brain was reached.
 *
 * <p><b>What a brain is given.</b> The resident as its one tool server, over HTTP with the resident's key, when the brain says it can
 * attach one; and the seat's allowlist, which is enforced where ACP lets a client enforce it: every {@code session/request_permission}
 * is answered by the allowlist, a tool the seat does not list is rejected, and an empty allowlist rejects every tool. A brain that
 * never asks permission for a tool is not bounded by this (measured in S2: a brain under the user's own pre-tool hooks never asks),
 * which is why the outcome names every tool call the brain made and the caller classifies them.</p>
 *
 * <p><b>Resume (D4).</b> Given the session id of an earlier run and a brain that advertises {@code loadSession}, the earlier session is
 * loaded instead of a new one opened, so a second hire of the same seat knows what the first saw. The history the brain replays while
 * loading is not collected as this run's answer. A brain that cannot load a session gets a new one, and the outcome carries the new id.</p>
 */
public final class AcpBrainSession {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int STDERR_TAIL_LIMIT = 8_192;
    private static final int METHOD_NOT_FOUND = -32601;
    /**
     * What each brain program's session offered as models the last time one opened: the engine's one catalogue of them (Sprint 28k, Stage 25m).
     * The chat's own session writes it as it opens, so a settings screen that reads it lists what the chat's brain offered.
     */
    private static final Map<Program, List<Model>> OFFERED = new java.util.concurrent.ConcurrentHashMap<>();

    /** The usual ways a brain says its user is not logged in: they decide whether the person is told how to connect it. */
    private static final java.util.regex.Pattern LOGIN_WORDS = java.util.regex.Pattern.compile(
        "authenticat|unauthorized|\\blog(ged|ging)?[ -]?(in|into|out)\\b|\\bsign(ed|ing)?[ -]?(in|into)\\b|credential|api[ _-]?key|token (has )?expired",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Whether what a brain said when it refused means its user must log in first. */
    public static boolean asksForLogin(String refusal) {
        return LOGIN_WORDS.matcher(refusal).find();
    }

    private AcpBrainSession() {
    }

    /**
     * The models this program's session offered the last time one of its sessions opened in this engine, in the order it listed them.
     *
     * @param program the brain program
     * @return the models, empty in the optional when no session of it has opened yet
     */
    public static Optional<List<Model>> offeredBy(Program program) {
        return Optional.ofNullable(OFFERED.get(program));
    }

    /**
     * The program a setting names: {@code {"command":[...],"env":{...}}}. None when the text is not a command line (a setting that is wrong
     * names no brain). The one reader of that form, for the principal's brain, the reviewer's and every hired seat's.
     */
    public static Optional<Program> parseProgram(String setting) {
        try {
            JsonNode root = JSON.readTree(setting);
            List<String> command = new ArrayList<>();
            root.path("command").forEach(part -> command.add(part.asText()));
            if (command.isEmpty()) {
                return Optional.empty();
            }
            Map<String, String> env = new LinkedHashMap<>();
            root.path("env").properties().forEach(entry -> env.put(entry.getKey(), entry.getValue().asText()));
            return Optional.of(new Program(command, env));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The program one brain has in a registry setting: {@code {"<brain>":{"command":[...],"env":{...}},...}}, where the brain is named the
     * way its dialect names itself. None when the text is not JSON, the brain has no entry, or its entry is not a command line.
     */
    public static Optional<Program> programFor(String registry, String brain) {
        try {
            JsonNode entry = JSON.readTree(registry).path(brain);
            return entry.isObject() ? parseProgram(entry.toString()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The brains a registry setting gives a program, in the order it names them.
     *
     * @param registry the brain registry setting
     * @return the names, empty when the text is not JSON or names none
     */
    public static List<String> brainsWithAProgramIn(String registry) {
        List<String> named = new ArrayList<>();
        try {
            JSON.readTree(registry == null || registry.isBlank() ? "{}" : registry).fieldNames()
                .forEachRemaining(id -> programFor(registry, id).ifPresent(program -> named.add(id)));
        } catch (IOException unreadable) {
            // a registry that is not JSON names no brain; the hire path refuses it in its own words
        }
        return named;
    }

    /**
     * The model a command line is given for a model a seat names: a name the kept catalogue holds becomes its value, any other model, and a
     * brain the registry names no program for, is passed as it is.
     *
     * @param registry the brain registry setting
     * @param brain    the brain
     * @param model    the model the seat names, or null for the brain's own
     * @return the model for the command line, or null
     */
    public static String commandLineModelIn(String registry, String brain, String model) {
        if (model == null || model.isBlank()) {
            return model;
        }
        String named = model.strip();
        String fromCatalogue = programFor(registry, brain).flatMap(AcpBrainSession::offeredBy)
            .flatMap(offered -> offered.stream().filter(one -> one.name().equalsIgnoreCase(named)).findFirst())
            .map(Model::value)
            .orElse(null);
        if (fromCatalogue != null) {
            return fromCatalogue;
        }
        // Claude's own command line refuses a display name (measured: "Haiku 5.5" is unrecognized_model; "haiku" is accepted).
        // A session catalogue's value wins when one is held. With none held, the value is the first word of that name.
        // Cursor's command line accepts the name as shown, so it is not rewritten here.
        if ("claude-code".equalsIgnoreCase(brain)) {
            int space = named.indexOf(' ');
            if (space > 0) {
                return named.substring(0, space).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return named;
    }

    /**
     * The models one brain of a registry offers: what the chat's own session of it offered, or, when none has opened yet, a session opened here
     * and closed again.
     *
     * @param registry the brain registry setting
     * @param brain    the brain
     * @return the models in the order the brain listed them
     * @throws IllegalArgumentException when the registry names no program for the brain
     * @throws java.io.UncheckedIOException when the brain's adapter cannot be started
     */
    public static List<Model> modelsOfferedIn(String registry, String brain) {
        Program program = programFor(registry, brain)
            .orElseThrow(() -> new IllegalArgumentException("The brain registry names no program for " + brain + "."));
        return offeredBy(program).orElseGet(() -> {
            try {
                Conversation session = Conversation.start(org.jawata.core.host.HostProcesses.system(), program, HireSite.in(listingFolder()), null,
                    List.of(), Optional.empty(), HiredBrain.Ceilings.of(java.time.Duration.ofSeconds(60)), OwnTools.KEPT);
                try {
                    return session.models();
                } finally {
                    session.close();
                }
            } catch (IOException failed) {
                throw new java.io.UncheckedIOException(brain + "'s adapter could not be started: " + failed.getMessage(), failed);
            }
        });
    }

    /**
     * The one folder a model listing opens its brain in, kept between listings (28h-C26-7). Claude and Cursor each keep a record per folder a
     * session was opened in, under the person's home, in a layout this engine does not own and cannot remove for every program, so a fresh folder
     * for every listing left one record behind for every listing. One named folder is one record, reused; it is named for the user so that
     * another user's folder of the name is never used, and it is made again when something removed it.
     */
    static java.nio.file.Path listingFolder() throws IOException {
        java.nio.file.Path folder = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "jawata-models-" + System.getProperty("user.name"));
        java.nio.file.Files.createDirectories(folder);
        return folder;
    }

    /** Times a prompt through the program the registry names for a brain (28h z93): the engine's own question about a brain, not a hire. */
    static java.util.List<java.util.Map<String, Object>> timedRunsIn(String brain, String model, String prompt,
            int repeat, boolean keepRunning, String registry) {
        Program program = programFor(registry, brain)
            .orElseThrow(() -> new IllegalArgumentException("The brain registry names no program for " + brain + "."));
        try {
            return TimedProbe.run(program, model, prompt, repeat, keepRunning);
        } catch (java.io.IOException failed) {
            throw new java.io.UncheckedIOException(brain + "'s adapter could not be started: " + failed.getMessage(), failed);
        }
    }

    /**
     * What it takes to start a brain that speaks ACP: the command line the brain's own installation defines, and the variables it needs
     * beyond the resident's. Per brain, never per call.
     */
    public record Program(List<String> command, Map<String, String> environment) {
    }

    /**
     * One model a brain offers for its session: the {@code value} the brain's own model option takes, and the {@code name} a person reads. They
     * differ (Cursor's agent offers {@code grok-4.5[effort=high,fast=true]} as "grok-4.5"), and the value is the one that is sent back.
     */
    public record Model(String value, String name) {
    }

    /**
     * What asking a session for a model came to: whether it was {@code moved}, the model it is on {@code now} (none when the brain offers no
     * models), the models the brain {@code offered}, and one sentence {@code said} for a person about what happened and why.
     */
    public record ModelChoice(boolean moved, Optional<Model> now, List<Model> offered, String said) {
    }

    /**
     * Whether a brain keeps its own tools that write files or run commands (28h D57 (j)). Measured 2026-11-17 on this machine
     * ({@code 28h-S22-evidence/s26-j-probe}): Claude's ACP adapter ran its shell and wrote a file without asking the client, and Cursor's agent
     * asked before its shell but wrote a file without asking, even with the client's own file writing offered. So a permission answer alone
     * bounds neither; what does is the Claude adapter's session option {@code disallowedTools}. Cursor's read-only "ask" mode bounds it too, and
     * hides every jawata tool with it (finding 28h-C26-18), so Cursor's agent is left in its normal mode and what a lead or the main seat writes
     * with its own editor outside the documents is read from its stream and flagged afterwards ({@link OwnWrites}, Stage 27 (z2)).
     */
    public enum OwnTools {
        /**
         * The brain keeps its own editors, not its shell (28h z31: a worker does not run a shell itself, it hands the command to jawata's
         * {@code shell} tool): a worker that changes code.
         */
        KEPT,
        /**
         * They are withheld: the session is opened with them disallowed, and every permission request for an edit or a command is refused,
         * whatever the allowlist says. A brain that offers no way to withhold them is not moved into a read-only mode, which would hide hire
         * with the rest; its own writes are flagged afterwards. The principal and a hired lead, which change no code.
         */
        WITHHELD
    }

    /**
     * The Claude adapter's own tools that write a file or run a command, withheld through its {@code disallowedTools} option; with them its
     * helper agents ({@code Task}, {@code Agent}), which carry editors of their own (seen 2026-11-17: the principal, its editors withheld, tried to
     * hand the edit to one).
     */
    static final List<String> OWN_WRITERS = List.of("Edit", "Write", "MultiEdit", "NotebookEdit", "Bash", "BashOutput", "KillShell", "Task", "Agent");

    /** The Claude adapter's own shell, withheld from every brain, a worker's too (28h z31). */
    static final List<String> OWN_SHELL = List.of("Bash", "BashOutput", "KillShell");

    /** The kinds of tool call ACP gives an edit or a command; a permission request for one is refused when the brain's own tools are withheld. */
    static final Set<String> WRITING_KINDS = Set.of("edit", "delete", "move", "execute");

    /**
     * Runs one prompt.
     *
     * @param host         the process boundary
     * @param program      how to start the brain
     * @param base         where it runs and with what environment
     * @param prompt       the brief
     * @param mcp          the resident's own endpoint, or null for a brain that is given no tool server
     * @param allowedTools the tools the seat may use, by bare name or by the name the brain shows; empty allows none
     * @param resume       the session id of an earlier run to continue, if any
     * @param ceilings     what the run may consume
     * @param onText       called with each piece of the brain's answer as it arrives, or null when only the finished outcome is wanted
     * @param onToolUpdate called with each {@code tool_call} and {@code tool_call_update} the brain streams, the moment it is read, so a
     *                     host sees the brain's tool calls as they happen; null when nobody is listening
     * @param onThinking   called with each {@code agent_thought_chunk} (thinking) and {@code plan} (a plan update) the brain streams, the
     *                     moment it is read, so a host sees them as they happen; null when nobody is listening
     * @param model        the model the seat names, by the value or the name its session offers; null or blank for the brain's own default
     * @param effort       the seat's reasoning effort, set on the session where the brain takes it; null for the brain's own default
     * @throws IOException when the brain cannot be started, or offers models and none is the one named
     */
    public static HiredBrain.Outcome run(HostProcesses host, Program program, HireSite base, String prompt, SelfEndpoint mcp,
            List<String> allowedTools, Optional<String> resume, HiredBrain.Ceilings ceilings, Consumer<String> onText, Consumer<JsonNode> onToolUpdate, Consumer<JsonNode> onThinking, String model, String effort) throws IOException, InterruptedException {
        Conversation conversation = Conversation.open(host, program, base, mcp, allowedTools, resume, ceilings, OwnTools.KEPT);
        try {
            conversation.onModel(model);
            conversation.onEffort(effort);
            conversation.turn(prompt, List.of(), ceilings, onText, onToolUpdate, onThinking);
        } finally {
            conversation.end();
        }
        return conversation.outcome();
    }

    /**
     * Runs the brain of a hired lead (Stage 24, M12): the first prompt as {@link #run} does, and then, while hires its brain started in the
     * background are still out, one more turn for each set of reports that comes, until nothing is out and nothing is waiting. The brain's
     * process stays alive between turns, so its context carries the reports. The wall-clock ceiling is armed for each turn and not across the
     * waits, and the tool-step ceiling is counted across the turns, so a lead that keeps going cannot spend without end. The wait goes on, in slices
     * of the lead's own wall-clock ceiling, for as long as a hire of the lead is out and running, because each hire is bounded by the ceilings of its
     * own seat and not by the lead's (Stage 27 (r), (v)); it ends when nothing is out, or when a hire is counted as out and none runs.
     * @param inbox  the reports of this lead's background hires, opened before the first turn so that none is missed
     * @param leadId the id this lead's hires are issued under, to say which of them are still out
     * @param model  the model the seat names, as {@link #run} takes it
     * @param openChecks what the lead's declared checks leave open, asked with its latest answer once nothing is out; empty when nothing is
     * @param effort the seat's reasoning effort, as {@link #run} takes it
     */
    public static HiredBrain.Outcome runAsLead(HostProcesses host, Program program, HireSite base, String prompt, SelfEndpoint mcp,
            List<String> allowedTools, Optional<String> resume, HiredBrain.Ceilings ceilings, Consumer<String> onText, Consumer<JsonNode> onToolUpdate,
            Consumer<JsonNode> onThinking, HireRegistry.Inbox inbox, String leadId, String model, Function<String, Optional<OpenCheck>> openChecks, String effort) throws IOException, InterruptedException {
        // A lead plans and hires and changes no code (28h D57 (l)): its brain's own editors and shell are withheld, as the principal's are.
        // Opened inside the try, so a brain that cannot be started still closes the inbox (the C27 audit).
        Conversation conversation = null;
        try {
            conversation = Conversation.open(host, program, base, mcp, allowedTools, resume, ceilings, OwnTools.WITHHELD);
            Conversation brain = conversation;
            conversation.onModel(model);
            conversation.onEffort(effort);
            // A hire this lead's brain starts is the lead's whatever caller id the engine saw its call under: the call's answer names the plan,
            // and the inbox counts the hires issued under the lead's own id (28h Stage 27, finding 28h-C27-1).
            Consumer<JsonNode> toolUpdates = update -> {
                if (onToolUpdate != null) {
                    onToolUpdate.accept(update);
                }
                StartedHires.planOf(update).ifPresent(started -> HireRegistry.shared().startedBy(started, leadId));
            };
            conversation.turn(prompt, List.of(), ceilings, onText, toolUpdates, onThinking);
            HiredBrain.Outcome last = conversation.outcome();
            int steps = last.toolSteps();
            List<String> names = new ArrayList<>(last.toolNames());
            List<ToolCall> calls = new ArrayList<>(last.toolCalls());
            long toolMillis = last.toolMillis();
            // 28h z142: summed over the turns, and absent once any turn reports none, since a partial sum would read as a cheaper run.
            Optional<TokenUsage> spentTokens = last.tokens();
            int passedWhenLastTold = -1;
            // A turn the wall-clock watchdog reaped sets no stoppedBy (only the tool-step and cost ceilings do), and lost() cannot tell a reaped brain
            // from a living one until the JDK marks its process dead; the outcome's timedOut() says the clock ended the turn, so no further turn is sent.
            while (last.stoppedBy().isEmpty() && !last.timedOut() && !conversation.lost()) {
                List<HireRegistry.Event> reports = List.of();
                String next;
                if (inbox.outstanding() > 0 || !inbox.isEmpty()) {
                    // waits only while the lead's brain lives: a brain that died (an exit 137) is never prompted again, so waiting on would leave
                    // the lead running with nobody to read the reports (28h-C27-15). The wait is cut in slices of a second, after each of which it
                    // asks again whether the brain lives, so a brain that dies with a long hire out ends the lead within a second.
                    reports = inbox.awaitWhileOut(
                        ceilings.wallClock() == null ? DEATH_CHECK_MILLIS : Math.min(ceilings.wallClock().toMillis(), DEATH_CHECK_MILLIS),
                        () -> !brain.lost() && !HireRegistry.shared().runningNamesFor(leadId).isEmpty());
                    // a report that came for a brain that has died is never put into it: closing the inbox hands it up (28h-C27-18)
                    if (reports.isEmpty() || conversation.lost()) {
                        break;
                    }
                    next = HireReport.ofAll(reports, HireRegistry.shared().runningNamesFor(leadId));
                } else {
                    // Design step 9: with nothing out and nothing unread, a lead whose declared check is still open gets another turn that says
                    // so, as long as the turn before moved a check; a lead that moves nothing ends, and its checks say what is left open.
                    Optional<OpenCheck> open = openChecks.apply(last.text());
                    // An open step of the plan the lead entered (ofPlan) is exempt from the no-progress rule, which is for declared checks (M3).
                    if (open.isEmpty() || (!open.get().ofPlan() && open.get().passed() <= passedWhenLastTold)) {
                        break;
                    }
                    if (!open.get().ofPlan()) {
                        passedWhenLastTold = open.get().passed();
                    }
                    next = open.get().told();
                }
                HiredBrain.Ceilings left = ceilings.maxToolSteps() > 0
                    ? new HiredBrain.Ceilings(ceilings.wallClock(), Math.max(1, ceilings.maxToolSteps() - steps), ceilings.maxCostUsd())
                    : ceilings;
                if (ceilings.maxToolSteps() > 0 && steps >= ceilings.maxToolSteps()) {
                    break;
                }
                conversation.turn(next, List.of(), left, onText, toolUpdates, onThinking);
                if (!conversation.lost()) {
                    // the brain answered the turn that showed it these reports; a brain that died in it read none, and closing the inbox hands them up
                    inbox.delivered(reports);
                }
                last = conversation.outcome();
                steps += last.toolSteps();
                names.addAll(last.toolNames());
                calls.addAll(last.toolCalls());
                toolMillis += last.toolMillis();
                Optional<TokenUsage> turnTokens = last.tokens();
                spentTokens = spentTokens.isPresent() && turnTokens.isPresent()
                    ? Optional.of(spentTokens.get().plus(turnTokens.get())) : Optional.empty();
            }
            Optional<String> stoppedBy = last.stoppedBy();
            java.util.OptionalInt exit = last.exitCode();
            if (stoppedBy.isEmpty() && conversation.lost() && (inbox.outstanding() > 0 || inbox.hasUndelivered())) {
                // the brain died with work of its hires still to read: it did not finish, so no check run afterwards proves its step, and the
                // outcome says so instead of the clean answer it gave before it died (the C27 audit)
                exit = conversation.outcome().exitCode();
                stoppedBy = Optional.of("the lead's brain ended" + (exit.isPresent() ? " (exit " + exit.getAsInt() + ")" : "")
                    + " while its hires were still out or their reports unread; those reports go to its chat");
            } else if (stoppedBy.isEmpty() && ceilings.maxToolSteps() > 0 && steps >= ceilings.maxToolSteps()
                && (inbox.outstanding() > 0 || inbox.hasUndelivered())) {
                stoppedBy = Optional.of("tool steps: " + steps + " over the ceiling of " + ceilings.maxToolSteps());
            } else if (stoppedBy.isPresent() && stoppedBy.get().startsWith("tool steps")) {
                stoppedBy = Optional.of("tool steps: " + steps + " over the ceiling of " + ceilings.maxToolSteps());
            }
            // 28h z109: a lead that was stopped does not wait for its hires, so it says which of them still run; they are its hirer's now.
            List<String> stillRunning = HireRegistry.shared().runningWithPlansFor(leadId);
            if (stoppedBy.isPresent() && !stillRunning.isEmpty()) {
                stoppedBy = Optional.of(stoppedBy.get() + "; its hires still running, now yours: " + String.join(", ", stillRunning));
            }
            return new HiredBrain.Outcome(last.text(), last.costUsd(), steps, last.unreadableLines(), last.sawTerminalCost(), last.timedOut(),
                exit, last.stderrTail(), stoppedBy, List.copyOf(names), Optional.empty(), spentTokens, List.copyOf(calls),
                last.brainSessionId(), toolMillis);
        } finally {
            try {
                if (conversation != null) {
                    conversation.end();
                }
            } finally {
                inbox.close();
            }
        }
    }

    /** What a lead's declared checks still leave open after a turn (design step 9): how many have passed, and what its brain is told. */
    public record OpenCheck(int passed, String told, boolean ofPlan) {

        public OpenCheck(int passed, String told) {
            this(passed, told, false);
        }
    }

    /** How long a lead's wait for its hires' reports goes before it asks again whether its brain still lives. */
    static final long DEATH_CHECK_MILLIS = 1_000;

    /**
     * One brain process kept for a whole conversation (D53 (g)): started once, and prompted as often as the conversation has something to say.
     * Measured on 2026-11-08 with Cursor's agent, a process started for every message took 10.9 s to the first word of a conversation and over
     * 3 s to the first word of each later one, and a process kept running 1.4 to 1.9 s.
     */
    public static final class Conversation implements AutoCloseable {

        private final Process child;
        private final Wire wire;
        private final Thread drain;
        private final StringBuilder stderrTail;
        private Thread watchdog;
        private volatile boolean reaped;
        private boolean endedByUs;
        private boolean takesImages;
        private String sessionId;
        private int promptId = 3;
        /** The models the brain offered when it opened this session, and the value of the one it is on; empty when it offers none. */
        private List<Model> models = List.of();
        private String modelValue = "";
        /** Whether the answer that opened the session says what models the brain offers: a new session's always does, a loaded one's only if it names them. */
        private boolean modelsAnswered;

        private StringBuilder said = new StringBuilder();
        private List<String> toolNames = new ArrayList<>();
        private List<ToolCall> toolCalls = new ArrayList<>();
        private OptionalDouble cost = OptionalDouble.empty();
        private String stoppedBy;
        private boolean answered;
        /** What the turn's prompt answer reported spending, when it did (28h z142). */
        private Optional<TokenUsage> tokens = Optional.empty();
        /** Whether the brain keeps its own editors and shell for this conversation (28h D57 (j)). */
        private final OwnTools ownTools;

        private Conversation(Process child, Set<String> allowed, HiredBrain.Ceilings ceilings, OwnTools ownTools) {
            this.ownTools = ownTools;
            this.child = child;
            this.stderrTail = new StringBuilder();
            this.drain = AcpBrainSession.drain(child, stderrTail);
            this.wire = new Wire(child, allowed, ceilings, ownTools);
        }

        /**
         * Starts the brain and opens its session, with no ceiling running afterwards: the conversation waits for its first prompt.
         *
         * @param ownTools whether the brain keeps its own editors and shell; the principal's are withheld (28h D57 (j))
         */
        public static Conversation start(HostProcesses host, Program program, HireSite base, SelfEndpoint mcp, List<String> allowedTools,
                Optional<String> resume, HiredBrain.Ceilings ceilings, OwnTools ownTools) throws IOException {
            Conversation conversation = open(host, program, base, mcp, allowedTools, resume, ceilings, ownTools);
            conversation.disarm();
            return conversation;
        }

        /** The variable every brain the engine starts is given; the studio's hook reads it and writes such a session under a machine client name. */
        static final String MACHINE_SESSION = "JAWATA_MACHINE_SESSION";

        /**
         * The environment a brain is started with: the site's, then the program's, and the machine-session mark last so nothing overrides it. A
         * session the engine starts for a brain is machine-made, and History leaves it out (his ruling 2026-11-18).
         */
        static Map<String, String> environmentOf(HireSite base, Program program) {
            Map<String, String> environment = new LinkedHashMap<>(base.environment());
            environment.putAll(program.environment());
            environment.put(MACHINE_SESSION, "1");
            // 28h-C27-31: where the guard in the brain's own client reads what the engine allows, on every call
            environment.putAll(org.jawata.mcp.access.BrainAccess.environment());
            return environment;
        }

        /**
         * The PATH a brain is started with (28h z21): the one in the environment {@link #environmentOf} builds (an adapter's folder first, from the
         * program), otherwise the engine's own, and "NOT SET" where the site starts the brain with a clean environment that names none.
         */
        public static String pathOf(HireSite base, Program program) {
            String own = environmentOf(base, program).get("PATH");
            if (own != null) {
                return own;
            }
            return base.cleanEnvironment() ? "NOT SET" : java.util.Optional.ofNullable(System.getenv("PATH")).orElse("NOT SET");
        }

        private static Conversation open(HostProcesses host, Program program, HireSite base, SelfEndpoint mcp, List<String> allowedTools,
                Optional<String> resume, HiredBrain.Ceilings ceilings, OwnTools ownTools) throws IOException {
            Map<String, String> environment = environmentOf(base, program);
            Process child = host.start(new HostCommand(program.command(), base.workingDirectory(), environment,
                ceilings.wallClock(), false, base.cleanEnvironment()));
            // A stop from the Agent Control panel kills this process: the read of its output is blocked and a thread interrupt cannot end it.
            HireRegistry.shared().onStop(() -> HostProcesses.reap(child));
            Conversation conversation = new Conversation(child, new HashSet<>(allowedTools == null ? List.of() : allowedTools), ceilings, ownTools);
            conversation.arm(ceilings);
            try {
                conversation.handshake(base, mcp, resume);
            } catch (IOException e) {
                conversation.close();
                throw e;
            }
            // only an answer that names the models is kept; a resumed one that names none would replace what the brain offered with nothing
            if (conversation.modelsAnswered) {
                OFFERED.put(program, conversation.models());
            }
            return conversation;
        }

        private void arm(HiredBrain.Ceilings ceilings) {
            if (ceilings.wallClock() == null) {
                return; // a hire has no wall clock (28h z132): it ends when the brain does, or when it is stopped
            }
            watchdog = new Thread(() -> {
                try {
                    if (!child.waitFor(ceilings.wallClock().toMillis(), TimeUnit.MILLISECONDS)) {
                        reaped = true;
                        HostProcesses.reap(child);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "jawata-acp-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        private void disarm() {
            if (watchdog != null) {
                watchdog.interrupt();
                watchdog = null;
            }
        }

        private void handshake(HireSite base, SelfEndpoint mcp, Optional<String> resume) throws IOException {
            JsonNode init = wire.call(1, "initialize", params -> {
                params.put("protocolVersion", 1);
                params.putObject("clientCapabilities");
            });
            if (init == null) {
                return;
            }
            JsonNode capabilities = init.path("agentCapabilities");
            takesImages = capabilities.path("promptCapabilities").path("image").asBoolean(false);
            String cwd = base.workingDirectory() == null ? "" : base.workingDirectory().toString();
            ArrayNode servers = mcpServers(mcp, capabilities.path("mcpCapabilities").path("http").asBoolean(false), wire.allowed);
            JsonNode opened = null;
            if (resume.isPresent() && capabilities.path("loadSession").asBoolean(false)) {
                String earlier = resume.get();
                opened = wire.call(2, "session/load", params -> {
                    params.put("sessionId", earlier);
                    params.put("cwd", cwd);
                    params.set("mcpServers", servers);
                    sessionOptions(params);
                });
                if (opened != null) {
                    sessionId = earlier;
                    readModels(opened, false);
                }
            }
            if (sessionId == null) {
                Consumer<ObjectNode> newSession = params -> {
                    params.put("cwd", cwd);
                    params.set("mcpServers", servers);
                    sessionOptions(params);
                };
                JsonNode created = wire.call(2, "session/new", newSession);
                if (created == null && wire.refusedForWantOfLogin() && init.path("authMethods").size() > 0) {
                    // A brain that wants its user logged in says so on session/new and lists how (Cursor's agent: cursor_login).
                    // Login is tried once, with the first method it listed, and only after the refusal, so a brain that never
                    // refuses is never sent authenticate.
                    String method = init.path("authMethods").get(0).path("id").asText();
                    if (wire.call(4, "authenticate", params -> params.put("methodId", method)) != null) {
                        created = wire.call(5, "session/new", newSession);
                    } else {
                        wire.loginFailed = true;
                    }
                }
                sessionId = created == null ? null : created.path("sessionId").asText(null);
                readModels(created, true);
                opened = created;
            }
            // 28h Stage 27 (z2): a brain with its own editors withheld is NOT moved into a read-only mode. Cursor's "ask" mode hides every jawata
            // tool with `hire` among them (measured live, finding 28h-C26-18), and a lead that cannot hire cannot hand work on. It stays in its
            // normal mode; what it writes with its own editor outside the documents is read from its stream and flagged afterwards.
        }

        /**
         * Sets the session's own options: the brain loads only the MCP servers this session names, and, when its own tools are withheld, leaves out
         * its editors and its shell (28h D57 (j)). Measured: the
         * Claude adapter honours {@code _meta.claudeCode.options.disallowedTools} on session/new and session/load and then refuses both; a brain
         * that does not know the option ignores it.
         */
        private void sessionOptions(ObjectNode params) {
            ObjectNode options = params.putObject("_meta").putObject("claudeCode").putObject("options");
            // A hired brain loads no MCP server but the ones this session names (28h Stage 27, finding 28h-C27-2): the Claude adapter otherwise
            // reads the user's own servers too, and a hired chief engineer was seen hiring through the user's own jawata server, not this
            // engine. A brain that does not know the option ignores it.
            options.put("strictMcpConfig", true);
            // A hired brain loads the person's own settings and no others (28h Stage 27 (z11)): the Claude process otherwise also reads the
            // project's .claude/settings.json and .claude/settings.local.json, and a rule there (permissions.allow, additionalDirectories, a hook,
            // env) can let a hire read or run what the person's own settings do not allow. The person's own file keeps their deny rules and the
            // guard hooks the studio puts there. A brain that does not know the option ignores it.
            options.putArray("settingSources").add("user");
            // The adapter builds its own settings reader on the session's folder and takes the starting permission mode from a project's own
            // permissions.defaultMode whatever sources are named above (claude-agent-acp, dist/v1/acp-agent.js, SettingsManager and
            // resolveInitialPermissionMode); refusing bypass outright is what keeps a project's file from putting a hire in the mode that runs
            // without asking (the C27 audit, finding 1).
            options.put("allowDangerouslySkipPermissions", false);
            ArrayNode disallowed = options.putArray("disallowedTools");
            (ownTools == OwnTools.WITHHELD ? OWN_WRITERS : OWN_SHELL).forEach(disallowed::add);
        }

        /**
         * The brain's own model option, from the answer that opened the session: its values, and the one it is on. Nothing when it has none.
         * A new session's answer without the option says the brain offers no model; a loaded one that leaves it out says nothing (28h z82).
         */
        private void readModels(JsonNode opened, boolean newSession) {
            if (opened == null) {
                return;
            }
            modelsAnswered = newSession;
            for (JsonNode option : opened.path("configOptions")) {
                if ("model".equals(option.path("id").asText()) || "model".equals(option.path("category").asText())) {
                    List<Model> offered = new ArrayList<>();
                    option.path("options").forEach(choice -> offered.add(new Model(choice.path("value").asText(), choice.path("name").asText(choice.path("value").asText()))));
                    models = List.copyOf(offered);
                    modelValue = option.path("currentValue").asText("");
                    modelsAnswered = true;
                    return;
                }
            }
        }

        /** The models this brain offers for the session, in the order it listed them. */
        public List<Model> models() {
            return models;
        }

        /** The model this session is on: the one the brain said it was on when it opened, or the one last selected. None when the brain offers no models. */
        public Optional<Model> currentModel() {
            return models.stream().filter(model -> model.value().equals(modelValue)).findFirst();
        }

        /**
         * Moves the session to the model named {@code wanted}, matched against the brain's offered values and then its names, ignoring case, through
         * the brain's own model option. False when the brain offers no such model or does not accept the change, and then the session stays on the
         * model it was on: a model the account does not have is never an error that ends a conversation.
         */
        public boolean selectModel(String wanted) {
            return chooseModel(wanted).moved();
        }

        /** The prefix of a request for the newest model of a family ({@code latest:<family>}); the only model text this class holds. */
        private static final String LATEST = "latest:";

        /** The digit groups of a version: a number inside a text, its groups separated by dots or dashes. */
        private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("\\d+(?:[.-]\\d+)*");

        /**
         * Asks the session for a model and says what came of it. {@code wanted} is a model's value or name (matched ignoring case, value first),
         * or {@code latest:<family>}: the offered model whose name or value holds the family, ignoring case, with the highest version (the
         * numbers inside its name, or inside its value when the name holds none; compared group by group as numbers, a missing group lower, a
         * model with no number lowest, the one listed first on a tie). The session is moved through the brain's own model option; when no
         * offered model fits, or the brain does not accept the change, nothing is sent or changed and the answer says what the brain offers.
         */
        public ModelChoice chooseModel(String wanted) {
            if (sessionId == null || wanted == null || wanted.isBlank()) {
                return answered(false, "No model was asked for" + (sessionId == null ? " and the brain has no open session" : "")
                    + "; the session stays on " + onNow() + ".");
            }
            String asked = wanted.strip();
            boolean newest = asked.regionMatches(true, 0, LATEST, 0, LATEST.length());
            String family = newest ? asked.substring(LATEST.length()).strip() : "";
            Optional<Model> found = newest ? newestOf(family) : models.stream().filter(model -> model.value().equalsIgnoreCase(asked)).findFirst()
                .or(() -> models.stream().filter(model -> model.name().equalsIgnoreCase(asked)).findFirst());
            if (found.isEmpty()) {
                return answered(false, "The brain offers no " + (newest ? "model of the family \"" + family + "\"" : "model \"" + asked + "\"")
                    + "; the session stays on " + onNow() + ". It offers " + models.stream().map(Model::name).toList() + ".");
            }
            if (!move(sessionId, found.get().value())) {
                return answered(false, "The brain would not move to " + found.get().name() + "; the session stays on " + onNow() + ".");
            }
            return answered(true, "Moved the session to " + found.get().name() + (newest ? ", the newest of \"" + family + "\" it offers" : "") + ".");
        }

        /** The offered model of {@code family} with the highest version, the first listed on a tie; none when the family is blank or none fits. */
        private Optional<Model> newestOf(String family) {
            String wantedFamily = family.toLowerCase(java.util.Locale.ROOT);
            if (wantedFamily.isEmpty()) {
                return Optional.empty();
            }
            Model best = null;
            List<java.math.BigInteger> bestVersion = List.of();
            for (Model model : models) {
                if (!model.name().toLowerCase(java.util.Locale.ROOT).contains(wantedFamily)
                    && !model.value().toLowerCase(java.util.Locale.ROOT).contains(wantedFamily)) {
                    continue;
                }
                List<java.math.BigInteger> version = versionOf(model);
                if (best == null || compareVersions(version, bestVersion) > 0) {
                    best = model;
                    bestVersion = version;
                }
            }
            return Optional.ofNullable(best);
        }

        /** The groups of the first version number in a model's name, or in its value when the name holds none; empty when neither does. */
        private static List<java.math.BigInteger> versionOf(Model model) {
            for (String text : List.of(model.name(), model.value())) {
                java.util.regex.Matcher found = VERSION.matcher(text);
                if (found.find()) {
                    return java.util.Arrays.stream(found.group().split("[.-]")).map(java.math.BigInteger::new).toList();
                }
            }
            return List.of();
        }

        /** Compares two versions group by group as numbers; a group one has and the other lacks counts for the one that has it. */
        private static int compareVersions(List<java.math.BigInteger> a, List<java.math.BigInteger> b) {
            for (int group = 0; group < Math.max(a.size(), b.size()); group++) {
                if (group >= a.size()) {
                    return -1;
                }
                if (group >= b.size()) {
                    return 1;
                }
                int order = a.get(group).compareTo(b.get(group));
                if (order != 0) {
                    return order;
                }
            }
            return 0;
        }

        private ModelChoice answered(boolean moved, String said) {
            return new ModelChoice(moved, currentModel(), models, said);
        }

        private String onNow() {
            return currentModel().map(Model::name).orElse("its default model");
        }

        private boolean move(String id, String value) {
            try {
                if (wire.call(promptId++, "session/set_config_option", params -> {
                    params.put("sessionId", id);
                    params.put("configId", "model");
                    params.put("value", value);
                }) == null) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
            modelValue = value;
            return true;
        }

        /** What the brain said when it would not open a session, such as that its user must sign in; empty when it said nothing. */
        public String refusal() {
            String said = wire.lastError == null ? "" : wire.lastError.path("message").asText("");
            // A login that failed in its own words ("the account is locked") is still a login the person has to make.
            return wire.loginFailed && !asksForLogin(said) ? "Authentication required, and the login failed: " + said : said;
        }

        /** Whether the brain is still there to be prompted: a process that died between two messages is started again by whoever holds this. */
        public boolean alive() {
            return child.isAlive() && sessionId != null;
        }

        /** Whether the last turn ended at the end of the brain's output with no answer, no ceiling and no clock having ended it. */
        private boolean endedUnanswered;

        /**
         * Whether the brain is gone: its process has ended, or its last turn ran into the end of its output unanswered. The second is said apart
         * because a brain that dies closes its output a moment before its process is seen to end, and in that moment {@link #alive()} still
         * says yes (the full suite, 2026-11-20: a lead took the report it showed a dying brain for read, and it was lost).
         */
        boolean lost() {
            return !alive() || endedUnanswered;
        }

        /** Tells the brain the person stopped its turn; the brain ends the turn with its reply, and the read waiting for that reply returns. */
        public void cancel() {
            if (sessionId == null) {
                return;
            }
            try {
                wire.notifyBrain("session/cancel", params -> params.put("sessionId", sessionId));
            } catch (IOException e) {
                HostProcesses.reap(child); // the brain cannot be told, so the read is ended the only other way there is
            }
        }

        /** Answers one prompt in this conversation, pictures included where the brain takes them, within {@code ceilings}. */
        public HiredBrain.Outcome prompt(String prompt, List<JsonNode> images, HiredBrain.Ceilings ceilings, Consumer<String> onText,
                Consumer<JsonNode> onToolUpdate, Consumer<JsonNode> onThinking) throws IOException {
            turn(prompt, images, ceilings, onText, onToolUpdate, onThinking);
            return outcome();
        }

        private void turn(String prompt, List<JsonNode> images, HiredBrain.Ceilings ceilings, Consumer<String> onText, Consumer<JsonNode> onToolUpdate,
                Consumer<JsonNode> onThinking) throws IOException {
            said = new StringBuilder();
            toolNames = new ArrayList<>();
            toolCalls = new ArrayList<>();
            cost = OptionalDouble.empty();
            stoppedBy = null;
            answered = false;
            endedUnanswered = false;
            tokens = Optional.empty();
            if (sessionId == null) {
                return;
            }
            if (watchdog == null) {
                arm(ceilings);
            }
            wire.beginTurn(ceilings, onText, onToolUpdate, onThinking);
            if (!images.isEmpty() && !takesImages && onText != null) {
                onText.accept("This brain does not take pictures, so the picture was not sent to it.\n\n");
            }
            try {
                String id = sessionId;
                // A new turn starts a new line, so a STEP line that opens it is not read as the end of the turn before (28h z85).
                HireRegistry.shared().noteText("\n");
                JsonNode stop = wire.call(promptId++, "session/prompt", params -> {
                    params.put("sessionId", id);
                    ArrayNode blocks = params.putArray("prompt");
                    blocks.addObject().put("type", "text").put("text", prompt);
                    if (takesImages) {
                        images.forEach(blocks::add);
                    }
                });
                read(stop);
            } finally {
                disarm();
            }
        }

        private void read(JsonNode stop) {
            // 28h z142: the prompt answer carries the turn's own usage; cached input is outside inputTokens, as the recorded Claude sessions show.
            // A brain that died or was stopped answered no prompt, so it reported nothing.
            tokens = TokenUsage.read(stop == null ? null : stop.path("usage"), "inputTokens", false, new String[] {"cachedReadTokens", "cachedWriteTokens"},
                "outputTokens", null, "acp: inputTokens counted without the cached parts, which are added; read from Claude's adapter, other "
                    + "adapters' figures not checked");
            Map<String, Integer> callAt = new HashMap<>();
            java.util.Set<Integer> edits = new java.util.HashSet<>();
            for (JsonNode update : wire.events) {
                switch (update.path("sessionUpdate").asText()) {
                    case "agent_message_chunk" -> said.append(update.path("content").path("text").asText(""));
                    case "tool_call" -> {
                        String name = update.path("title").asText(update.path("kind").asText("tool"));
                        if ("edit".equals(update.path("kind").asText())) {
                            edits.add(toolNames.size());
                            // 28h Stage 27 (z5), live: Claude's adapter opens the write of a new file as "Preparing file…" and names the editor
                            // only in _meta; the title is never renamed, so no rule that reads editor writes saw the file a brain created.
                            String editor = update.path("_meta").path("claudeCode").path("toolName").asText("");
                            if (RunEffects.isCliEditor(editor)) {
                                name = editor;
                            }
                        }
                        // 28h Stage 27 (z24), seen in the hires' records as "the worker never called Bash": Claude's adapter opens a shell call
                        // under the call's own description (kind execute), so it was named by that text and no instruction for Bash found it.
                        if ("execute".equals(update.path("kind").asText())) {
                            String shell = update.path("_meta").path("claudeCode").path("toolName").asText("");
                            if (RunEffects.isCliShell(shell)) {
                                name = shell;
                            }
                        }
                        // 28h z129: the adapter titles a read or search by what it shows ("Read File"), so no reading of Java was seen.
                        String reader = update.path("_meta").path("claudeCode").path("toolName").asText("");
                        if (RunEffects.isCliFileReader(reader)) {
                            name = reader;
                        }
                        callAt.put(update.path("toolCallId").asText(""), toolNames.size());
                        toolNames.add(name);
                        toolCalls.add(new ToolCall(name, update.has("rawInput") ? update.get("rawInput") : JSON.createObjectNode()));
                    }
                    case "tool_call_update" -> {
                        // Cursor's agent opens a tool call as "MCP: tool" with no input and names it in a later update (measured
                        // 2026-11-03): the server and the tool arrive in rawInput, and the call is known from then on by the form the
                        // classification reads, mcp__<server>__<tool>.
                        Integer index = callAt.get(update.path("toolCallId").asText(""));
                        JsonNode raw = update.path("rawInput");
                        if (index != null && raw.hasNonNull("providerIdentifier") && raw.hasNonNull("toolName")) {
                            String name = "mcp__" + raw.get("providerIdentifier").asText() + "__" + raw.get("toolName").asText();
                            toolNames.set(index, name);
                            toolCalls.set(index, new ToolCall(name, raw.has("args") ? raw.get("args") : JSON.createObjectNode()));
                        } else if (index != null && raw.isObject() && !raw.isEmpty()) {
                            // 28h D57 (d): Claude's ACP adapter opens every call with an empty input and streams the input in later updates,
                            // partial and then whole (recorded 2026-11-17); the last input sent is the call's, so an instruction can be
                            // compared with it. An update with no input, as the call runs and completes, leaves it as it is.
                            toolCalls.set(index, new ToolCall(toolCalls.get(index).name(), raw));
                        }
                        // 28h D57 (d): Cursor's agent reports an edit of its own as "Edit File" with the path alone, and the replacement only
                        // as the diff of the completed call (measured 2026-11-17). Such an edit is kept as an Edit of that file with the text
                        // before and after, which an Edit instruction can be compared with; an input that names the replaced text is kept.
                        if (index != null && edits.contains(index) && !toolCalls.get(index).arguments().has("old_string")) {
                            for (JsonNode shown : update.path("content")) {
                                // A file the agent CREATES has a diff with no old text (architect watch of C27): it is an edit of that file all the same.
                                if ("diff".equals(shown.path("type").asText()) && shown.hasNonNull("newText")) {
                                    String name = java.util.Set.of("Edit", "Write", "MultiEdit").contains(toolNames.get(index)) ? toolNames.get(index) : "Edit";
                                    toolNames.set(index, name);
                                    com.fasterxml.jackson.databind.node.ObjectNode edit = JSON.createObjectNode().put("file_path", shown.path("path").asText(""));
                                    if (shown.hasNonNull("oldText")) {
                                        edit.put("oldText", shown.get("oldText").asText());
                                    }
                                    toolCalls.set(index, new ToolCall(name, edit.put("newText", shown.get("newText").asText())));
                                    break;
                                }
                            }
                        }
                        // 28h z120: a call that completed has its whole input, so what the brain's own editor wrote is kept as the hire's now.
                        if (index != null && "completed".equals(update.path("status").asText())) {
                            HireRegistry.shared().noteOwnWrites(List.of(toolCalls.get(index)));
                        }
                    }
                    case "usage_update" -> {
                        JsonNode amount = update.path("cost").path("amount");
                        if (amount.isNumber()) {
                            cost = OptionalDouble.of(amount.asDouble());
                        }
                    }
                    default -> { }
                }
            }
            // 28h z61: Antigravity names a call of an MCP server either in the title ("Run mcp_<server>_<tool>") or only in the input
            // ({ServerName, ToolName, Arguments}), and no rule that reads a call's name knew either form, so a hire's card counted its calls
            // of the engine as other calls. Both are kept as mcp__<server>__<tool>, the form every such rule reads.
            for (int i = 0; i < toolCalls.size(); i++) {
                String called = toolCalls.get(i).name();
                JsonNode input = toolCalls.get(i).arguments();
                String name = null;
                if (input.hasNonNull("ServerName") && input.hasNonNull("ToolName")) {
                    name = "mcp__" + input.get("ServerName").asText() + "__" + input.get("ToolName").asText();
                    input = input.has("Arguments") ? input.get("Arguments") : JSON.createObjectNode();
                } else if (called.startsWith("Run mcp_") && residentToolIn(called) != null) {
                    String tool = residentToolIn(called);
                    name = "mcp__" + called.substring("Run mcp_".length(), called.length() - tool.length() - 1) + "__" + tool;
                }
                if (name != null) {
                    toolNames.set(i, name);
                    toolCalls.set(i, new ToolCall(name, input));
                }
            }
            wire.closeToolTime();
            stoppedBy = wire.stoppedBy;
            answered = stop != null;
            endedUnanswered = stop == null && !reaped && stoppedBy == null;
            if (endedUnanswered) {
                said.append(said.length() == 0 ? "" : "\n").append("(the brain ended before it answered the prompt)");
            }
        }

        private void end() throws InterruptedException {
            disarm();
            try {
                child.getOutputStream().close();
            } catch (IOException ignored) {
                // the child is already gone
            }
            // A brain that has answered has nothing left to finish that is worth a person's wait (Cursor's agent does not exit at all when its input closes).
            if (!child.waitFor(answered ? 1 : 5, TimeUnit.SECONDS)) {
                endedByUs = true;
                HostProcesses.reap(child);
                child.waitFor(2, TimeUnit.SECONDS);
            }
            drain.join(2_000);
        }

        private HiredBrain.Outcome outcome() {
            OptionalInt exit = child.isAlive() ? OptionalInt.empty() : OptionalInt.of(child.exitValue());
            // A brain that answered its prompt and then did not exit when its input closed (measured with Cursor's agent) was ended by this client,
            // and the status of that kill is not a verdict on its work: the run is clean when the answer came, no ceiling and no clock ended it.
            // The same holds for a brain still running because the conversation goes on.
            if (answered && (endedByUs || child.isAlive()) && !reaped && stoppedBy == null) {
                exit = OptionalInt.of(0);
            }
            return new HiredBrain.Outcome(said.toString(), cost, toolNames.size(), wire.unreadable, cost.isPresent(), reaped, exit,
                stderrTail.toString(), Optional.ofNullable(stoppedBy), List.copyOf(toolNames), Optional.empty(), tokens,
                List.copyOf(toolCalls), Optional.ofNullable(sessionId), wire.toolMillis);
        }

        /** Ends the brain's process. */
        @Override
        public void close() {
            try {
                end();
            } catch (InterruptedException e) {
                HostProcesses.reap(child);
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Moves a hire's session to the model its seat names (28h D57 (c)), before the brief is sent. Over ACP the model is a session option, not a
         * command-line flag (Cursor's agent ignores {@code --model} in this mode), so without this every hire ran on the brain's own default. A brain
         * that offers models but not this one refuses the hire, naming what it offers, so a hire never runs on another model than the one chosen; a
         * session that names none (a resumed one) runs as it opened.
         */
        void onModel(String model) throws IOException {
            if (model == null || model.isBlank() || models().isEmpty()) {
                return;
            }
            if (!selectModel(model)) {
                throw new IOException("The brain could not be moved to the model " + model + "; its session offers "
                    + models().stream().map(Model::name).toList() + ".");
            }
        }

        /**
         * Sets the session's reasoning effort through the brain's own effort option (28h z96). A brain that has none, or does not take this
         * level, answers an error and keeps its own; the effort is a setting, never a reason to refuse the hire.
         */
        void onEffort(String effort) throws IOException {
            if (effort == null || effort.isBlank() || sessionId == null) {
                return;
            }
            String id = sessionId;
            wire.call(promptId++, "session/set_config_option", params -> {
                params.put("sessionId", id);
                params.put("configId", "effort");
                params.put("value", effort);
            });
        }
    }

    /** How Cursor's agent titles a permission request for a tool of an MCP server: "jawata-health_check: health_check" (measured against the real agent, S7a). */
    private static final java.util.regex.Pattern RESIDENT_TITLE =
        java.util.regex.Pattern.compile("^" + SelfEndpoint.SERVER_NAME + "[^:]*: (\\w+)$");

    /**
     * The tool name inside a permission title in the form Cursor's agent uses for a tool of the resident, or null for any other title, so a
     * tool of another server is never taken for one of ours.
     */
    /**
     * Whether an update from the brain says it began or ended compacting its context (D6, D40): the tool call the Claude adapter shows for
     * it, named {@code compact} in {@code _meta.claudeCode.toolName}, in progress while it runs and completed after. Measured on
     * 2026-11-06 with {@code @agentclientprotocol/claude-agent-acp} 0.101.0 answering {@code /compact}: a {@code tool_call} titled
     * "Compact conversation" with status {@code in_progress}, then a {@code tool_call_update} {@code completed}, then one carrying the
     * sizes before and after and no status. Another brain that sends no such call is never shown as compacting: its state stays running.
     *
     * @return true for began, false for ended, none for any other update
     */
    static Optional<Boolean> compactionIn(JsonNode update) {
        String kind = update.path("sessionUpdate").asText();
        if (!"tool_call".equals(kind) && !"tool_call_update".equals(kind)) {
            return Optional.empty();
        }
        if (!"compact".equals(update.path("_meta").path("claudeCode").path("toolName").asText())) {
            return Optional.empty();
        }
        String status = update.path("status").asText("");
        if ("in_progress".equals(status) || "pending".equals(status)) {
            return Optional.of(true);
        }
        return "completed".equals(status) || "failed".equals(status) ? Optional.of(false) : Optional.empty();
    }

    /**
     * How Antigravity's agent titles a permission request for a tool of an MCP server: "Run mcp_fake_dummy_fake_0" for the tool dummy_fake_0 of the
     * server fake (measured against agy 1.5.15 on 2026-11-24, 28h z64). A server of ours has no underscore in its name, so the tool is what follows the
     * first underscore after the server name.
     */
    private static final java.util.regex.Pattern ANTIGRAVITY_TITLE =
        java.util.regex.Pattern.compile("^Run mcp_" + SelfEndpoint.SERVER_NAME + "[^_]*_(\\w+)$");

    public static String residentToolIn(String title) {
        java.util.regex.Matcher matched = RESIDENT_TITLE.matcher(title);
        if (matched.matches()) {
            return matched.group(1);
        }
        java.util.regex.Matcher antigravity = ANTIGRAVITY_TITLE.matcher(title);
        return antigravity.matches() ? antigravity.group(1) : null;
    }

    /**
     * The tool of the resident a permission request names in its raw input, or null: Antigravity's agent titles a call of a server configured in its
     * own files only "MCP Tool experience", with no server in the title, and puts the server and the tool in the raw input as
     * {@code {"ServerName": "jawata-javata-dev", "ToolName": "experience", "Arguments": {...}}} (measured against agy 1.5.15 on 2026-11-24, 28h z64).
     * A server whose name does not begin with ours is never taken for the resident.
     */
    public static String residentToolOf(JsonNode rawInput) {
        String server = rawInput.path("ServerName").asText("");
        String tool = rawInput.path("ToolName").asText("");
        return server.startsWith(SelfEndpoint.SERVER_NAME) && !tool.isEmpty() ? tool : null;
    }

    /** The resident as the brain's one tool server: over HTTP where the brain can attach one, else as a stdio program; none when there is no endpoint. 
     * @param allowed TODO*/
    private static ArrayNode mcpServers(SelfEndpoint mcp, boolean brainCanAttachHttp, Set<String> allowed) {
        ArrayNode servers = JSON.createArrayNode();
        if (mcp != null && brainCanAttachHttp) {
            ObjectNode server = servers.addObject();
            server.put("type", "http");
            server.put("name", SelfEndpoint.SERVER_NAME);
            // The session id goes in the URL as well as in a header (see SelfEndpoint#urlWithSession).
            server.put("url", mcp.urlWithSession());
            ArrayNode headers = server.putArray("headers");
            ObjectNode header = headers.addObject();
            header.put("name", "Authorization");
            header.put("value", "Bearer " + mcp.token());
            if (mcp.sessionId() != null) {
                ObjectNode session = headers.addObject();
                session.put("name", SelfEndpoint.SESSION_HEADER);
                session.put("value", mcp.sessionId());
            }
        } else if (mcp != null) {
            // 28h z67: a brain that cannot attach an HTTP server (Antigravity: mcpCapabilities.http false) is given the same endpoint as a stdio program
            // that carries each message to it, so its calls reach THIS engine, under this session, and its hires are seen by the chat.
            stdioServerFor(mcp, allowed).ifPresent(servers::add);
        }
        return servers;
    }

    /**
     * The resident as a stdio server (the program {@code jawata.jar --mcp} of the dist this engine runs from), or none when the dist's boot jar
     * cannot be found. Every ACP agent must accept a stdio server, which is why this is the way in for a brain that takes no HTTP one.
     * @param allowed the names of the tools the brain's seat may call; the program asks the engine for those alone, none for all of them
     */
    static Optional<ObjectNode> stdioServerFor(SelfEndpoint mcp, Set<String> allowed) {
        String dist = System.getProperty("jawata.dist.root");
        if (dist == null || dist.isBlank()) {
            return Optional.empty();
        }
        java.nio.file.Path jar = java.nio.file.Path.of(dist, "jawata.jar");
        if (!java.nio.file.Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        ObjectNode server = JSON.createObjectNode();
        server.put("name", SelfEndpoint.SERVER_NAME + "-engine");
        server.put("command", org.jawata.mcp.runtime.ChildJvm.binary(null, "java").toString());
        ArrayNode args = server.putArray("args");
        args.add("-jar");
        args.add(jar.toString());
        args.add("--mcp");
        ArrayNode env = server.putArray("env");
        env.addObject().put("name", "JAWATA_MCP_URL").put("value", mcp.urlWithSession());
        env.addObject().put("name", "JAWATA_KEY").put("value", mcp.token());
        // The brain is refused every tool its seat may not call, so the program asks the engine for those alone: that leaves each of them the room
        // for a whole definition in the list the brain can take (28h z67).
        List<String> names = allowed.stream().filter(name -> name.matches("[a-z][a-z0-9_]*") && !name.startsWith("mcp_")).sorted().toList();
        if (!names.isEmpty()) {
            env.addObject().put("name", "JAWATA_MCP_TOOLS").put("value", String.join(",", names));
        }
        return Optional.of(server);
    }

    private static Thread drain(Process child, StringBuilder tail) {
        Thread drain = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String line = err.readLine(); line != null; line = err.readLine()) {
                    synchronized (tail) {
                        if (tail.length() < STDERR_TAIL_LIMIT) {
                            tail.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
                // the stream dies with the process
            }
        }, "jawata-acp-drain-err");
        drain.setDaemon(true);
        drain.start();
        return drain;
    }

    /** The brain's stdio as JSON-RPC lines: one request at a time, the brain's own requests answered while a reply is awaited. */
    private static final class Wire {

        private final BufferedReader in;
        private final OutputStream out;
        private final Set<String> allowed;
        final List<JsonNode> events = new ArrayList<>();
        private final Process child;
        private HiredBrain.Ceilings ceilings;
        private int steps;
        private double spent;
        int unreadable;
        boolean collect;
        String stoppedBy;
        /** The tool calls open right now, by their id; the time any of them is open is time in a tool. */
        private final java.util.Set<String> openCalls = new java.util.HashSet<>();
        /** When the first of the calls now open was opened, in nanoseconds; 0 when none is open. */
        private long openedAt;
        /** Milliseconds this turn in which at least one tool call was open, summed from the moments the updates were read. */
        long toolMillis;
        /** Called with each piece of the answer the moment it is read, so a host sees it stream; null when nobody is listening. */
        Consumer<String> onText;
        /** Called with each tool call update the moment it is read; null when nobody is listening. */
        Consumer<JsonNode> onToolUpdate;
        /** Called with each thinking chunk and each plan update the moment it is read; null when nobody is listening. */
        Consumer<JsonNode> onThinking;
        /** Whether the brain's own editors and shell are withheld, so a permission request for one is refused whatever the allowlist says. */
        private final OwnTools ownTools;

        Wire(Process child, Set<String> allowed, HiredBrain.Ceilings ceilings, OwnTools ownTools) {
            this.child = child;
            this.in = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            this.out = child.getOutputStream();
            this.allowed = allowed;
            this.ceilings = ceilings;
            this.ownTools = ownTools;
        }

        /** Whether a permission request asks for a tool of the brain's own that this brain is refused: edits and commands, or only commands. */
        private boolean withheld(JsonNode tool) {
            String kind = tool.path("kind").asText("");
            String title = tool.path("title").asText("");
            if (ownTools == OwnTools.WITHHELD) {
                return WRITING_KINDS.contains(kind) || OWN_WRITERS.contains(RunEffects.bareName(title));
            }
            // a worker keeps its editors; a command of its own is refused, a jawata call is not one
            return "execute".equals(kind) && !title.startsWith("mcp__") || OWN_SHELL.contains(RunEffects.bareName(title));
        }

        /** Readies the wire for the next prompt's answer: what streams from here on is that answer, not an earlier one or a history a load replayed. */
        void beginTurn(HiredBrain.Ceilings turnCeilings, Consumer<String> text, Consumer<JsonNode> toolUpdate, Consumer<JsonNode> thinking) {
            ceilings = turnCeilings;
            onText = text;
            onToolUpdate = toolUpdate;
            onThinking = thinking;
            events.clear();
            openCalls.clear();
            openedAt = 0;
            toolMillis = 0;
            steps = 0;
            spent = 0;
            stoppedBy = null;
            collect = true;
        }

        private synchronized void send(ObjectNode message) throws IOException {
            out.write((message.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** Sends a notification, from any thread: the answer to a request being read on another thread is not waited for here. */
        void notifyBrain(String method, Consumer<ObjectNode> fill) throws IOException {
            ObjectNode notification = JSON.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            fill.accept(notification.putObject("params"));
            send(notification);
        }

        /** The next line the brain wrote, or null at the end of its output. Lines that are not JSON are counted. */
        private JsonNode next() throws IOException {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    return JSON.readTree(line);
                } catch (IOException e) {
                    unreadable++;
                }
            }
            return null;
        }

        /** The error object of the last reply the brain gave to a request, or null when that reply was a result. */
        private JsonNode lastError;

        /** Whether the brain refused to open a session for want of a login and the login it was then sent failed. */
        private boolean loginFailed;

        /** Whether the last reply was a refusal that says the user must log in first, in the words that make it worth sending the login. */
        boolean refusedForWantOfLogin() {
            return lastError != null && lastError.path("message").asText("").toLowerCase(java.util.Locale.ROOT).contains("authentication required");
        }

        /** Sends a request and returns its result, or null when the brain ended or answered with an error. */
        JsonNode call(int id, String method, Consumer<ObjectNode> fill) throws IOException {
            ObjectNode request = JSON.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            fill.accept(request.putObject("params"));
            send(request);
            for (JsonNode message = next(); message != null; message = next()) {
                if (message.has("id") && !message.has("method")) {
                    if (message.get("id").asInt(-1) == id) {
                        lastError = message.get("error");
                        return lastError != null ? null : message.path("result");
                    }
                } else if (message.has("id")) {
                    answerRequest(message);
                } else if (collect && "session/update".equals(message.path("method").asText())) {
                    JsonNode update = message.path("params").path("update");
                    events.add(update);
                    noteToolTime(update);
                    noteProgress(update);
                    if (onText != null && "agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                        onText.accept(update.path("content").path("text").asText(""));
                    }
                    String updateKind = update.path("sessionUpdate").asText();
                    if (onToolUpdate != null && ("tool_call".equals(updateKind) || "tool_call_update".equals(updateKind))) {
                        onToolUpdate.accept(update);
                    }
                    if (onThinking != null && ("agent_thought_chunk".equals(updateKind) || "plan".equals(updateKind))) {
                        onThinking.accept(update);
                    }
                    if (exceeded(update)) {
                        HostProcesses.reap(child);
                        return null;
                    }
                }
            }
            return null;
        }

        /**
         * Adds to {@link #toolMillis} the time between a tool call opening and its last open call closing, read at the moments the updates
         * arrive: a call is open from its {@code tool_call} until an update of it says completed or failed, and two calls open together
         * count once. A call still open when the turn ends is closed by {@link #closeToolTime()}.
         */
        private void noteToolTime(JsonNode update) {
            String kind = update.path("sessionUpdate").asText();
            String id = update.path("toolCallId").asText("");
            String status = update.path("status").asText("");
            if ("tool_call".equals(kind) && !"completed".equals(status) && !"failed".equals(status)) {
                if (openCalls.isEmpty()) {
                    openedAt = System.nanoTime();
                }
                openCalls.add(id);
            } else if (("tool_call".equals(kind) || "tool_call_update".equals(kind)) && ("completed".equals(status) || "failed".equals(status))
                && openCalls.remove(id) && openCalls.isEmpty()) {
                toolMillis += (System.nanoTime() - openedAt) / 1_000_000L;
            }
        }

        /** Closes the time of any call still open at the end of the turn, so a brain stopped in a tool counts the time it spent there. */
        void closeToolTime() {
            if (!openCalls.isEmpty()) {
                toolMillis += (System.nanoTime() - openedAt) / 1_000_000L;
                openCalls.clear();
            }
        }

        /**
         * Tells the registry of running hires what this update shows of the hire on this thread: one more tool call, or the context size and
         * cost a usage report carries. A report is read as the brain sent it; a figure it does not carry is passed on as absent.
         */
        private static void noteProgress(JsonNode update) {
            String kind = update.path("sessionUpdate").asText();
            Optional<Boolean> compaction = compactionIn(update);
            if (compaction.isPresent()) {
                // The brain compacting its context is not a tool call of the hire's work.
                HireRegistry.shared().noteCompaction(compaction.get());
            } else if ("agent_message_chunk".equals(kind)) {
                // 28h z85: its text as it arrives, so the lines it writes for its lead can be read while it runs.
                HireRegistry.shared().noteText(update.path("content").path("text").asText(""));
            } else if ("tool_call".equals(kind)) {
                HireRegistry.shared().noteToolCall(update.path("toolCallId").asText(""), update.path("title").asText(""), update.path("rawInput"));
            } else if ("tool_call_update".equals(kind) && update.path("rawInput").isObject() && !update.path("rawInput").isEmpty()) {
                // 28h z129: Claude's adapter sends a call's input after opening it, so the call's line is written again with it.
                HireRegistry.shared().noteCallInput(update.path("toolCallId").asText(""), update.path("rawInput"));
            } else if ("usage_update".equals(kind)) {
                JsonNode used = update.path("used");
                JsonNode amount = update.path("cost").path("amount");
                HireRegistry.shared().noteUsage(used.isNumber() ? java.util.OptionalLong.of(used.asLong()) : java.util.OptionalLong.empty(),
                    amount.isNumber() ? OptionalDouble.of(amount.asDouble()) : OptionalDouble.empty());
            }
        }

        /** Counts a streamed tool call or cost against the ceilings as it arrives; true, with {@link #stoppedBy} set, once one is passed. */
        private boolean exceeded(JsonNode update) {
            String kind = update.path("sessionUpdate").asText();
            if ("tool_call".equals(kind)) {
                steps++;
            } else if ("usage_update".equals(kind) && update.path("cost").path("amount").isNumber()) {
                spent = update.path("cost").path("amount").asDouble();
            }
            if (ceilings.maxToolSteps() > 0 && steps > ceilings.maxToolSteps()) {
                stoppedBy = "tool steps: " + steps + " over the ceiling of " + ceilings.maxToolSteps();
            } else if (ceilings.maxCostUsd() != null && spent > ceilings.maxCostUsd()) {
                stoppedBy = "cost: " + spent + " over the ceiling of " + ceilings.maxCostUsd();
            }
            return stoppedBy != null;
        }

        /** Answers a request the brain sent: a permission request by the seat's allowlist, anything else as not supported. */
        private void answerRequest(JsonNode request) throws IOException {
            ObjectNode reply = JSON.createObjectNode();
            reply.put("jsonrpc", "2.0");
            reply.set("id", request.get("id"));
            if ("session/request_permission".equals(request.path("method").asText())) {
                JsonNode tool = request.path("params").path("toolCall");
                String name = tool.path("title").asText(tool.path("kind").asText(""));
                String ofTheResident = residentToolIn(name);
                if (ofTheResident == null) {
                    ofTheResident = residentToolOf(tool.path("rawInput"));
                }
                // D22, the loop for lookups: a seat that may use the shell is still refused a text search over Java source
                boolean listed = allowed.contains(name) || allowed.contains(RunEffects.bareName(name))
                    || ofTheResident != null && allowed.contains(ofTheResident);
                // A call of the resident is never "a command of the brain's own", whatever kind the brain files it under (agy files a tool of an MCP
                // server as kind execute, which a brain whose own tools are withheld is refused).
                String wanted = listed && (ofTheResident != null || !withheld(tool)) && LookupDriver.refusal(tool.path("rawInput")).isEmpty() ? "allow" : "reject";                String chosen = null;
                for (JsonNode option : request.path("params").path("options")) {
                    if (option.path("kind").asText("").startsWith(wanted)) {
                        chosen = option.path("optionId").asText();
                        break;
                    }
                }
                ObjectNode outcome = reply.putObject("result").putObject("outcome");
                if (chosen == null) {
                    outcome.put("outcome", "cancelled");
                } else {
                    outcome.put("outcome", "selected");
                    outcome.put("optionId", chosen);
                }
            } else {
                ObjectNode error = reply.putObject("error");
                error.put("code", METHOD_NOT_FOUND);
                error.put("message", "this client does not serve " + request.path("method").asText());
            }
            send(reply);
        }
    }
}
