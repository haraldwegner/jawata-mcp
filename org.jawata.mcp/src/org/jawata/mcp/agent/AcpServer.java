package org.jawata.mcp.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jawata.mcp.agent.ReviewBeforeSend.Reviewer;
import org.jawata.mcp.execution.Hirer;
import org.jawata.mcp.execution.RunningHires;
import org.jawata.mcp.execution.SeatBrainPicks;
import org.jawata.mcp.execution.SeatHire;
import org.jawata.mcp.execution.SeatTree;
import org.jawata.mcp.protocol.JsonRpcMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The agent's side of one ACP connection (Sprint 28h, D1; contract Level 0): it answers {@code initialize}, {@code session/new},
 * {@code session/set_config_option} and {@code session/prompt}, streams the answer as {@code session/update} messages and ends the prompt
 * with its stop reason. Messages are the engine's own {@link JsonRpcMessage}: ACP is JSON-RPC, and one codec serves both protocols.
 *
 * <p>The turn boundary (S8): a brain ending its turn is not yet the end of the prompt. {@link TurnBoundary} is asked first, and a step
 * still waiting for its gate either has the brain prompted again (while the person has granted autonomy, as often as the loop may spend)
 * or is named in the answer. The grant of autonomy is the plain select config option {@code autonomy}, set by the person's channel only.</p>
 *
 * <p>What it does not do yet is named by the plan, stage by stage: history and the hire views (S9).</p>
 */
public final class AcpServer {

    static final int PROTOCOL_VERSION = 1;
    static final String AGENT_NAME = "jawata";
    static final String AGENT_VERSION = "5.0-dev";
    /** The id of the select config option that carries the grant of autonomy (agreed with the 28i chat). */
    static final String AUTONOMY = "autonomy";
    /** The prefix of the select config options that carry one seat's brain and model: the id is this and the seat's name (contract E6). */
    static final String SEAT_MODEL = "seat-model:";
    /** The value of a seat's option that leaves the choice to the seat's own file. */
    static final String SEAT_FILE = "seat-file";

    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int NOT_PERMITTED = -32003;
    /** How many sessions {@code session/list} returns, the most recently active first. */
    private static final int SESSIONS_LISTED = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentBrain brain;
    private final Supplier<List<AdvertisedCommand>> commands;
    private final Consumer<JsonRpcMessage> out;
    private final TurnBoundary boundary;
    private final AgentRecorder recorder;
    /** Supplies the brain a side thread is answered by; {@code null} answers it with {@link #brain}. */
    private final Supplier<AgentBrain> sideBrains;
    /** The review every message of the main seat passes before the person reads it (S11, D25); {@code null} sends them as they are written. */
    private final Reviewer reviewer;
    /** The brain of each side thread, made when its first question arrives and kept so the thread has a conversation of its own. */
    private final Map<String, AgentBrain> sideBrainOf = new ConcurrentHashMap<>();
    /** The hires the brain has made through this connection, read from its tool calls as they stream. */
    private final HireWatch hires = new HireWatch(System::currentTimeMillis, () -> this.brainRegistry.get());
    private final Map<String, PrincipalSession> sessions = new ConcurrentHashMap<>();
    /** The thread running each session's prompt, so the person's cancel can interrupt the brain's work. */
    private final Map<String, Thread> running = new ConcurrentHashMap<>();
    /** Sessions whose running prompt the person cancelled; read at each turn end so a cancelled prompt is not resumed. */
    private final java.util.Set<String> cancelled = ConcurrentHashMap.newKeySet();

    AcpServer(AgentBrain brain, Supplier<List<AdvertisedCommand>> commands, Consumer<JsonRpcMessage> out) {
        this(brain, commands, out, TurnBoundary.open(), AgentRecorder.off(), null, null);
    }

    AcpServer(AgentBrain brain, Supplier<List<AdvertisedCommand>> commands, Consumer<JsonRpcMessage> out, TurnBoundary boundary, AgentRecorder recorder, Supplier<AgentBrain> sideBrains, Reviewer reviewer) {
        this.brain = brain;
        this.commands = commands;
        this.out = out;
        this.boundary = boundary;
        this.recorder = recorder;
        this.sideBrains = sideBrains;
        this.reviewer = reviewer;
    }

    /** Takes one message from the host, on the person's channel. A request is answered through the sink; a notification needs no answer. */
    public void receive(JsonRpcMessage message) {
        receive(message, Channel.HOST);
    }

