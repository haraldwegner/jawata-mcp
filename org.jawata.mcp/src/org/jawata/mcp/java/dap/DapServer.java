package org.jawata.mcp.java.dap;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.spi.host.PersonDebugTurn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The person's debugger as a face of the engine (Sprint 28i, Stage 25, step A2; ARCHITECTURE-28i-s25.md): one editor connection speaks the
 * debug adapter protocol, and each request becomes a call of the engine's {@code debug} tool made as an editor caller, so recording, the
 * workspace rules and the session registry are the ones the agent's debugging already goes through. While a launch is running it is the
 * person's turn to debug ({@link PersonDebugTurn}), which keeps the agent's own debugging out of the program.
 *
 * <p>A message arrives as the {@code params} of a JSON-RPC message (the launcher's envelope, see {@code Bridge.DAP}) and is answered the same
 * way. A request the server does not serve yet is answered as unsupported and never ignored.</p>
 */
public final class DapServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final Consumer<JsonRpcMessage> out;
    private final PersonDebugTurn turn;
    private final AtomicInteger seq = new AtomicInteger();
    private PersonDebugTurn.Launch launch;
    private String sessionId;

    /**
     * @param tools  the engine's tools, through which every request is made
     * @param caller the editor connection this server answers
     * @param out    where what the server says goes, one JSON-RPC message per debug message
     * @param turn   whose turn it is to debug, which a launch takes for the person
     */
    public DapServer(ToolCalls tools, CallerIdentity caller, Consumer<JsonRpcMessage> out, PersonDebugTurn turn) {
        this.tools = tools;
        this.caller = caller;
        this.out = out;
        this.turn = turn;
    }

    /** One message from the editor: a request is served, and anything else a debug client sends is not a request and is left alone. */
    public synchronized void receive(JsonRpcMessage message) {
        JsonNode request = message.getParams();
        if (request == null || !"request".equals(request.path("type").asText())) {
            return;
        }
        int requestSeq = request.path("seq").asInt();
        String command = request.path("command").asText();
        JsonNode arguments = request.path("arguments");
        try {
            switch (command) {
                case "initialize" -> initialize(requestSeq);
                case "launch" -> launch(requestSeq, arguments);
                case "configurationDone" -> configurationDone(requestSeq);
                case "disconnect" -> disconnect(requestSeq);
                default -> refuse(requestSeq, command, "the request " + command + " is not served yet");
            }
        } catch (RuntimeException e) {
            refuse(requestSeq, command, "the request " + command + " failed: " + e.getMessage());
        }
    }

    /** The editor's connection ended: whatever the person was debugging is let go and the turn is the agent's again. */
    public synchronized void closed() {
        end();
    }

    private void initialize(int requestSeq) {
        ObjectNode body = JSON.createObjectNode().put("supportsConfigurationDoneRequest", true);
        respond(requestSeq, "initialize", body);
        say(event("initialized", null));
    }

    private void launch(int requestSeq, JsonNode arguments) {
        if (launch != null) {
            refuse(requestSeq, "launch", "a program is already running under this debugger");
            return;
        }
        PersonDebugTurn.Launch begun = turn.begin();
        ObjectNode call = JSON.createObjectNode().put("action", "launch");
        copy(arguments, "mainClass", call, "mainClass");
        copy(arguments, "classpath", call, "classpath");
        copy(arguments, "cwd", call, "workingDirectory");
        copy(arguments, "args", call, "args");
        copy(arguments, "jvmArgs", call, "jvmArgs");
        ToolResponse answer;
        try {
            answer = callDebug(call);
        } catch (RuntimeException e) {
            begun.end();
            throw e;
        }
        if (!answer.isSuccess()) {
            begun.end();
            refuse(requestSeq, "launch", answer.getError().getMessage());
            return;
        }
        launch = begun;
        sessionId = JSON.valueToTree(answer.getData()).path("sessionId").asText();
        respond(requestSeq, "launch", null);
        say(event("jawata/session", JSON.createObjectNode().put("sessionId", sessionId)));
    }

    private void configurationDone(int requestSeq) {
        if (sessionId == null) {
            refuse(requestSeq, "configurationDone", "no program has been launched");
            return;
        }
        ToolResponse answer = callDebug(JSON.createObjectNode().put("action", "resume").put("sessionId", sessionId));
        if (!answer.isSuccess()) {
            refuse(requestSeq, "configurationDone", answer.getError().getMessage());
            return;
        }
        respond(requestSeq, "configurationDone", null);
    }

    private void disconnect(int requestSeq) {
        end();
        respond(requestSeq, "disconnect", null);
    }

    /** Lets the running session go, kills it when it was launched here, and gives the turn back; does nothing when nothing is running. */
    private void end() {
        try {
            if (sessionId != null) {
                callDebug(JSON.createObjectNode().put("action", "detach").put("sessionId", sessionId));
            }
        } finally {
            sessionId = null;
            if (launch != null) {
                launch.end();
                launch = null;
            }
        }
    }

    /** One call of the engine's debug tool as this editor connection; a registry without the tool is a failure of this request. */
    private ToolResponse callDebug(ObjectNode call) {
        try {
            return tools.callTool("debug", call, caller);
        } catch (ToolNotFoundException e) {
            throw new IllegalStateException("the debug tool is not available: " + e.getMessage(), e);
        }
    }

    private static void copy(JsonNode from, String name, ObjectNode to, String as) {
        if (from.has(name)) {
            to.set(as, from.get(name));
        }
    }

    private void respond(int requestSeq, String command, JsonNode body) {
        ObjectNode response = JSON.createObjectNode().put("seq", seq.incrementAndGet()).put("type", "response")
            .put("request_seq", requestSeq).put("success", true).put("command", command);
        if (body != null) {
            response.set("body", body);
        }
        say(response);
    }

    private void refuse(int requestSeq, String command, String message) {
        say(JSON.createObjectNode().put("seq", seq.incrementAndGet()).put("type", "response").put("request_seq", requestSeq)
            .put("success", false).put("command", command).put("message", message));
    }

    private ObjectNode event(String name, JsonNode body) {
        ObjectNode event = JSON.createObjectNode().put("seq", seq.incrementAndGet()).put("type", "event").put("event", name);
        if (body != null) {
            event.set("body", body);
        }
        return event;
    }

    private void say(ObjectNode debugMessage) {
        out.accept(JsonRpcMessage.notification("dap", debugMessage));
    }
}
