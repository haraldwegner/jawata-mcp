package org.jawata.mcp.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int NOT_PERMITTED = -32003;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentBrain brain;
    private final Supplier<List<AdvertisedCommand>> commands;
    private final Consumer<JsonRpcMessage> out;
    private final TurnBoundary boundary;
    private final Map<String, PrincipalSession> sessions = new ConcurrentHashMap<>();
    /** The thread running each session's prompt, so the person's cancel can interrupt the brain's work. */
    private final Map<String, Thread> running = new ConcurrentHashMap<>();
    /** Sessions whose running prompt the person cancelled; read at each turn end so a cancelled prompt is not resumed. */
    private final java.util.Set<String> cancelled = ConcurrentHashMap.newKeySet();

    AcpServer(AgentBrain brain, Supplier<List<AdvertisedCommand>> commands, Consumer<JsonRpcMessage> out) {
        this(brain, commands, out, TurnBoundary.open());
    }

    AcpServer(AgentBrain brain, Supplier<List<AdvertisedCommand>> commands, Consumer<JsonRpcMessage> out, TurnBoundary boundary) {
        this.brain = brain;
        this.commands = commands;
        this.out = out;
        this.boundary = boundary;
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
            }
            return;
        }
        JsonRpcMessage response = answer(message, from);
        out.accept(response);
        if ("session/new".equals(message.getMethod()) && response.getError() == null) {
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
            case "session/prompt" -> prompt(id, params, from);
            default -> JsonRpcMessage.errorResponse(id, METHOD_NOT_FOUND, "Method not found: " + request.getMethod(), null);
        };
    }

    private ObjectNode initializeResult() {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("agentCapabilities");
        capabilities.put("loadSession", false);
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
        PrincipalSession session = new PrincipalSession(UUID.randomUUID().toString(), cwd);
        sessions.put(session.id(), session);
        ObjectNode result = JSON.createObjectNode();
        result.put("sessionId", session.id());
        result.set("configOptions", configOptions(session));
        return result;
    }

    /** The session's config options as ACP shows them: one select, {@code autonomy}, with the grant as its current value. */
    private static ArrayNode configOptions(PrincipalSession session) {
        ArrayNode options = JSON.createArrayNode();
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
        return options;
    }

    private JsonRpcMessage setConfigOption(Object id, JsonNode params, Channel from) {
        PrincipalSession session = sessionOf(params);
        if (session == null) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "Unknown session: " + textField(params, "sessionId"), null);
        }
        if (!AUTONOMY.equals(textField(params, "configId"))) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "Unknown config option: " + textField(params, "configId"), null);
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

    private JsonRpcMessage prompt(Object id, JsonNode params, Channel from) {
        PrincipalSession session = sessionOf(params);
        if (session == null) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "Unknown session: " + textField(params, "sessionId"), null);
        }
        String messageId = "msg_" + UUID.randomUUID();
        String text = textOf(params.get("prompt"));
        if (from == Channel.HOST) {
            // Only the person's message is the person's word: it restarts the tries and ends a grant. The same call from a hired agent
            // does neither.
            boundary.personSpoke();
            if (session.autonomy()) {
                session.setAutonomy(Channel.HOST, false);
                update(session.id(), agentMessageChunk("Autonomy ended: you sent a message.\n\n", messageId));
                update(session.id(), configOptionUpdate(session));
                text = "[jawata] autonomy: off (the person typed a message, which ends it)\n" + text;
            }
        }
        Consumer<String> chunks = chunk -> update(session.id(), agentMessageChunk(chunk, messageId));
        String stopReason = "end_turn";
        cancelled.remove(session.id());
        running.put(session.id(), Thread.currentThread());
        try {
            while (true) {
                brain.answer(text, chunks);
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
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("stopReason", stopReason);
        return JsonRpcMessage.successResponse(id, result);
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

    private static ObjectNode configOptionUpdate(PrincipalSession session) {
        ObjectNode update = JSON.createObjectNode();
        update.put("sessionUpdate", "config_option_update");
        update.set("configOptions", configOptions(session));
        return update;
    }

    private static ObjectNode agentMessageChunk(String text, String messageId) {
        ObjectNode update = JSON.createObjectNode();
        update.put("sessionUpdate", "agent_message_chunk");
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