    /** Takes one message that arrived on {@code from}. The channel is a fact of how the message arrived, never of what it says. */
    public void receive(JsonRpcMessage message, Channel from) {
        if (!message.isRequest()) {
            if (from == Channel.HOST && "session/cancel".equals(message.getMethod())) {
                cancel(message);
            } else if (from == Channel.HOST && message.isResponse()) {
                answerProposal(message);
            }
            return;
        }
        JsonRpcMessage response = answer(message, from);
        out.accept(response);
        if ("session/new".equals(message.getMethod()) && response.getError() == null && !isSide(message.getParams())) {
            advertiseCommands(((ObjectNode) response.getResult()).get("sessionId").asText());
        }
    }

    private void cancel(JsonRpcMessage message) {
        String target = textField(message.getParams(), "sessionId");
        if (target != null) {
            cancelled.add(target);
            Thread working = running.get(target);
            if (working != null) {
                working.interrupt();
            }
        }
    }

    private JsonRpcMessage answer(JsonRpcMessage request, Channel from) {
        Object id = request.getId();
        JsonNode params = request.getParams();
        return switch (request.getMethod()) {
            case "initialize" -> JsonRpcMessage.successResponse(id, initializeResult());
            case "session/new" -> JsonRpcMessage.successResponse(id, newSession(params));
            case "session/set_config_option" -> setConfigOption(id, params, from);
            case "session/list" -> listSessions(id, params);
            case "session/load" -> loadSession(id, params);
            case "session/prompt" -> prompt(id, params, from);
            case "_jawata/brains" -> JsonRpcMessage.successResponse(id, brainsResult());
            case "_jawata/hire/transcript" -> hireTranscript(id, params);
            case "_jawata/hire/figures" -> JsonRpcMessage.successResponse(id, figuresResult());
            case "_jawata/hire/brain" -> hireBrain(id, params);
            case "_jawata/hire/stop" -> hireStop(id, params);
            default -> JsonRpcMessage.errorResponse(id, METHOD_NOT_FOUND, "Method not found: " + request.getMethod(), null);
        };
    }

