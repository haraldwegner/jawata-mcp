package org.jawata.mcp.java.dap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.spi.host.PersonDebugTurn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    private volatile String sessionId;
    /** The breakpoints this server armed, by the source file they were set for, so a new list for a file replaces the old one. */
    private final Map<String, ArrayList<String>> breakpointsBySource = new HashMap<>();
    /** The threads a step was asked for and that have not stopped since: their next stop is a step, not a breakpoint. */
    private final ConcurrentHashMap<Long, Boolean> stepping = new ConcurrentHashMap<>();
    /** What each frame id the editor was given stands for: the thread and the frame's place in its stack. */
    private final Map<Integer, long[]> frames = new ConcurrentHashMap<>();
    private final AtomicInteger frameIds = new AtomicInteger();
    /** What each variables reference the editor was given opens: names and the debug tool's value for each, in order; valid while the program stays stopped. */
    private final Map<Integer, JsonNode> listings = new ConcurrentHashMap<>();
    private final AtomicInteger listingIds = new AtomicInteger();
    private final AtomicBoolean terminatedTold = new AtomicBoolean();
    private volatile boolean ending;
    private volatile Thread stopLoop;
    private final Function<String, Optional<Path>> sources;

    /** How long one wait for the program's next stop lasts; the loop asks again when nothing stopped. */
    private static final long WAIT_MILLIS = 60_000;

    /**
     * @param tools  the engine's tools, through which every request is made
     * @param caller the editor connection this server answers
     * @param out    where what the server says goes, one JSON-RPC message per debug message
     * @param turn   whose turn it is to debug, which a launch takes for the person
     * @param sources the source file of a class by its name as the running program reports it, from the engine's Java model; empty for a class
     *                that is not in source of a loaded project, whose frame then shows no file
     */
    public DapServer(ToolCalls tools, CallerIdentity caller, Consumer<JsonRpcMessage> out, PersonDebugTurn turn, Function<String, Optional<Path>> sources) {
        this.tools = tools;
        this.caller = caller;
        this.out = out;
        this.turn = turn;
        this.sources = sources;
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
                case "setBreakpoints" -> setBreakpoints(requestSeq, arguments);
                case "threads" -> threads(requestSeq);
                case "stackTrace" -> stackTrace(requestSeq, arguments);
                case "scopes" -> scopes(requestSeq, arguments);
                case "variables" -> variables(requestSeq, arguments);
                case "evaluate" -> evaluate(requestSeq, arguments);
                case "continue" -> resume(requestSeq, arguments);
                case "next" -> step(requestSeq, "next", "over", arguments);
                case "stepIn" -> step(requestSeq, "stepIn", "in", arguments);
                case "stepOut" -> step(requestSeq, "stepOut", "out", arguments);
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
        ending = false;
        terminatedTold.set(false);
        stopLoop = null;
        stepping.clear();
        frames.clear();
        breakpointsBySource.clear();
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
        watchForStops();
    }

    private void disconnect(int requestSeq) {
        end();
        respond(requestSeq, "disconnect", null);
    }

    /** Lets the running session go, kills it when it was launched here, and gives the turn back; does nothing when nothing is running. */
    private void end() {
        ending = true;
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

    /**
     * Arms the lines the editor asks for in one source file, as line breakpoints in the file's class, after clearing the ones this server
     * armed for that file before: the editor sends the whole list of a file each time.
     */
    private void setBreakpoints(int requestSeq, JsonNode arguments) {
        if (sessionId == null) {
            refuse(requestSeq, "setBreakpoints", "no program has been launched");
            return;
        }
        String path = arguments.path("source").path("path").asText();
        String className;
        try {
            className = classNameOf(path);
        } catch (java.io.IOException | RuntimeException e) {
            refuse(requestSeq, "setBreakpoints", "the class of " + path + " could not be worked out: " + e.getMessage());
            return;
        }
        for (String armed : breakpointsBySource.getOrDefault(path, new ArrayList<>())) {
            callDebug(JSON.createObjectNode().put("action", "breakpoint_clear").put("sessionId", sessionId).put("breakpointId", armed));
        }
        ArrayList<String> armedNow = new ArrayList<>();
        ArrayNode results = JSON.createArrayNode();
        for (JsonNode wanted : arguments.path("breakpoints")) {
            int line = wanted.path("line").asInt();
            ToolResponse answer = callDebug(JSON.createObjectNode().put("action", "breakpoint_set").put("sessionId", sessionId)
                .put("kind", "line").put("className", className).put("line", line));
            ObjectNode result = results.addObject().put("line", line);
            if (answer.isSuccess()) {
                JsonNode data = JSON.valueToTree(answer.getData());
                armedNow.add(data.path("breakpointId").asText());
                result.put("verified", data.path("bound").asBoolean());
            } else {
                result.put("verified", false).put("message", answer.getError().getMessage());
            }
        }
        breakpointsBySource.put(path, armedNow);
        respond(requestSeq, "setBreakpoints", JSON.createObjectNode().set("breakpoints", results));
    }

    /** The class a source file declares, from its package line and its name; the top-level class of the file. */
    private static String classNameOf(String file) throws java.io.IOException {
        java.nio.file.Path source = java.nio.file.Path.of(file);
        String text = java.nio.file.Files.readString(source);
        java.util.regex.Matcher declared = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
        String simple = source.getFileName().toString().replaceFirst("\\.java$", "");
        return declared.find() ? declared.group(1) + "." + simple : simple;
    }

    /** Answers the editor's threads request from the debug tool's thread list. */
    private void threads(int requestSeq) {
        ToolResponse answer = callDebug(JSON.createObjectNode().put("action", "threads").put("sessionId", sessionId));
        if (!answer.isSuccess()) {
            refuse(requestSeq, "threads", answer.getError().getMessage());
            return;
        }
        ArrayNode threads = JSON.createArrayNode();
        for (JsonNode thread : JSON.valueToTree(answer.getData()).path("threads")) {
            threads.addObject().put("id", thread.path("threadId").asLong()).put("name", thread.path("name").asText());
        }
        respond(requestSeq, "threads", JSON.createObjectNode().set("threads", threads));
    }

    /** Answers the editor's stack of one stopped thread from the debug tool's snapshot, giving each frame an id the editor can hand back. */
    private void stackTrace(int requestSeq, JsonNode arguments) {
        long threadId = arguments.path("threadId").asLong();
        ToolResponse answer = callDebug(JSON.createObjectNode().put("action", "snapshot").put("sessionId", sessionId).put("threadId", threadId));
        if (!answer.isSuccess()) {
            refuse(requestSeq, "stackTrace", answer.getError().getMessage());
            return;
        }
        JsonNode snapshot = JSON.valueToTree(answer.getData());
        ArrayNode stackFrames = JSON.createArrayNode();
        for (JsonNode frame : snapshot.path("stack")) {
            int id = frameIds.incrementAndGet();
            frames.put(id, new long[] {threadId, frame.path("frameIndex").asLong()});
            ObjectNode described = stackFrames.addObject().put("id", id)
                .put("name", frame.path("class").asText() + "." + frame.path("method").asText())
                .put("line", frame.path("line").asInt()).put("column", 1);
            ObjectNode where = described.putObject("source").put("name", frame.path("source").asText());
            sources.apply(frame.path("class").asText()).ifPresent(file -> where.put("path", file.toString()));
        }
        ObjectNode body = JSON.createObjectNode().put("totalFrames", snapshot.path("stackDepth").asInt(stackFrames.size()));
        body.set("stackFrames", stackFrames);
        respond(requestSeq, "stackTrace", body);
    }

    /** The frame ids and variable references the editor holds stop standing for anything the moment the program runs on. */
    private void forgetWhatTheStopShowed() {
        frames.clear();
        listings.clear();
    }

    /** Opens the frame the editor names as its scopes: the arguments, the locals and, for an instance method, the receiver. */
    private void scopes(int requestSeq, JsonNode arguments) {
        long[] where = frames.get(arguments.path("frameId").asInt());
        if (where == null) {
            refuse(requestSeq, "scopes", "the frame is not one this debugger gave the editor, or the program has run on since");
            return;
        }
        ToolResponse answer = callDebug(JSON.createObjectNode().put("action", "snapshot").put("sessionId", sessionId)
            .put("threadId", where[0]).put("frameIndex", where[1]));
        if (!answer.isSuccess()) {
            refuse(requestSeq, "scopes", answer.getError().getMessage());
            return;
        }
        JsonNode frame = JSON.valueToTree(answer.getData()).path("frame");
        ObjectNode passed = JSON.createObjectNode();
        for (JsonNode argument : frame.path("arguments")) {
            passed.set(argument.path("name").asText(), argument.path("value"));
        }
        ArrayNode scopes = JSON.createArrayNode();
        scopes.addObject().put("name", "Arguments").put("variablesReference", listing(passed)).put("expensive", false);
        scopes.addObject().put("name", "Locals").put("variablesReference", listing(frame.path("locals"))).put("expensive", false);
        JsonNode receiver = frame.path("this");
        if (receiver.has("fields")) {
            scopes.addObject().put("name", "This").put("variablesReference", listing(receiver.get("fields"))).put("expensive", false);
        }
        respond(requestSeq, "scopes", JSON.createObjectNode().set("scopes", scopes));
    }

    /** Lists what one variables reference opens, each with its value as a debugger shows it and a reference of its own when it can be opened. */
    private void variables(int requestSeq, JsonNode arguments) {
        JsonNode named = listings.get(arguments.path("variablesReference").asInt());
        if (named == null) {
            refuse(requestSeq, "variables", "the reference is not one this debugger gave the editor, or the program has run on since");
            return;
        }
        ArrayNode variables = JSON.createArrayNode();
        for (String name : (Iterable<String>) named::fieldNames) {
            JsonNode value = named.get(name);
            variables.addObject().put("name", name).put("value", display(value)).put("type", value.path("type").asText())
                .put("variablesReference", openable(value));
        }
        respond(requestSeq, "variables", JSON.createObjectNode().set("variables", variables));
    }

    /** Evaluates an expression in the frame the editor names, in the program, and answers with the value and its type. */
    private void evaluate(int requestSeq, JsonNode arguments) {
        long[] where = frames.get(arguments.path("frameId").asInt());
        if (where == null) {
            refuse(requestSeq, "evaluate", "an expression is evaluated in a frame the debugger gave the editor, and none was named");
            return;
        }
        ToolResponse answer = callDebug(JSON.createObjectNode().put("action", "evaluate").put("sessionId", sessionId)
            .put("threadId", where[0]).put("frameIndex", where[1]).put("expression", arguments.path("expression").asText()));
        if (!answer.isSuccess()) {
            refuse(requestSeq, "evaluate", answer.getError().getMessage());
            return;
        }
        JsonNode value = JSON.valueToTree(answer.getData()).path("value");
        respond(requestSeq, "evaluate", JSON.createObjectNode().put("result", display(value)).put("type", value.path("type").asText())
            .put("variablesReference", openable(value)));
    }

    private int listing(JsonNode named) {
        int id = listingIds.incrementAndGet();
        listings.put(id, named);
        return id;
    }

    /** A reference that opens an object's fields or an array's elements, or zero for a value with nothing inside. */
    private int openable(JsonNode value) {
        if (value.has("fields")) {
            return listing(value.get("fields"));
        }
        if (value.has("items")) {
            ObjectNode elements = JSON.createObjectNode();
            int index = 0;
            for (JsonNode item : value.get("items")) {
                elements.set(String.valueOf(index++), item);
            }
            return listing(elements);
        }
        return 0;
    }

    /** One value of the debug tool as the text a debugger shows: a string quoted, an array by its length, an object by its type and id. */
    private static String display(JsonNode value) {
        if (value.has("items")) {
            return value.path("type").asText() + "[" + value.path("length").asInt() + "]";
        }
        if (value.has("fields")) {
            return value.path("type").asText() + "@" + value.path("id").asText();
        }
        if (value.has("summary")) {
            return value.path("summary").asText();
        }
        JsonNode plain = value.path("value");
        if (plain.isMissingNode() || plain.isNull()) {
            return "null";
        }
        return "java.lang.String".equals(value.path("type").asText()) ? "\"" + plain.asText() + "\"" : plain.asText();
    }

    /** Resumes the one thread the editor names. */
    private void resume(int requestSeq, JsonNode arguments) {
        forgetWhatTheStopShowed();
        long threadId = arguments.path("threadId").asLong();
        ToolResponse answer = callDebug(JSON.createObjectNode().put("action", "resume").put("sessionId", sessionId).put("threadId", threadId));
        if (!answer.isSuccess()) {
            refuse(requestSeq, "continue", answer.getError().getMessage());
            return;
        }
        respond(requestSeq, "continue", JSON.createObjectNode().put("allThreadsContinued", false));
    }

    /** Steps the thread the editor names; its next stop is told to the editor as a step. */
    private void step(int requestSeq, String command, String mode, JsonNode arguments) {
        forgetWhatTheStopShowed();
        long threadId = arguments.path("threadId").asLong();
        stepping.put(threadId, Boolean.TRUE);
        ToolResponse answer = callDebug(JSON.createObjectNode().put("action", "step").put("sessionId", sessionId).put("threadId", threadId).put("mode", mode));
        if (!answer.isSuccess()) {
            stepping.remove(threadId);
            refuse(requestSeq, command, answer.getError().getMessage());
            return;
        }
        respond(requestSeq, command, null);
    }

    /** Starts the thread that waits for the program's stops and tells each to the editor, once, until the program ends or the debugging does. */
    private synchronized void watchForStops() {
        if (stopLoop != null) {
            return;
        }
        String mine = sessionId;
        stopLoop = new Thread(() -> tellStops(mine), "dap-stops-" + caller.id());
        stopLoop.setDaemon(true);
        stopLoop.start();
    }

    /** Tells the editor each stop of the program one launch started; a loop whose launch has been replaced ends, so two never run side by side. */
    private void tellStops(String mine) {
        while (!ending && mine != null && mine.equals(sessionId)) {
            ToolResponse answer;
            try {
                answer = callDebug(JSON.createObjectNode().put("action", "wait").put("sessionId", mine).put("timeoutMillis", WAIT_MILLIS));
            } catch (RuntimeException e) {
                endedBecause("the debugger lost its link to the engine: " + e.getMessage());
                return;
            }
            if (ending || !mine.equals(sessionId)) {
                return;
            }
            JsonNode data = JSON.valueToTree(answer.getData());
            if (!answer.isSuccess()) {
                endedBecause("the engine could not follow the program: " + (answer.getError() == null ? "no reason given" : answer.getError().getMessage()));
                return;
            }
            if (!data.path("targetAlive").asBoolean(true) && !data.path("hit").asBoolean()) {
                endedBecause(null);
                return;
            }
            if (data.path("hit").asBoolean()) {
                long threadId = data.path("threadId").asLong();
                String reason = stepping.remove(threadId) != null ? "step" : "exception".equals(data.path("kind").asText()) ? "exception" : "breakpoint";
                say(event("stopped", JSON.createObjectNode().put("reason", reason).put("threadId", threadId).put("allThreadsStopped", false)));
            }
        }
    }

    /** Ends the editor's debugging once: says why first when the end is not the program finishing, so a lost engine is not read as a program that ended. */
    private void endedBecause(String problem) {
        if (!terminatedTold.compareAndSet(false, true)) {
            return;
        }
        if (problem != null) {
            say(event("output", JSON.createObjectNode().put("category", "stderr").put("output", problem + "\n")));
        }
        say(event("terminated", null));
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
