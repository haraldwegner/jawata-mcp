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

    private AcpBrainSession() {
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
     * What it takes to start a brain that speaks ACP: the command line the brain's own installation defines, and the variables it needs
     * beyond the resident's. Per brain, never per call.
     */
    public record Program(List<String> command, Map<String, String> environment) {
    }

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
     */
    public static HiredBrain.Outcome run(HostProcesses host, Program program, HireSite base, String prompt, SelfEndpoint mcp,
            List<String> allowedTools, Optional<String> resume, HiredBrain.Ceilings ceilings, Consumer<String> onText, Consumer<JsonNode> onToolUpdate, Consumer<JsonNode> onThinking) throws IOException, InterruptedException {
        Conversation conversation = Conversation.open(host, program, base, mcp, allowedTools, resume, ceilings);
        try {
            conversation.turn(prompt, List.of(), ceilings, onText, onToolUpdate, onThinking);
        } finally {
            conversation.end();
        }
        return conversation.outcome();
    }

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

        private StringBuilder said = new StringBuilder();
        private List<String> toolNames = new ArrayList<>();
        private List<ToolCall> toolCalls = new ArrayList<>();
        private OptionalDouble cost = OptionalDouble.empty();
        private String stoppedBy;
        private boolean answered;

        private Conversation(Process child, Set<String> allowed, HiredBrain.Ceilings ceilings) {
            this.child = child;
            this.stderrTail = new StringBuilder();
            this.drain = AcpBrainSession.drain(child, stderrTail);
            this.wire = new Wire(child, allowed, ceilings);
        }

        /** Starts the brain and opens its session, with no ceiling running afterwards: the conversation waits for its first prompt. */
        public static Conversation start(HostProcesses host, Program program, HireSite base, SelfEndpoint mcp, List<String> allowedTools,
                Optional<String> resume, HiredBrain.Ceilings ceilings) throws IOException {
            Conversation conversation = open(host, program, base, mcp, allowedTools, resume, ceilings);
            conversation.disarm();
            return conversation;
        }

        private static Conversation open(HostProcesses host, Program program, HireSite base, SelfEndpoint mcp, List<String> allowedTools,
                Optional<String> resume, HiredBrain.Ceilings ceilings) throws IOException {
            Map<String, String> environment = new LinkedHashMap<>(base.environment());
            environment.putAll(program.environment());
            Process child = host.start(new HostCommand(program.command(), base.workingDirectory(), environment,
                ceilings.wallClock(), false, base.cleanEnvironment()));
            // A stop from the Agent Control panel kills this process: the read of its output is blocked and a thread interrupt cannot end it.
            RunningHires.shared().onStop(() -> HostProcesses.reap(child));
            Conversation conversation = new Conversation(child, new HashSet<>(allowedTools == null ? List.of() : allowedTools), ceilings);
            conversation.arm(ceilings);
            try {
                conversation.handshake(base, mcp, resume);
            } catch (IOException e) {
                conversation.close();
                throw e;
            }
            return conversation;
        }

        private void arm(HiredBrain.Ceilings ceilings) {
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
            ArrayNode servers = mcpServers(mcp, capabilities.path("mcpCapabilities").path("http").asBoolean(false));
            if (resume.isPresent() && capabilities.path("loadSession").asBoolean(false)) {
                String earlier = resume.get();
                if (wire.call(2, "session/load", params -> {
                    params.put("sessionId", earlier);
                    params.put("cwd", cwd);
                    params.set("mcpServers", servers);
                }) != null) {
                    sessionId = earlier;
                }
            }
            if (sessionId == null) {
                Consumer<ObjectNode> newSession = params -> {
                    params.put("cwd", cwd);
                    params.set("mcpServers", servers);
                };
                JsonNode created = wire.call(2, "session/new", newSession);
                if (created == null && wire.refusedForWantOfLogin() && init.path("authMethods").size() > 0) {
                    // A brain that wants its user logged in says so on session/new and lists how (Cursor's agent: cursor_login).
                    // Login is tried once, with the first method it listed, and only after the refusal, so a brain that never
                    // refuses is never sent authenticate.
                    String method = init.path("authMethods").get(0).path("id").asText();
                    if (wire.call(4, "authenticate", params -> params.put("methodId", method)) != null) {
                        created = wire.call(5, "session/new", newSession);
                    }
                }
                sessionId = created == null ? null : created.path("sessionId").asText(null);
            }
        }

        /** What the brain said when it would not open a session, such as that its user must sign in; empty when it said nothing. */
        public String refusal() {
            return wire.lastError == null ? "" : wire.lastError.path("message").asText("");
        }

        /** Whether the brain is still there to be prompted: a process that died between two messages is started again by whoever holds this. */
        public boolean alive() {
            return child.isAlive() && sessionId != null;
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
            Map<String, Integer> callAt = new HashMap<>();
            for (JsonNode update : wire.events) {
                switch (update.path("sessionUpdate").asText()) {
                    case "agent_message_chunk" -> said.append(update.path("content").path("text").asText(""));
                    case "tool_call" -> {
                        String name = update.path("title").asText(update.path("kind").asText("tool"));
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
            stoppedBy = wire.stoppedBy;
            answered = stop != null;
            if (stop == null && !reaped && stoppedBy == null) {
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
                stderrTail.toString(), Optional.ofNullable(stoppedBy), List.copyOf(toolNames), Optional.empty(), Optional.empty(),
                List.copyOf(toolCalls), Optional.ofNullable(sessionId));
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

    static String residentToolIn(String title) {
        java.util.regex.Matcher matched = RESIDENT_TITLE.matcher(title);
        return matched.matches() ? matched.group(1) : null;
    }

    /** The resident as the brain's one tool server, or none when there is no endpoint or the brain cannot attach an HTTP one. */
    private static ArrayNode mcpServers(SelfEndpoint mcp, boolean brainCanAttachHttp) {
        ArrayNode servers = JSON.createArrayNode();
        if (mcp != null && brainCanAttachHttp) {
            ObjectNode server = servers.addObject();
            server.put("type", "http");
            server.put("name", SelfEndpoint.SERVER_NAME);
            server.put("url", mcp.url());
            ArrayNode headers = server.putArray("headers");
            ObjectNode header = headers.addObject();
            header.put("name", "Authorization");
            header.put("value", "Bearer " + mcp.token());
            if (mcp.sessionId() != null) {
                ObjectNode session = headers.addObject();
                session.put("name", SelfEndpoint.SESSION_HEADER);
                session.put("value", mcp.sessionId());
            }
        }
        return servers;
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
        /** Called with each piece of the answer the moment it is read, so a host sees it stream; null when nobody is listening. */
        Consumer<String> onText;
        /** Called with each tool call update the moment it is read; null when nobody is listening. */
        Consumer<JsonNode> onToolUpdate;
        /** Called with each thinking chunk and each plan update the moment it is read; null when nobody is listening. */
        Consumer<JsonNode> onThinking;

        Wire(Process child, Set<String> allowed, HiredBrain.Ceilings ceilings) {
            this.child = child;
            this.in = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            this.out = child.getOutputStream();
            this.allowed = allowed;
            this.ceilings = ceilings;
        }

        /** Readies the wire for the next prompt's answer: what streams from here on is that answer, not an earlier one or a history a load replayed. */
        void beginTurn(HiredBrain.Ceilings turnCeilings, Consumer<String> text, Consumer<JsonNode> toolUpdate, Consumer<JsonNode> thinking) {
            ceilings = turnCeilings;
            onText = text;
            onToolUpdate = toolUpdate;
            onThinking = thinking;
            events.clear();
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

        /** Whether the last reply was a refusal that says the user must log in first. */
        boolean refusedForWantOfLogin() {
            return lastError != null && lastError.path("message").asText("").toLowerCase().contains("authentication required");
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
         * Tells the registry of running hires what this update shows of the hire on this thread: one more tool call, or the context size and
         * cost a usage report carries. A report is read as the brain sent it; a figure it does not carry is passed on as absent.
         */
        private static void noteProgress(JsonNode update) {
            String kind = update.path("sessionUpdate").asText();
            Optional<Boolean> compaction = compactionIn(update);
            if (compaction.isPresent()) {
                // The brain compacting its context is not a tool call of the hire's work.
                RunningHires.shared().noteCompaction(compaction.get());
            } else if ("tool_call".equals(kind)) {
                RunningHires.shared().noteToolStep();
            } else if ("usage_update".equals(kind)) {
                JsonNode used = update.path("used");
                JsonNode amount = update.path("cost").path("amount");
                RunningHires.shared().noteUsage(used.isNumber() ? java.util.OptionalLong.of(used.asLong()) : java.util.OptionalLong.empty(),
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
                // D22, the loop for lookups: a seat that may use the shell is still refused a text search over Java source
                boolean listed = allowed.contains(name) || allowed.contains(RunEffects.bareName(name))
                    || ofTheResident != null && allowed.contains(ofTheResident);
                String wanted = listed && LookupDriver.refusal(tool.path("rawInput")).isEmpty() ? "allow" : "reject";
                String chosen = null;
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