    /**
     * The per-role figures of the measures (D10), read from the hires this agent has seen, one row per role, so the Agent Control panel can show
     * how each role is doing. What is not measured is said and not counted as zero: a zero would read as good news.
     */
    private ObjectNode figuresResult() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode roles = result.putArray("roles");
        for (RoleFigures figures : hires.figures()) {
            ObjectNode row = roles.addObject();
            row.put("seat", figures.seat());
            row.put("hires", figures.hires());
            row.put("tokens", figures.tokens());
            row.put("toolSteps", figures.toolSteps());
            row.put("countedAgainst", figures.countedAgainst());
        }
        result.putArray("notMeasured").add("chief-engineer: edit orders given where a tool existed. An instruction names a tool and does not "
            + "label an edit order, so none is counted against the chief engineer and its row reads 0 for want of a reading, not as a good result.");
        return result;
    }

    /** Where the registry of brains is read from, each time it is asked for; none offered reads as no brains. */
    private volatile Supplier<String> brainRegistry = () -> "";

    /** Installs the source of the model registry that {@code _jawata/brains} serves (application wiring). */
    void offerBrains(Supplier<String> registry) {
        this.brainRegistry = registry == null ? () -> "" : registry;
    }

    /** Where the tree of seats is read from, each time it is asked for (D51); none offered reads as the signed tree. */
    private volatile Supplier<SeatTree> seatTree = SeatTree::standard;

    /** Installs the source of the seat tree whose seats get a brain-and-model choice (application wiring). */
    void offerSeats(Supplier<SeatTree> tree) {
        this.seatTree = tree == null ? SeatTree::standard : tree;
    }

    /**
     * The brains the studio offers with their models, for the Agent Control panel's brain control (S9; his ruling 2026-10-31). The method name is
     * provisional until the chat's recording R12 carries it. A registry that is not readable offers none, which is an empty list and not an error.
     */
    private ObjectNode brainsResult() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode brains = result.putArray("brains");
        Hirer.brainsIn(brainRegistry.get()).forEach((name, brain) -> {
            ObjectNode row = brains.addObject();
            row.put("brain", name);
            row.put("vendor", brain.vendor());
            row.put("model", brain.model());
        });
        return result;
    }

    /**
     * What was said in one hire, for unfolding an agent in the Agent Control panel (S9): the brief its issuer gave and, once it has finished, the
     * answer it gave. The method name is provisional until the chat's recording R12 carries it. A hire the agent never saw, or a request with no
     * plan id, is a bad request and not an empty transcript.
     */
    private JsonRpcMessage hireTranscript(Object id, JsonNode params) {
        String planId = textField(params, "planId");
        java.util.Optional<List<HireWatch.Said>> said = planId == null ? java.util.Optional.empty() : hires.transcript(planId);
        if (said.isEmpty()) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "no hire " + planId + " was seen by this agent", null);
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("planId", planId);
        ArrayNode turns = result.putArray("turns");
        said.get().forEach(turn -> turns.addObject().put("role", turn.role()).put("from", turn.from()).put("text", turn.text()));
        return JsonRpcMessage.successResponse(id, result);
    }

    /**
     * The Agent Control panel's brain control (S9; his ruling 2026-10-31): the pick sets the brain and model of that agent's seat for its next hire
     * and every hire after it, because a process already started cannot change its brain. The answer is the agent's hire entry as it will be.
     * The method name is provisional until the chat's recording R12 carries it. A hire the agent never saw, a brain this product has no dialect
     * for, and the script brain (which needs a program of its own) are bad requests. A model named for a brain whose command line takes none
     * is not applied and is not echoed.
     */
    private JsonRpcMessage hireBrain(Object id, JsonNode params) {
        String planId = textField(params, "planId");
        java.util.Optional<HireEntry> entry = planId == null ? java.util.Optional.empty() : hires.entry(planId);
        if (entry.isEmpty()) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "no hire " + planId + " was seen by this agent", null);
        }
        java.util.Optional<SeatHire.Brain> brain = SeatHire.Brain.named(textField(params, "brain"));
        if (brain.isEmpty() || brain.get() == SeatHire.Brain.SCRIPT) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS,
                "brain must be one of claude-code, cursor, copilot, codex; the script brain needs a program of its own and cannot be picked", null);
        }
        String model = textField(params, "model");
        String applied = model != null && !model.isBlank() && brain.get().takesAModel() ? model.strip() : "";
        SeatBrainPicks.shared().pick(entry.get().seat(), brain.get(), applied);
        ObjectNode meta = entry.get().toMeta();
        meta.put("brain", brain.get().label());
        if (applied.isEmpty()) {
            meta.remove("model");
        } else {
            meta.put("model", applied);
        }
        return JsonRpcMessage.successResponse(id, meta);
    }

    /**
     * The Agent Control panel's stop for one agent (S9; his ruling 2026-10-31): ends that one hire while the principal's turn goes on and
     * receives a failed-hire answer. The method name is provisional until the chat's recording R12 carries it. A hire the agent never saw, one
     * that has already finished, and one this engine is not running are bad requests, each said in its own words, and nothing is stopped.
     */
    private JsonRpcMessage hireStop(Object id, JsonNode params) {
        String planId = textField(params, "planId");
        java.util.Optional<HireEntry> entry = planId == null ? java.util.Optional.empty() : hires.entry(planId);
        if (entry.isEmpty()) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "no hire " + planId + " was seen by this agent", null);
        }
        if (!HireEntry.RUNNING.equals(entry.get().state())) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "hire " + planId + " has already " + entry.get().state() + "; there is nothing to stop", null);
        }
        int stopped = RunningHires.shared().stop(entry.get().seat(), entry.get().task());
        if (stopped == 0) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS,
                "this engine is not running a hire of " + entry.get().seat() + " for that brief, so there is nothing here to stop", null);
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("ok", true);
        result.put("planId", planId);
        result.put("stopped", stopped);
        return JsonRpcMessage.successResponse(id, result);
    }

    private ObjectNode initializeResult() {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("agentCapabilities");
        capabilities.put("loadSession", true);
        capabilities.putObject("sessionCapabilities").putObject("list");
        ObjectNode prompt = capabilities.putObject("promptCapabilities");
        prompt.put("image", false);
        prompt.put("audio", false);
        prompt.put("embeddedContext", false);
        ObjectNode mcp = capabilities.putObject("mcpCapabilities");
        mcp.put("http", false);
        mcp.put("sse", false);
        ObjectNode info = result.putObject("agentInfo");
        info.put("name", AGENT_NAME);
        info.put("title", AGENT_NAME);
        info.put("version", AGENT_VERSION);
        result.putArray("authMethods");
        return result;
    }

    private ObjectNode newSession(JsonNode params) {
        String cwd = params != null && params.hasNonNull("cwd") ? params.get("cwd").asText() : "";
        PrincipalSession session = new PrincipalSession(UUID.randomUUID().toString(), cwd, isSide(params));
        sessions.put(session.id(), session);
        ObjectNode result = JSON.createObjectNode();
        result.put("sessionId", session.id());
        if (!session.side()) {
            result.set("configOptions", configOptions(session));
        }
        return result;
    }

    /** Whether the host opened this session as a side thread ({@code _meta["jawata/side"]}): a question beside the main conversation. */
    private static boolean isSide(JsonNode params) {
        return params != null && params.path("_meta").path("jawata/side").asBoolean(false);
    }

    /**
     * A side thread's prompt (S9, contract R13): answered by the thread's own brain and by nothing else of the main conversation. The
     * step ledger is not asked, no try is spent, the grant of autonomy is neither read nor ended, and nothing is recorded or listed.
     */
    private JsonRpcMessage sidePrompt(Object id, JsonNode params, PrincipalSession session) {
        AgentBrain own = sideBrainOf.computeIfAbsent(session.id(), key -> sideBrains == null ? brain : sideBrains.get());
        String messageId = "msg_" + UUID.randomUUID();
        cancelled.remove(session.id());
        running.put(session.id(), Thread.currentThread());
        String stopReason = "end_turn";
        try {
            own.answer(textOf(params.get("prompt")), chunk -> update(session.id(), messageChunk("agent_message_chunk", chunk, messageId)));
            if (cancelled.contains(session.id())) {
                stopReason = "cancelled";
            }
        } finally {
            running.remove(session.id());
            Thread.interrupted();
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("stopReason", stopReason);
        return JsonRpcMessage.successResponse(id, result);
    }

    /**
     * The session's config options as ACP shows them: the select {@code autonomy} with the grant as its current value, then one select per hired
     * seat, {@code seat-model:<seat>} (category {@code model}), whose choices are the brains the registry offers and whose current value is the
     * brain picked for the seat or {@value #SEAT_FILE} while nobody has picked (contract E6; R5).
     */
    private ArrayNode configOptions(PrincipalSession session) {
        ArrayNode options = JSON.createArrayNode();
        addAutonomy(options, session);
        seatTree.get().seats().forEach(seat -> addSeatModel(options, seat));
        return options;
    }

    private void addSeatModel(ArrayNode options, String seat) {
        ObjectNode option = options.addObject();
        option.put("id", SEAT_MODEL + seat);
        option.put("name", "Brain and model for " + seat);
        option.put("category", "model");
        option.put("description", "Which brain, with which model, the " + seat + " seat uses for its next hire and every hire after it. "
            + "'As the seat file says' leaves the choice to the seat's own file. A hire already running keeps the brain it started with.");
        option.put("type", "select");
        option.put("currentValue", SeatBrainPicks.shared().forSeat(seat).map(pick -> pick.brain().label()).orElse(SEAT_FILE));
        ArrayNode values = option.putArray("options");
        values.addObject().put("value", SEAT_FILE).put("name", "As the seat file says");
        Hirer.brainsIn(brainRegistry.get()).forEach((name, brain) -> {
            // Only a brain a hire can use is offered: the registry may name a program for one this product has no dialect for.
            if (SeatHire.Brain.named(name).filter(dialect -> dialect != SeatHire.Brain.SCRIPT).isPresent()) {
                values.addObject().put("value", name).put("name", brain.model().isBlank() ? name : name + " · " + brain.model());
            }
        });
    }

    private static void addAutonomy(ArrayNode options, PrincipalSession session) {
        ObjectNode autonomy = options.addObject();
        autonomy.put("id", AUTONOMY);
        autonomy.put("name", "Autonomy");
        autonomy.put("description", "on: the agent carries on after each turn while a step still waits for its gate, within the limit "
            + "on tries per step. off: it stops at the end of each turn. Any message you type ends it.");
        autonomy.put("type", "select");
        autonomy.put("currentValue", session.autonomy() ? "on" : "off");
        ArrayNode values = autonomy.putArray("options");
        values.addObject().put("value", "off").put("name", "Stop at each turn");
        values.addObject().put("value", "on").put("name", "Carry on");
    }

    private JsonRpcMessage setConfigOption(Object id, JsonNode params, Channel from) {
        PrincipalSession session = sessionOf(params);
        if (session == null) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "Unknown session: " + textField(params, "sessionId"), null);
        }
        String configId = textField(params, "configId");
        if (configId != null && configId.startsWith(SEAT_MODEL)) {
            return setSeatModel(id, session, configId.substring(SEAT_MODEL.length()), textField(params, "value"), from);
        }
        if (!AUTONOMY.equals(configId)) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "Unknown config option: " + configId, null);
        }
        String value = textField(params, "value");
        if (!"on".equals(value) && !"off".equals(value)) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "Autonomy is on or off, not: " + value, null);
        }
        if (!session.setAutonomy(from, "on".equals(value))) {
            return JsonRpcMessage.errorResponse(id, NOT_PERMITTED,
                "Only the person sets autonomy; a message from a hired agent does not change it.", null);
        }
        ObjectNode result = JSON.createObjectNode();
        result.set("configOptions", configOptions(session));
        return JsonRpcMessage.successResponse(id, result);
    }

    /**
     * The person's choice of brain and model for one seat (contract R5, E6): {@link #SEAT_FILE} hands the seat back to its own file, and any
     * other value must be a brain the registry offers, taken with the model the registry names for it (none for a brain whose command line
     * takes none). The answer carries the options as they now stand, because a host that sets an option is answered in its own response.
     */
    private JsonRpcMessage setSeatModel(Object id, PrincipalSession session, String seat, String value, Channel from) {
        if (from != Channel.HOST) {
            return JsonRpcMessage.errorResponse(id, NOT_PERMITTED,
                "Only the person chooses a seat's brain; a message from a hired agent does not change it.", null);
        }
        if (!seatTree.get().seats().contains(seat)) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "Unknown seat: " + seat, null);
        }
        if (SEAT_FILE.equals(value)) {
            SeatBrainPicks.shared().forget(seat);
        } else {
            Map<String, Hirer.Brain> offered = Hirer.brainsIn(brainRegistry.get());
            java.util.Optional<SeatHire.Brain> brain = value == null || !offered.containsKey(value)
                ? java.util.Optional.empty() : SeatHire.Brain.named(value);
            if (brain.isEmpty() || brain.get() == SeatHire.Brain.SCRIPT) {
                return JsonRpcMessage.errorResponse(id, INVALID_PARAMS,
                    "A brain the registry offers, or " + SEAT_FILE + ", not: " + value + " (offered: " + String.join(", ", offered.keySet()) + ")", null);
            }
            String model = brain.get().takesAModel() ? offered.get(value).model().strip() : "";
            SeatBrainPicks.shared().pick(seat, brain.get(), model);
        }
        ObjectNode result = JSON.createObjectNode();
        result.set("configOptions", configOptions(session));
        return JsonRpcMessage.successResponse(id, result);
    }

    /** A proposal put to the person and not yet answered: the session it was put in, and the hire it is about. */
    private record Asked(PrincipalSession session, String planId, String seat) {
    }

    /** The proposals put to the person and waiting for the answer, by the id of the request that carries each. */
    private final Map<String, Asked> proposals = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger proposalRequests = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A seat's proposal, put to the person as {@code session/request_permission} (R7): when a hire has ended well and changed files, the host is
     * asked once whether to accept it, with the files in the request. The turn is not held for the answer; the answer is recorded when it comes
     * and shown on the hire's entry. The request lists the files and, when the hire's answer carried it, the change as text after them.
     */
    private void askAboutProposal(PrincipalSession session, HireEntry entry) {
        hires.proposalToAsk(entry.planId()).ifPresent(proposal -> {
            List<String> files = proposal.files();
            String requestId = "jawata-proposal-" + proposalRequests.incrementAndGet();
            proposals.put(requestId, new Asked(session, entry.planId(), entry.seat()));
            ObjectNode params = JSON.createObjectNode();
            params.put("sessionId", session.id());
            ObjectNode call = params.putObject("toolCall");
            call.put("toolCallId", entry.planId());
            call.put("title", "Proposal of " + entry.seat() + ": " + files.size() + (files.size() == 1 ? " file" : " files") + " changed");
            call.put("status", "completed");
            ArrayNode content = call.putArray("content");
            // A chat shows a permission request to the person only when it carries diff blocks (a request without any is answered "allow once"
            // at once), so each file the hire's answer could show is a diff block: its path, its old text (null for a new file), its new text
            // (empty for a file that is gone). Every other changed file, whether too large to carry or not shown for another reason, is named
            // as text, so no changed file goes unnamed.
            java.util.Set<String> drawn = new java.util.LinkedHashSet<>();
            proposal.changes().forEach(change -> {
                ObjectNode block = content.addObject();
                block.put("type", "diff");
                block.put("path", change.path());
                if (change.oldText() == null) {
                    block.putNull("oldText");
                } else {
                    block.put("oldText", change.oldText());
                }
                block.put("newText", change.newText() == null ? "" : change.newText());
                drawn.add(change.path());
            });
            files.stream().filter(file -> !drawn.contains(file))
                .forEach(file -> content.addObject().put("type", "content").putObject("content").put("type", "text").put("text", file));
            ArrayNode options = params.putArray("options");
            options.addObject().put("optionId", "accept").put("name", "Accept the proposal").put("kind", "allow_once");
            options.addObject().put("optionId", "reject").put("name", "Reject the proposal").put("kind", "reject_once");
            params.putObject("_meta").set("jawata/hire", entry.toMeta());
            out.accept(JsonRpcMessage.request(requestId, "session/request_permission", params));
        });
    }

    /**
     * The person's answer to a proposal put to them: it is kept on the hire's entry, shown to the host as an update of the hire's tool call,
     * and recorded in the transcript lane as the person's verdict. A response to anything else is ignored, and so is an answer that chose
     * nothing (the host cancelled the question): no choice is no verdict, and none is invented. The agent changes no file on a verdict.
     */
    private void answerProposal(JsonRpcMessage response) {
        Asked asked = proposals.remove(String.valueOf(response.getId()));
        if (asked == null || response.getError() != null || response.getResult() == null) {
            return;
        }
        JsonNode outcome = JSON.valueToTree(response.getResult()).path("outcome");
        String chosen = "selected".equals(outcome.path("outcome").asText()) ? outcome.path("optionId").asText("") : "";
        String verdict = "accept".equals(chosen) ? HireEntry.ACCEPTED : "reject".equals(chosen) ? HireEntry.REJECTED : null;
        if (verdict == null) {
            return;
        }
        java.util.Optional<HireEntry> answered = hires.answered(asked.planId(), verdict);
        answered.ifPresent(entry -> {
            ObjectNode shown = JSON.createObjectNode();
            shown.put("sessionUpdate", "tool_call_update");
            shown.put("toolCallId", asked.planId());
            shown.putObject("_meta").set("jawata/hire", entry.toMeta());
            update(asked.session().id(), shown);
        });
        // The card with the verdict goes into the same turn, so a session loaded later shows the answer on the hire's card.
        recorder.record(AgentRecorder.Level.HIRED, asked.session(), "verdict_" + asked.planId(),
            "The person's answer to the proposal of " + asked.seat() + " (" + asked.planId() + "): " + verdict,
            "Recorded the person's verdict on the proposal of " + asked.seat() + ": " + verdict,
            answered.map(entry -> List.of(entry.cardLine())).orElse(List.of()));
    }

    /** Shows the host a hire's card as it now stands, as an update of the hire's own tool call. */
    private void showCardUpdate(String sessionId, HireEntry entry) {
        ObjectNode shown = JSON.createObjectNode();
        shown.put("sessionUpdate", "tool_call_update");
        shown.put("toolCallId", entry.planId());
        shown.putObject("_meta").set("jawata/hire", entry.toMeta());
        update(sessionId, shown);
    }

    /**
     * An earlier hire has just been marked accepted-with-fix by a later hire that names it in {@code fixes}: the host sees its card change at
     * once, and the change is kept in the lane under its own key so a session loaded later shows it.
     */
    private void showFixed(PrincipalSession session, HireEntry fixed) {
        showCardUpdate(session.id(), fixed);
        recorder.record(AgentRecorder.Level.HIRED, session, "fixed_" + fixed.planId(),
            "A fix was sent for the result of " + fixed.seat() + " (" + fixed.planId() + "): " + HireEntry.ACCEPTED_WITH_FIX,
            "Recorded that the result of " + fixed.seat() + " was accepted with a fix to follow.", List.of(fixed.cardLine()));
    }

    private JsonRpcMessage prompt(Object id, JsonNode params, Channel from) {
        PrincipalSession session = sessionOf(params);
        if (session == null) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "Unknown session: " + textField(params, "sessionId"), null);
        }
        if (session.side()) {
            return sidePrompt(id, params, session);
        }
        String messageId = "msg_" + UUID.randomUUID();
        String text = textOf(params.get("prompt"));
        String typed = text;
        if (from == Channel.HOST) {
            // Only the person's message is the person's word: it restarts the tries and ends a grant. The same call from a hired agent
            // does neither.
            boundary.personSpoke(session);
            if (session.autonomy()) {
                session.setAutonomy(Channel.HOST, false);
                update(session.id(), messageChunk("agent_message_chunk", "Autonomy ended: you sent a message.\n\n", messageId));
                update(session.id(), configOptionUpdate(session));
                text = "[jawata] autonomy: off (the person typed a message, which ends it)\n" + text;
            }
        }
        StringBuilder said = new StringBuilder();
        // WITH A REVIEWER, THE MAIN SEAT'S TEXT IS HELD AND ONLY THE REVIEWED TEXT IS SENT (S11, D25): the one road to the person is the
        // release below, so there is no way for a message to reach the person unreviewed.
        ReviewBeforeSend review = reviewer == null ? null
            : new ReviewBeforeSend(reviewer, reviewed -> update(session.id(), messageChunk("agent_message_chunk", reviewed, messageId)));
        Consumer<String> chunks = review != null ? review::hold : chunk -> {
            said.append(chunk);
            update(session.id(), messageChunk("agent_message_chunk", chunk, messageId));
        };
        Map<String, ObjectNode> toolState = new java.util.LinkedHashMap<>();
        Consumer<JsonNode> toolCalls = call -> {
            ObjectNode forwarded = ((ObjectNode) call).deepCopy();
            java.util.Optional<HireEntry> seen = hires.observe(call);
            seen.ifPresent(entry -> {
                ObjectNode meta = forwarded.path("_meta").isObject() ? (ObjectNode) forwarded.get("_meta") : forwarded.putObject("_meta");
                meta.set("jawata/hire", entry.toMeta());
                // A client with no jawata view still shows each running agent as one tool-call line with its status (contract E3).
                forwarded.put("kind", "execute");
                forwarded.put("title", entry.title());
            });
            update(session.id(), forwarded);
            seen.ifPresent(entry -> askAboutProposal(session, entry));
            hires.takeFixed().ifPresent(fixed -> showFixed(session, fixed));
            ObjectNode state = toolState.computeIfAbsent(call.path("toolCallId").asText(), key -> JSON.createObjectNode());
            for (String field : List.of("title", "status")) {
                if (call.hasNonNull(field)) {
                    state.set(field, call.get(field));
                }
            }
        };
        String stopReason = "end_turn";
        cancelled.remove(session.id());
        running.put(session.id(), Thread.currentThread());
        try {
            while (true) {
                brain.answer(text, chunks, toolCalls, thought -> update(session.id(), ((ObjectNode) thought).deepCopy()));
                if (cancelled.contains(session.id())) {
                    stopReason = "cancelled";
                    break;
                }
                TurnBoundary.Verdict verdict = boundary.atTurnEnd(session);
                if (verdict.kind() == TurnBoundary.Kind.RESUME) {
                    chunks.accept("\n\n" + verdict.text() + "\n\n");
                    text = "[jawata] autonomy: " + (session.autonomy() ? "on" : "off") + "\n" + verdict.text();
                    continue;
                }
                if (verdict.kind() == TurnBoundary.Kind.REPORT) {
                    chunks.accept("\n\n" + verdict.text());
                }
                break;
            }
        } finally {
            running.remove(session.id());
            Thread.interrupted(); // a cancel that arrived after the brain finished must not poison the connection's worker
            if (review != null) {
                said.append(review.release());
            }
            recorder.record(AgentRecorder.Level.PRINCIPAL, session, messageId, typed, said.toString(),
                // A hire is kept as its card, so a session loaded later shows the hire's figures and state again; any other call as one finished line.
                toolState.entrySet().stream()
                    .map(call -> hires.entry(call.getKey()).map(HireEntry::cardLine).orElseGet(() -> toolLine(call.getValue()))).toList());
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("stopReason", stopReason);
        return JsonRpcMessage.successResponse(id, result);
    }

    /**
     * One tool call as the recording keeps it, a single finished line: its title and how it ended. An update that carries no title or no
     * status (the brain streams both in pieces) leaves the missing half out rather than inventing it.
     */
    private static String toolLine(JsonNode call) {
        String title = call.path("title").asText("");
        String status = call.path("status").asText("");
        return title.isEmpty() ? status : status.isEmpty() ? title : title + " — " + status;
    }

    /** {@code session/list}: the sessions the recording holds, most recently active first. */
    private JsonRpcMessage listSessions(Object id, JsonNode params) {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode list = result.putArray("sessions");
        String cwd = textField(params, "cwd");
        for (AgentRecorder.SessionInfo s : recorder.sessions(SESSIONS_LISTED)) {
            ObjectNode row = list.addObject();
            row.put("sessionId", s.sessionId());
            row.put("cwd", cwd == null ? "" : cwd);
            row.put("title", s.title());
            row.put("updatedAt", s.updatedAt());
        }
        return JsonRpcMessage.successResponse(id, result);
    }

    /**
     * {@code session/load}: replays what the recording holds of a session to the host as it was said (what was typed, the tool calls
     * as one finished line each, what the agent answered), then answers, and the session takes prompts again under its old id.
     */
    private JsonRpcMessage loadSession(Object id, JsonNode params) {
        String sessionId = textField(params, "sessionId");
        List<AgentRecorder.Turn> turns = sessionId == null ? List.of() : recorder.turns(sessionId);
        if (turns.isEmpty()) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "No recorded session: " + sessionId, null);
        }
        String cwd = textField(params, "cwd");
        PrincipalSession session = sessions.computeIfAbsent(sessionId, key -> new PrincipalSession(key, cwd == null ? "" : cwd, false));
        int turnNo = 0;
        java.util.Set<String> replayedCards = new java.util.HashSet<>();
        for (AgentRecorder.Turn turn : turns) {
            turnNo++;
            for (String typed : turn.his()) {
                update(sessionId, messageChunk("user_message_chunk", typed, "replay_" + turnNo + "_u"));
            }
            int line = 0;
            for (String call : turn.tools()) {
                java.util.Optional<ObjectNode> card = HireEntry.readCardLine(call);
                if (card.isPresent()) {
                    replayCard(sessionId, card.get(), replayedCards);
                    continue;
                }
                ObjectNode update = JSON.createObjectNode();
                update.put("sessionUpdate", "tool_call");
                update.put("toolCallId", "replay_" + turnNo + "_t" + (++line));
                update.put("title", call);
                update.put("status", "completed");
                update(sessionId, update);
            }
            for (String said : turn.agent()) {
                update(sessionId, messageChunk("agent_message_chunk", said, "replay_" + turnNo + "_a"));
            }
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("sessionId", session.id());
        result.set("configOptions", configOptions(session));
        return JsonRpcMessage.successResponse(id, result);
    }

    /**
     * Shows one stored hire card to the host as the tool call it was: the first line of a hire is a {@code tool_call}, a later line of the same
     * hire (its verdict, for one) an update of it. A hire stored as still running or compacting was cut off with its session, so it is shown
     * as failed with the time it had and never as running forever. The agent no longer holds the hire, so its stop, transcript and brain
     * pick answer that it is unknown.
     */
    private void replayCard(String sessionId, ObjectNode card, java.util.Set<String> shownBefore) {
        String state = card.path("state").asText("");
        if (HireEntry.RUNNING.equals(state) || HireEntry.COMPACTING.equals(state)) {
            card.put("state", HireEntry.FAILED);
            state = HireEntry.FAILED;
        }
        String planId = card.path("planId").asText();
        ObjectNode shown = JSON.createObjectNode();
        shown.put("sessionUpdate", shownBefore.add(planId) ? "tool_call" : "tool_call_update");
        shown.put("toolCallId", planId);
        shown.put("kind", "execute");
        shown.put("title", java.util.stream.Stream.of(card.path("seat").asText(""), card.path("brain").asText(""), card.path("model").asText(""))
            .filter(part -> !part.isEmpty()).collect(java.util.stream.Collectors.joining(" · ")) + " — " + state);
        shown.put("status", HireEntry.FAILED.equals(state) ? "failed" : "completed");
        shown.putObject("_meta").set("jawata/hire", card);
        update(sessionId, shown);
    }

    private PrincipalSession sessionOf(JsonNode params) {
        String sessionId = textField(params, "sessionId");
        return sessionId == null ? null : sessions.get(sessionId);
    }

    private static String textField(JsonNode params, String name) {
        return params != null && params.hasNonNull(name) ? params.get(name).asText() : null;
    }

    private void advertiseCommands(String sessionId) {
        ObjectNode update = JSON.createObjectNode();
        update.put("sessionUpdate", "available_commands_update");
        ArrayNode list = update.putArray("availableCommands");
        for (AdvertisedCommand command : commands.get()) {
            ObjectNode entry = list.addObject();
            entry.put("name", command.name());
            entry.put("description", command.description());
            entry.putObject("_meta").put("jawata/group", command.group());
        }
        update(sessionId, update);
    }

    private ObjectNode configOptionUpdate(PrincipalSession session) {
        ObjectNode update = JSON.createObjectNode();
        update.put("sessionUpdate", "config_option_update");
        update.set("configOptions", configOptions(session));
        return update;
    }

    /** One piece of a message as an ACP update; {@code kind} says whose it is: {@code user_message_chunk} or {@code agent_message_chunk}. */
    private static ObjectNode messageChunk(String kind, String text, String messageId) {
        ObjectNode update = JSON.createObjectNode();
        update.put("sessionUpdate", kind);
        update.putObject("content").put("type", "text").put("text", text);
        update.put("messageId", messageId);
        return update;
    }

    private void update(String sessionId, ObjectNode update) {
        ObjectNode params = JSON.createObjectNode();
        params.put("sessionId", sessionId);
        params.set("update", update);
        out.accept(JsonRpcMessage.notification("session/update", params));
    }

    private static String textOf(JsonNode blocks) {
        StringBuilder text = new StringBuilder();
        if (blocks != null && blocks.isArray()) {
            for (JsonNode block : blocks) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
        }
        return text.toString();
    }
}
