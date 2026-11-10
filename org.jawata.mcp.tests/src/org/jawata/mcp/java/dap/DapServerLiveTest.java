package org.jawata.mcp.java.dap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.spi.host.PersonDebugTurn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28i, Stage 25, step A3c (ARCHITECTURE-28i-s25.md): the person's debugger talks to a real program. The debug adapter server runs over
 * the engine's real debug tool, and a real JVM runs the debug fixture, so the whole conversation of an editor is played: launch, a breakpoint
 * in a method, the stop, the stack, the variables, an expression, a step, a second stop, and the end of the debugging.
 */
class DapServerLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The line of the fixture's {@code computeSignal} that reads {@code int adjusted = doubled + offset();}. */
    private static final int THE_SEAM = 66;

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final PersonDebugTurn turn = new PersonDebugTurn();
    private final List<JsonNode> said = new CopyOnWriteArrayList<>();
    private RuntimeSessionRegistry sessions;
    private DapServer server;
    private Path source;
    private Path classes;
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        DebugTool tool = new DebugTool(() -> service, sessions, new RuntimeArtifactStore(Files.createTempDirectory("jawata-dap-live-")), null, turn);
        ToolCalls calls = new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) throws ToolNotFoundException {
                return caller.speaking(() -> tool.execute(arguments));
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
        server = new DapServer(calls, CallerIdentity.editor("dap-live"), message -> said.add(message.getParams()), turn);
        source = service.getProjectRoot().resolve("src/main/java/com/example/debug/DebugTarget.java");
        classes = Files.createTempDirectory("jawata-dap-live-classes-");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), source.toString()),
            "the debug fixture must compile");
    }

    @AfterEach
    void tearDown() {
        server.closed();
        sessions.closeAll();
    }

    private int send(String command, ObjectNode arguments) {
        ObjectNode request = JSON.createObjectNode().put("seq", ++seq).put("type", "request").put("command", command);
        request.set("arguments", arguments == null ? JSON.createObjectNode() : arguments);
        server.receive(JsonRpcMessage.notification("dap", request));
        return seq;
    }

    private JsonNode answerTo(int requestSeq) {
        return said.stream().filter(m -> "response".equals(m.path("type").asText()) && m.path("request_seq").asInt() == requestSeq).findFirst()
            .orElseThrow(() -> new AssertionError("no answer to request " + requestSeq + " in " + said));
    }

    private long stops() {
        return said.stream().filter(m -> "stopped".equals(m.path("event").asText())).count();
    }

    private boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 1500 && !condition.getAsBoolean(); i++) {
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private JsonNode variable(JsonNode variables, String name) {
        for (JsonNode candidate : variables) {
            if (name.equals(candidate.path("name").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("no variable " + name + " in " + variables);
    }

    @Test
    @DisplayName("an editor's whole conversation with a real program: a breakpoint stops it in a method, its stack, variables and an expression can be read, a step moves on, and disconnect gives the turn back")
    void aRealProgramIsDebuggedFromLaunchToDisconnect() throws Exception {
        send("initialize", JSON.createObjectNode().put("adapterID", "jawata"));
        ObjectNode launch = JSON.createObjectNode().put("mainClass", "com.example.debug.DebugTarget").put("classpath", classes.toString());
        assertTrue(answerTo(send("launch", launch)).path("success").asBoolean(), said.toString());
        assertTrue(turn.personIsDebugging(), "the turn is the person's while the program runs");

        ObjectNode breakpoints = JSON.createObjectNode();
        breakpoints.putObject("source").put("path", source.toString());
        breakpoints.putArray("breakpoints").addObject().put("line", THE_SEAM);
        JsonNode armed = answerTo(send("setBreakpoints", breakpoints)).path("body").path("breakpoints").path(0);
        assertEquals(THE_SEAM, armed.path("line").asInt());

        send("configurationDone", null);
        assertTrue(waitUntil(() -> stops() >= 1), "the breakpoint never stopped the program: " + said);
        JsonNode stopped = said.stream().filter(m -> "stopped".equals(m.path("event").asText())).findFirst().orElseThrow();
        assertEquals("breakpoint", stopped.path("body").path("reason").asText());
        long thread = stopped.path("body").path("threadId").asLong();

        JsonNode stack = answerTo(send("stackTrace", JSON.createObjectNode().put("threadId", thread))).path("body").path("stackFrames");
        assertEquals("com.example.debug.DebugTarget.computeSignal", stack.path(0).path("name").asText());
        assertEquals(THE_SEAM, stack.path(0).path("line").asInt());
        assertEquals("com.example.debug.DebugTarget.main", stack.path(1).path("name").asText());
        int frame = stack.path(0).path("id").asInt();

        JsonNode scopes = answerTo(send("scopes", JSON.createObjectNode().put("frameId", frame))).path("body").path("scopes");
        JsonNode arguments = answerTo(send("variables", JSON.createObjectNode().put("variablesReference", scopes.path(0).path("variablesReference").asInt())))
            .path("body").path("variables");
        JsonNode locals = answerTo(send("variables", JSON.createObjectNode().put("variablesReference", scopes.path(1).path("variablesReference").asInt())))
            .path("body").path("variables");
        assertEquals("0", variable(arguments, "iteration").path("value").asText(), "the first call has iteration 0");
        assertEquals("0", variable(locals, "doubled").path("value").asText());

        JsonNode evaluated = answerTo(send("evaluate", JSON.createObjectNode().put("expression", "iteration + 5").put("frameId", frame))).path("body");
        assertEquals("5", evaluated.path("result").asText());

        send("next", JSON.createObjectNode().put("threadId", thread));
        assertTrue(waitUntil(() -> stops() >= 2), "the step never stopped: " + said);
        JsonNode afterTheStep = said.stream().filter(m -> "stopped".equals(m.path("event").asText())).skip(1).findFirst().orElseThrow();
        assertEquals("step", afterTheStep.path("body").path("reason").asText());
        JsonNode next = answerTo(send("stackTrace", JSON.createObjectNode().put("threadId", thread))).path("body").path("stackFrames").path(0);
        assertEquals(THE_SEAM + 1, next.path("line").asInt(), "a step over moves to the next line of the method");

        assertTrue(answerTo(send("disconnect", null)).path("success").asBoolean());
        assertFalse(turn.personIsDebugging(), "the turn is the agent's again");
    }
}
