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
 * The agent's side of one ACP connection (Sprint 28h, D1; contract Level 0): it answers {@code initialize}, {@code session/new} and
 * {@code session/prompt}, streams the answer as {@code session/update} messages and ends the prompt with its stop reason. Messages are
 * the engine's own {@link JsonRpcMessage}: ACP is JSON-RPC, and one codec serves both protocols.
 *
 * <p>What it does not do yet is named by the plan, stage by stage: the hierarchy behind {@link AgentBrain} (S6, S7), the turn
 * boundary and autonomy (S8), history and the hire views (S9).</p>
 */
public final class AcpServer {

    static final int PROTOCOL_VERSION = 1;
    static final String AGENT_NAME = "jawata";
    static final String AGENT_VERSION = "5.0-dev";

    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentBrain brain;
    private final Supplier<List<AdvertisedCommand>> commands;
    private final Consumer<JsonRpcMessage> out;
    private final Map<String, PrincipalSession> sessions = new ConcurrentHashMap<>();

    AcpServer(AgentBrain brain, Supplier<List<AdvertisedCommand>> commands, Consumer<JsonRpcMessage> out) {
        this.brain = brain;
        this.commands = commands;
        this.out = out;
    }

    /** Takes one message from the host. A request is answered through the sink; a notification needs no answer. */
    public void receive(JsonRpcMessage message) {
        if (!message.isRequest()) {
            return;
        }
        JsonRpcMessage response = answer(message);
        out.accept(response);
        if ("session/new".equals(message.getMethod()) && response.getError() == null) {
            advertiseCommands(((ObjectNode) response.getResult()).get("sessionId").asText());
        }
    }

    private JsonRpcMessage answer(JsonRpcMessage request) {
        Object id = request.getId();
        JsonNode params = request.getParams();
        return switch (request.getMethod()) {
            case "initialize" -> JsonRpcMessage.successResponse(id, initializeResult());
            case "session/new" -> JsonRpcMessage.successResponse(id, newSession(params));
            case "session/prompt" -> prompt(id, params);
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
        result.putArray("configOptions");
        return result;
    }

    private JsonRpcMessage prompt(Object id, JsonNode params) {
        String sessionId = params != null && params.hasNonNull("sessionId") ? params.get("sessionId").asText() : null;
        if (sessionId == null || !sessions.containsKey(sessionId)) {
            return JsonRpcMessage.errorResponse(id, INVALID_PARAMS, "Unknown session: " + sessionId, null);
        }
        String messageId = "msg_" + UUID.randomUUID();
        brain.answer(textOf(params.get("prompt")), chunk -> update(sessionId, agentMessageChunk(chunk, messageId)));
        ObjectNode result = JSON.createObjectNode();
        result.put("stopReason", "end_turn");
        return JsonRpcMessage.successResponse(id, result);
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
