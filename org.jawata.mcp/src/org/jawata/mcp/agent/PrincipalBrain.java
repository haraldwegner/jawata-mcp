package org.jawata.mcp.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.execution.AcpBrainSession;
import org.jawata.mcp.execution.HireSite;
import org.jawata.mcp.execution.HiredBrain;
import org.jawata.mcp.execution.Hirer;
import org.jawata.mcp.execution.ScheduledHires;
import org.jawata.mcp.execution.SeatHire;
import org.jawata.mcp.execution.SeatPrompts;
import org.jawata.mcp.execution.SeatTree;
import org.jawata.mcp.execution.SelfEndpoint;
import org.jawata.spi.host.McpEndpoint;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What answers a host's prompt (Sprint 28h, S7a, D1): the principal seat, main, run in a hired brain through the engine's ACP client. The
 * prompt goes to the brain, the brain is given the engine as its one tool server and the allowlist the hirer builds for main, and what the
 * brain says reaches the host as it is said. Each answered prompt leaves a hire record; the brain's session id is kept, so the next prompt
 * of the same connection continues the same brain session.
 *
 * <p>The brain is named by one host setting, {@value #SETTING}: a JSON object {@code {"command":[...],"env":{...}}}, the command line the
 * brain's own installation defines. With none, the agent says so and stays silent otherwise, as before.</p>
 */
public final class PrincipalBrain implements AgentBrain {

    /** The host setting that names the brain to hire for the principal seat. */
    public static final String SETTING = Hirer.MAIN_BRAIN_SETTING;

    /** The seat whose file names the brain of a side discussion; a file that names none leaves it on the principal's brain. */
    static final String SIDE_SEAT = "btw";

    /**
     * One answered prompt, as the hire record says it.
     *
     * @param seat           the seat that ran
     * @param command        the program that was started
     * @param brainSessionId the brain's own session id, if it opened one
     * @param toolSteps      the tool calls the brain made
     * @param clean          whether the brain ended cleanly
     * @param stoppedBy      the ceiling that stopped it, if one did
     * @param elapsed        how long the prompt took
     * @param vendor         the brain product the command started, named as its dialect names itself, or none when the command names none
     */
    public record HireRecord(String seat, List<String> command, Optional<String> brainSessionId, int toolSteps, boolean clean,
            Optional<String> stoppedBy, Duration elapsed, Optional<String> vendor) {
    }

    private final HostProcesses host;
    private volatile Optional<AcpBrainSession.Program> program;
    private HireSite site; // guarded by starting: the folder is the conversation's, told by the host when it opens
    private final SelfEndpoint self;
    private final List<String> engineTools;
    private final Hirer hirer;
    private final HiredBrain.Ceilings ceilings;
    private final List<HireRecord> records = new ArrayList<>();
    private volatile Optional<String> resume = Optional.empty();
    /** The principal seat's own instructions, as its file says them now; none outside a seats folder (D53 (h)). */
    private final java.util.function.Supplier<Optional<String>> stance;
    /** The brain process of this conversation (D53 (g)); guarded by itself being the lock's subject, {@link #starting}. */
    private AcpBrainSession.Conversation conversation;
    private final Object starting = new Object();
    /** Set when the person has chosen another brain: the process goes at the next prompt, so the prompt running now is not cut off. */
    private boolean brainChanged;
    /** Pictures that go with the next prompt. */
    private volatile List<JsonNode> images = List.of();
    /** What has been said, so a brain the person moves the principal onto knows the conversation (D53 (i)). */
    private final StringBuilder transcript = new StringBuilder();
    private static final int TRANSCRIPT_LIMIT = 60_000;

    /**
     * The session id this brain presents to the resident on every tool call (Sprint 28h, S3, D30), so the resident keeps its calls in a
     * history of their own and not in the one shared by callers that send none. One per brain, so two connections do not share one.
     */
    private final String sessionKey = "principal-" + java.util.UUID.randomUUID();

    public PrincipalBrain(HostProcesses host, Optional<AcpBrainSession.Program> program, HireSite site, SelfEndpoint self,
            List<String> engineTools, Hirer hirer, HiredBrain.Ceilings ceilings) {
        this(host, program, site, self, engineTools, hirer, ceilings, Optional::empty);
    }

    PrincipalBrain(HostProcesses host, Optional<AcpBrainSession.Program> program, HireSite site, SelfEndpoint self,
            List<String> engineTools, Hirer hirer, HiredBrain.Ceilings ceilings, java.util.function.Supplier<Optional<String>> stance) {
        this.stance = stance;
        this.host = host;
        this.program = program;
        this.site = site;
        this.self = self;
        this.engineTools = List.copyOf(engineTools);
        this.hirer = hirer;
        this.ceilings = ceilings;
    }

    /**
     * The principal brain for what the engine offers at this moment: the brain the host setting names, the engine's MCP address as its tool
     * server, and the engine's published tools. A connection opened later asks again, so a changed setting takes effect at the next
     * connection.
     */
    public static PrincipalBrain over(EngineApi engine, Path workingDirectory) {
        Optional<AcpBrainSession.Program> program = programOfSeat(engine, "main").or(() -> engine.settings().get(SETTING).flatMap(AcpBrainSession::parseProgram));
        SelfEndpoint self = engine.mcp().map(PrincipalBrain::endpointOf).orElse(null);
        List<Map<String, Object>> definitions = engine.tools().getToolDefinitions();
        List<String> tools = definitions.stream().map(d -> String.valueOf(d.get("name"))).toList();
        Set<String> codeChanging = codeChangingTools(definitions);
        // D51: who hires whom is what the seat files of the seats folder declare, laid over the signed tree.
        Hirer hirer = new Hirer(SeatTree.forSetting(engine.settings().get(ScheduledHires.SEATS_SETTING), codeChanging).tree(), Map.of(), codeChanging);
        SeatPrompts seats = new SeatPrompts(() -> engine.settings().get(ScheduledHires.SEATS_SETTING).filter(folder -> !folder.isBlank()).map(Path::of));
        return new PrincipalBrain(HostProcesses.system(), program, HireSite.in(workingDirectory), self, tools, hirer,
            HiredBrain.Ceilings.of(Duration.ofMinutes(30)), () -> seats.text("main"));
    }

    /**
     * The brain of a side discussion (D53 (b)): the program the side setting names, and the principal's where it names none. It answers as itself,
     * without the principal seat's instructions: a side discussion is a question beside the work.
     */
    public static PrincipalBrain forSide(EngineApi engine, Path workingDirectory) {
        PrincipalBrain principal = over(engine, workingDirectory);
        Optional<AcpBrainSession.Program> side = programOfSeat(engine, SIDE_SEAT).or(() -> principal.program);
        return new PrincipalBrain(principal.host, side, principal.site, principal.self, principal.engineTools, principal.hirer, principal.ceilings);
    }

    /**
     * The program of the brain a seat's own file names (D53 (j)): the default the person set in the studio's Seats view, read for each new
     * conversation. None when the file names no brain or the registry has no program for it, and the host setting then decides.
     */
    private static Optional<AcpBrainSession.Program> programOfSeat(EngineApi engine, String seat) {
        Optional<Path> folder = engine.settings().get(ScheduledHires.SEATS_SETTING).filter(text -> !text.isBlank()).map(Path::of);
        Optional<String> registry = engine.settings().get(Hirer.BRAINS_SETTING);
        if (folder.isEmpty() || registry.isEmpty()) {
            return Optional.empty();
        }
        return new SeatPrompts(() -> folder).brainNamedBy(seat).flatMap(brain -> AcpBrainSession.programFor(registry.get(), brain));
    }

    /**
     * The published tools that change code, read from the flag each tool publishes of itself ({@code _meta."jawata/changesCode"}, the
     * engine's own answer to "did this call change code?") instead of from a list kept here. The annotations cannot answer it:
     * {@code destructiveHint} is absent for {@code format}, {@code quick_fix}, {@code refactoring} and {@code dependency}, which change
     * code. The principal is a lead: it carries every other tool and none of these, so the code is changed by the seats that are allowed
     * to, never by the one that decides, and a tool added later is held back from it the day it says it changes code.
     */
    static Set<String> codeChangingTools(List<Map<String, Object>> definitions) {
        return SeatTree.codeChangingIn(definitions);
    }

    /**
     * The binaries each brain is started by that its seat spellings do not already name: Cursor's CLI installs as {@code agent}, and the
     * Claude and Codex ACP adapters are started by their package names.
     */
    private static final Map<String, SeatHire.Brain> STARTED_BY = Map.of(
        "agent", SeatHire.Brain.CURSOR,
        "claude-agent-acp", SeatHire.Brain.CLAUDE_CODE,
        "claude-code-acp", SeatHire.Brain.CLAUDE_CODE,
        "codex-acp", SeatHire.Brain.CODEX);

    /**
     * The brain product a start command runs, named the way its dialect names itself ({@code cursor}, {@code copilot},
     * {@code claude-code}, {@code codex}): the first word of the command line that is the binary of one of them, taken without its folder
     * and its {@code .exe} or {@code .cmd}. None when no word is, because a vendor is read from the command line and never guessed.
     */
    static Optional<String> vendorOf(List<String> command) {
        for (String word : command) {
            String name = word.substring(Math.max(word.lastIndexOf('/'), word.lastIndexOf('\\')) + 1).toLowerCase(java.util.Locale.ROOT);
            name = name.replaceFirst("\\.(exe|cmd|bat)$", "");
            Optional<SeatHire.Brain> brain = SeatHire.Brain.named(name).filter(known -> known != SeatHire.Brain.SCRIPT);
            if (brain.isEmpty()) {
                brain = Optional.ofNullable(STARTED_BY.get(name));
            }
            if (brain.isPresent()) {
                return Optional.of(brain.get().label());
            }
        }
        return Optional.empty();
    }

    private static SelfEndpoint endpointOf(McpEndpoint endpoint) {
        return new SelfEndpoint(endpoint.url().toString(), endpoint.key().orElse(""));
    }

    @Override
    public void answer(String prompt, Consumer<String> chunks) {
        answer(prompt, chunks, null);
    }

    @Override
    public java.util.Optional<String> toolSessionKey() {
        return self == null ? java.util.Optional.empty() : java.util.Optional.of(sessionKey);
    }

    @Override
    public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
        answer(prompt, chunks, toolCalls, null);
    }

    @Override
    public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls, Consumer<JsonNode> thinking) {
        if (program.isEmpty()) {
            chunks.accept(AgentFace.NO_BRAIN);
            return;
        }
        long started = System.nanoTime();
        boolean[] streamed = {false};
        HiredBrain.Outcome outcome;
        List<JsonNode> pictures = images;
        images = List.of();
        String refusal;
        try {
            AcpBrainSession.Conversation brain = conversation();
            outcome = brain.prompt(opening(prompt), pictures, ceilings,
                piece -> {
                    streamed[0] = true;
                    chunks.accept(piece);
                }, toolCalls, thinking);
            refusal = brain.refusal();
        } catch (IOException e) {
            chunks.accept("jawata's agent could not reach its brain: " + e.getMessage());
            return;
        }
        outcome.brainSessionId().ifPresent(id -> resume = Optional.of(id));
        if (!streamed[0] && !outcome.text().isBlank()) {
            chunks.accept(outcome.text());
        }
        if (outcome.brainSessionId().isEmpty()) {
            // Most often a brain that wants its user signed in: the panel says so instead of staying silent (D53 (b)).
            chunks.accept("The brain did not open a session" + (refusal.isBlank() ? ". Is it installed and signed in?" : ": " + refusal));
        }
        remember(prompt, outcome.text());
        synchronized (records) {
            records.add(new HireRecord("main", program.get().command(), outcome.brainSessionId(), outcome.toolSteps(), outcome.clean(),
                outcome.stoppedBy(), Duration.ofNanos(System.nanoTime() - started), vendorOf(program.get().command())));
        }
    }

    /** The brain process of this conversation, started if there is none, it has died, or the person has chosen another brain. */
    private AcpBrainSession.Conversation conversation() throws IOException {
        synchronized (starting) {
            if (brainChanged && conversation != null) {
                conversation.close();
                conversation = null;
            }
            if (brainChanged) {
                resume = Optional.empty();
                brainChanged = false;
            }
            if (conversation == null || !conversation.alive()) {
                conversation = AcpBrainSession.Conversation.start(host, program.get(), site, self == null ? null : self.forSession(sessionKey),
                    hirer.allowlist("main", engineTools), resume, ceilings);
            }
            return conversation;
        }
    }

    /**
     * What the brain is sent for the first prompt it sees: the principal seat's own instructions, what has been said so far when it came in part
     * way through, and the prompt. A later prompt is the prompt alone, because the brain's session already holds the rest (D53 (h)).
     */
    private String opening(String prompt) {
        if (resume.isPresent()) {
            return prompt;
        }
        StringBuilder opening = new StringBuilder();
        stance.get().ifPresent(text -> opening.append("You are jawata, the principal agent the person is talking to in this conversation.\n\n").append(text).append("\n\n")
            .append("Those rules are for work. A greeting or a plain question is answered at once, in a sentence or two, without calling a tool.\n\n"));
        synchronized (transcript) {
            if (transcript.length() > 0) {
                opening.append("What has been said in this conversation so far:\n\n").append(transcript).append("\n");
            }
        }
        return opening.append(prompt).toString();
    }

    private void remember(String prompt, String answer) {
        synchronized (transcript) {
            transcript.append("The person: ").append(prompt).append("\n\nThe principal: ").append(answer).append("\n\n");
            if (transcript.length() > TRANSCRIPT_LIMIT) {
                transcript.delete(0, transcript.length() - TRANSCRIPT_LIMIT);
            }
        }
    }

    @Override
    public Optional<String> currentBrain() {
        return program.flatMap(known -> vendorOf(known.command()));
    }

    @Override
    public boolean useBrain(String registry, String name) {
        Optional<AcpBrainSession.Program> next = AcpBrainSession.programFor(registry, name);
        if (next.isEmpty()) {
            return false;
        }
        synchronized (starting) {
            program = next;
            brainChanged = true;
        }
        return true;
    }

    @Override
    public void attachImages(List<JsonNode> pictures) {
        images = List.copyOf(pictures);
    }

    @Override
    public void cancel() {
        synchronized (starting) {
            if (conversation != null) {
                conversation.cancel();
            }
        }
    }

    /** A new conversation owes nothing to the last one: the brain that answered it goes, and what was said there is not carried over (D53 (g)). */
    private void forgetTheLastConversation() {
        synchronized (starting) {
            if (resume.isEmpty()) {
                return;
            }
            if (conversation != null) {
                conversation.close();
                conversation = null;
            }
            resume = Optional.empty();
        }
        synchronized (transcript) {
            transcript.setLength(0);
        }
    }

    @Override
    public void workIn(String folder) {
        if (folder == null || folder.isBlank()) {
            return; // a host that names no folder leaves the one this engine started in
        }
        synchronized (starting) {
            site = HireSite.in(Path.of(folder));
        }
    }

    /** Starts the brain beside the opening of the conversation, so the first message does not wait for it; a failure is reported by that message. */
    @Override
    public void prepare() {
        if (program.isEmpty()) {
            return;
        }
        forgetTheLastConversation();
        Thread warm = new Thread(() -> {
            try {
                conversation();
            } catch (IOException | RuntimeException e) {
                // the first prompt starts it again and tells the person what went wrong
            }
        }, "jawata-principal-warm");
        warm.setDaemon(true);
        warm.start();
    }

    @Override
    public void close() {
        synchronized (starting) {
            if (conversation != null) {
                conversation.close();
                conversation = null;
            }
        }
    }

    /** The hire records of the prompts answered so far, oldest first. */
    public List<HireRecord> records() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }
}
