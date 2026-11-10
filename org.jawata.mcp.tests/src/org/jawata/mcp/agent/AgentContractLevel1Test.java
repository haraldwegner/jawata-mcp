package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S9: R4 (two hires, a stop, a transcript), R5 (a seat's model change), R7 (a proposal as a permission request) and R12 (the
 * brains and a brain pick) "pass the checker". The checker is the chat's {@code packages/acp-contract/src/level1.ts} {@code checkLevel1},
 * which the chat's own tests run over every recording; {@link #violations} is its port, rule for rule, and the first test holds the port to
 * the four recordings it must accept. The rest play what the engine sends for the same requests and require the same silence from it.
 *
 * <p>R4, R5 and R12 are shape-only recordings made by the chat from a real Claude turn plus an overlay, so a recorded id (a plan id, a value
 * such as {@code default}) names nothing in the engine: each test sends the request the recording sends, with the engine's own ids and values
 * where the recording's cannot exist, and compares what the engine answers by the checker's rules and by the fields the recording carries.</p>
 */
class AgentContractLevel1Test {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Line(String dir, JsonNode msg) {
    }

    private static List<Line> recording(String name) throws IOException {
        try (InputStream in = AgentContractLevel1Test.class.getResourceAsStream("/test-resources/acp/" + name + ".jsonl")) {
            assertNotNull(in, "the recorded session " + name + " is not among the test resources");
            List<Line> lines = new ArrayList<>();
            for (String text : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!text.isBlank()) {
                    JsonNode line = JSON.readTree(text);
                    lines.add(new Line(line.get("dir").asText(), line.get("msg")));
                }
            }
            return lines;
        }
    }

    // ---- the port of level1.ts -------------------------------------------------------------------------------------------------------------

    private static final Map<String, String> HIRE_FIELDS = new LinkedHashMap<>();
    /** The fields a hire may leave out. */
    private static final Set<String> OPTIONAL_HIRE_FIELDS = Set.of("brainSessionId", "parentPlanId", "costUsd", "level", "toolSteps", "contextTokens", "verdict", "enginePlan", "name");
    private static final Set<String> HIRE_STATES = Set.of("running", "compacting", "done", "failed");
    private static final Set<String> HIRE_METHODS = Set.of("_jawata/hire/stop", "_jawata/hire/transcript", "_jawata/hire/brain");

    static {
        for (String field : List.of("planId", "seat", "brain", "model", "state", "seatCommand", "task", "parentPlanId", "brainSessionId", "verdict", "enginePlan", "name")) {
            HIRE_FIELDS.put(field, "string");
        }
        for (String field : List.of("elapsedMs", "tokens", "costUsd", "level", "toolSteps", "contextTokens")) {
            HIRE_FIELDS.put(field, "number");
        }
    }

    private static String typeOf(JsonNode value) {
        return value.isTextual() ? "string" : value.isNumber() ? "number" : value.isBoolean() ? "boolean" : "other";
    }

    private static List<String> hireViolations(JsonNode meta) {
        List<String> out = new ArrayList<>();
        HIRE_FIELDS.forEach((field, type) -> {
            JsonNode v = meta == null ? null : meta.get(field);
            if (v == null) {
                if (!OPTIONAL_HIRE_FIELDS.contains(field)) {
                    out.add("hire: " + field + " is missing");
                }
            } else if (!typeOf(v).equals(type)) {
                out.add("hire: " + field + " is not a " + type);
            }
        });
        if (meta != null) {
            meta.fieldNames().forEachRemaining(field -> {
                if (!HIRE_FIELDS.containsKey(field)) {
                    out.add("hire: " + field + " is not a field of the contract");
                }
            });
            if (meta.has("state") && !HIRE_STATES.contains(meta.get("state").asText())) {
                out.add("hire: state " + meta.get("state") + " is not one of " + HIRE_STATES);
            }
        }
        return out;
    }

    private static List<String> replyViolations(String method, JsonNode result) {
        List<String> out = new ArrayList<>();
        if ("_jawata/hire/stop".equals(method)) {
            if (!result.path("ok").isBoolean()) {
                out.add("stop reply: ok is not a boolean");
            }
            return out;
        }
        if ("_jawata/hire/brain".equals(method)) {
            hireViolations(result).forEach(v -> out.add("brain reply: " + v));
            return out;
        }
        if ("_jawata/brains".equals(method)) {
            if (!result.path("brains").isArray()) {
                out.add("brains reply: brains is not an array");
                return out;
            }
            for (JsonNode choice : result.path("brains")) {
                if (!choice.path("brain").isTextual() || !choice.path("model").isTextual()) {
                    out.add("brains reply: a choice needs a brain and a model");
                }
            }
            return out;
        }
        if (!result.path("planId").isTextual()) {
            out.add("transcript reply: planId is not a string");
        }
        if (!result.path("turns").isArray()) {
            out.add("transcript reply: turns is not an array");
        }
        for (JsonNode turn : result.path("turns")) {
            String role = turn.path("role").asText(null);
            if (!"user".equals(role) && !"assistant".equals(role)) {
                out.add("transcript reply: role " + turn.get("role") + " is not user or assistant");
            }
            if (!turn.path("text").isTextual()) {
                out.add("transcript reply: a turn has no text");
            }
        }
        return out;
    }

    /** Every way the session breaks the contract's Level-1 rules; empty means it passes. A port of {@code checkLevel1}. */
    private static List<String> violations(List<Line> lines) {
        List<String> out = new ArrayList<>();
        Map<JsonNode, String> asked = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String dir = lines.get(i).dir();
            JsonNode msg = lines.get(i).msg();
            String at = "line " + (i + 1);
            JsonNode update = msg.path("params").path("update");
            JsonNode meta = update.path("_meta");
            meta.fieldNames().forEachRemaining(key -> {
                if (key.startsWith("jawata/") && !key.equals("jawata/hire")) {
                    out.add(at + ": _meta key " + key + " is not part of the contract");
                }
            });
            if (meta.has("jawata/hire")) {
                hireViolations(meta.get("jawata/hire")).forEach(v -> out.add(at + ": " + v));
                if ("tool_call".equals(update.path("sessionUpdate").asText()) && !"execute".equals(update.path("kind").asText(null))) {
                    out.add(at + ": a hire's tool call has kind " + update.get("kind") + ", not \"execute\"");
                }
            }
            for (JsonNode command : update.path("availableCommands")) {
                JsonNode group = command.path("_meta").path("jawata/group");
                if (!group.isMissingNode() && !Set.of("seat", "skill", "utility").contains(group.asText())) {
                    out.add(at + ": command " + command.path("name").asText() + ": group " + group + " is not one of seat, skill, utility");
                }
            }
            JsonNode options = msg.path("result").has("configOptions") ? msg.path("result").path("configOptions") : update.path("configOptions");
            for (JsonNode option : options) {
                String id = option.path("id").asText("");
                if (id.equals(AcpServer.PRINCIPAL_BRAIN)) {
                    if (!"model".equals(option.path("category").asText(null))) {
                        out.add(at + ": option " + id + ": category is " + option.get("category") + ", not \"model\"");
                    }
                    if (!"select".equals(option.path("type").asText(null))) {
                        out.add(at + ": option " + id + ": type is " + option.get("type") + ", not \"select\"");
                    }
                }
                if (id.equals("autonomy")) {
                    List<String> values = new ArrayList<>();
                    option.path("options").forEach(choice -> values.add(choice.path("value").asText()));
                    if (!"select".equals(option.path("type").asText(null))) {
                        out.add(at + ": option autonomy: type is " + option.get("type") + ", not \"select\"");
                    }
                    if (!values.equals(List.of("off", "on"))) {
                        out.add(at + ": option autonomy: values are " + values + ", not [off, on]");
                    }
                    if (!List.of("off", "on").contains(option.path("currentValue").asText())) {
                        out.add(at + ": option autonomy: currentValue " + option.get("currentValue") + " is not one of off, on");
                    }
                }
            }
            String method = msg.path("method").asText(null);
            if (method != null && method.startsWith("_jawata/")) {
                if ("_jawata/brains".equals(method)) {
                    if (dir.equals("send")) {
                        asked.put(msg.get("id"), method);
                    }
                } else if (!HIRE_METHODS.contains(method)) {
                    out.add(at + ": method " + method + " is not a jawata extension method");
                } else if (dir.equals("send")) {
                    if (!msg.path("params").path("planId").isTextual()) {
                        out.add(at + ": " + method + " needs a planId");
                    }
                    if ("_jawata/hire/brain".equals(method) && (!msg.path("params").path("brain").isTextual() || !msg.path("params").path("model").isTextual())) {
                        out.add(at + ": " + method + " needs a brain and a model");
                    }
                    asked.put(msg.get("id"), method);
                }
            } else if (dir.equals("recv") && method == null && asked.containsKey(msg.get("id")) && msg.has("result")) {
                replyViolations(asked.get(msg.get("id")), msg.get("result")).forEach(v -> out.add(at + ": " + v));
            }
        }
        return out;
    }

    // ---- an engine to play against ---------------------------------------------------------------------------------------------------------

    private static final String REGISTRY = "{\"claude-code\":{\"command\":[\"claude-agent-acp\"],\"vendor\":\"anthropic\",\"model\":\"sonnet\"},"
        + "\"codex\":{\"command\":[\"codex-acp\"],\"vendor\":\"openai\",\"model\":\"gpt-5\"}}";

    /** A hire the brain issues: its call, then (when {@code answer} is not null) the call's end. */
    private record Hire(String planId, String seat, String brief, String seatFile, String answer) {
    }

    /** The agent over one connection, every message of both directions kept in the order it went. */
    private static final class Wire {
        final List<Line> lines = new ArrayList<>();
        final AcpServer server;
        String session;

        Wire(List<Hire> hires) {
            AgentBrain brain = new AgentBrain() {
                private String current = "cursor";

                @Override
                public java.util.Optional<String> currentBrain() {
                    return java.util.Optional.of(current);
                }

                @Override
                public boolean useBrain(String registry, String name) {
                    current = name;
                    return true;
                }

                @Override
                public void answer(String prompt, Consumer<String> chunks) {
                    answer(prompt, chunks, call -> { });
                }

                @Override
                public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                    for (Hire hire : hires) {
                        ObjectNode started = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", hire.planId()).put("status", "pending")
                            .put("title", "mcp__jawata-javata-dev__hire");
                        started.putObject("rawInput").put("hirer", "main").put("seat", hire.seat()).put("brief", hire.brief()).put("seatFile", hire.seatFile());
                        toolCalls.accept(started);
                        if (hire.answer() != null) {
                            toolCalls.accept(JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", hire.planId())
                                .put("status", "completed").put("title", "mcp__jawata-javata-dev__hire").put("rawOutput", hire.answer()));
                        }
                    }
                    chunks.accept("ok");
                }
            };
            server = new AcpServer(brain, List::of, message -> lines.add(new Line("recv", JSON.valueToTree(message))), TurnBoundary.open(), AgentRecorder.off(), null, null);
            server.offerBrains(() -> REGISTRY);
            JsonNode opened = send(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace"))).path("result");
            session = opened.path("sessionId").asText();
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", "Reply done");
            send(JsonRpcMessage.request(2, "session/prompt", params));
        }

        /** Sends one request as the host and answers what came back for its id. */
        JsonNode send(JsonRpcMessage request) {
            lines.add(new Line("send", JSON.valueToTree(request)));
            server.receive(request);
            JsonNode id = JSON.valueToTree(request).get("id");
            return lines.stream().filter(l -> l.dir().equals("recv") && l.msg().has("id") && l.msg().get("id").equals(id)
                && (l.msg().has("result") || l.msg().has("error"))).map(Line::msg).reduce((first, second) -> second).orElseThrow();
        }

        /** The request a recording's host sent, with the engine's plan id in place of the recorded one. */
        JsonNode sendRecorded(Line recorded, String planId) {
            ObjectNode request = recorded.msg().deepCopy();
            if (planId != null && request.path("params").has("planId")) {
                ((ObjectNode) request.get("params")).put("planId", planId);
            }
            return send(JSON.convertValue(request, JsonRpcMessage.class));
        }
    }

    private static Line sentRequest(List<Line> recording, String method) {
        return recording.stream().filter(l -> l.dir().equals("send") && method.equals(l.msg().path("method").asText(null))).findFirst().orElseThrow();
    }

    private static final String DONE_ANSWER = "{\"data\":{\"cleanExit\":true,\"answer\":\"done\",\"tokens\":{\"input\":100,\"cached\":0,\"output\":50,\"reasoning\":10},\"costUsd\":0.03}}";

    private static String seatFile(Path dir, String name, String command) throws IOException {
        return Files.writeString(dir.resolve(name + ".md"), "---\nname: " + name + "\nbrain: claude-code\nmodel: sonnet\ncommand: " + command + "\n---\n\nbody\n").toString();
    }

    // ---- the tests -------------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the port accepts the four recordings the chat's checker accepts, and finds what is wrong in sessions that break each rule")
    void thePortAcceptsTheRecordingsAndFindsWhatIsWrong() throws IOException {
        for (String name : List.of("R4", "R5", "R7", "R12")) {
            assertEquals(List.of(), violations(recording(name)), name);
        }
        List<Line> stop = new ArrayList<>(List.of(new Line("send", JSON.readTree("{\"id\":3,\"method\":\"_jawata/hire/stop\",\"params\":{\"planId\":\"p\"}}"))));
        stop.add(new Line("recv", JSON.readTree("{\"id\":3,\"result\":{\"planId\":\"p\",\"stopped\":1}}")));
        assertTrue(violations(stop).contains("line 2: stop reply: ok is not a boolean"), violations(stop).toString());

        JsonNode wrongHire = JSON.readTree("{\"method\":\"session/update\",\"params\":{\"update\":{\"sessionUpdate\":\"tool_call\",\"kind\":\"other\","
            + "\"_meta\":{\"jawata/hire\":{\"planId\":\"p\",\"seat\":\"s\",\"state\":\"running\",\"bogus\":1}}}}}");
        List<String> hire = violations(List.of(new Line("recv", wrongHire)));
        assertTrue(hire.contains("line 1: hire: task is missing"), hire.toString());
        assertTrue(hire.contains("line 1: hire: bogus is not a field of the contract"), hire.toString());
        assertTrue(hire.contains("line 1: a hire's tool call has kind \"other\", not \"execute\""), hire.toString());

        List<Line> turns = new ArrayList<>(List.of(new Line("send", JSON.readTree("{\"id\":4,\"method\":\"_jawata/hire/transcript\",\"params\":{\"planId\":\"p\"}}"))));
        turns.add(new Line("recv", JSON.readTree("{\"id\":4,\"result\":{\"planId\":\"p\",\"turns\":[{\"from\":\"pm\",\"text\":\"x\"}]}}")));
        assertTrue(violations(turns).stream().anyMatch(v -> v.contains("role null is not user or assistant")), violations(turns).toString());
    }

    @Test
    @DisplayName("R4: two hires listed as tool calls, a stop and a transcript — what the engine sends for the same requests passes the checker and carries every field the recording carries")
    void r4TwoHiresAStopAndATranscriptPassTheChecker(@TempDir Path dir) throws Exception {
        List<Line> r4 = recording("R4");
        Wire wire = new Wire(List.of(
            new Hire("p-architect", "architect", "Review the design of the change against the target architecture.", seatFile(dir, "architect", "refactor"), DONE_ANSWER),
            new Hire("p-test-writer", "test-writer", "Write the tests the change owes.", seatFile(dir, "test-writer", "cover"), null)));
        CountDownLatch registered = new CountDownLatch(1);
        Thread running = new Thread(() -> {
            try (HireRegistry.Registration registration = HireRegistry.shared().begin("test-writer", "Write the tests the change owes.", "")) {
                registered.countDown();
                Thread.sleep(30_000);
            } catch (InterruptedException stopped) {
                // the stop below ended it
            }
        });
        running.setDaemon(true);
        running.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the running hire did not register");

        JsonNode stop = wire.sendRecorded(sentRequest(r4, "_jawata/hire/stop"), "p-test-writer");
        JsonNode transcript = wire.sendRecorded(sentRequest(r4, "_jawata/hire/transcript"), "p-architect");
        running.join(5_000);

        assertEquals(List.of(), violations(wire.lines));
        assertTrue(stop.path("result").path("ok").asBoolean(), stop.toString());
        assertEquals("user", transcript.path("result").path("turns").get(0).path("role").asText(), transcript.toString());
        assertEquals("assistant", transcript.path("result").path("turns").get(1).path("role").asText(), transcript.toString());
        JsonNode recordedHire = null;
        for (Line line : r4) {
            if (recordedHire == null && line.msg().path("params").path("update").path("_meta").has("jawata/hire")) {
                recordedHire = line.msg().path("params").path("update").path("_meta").get("jawata/hire");
            }
        }
        JsonNode ours = null;
        for (Line line : wire.lines) {
            JsonNode update = line.msg().path("params").path("update");
            if (ours == null && "p-architect".equals(update.path("toolCallId").asText()) && update.path("_meta").has("jawata/hire")) {
                ours = update.path("_meta").get("jawata/hire");
            }
        }
        assertNotNull(ours, "the engine sent no entry for the hire");
        assertNotNull(recordedHire);
        Map<String, String> tasks = new LinkedHashMap<>();
        for (Line line : wire.lines) {
            JsonNode update = line.msg().path("params").path("update");
            if ("tool_call".equals(update.path("sessionUpdate").asText()) && update.path("_meta").has("jawata/hire")) {
                tasks.put(update.path("toolCallId").asText(), update.path("_meta").path("jawata/hire").path("task").asText());
            }
        }
        assertEquals(Map.of("p-architect", "Review the design of the change against the target architecture.", "p-test-writer", "Write the tests the change owes."), tasks,
            "both hires are listed on the wire, each with its task");
        for (String field : List.of("planId", "seat", "brain", "model", "state", "elapsedMs", "tokens", "seatCommand", "task")) {
            assertTrue(ours.has(field), "the entry the engine sent has no " + field + ": " + ours);
            assertEquals(typeOf(recordedHire.get(field)), typeOf(ours.get(field)), field);
        }
        assertEquals("running", ours.path("state").asText());
        assertEquals("/refactor", ours.path("seatCommand").asText());
        assertEquals(recordedHire.path("seatCommand").asText(), ours.path("seatCommand").asText(), "the command is written as the person types it, with its slash, as the recording writes it");
    }

    @Test
    @DisplayName("R12: the brains the agent offers and the Replace of a running hire's brain pass the checker, and the answer is the hire as it now is")
    void r12TheBrainsAndABrainPickPassTheChecker(@TempDir Path dir) throws Exception {
        List<Line> r12 = recording("R12");
        Wire wire = new Wire(List.of(new Hire("p-pm", "pm", "Run sprint 28i.", seatFile(dir, "pm-r12", "pm"), null)));

        ObjectNode brainsRequest = sentRequest(r12, "_jawata/brains").msg().deepCopy();
        brainsRequest.remove("params");
        JsonNode brains = wire.send(JSON.convertValue(brainsRequest, JsonRpcMessage.class));
        ObjectNode pick = sentRequest(r12, "_jawata/hire/brain").msg().deepCopy();
        ((ObjectNode) pick.get("params")).put("planId", "p-pm").put("brain", "claude-code").put("model", "sonnet");
        // The Replace ends the brain process of a hire the engine is running, so the hire stands on a thread of its own, waiting for that end.
        java.util.concurrent.CountDownLatch registered = new java.util.concurrent.CountDownLatch(1);
        Thread hire = new Thread(() -> {
            try (var running = org.jawata.mcp.execution.HireRegistry.shared().begin("pm", "Run sprint 28i.", "")) {
                java.util.concurrent.CountDownLatch brainEnded = new java.util.concurrent.CountDownLatch(1);
                org.jawata.mcp.execution.HireRegistry.shared().onStop(brainEnded::countDown);
                registered.countDown();
                brainEnded.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        hire.setDaemon(true);
        hire.start();
        registered.await();
        JsonNode picked = wire.send(JSON.convertValue(pick, JsonRpcMessage.class));
        hire.join(5_000);
        assertFalse(hire.isAlive(), "the Replace did not stop the hire");

        assertEquals(List.of(), violations(wire.lines));
        assertEquals(2, brains.path("result").path("brains").size(), brains.toString());
        assertEquals("p-pm", picked.path("result").path("planId").asText(), picked.toString());
        assertEquals("claude-code", picked.path("result").path("brain").asText(), picked.toString());
        assertEquals("sonnet", picked.path("result").path("model").asText(), picked.toString());
        assertEquals("/pm", picked.path("result").path("seatCommand").asText(), picked.toString());
    }

    @Test
    @DisplayName("D53 (i): the principal's brain is one select option in the category model, no per-seat option is offered, and setting it is answered with the options as they now stand")
    void thePrincipalsBrainIsTheOnlyBrainOption() {
        Wire wire = new Wire(List.of());
        ObjectNode params = JSON.createObjectNode().put("sessionId", wire.session).put("configId", AcpServer.PRINCIPAL_BRAIN).put("value", "claude-code");

        JsonNode answer = wire.send(JsonRpcMessage.request(90, "session/set_config_option", params));

        assertEquals(List.of(), violations(wire.lines));
        JsonNode option = null;
        for (JsonNode candidate : answer.path("result").path("configOptions")) {
            assertFalse(candidate.path("id").asText().startsWith("seat-model:"), candidate.toString());
            if (AcpServer.PRINCIPAL_BRAIN.equals(candidate.path("id").asText())) {
                option = candidate;
            }
        }
        assertNotNull(option, answer.toString());
        assertEquals("claude-code", option.path("currentValue").asText());
        assertEquals("model", option.path("category").asText());
    }

    @Test
    @DisplayName("S9: a running hire's context size reaches the host on each update of the hire's call, and a fall in it is the value the host receives, not the largest seen")
    void aRunningHiresContextSizeFallsOnTheWireWhenItFalls() {
        List<Line> lines = new ArrayList<>();
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                ObjectNode started = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "ctx-1").put("status", "pending")
                    .put("title", "mcp__jawata-javata-dev__hire");
                started.putObject("rawInput").put("hirer", "main").put("seat", "ctx-seat").put("brief", "ctx brief");
                ObjectNode working = JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "ctx-1").put("status", "in_progress")
                    .put("title", "mcp__jawata-javata-dev__hire");
                try (HireRegistry.Registration running = HireRegistry.shared().begin("ctx-seat", "ctx brief", "")) {
                    toolCalls.accept(started);
                    HireRegistry.shared().noteUsage(java.util.OptionalLong.of(9_000), java.util.OptionalDouble.empty());
                    toolCalls.accept(working.deepCopy());
                    HireRegistry.shared().noteUsage(java.util.OptionalLong.of(2_500), java.util.OptionalDouble.empty());
                    toolCalls.accept(working.deepCopy());
                }
                chunks.accept("ok");
            }
        };
        AcpServer server = new AcpServer(brain, List::of, message -> lines.add(new Line("recv", JSON.valueToTree(message))), TurnBoundary.open(), AgentRecorder.off(), null, null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));
        String session = lines.stream().filter(l -> l.msg().path("result").has("sessionId")).findFirst().orElseThrow().msg().path("result").path("sessionId").asText();
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "go");
        server.receive(JsonRpcMessage.request(2, "session/prompt", params));

        List<Long> sizes = new ArrayList<>();
        for (Line line : lines) {
            JsonNode entry = line.msg().path("params").path("update").path("_meta").path("jawata/hire");
            if (!entry.isMissingNode()) {
                sizes.add(entry.has("contextTokens") ? entry.get("contextTokens").asLong() : -1L);
            }
        }
        assertEquals(List.of(-1L, 9_000L, 2_500L), sizes, "no figure before the brain reports one, then each report as it stands");
    }

    @Test
    @DisplayName("R7: a hire's proposal is asked as a permission request the chat will put to the person — a diff block with a path and its new text, and a choice that allows once and one that rejects")
    void r7AProposalIsARequestTheChatPutsToThePerson(@TempDir Path dir) throws Exception {
        List<Line> r7 = recording("R7");
        String changed = "{\"data\":{\"cleanExit\":true,\"answer\":\"done\",\"changedFiles\":[\"a.txt\"],"
            + "\"changes\":[{\"path\":\"a.txt\",\"oldText\":null,\"newText\":\"one\\n\"}]}}";
        Wire wire = new Wire(List.of(new Hire("p-dev", "developer", "Write a.txt.", seatFile(dir, "developer", "dev"), changed)));
        JsonNode recorded = null;
        for (Line line : r7) {
            if (recorded == null && line.dir().equals("recv") && "session/request_permission".equals(line.msg().path("method").asText(null))) {
                recorded = line.msg();
            }
        }
        JsonNode ours = wire.lines.stream().filter(l -> "session/request_permission".equals(l.msg().path("method").asText(null))).map(Line::msg).findFirst().orElse(null);

        assertNotNull(ours, "the engine put nothing to the person");
        assertNotNull(recorded);
        JsonNode diff = null;
        for (JsonNode block : ours.path("params").path("toolCall").path("content")) {
            if ("diff".equals(block.path("type").asText())) {
                diff = block;
            }
        }
        assertNotNull(diff, "the request carries no diff block, so the chat would answer it itself: " + ours);
        JsonNode recordedDiff = recorded.path("params").path("toolCall").path("content").get(0);
        assertEquals("a.txt", diff.path("path").asText());
        assertTrue(diff.has("oldText") && diff.get("oldText").isNull(), "a new file's oldText is null, as the recorded one is: " + diff);
        assertTrue(recordedDiff.has("oldText") && recordedDiff.get("oldText").isNull());
        assertEquals("one\n", diff.path("newText").asText());
        List<String> kinds = new ArrayList<>();
        ours.path("params").path("options").forEach(option -> {
            kinds.add(option.path("kind").asText());
            assertTrue(option.path("optionId").isTextual() && option.path("name").isTextual(), option.toString());
        });
        assertTrue(kinds.contains("allow_once"), kinds.toString());
        assertTrue(kinds.contains("reject_once"), kinds.toString());
        assertFalse(kinds.contains("allow_always"), "a proposal is answered this once, never as a standing grant: " + kinds);
        assertEquals(List.of(), violations(wire.lines));
    }
}
