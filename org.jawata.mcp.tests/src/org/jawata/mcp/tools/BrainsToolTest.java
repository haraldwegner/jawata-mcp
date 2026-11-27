package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jawata.mcp.execution.SeatSettingsService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.EngineSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, D57 (c): a host that is not the engine's own process (the studio) reads the models of the chat's brains from the engine's one
 * catalogue through this tool, instead of reading each brain's program itself, so every seat is offered the list main is.
 */
class BrainsToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> stored = new HashMap<>();
    private final EngineSettings settings = key -> Optional.ofNullable(stored.get(key));

    private BrainsTool tool(Path seats) {
        return new BrainsTool(() -> SeatSettingsService.overFolder(seats, settings, Set.of()));
    }

    /** A registry naming one brain, "fake", started as the stand-in ACP agent that offers these models ({@code value=Name,...}). */
    private static String fakeAgent(Path dir, String models) throws IOException {
        try (InputStream in = BrainsToolTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            Files.write(dir.resolve("FakeAcpAgent.java"), in.readAllBytes());
        }
        ObjectNode registry = JSON.createObjectNode();
        ObjectNode fake = registry.putObject("fake");
        fake.putArray("command").add(Path.of(System.getProperty("java.home"), "bin", "java").toString()).add(dir.resolve("FakeAcpAgent.java").toString());
        fake.putObject("env").put("FAKE_ACP_STATE", dir.toString()).put("FAKE_ACP_MODELS", models);
        return registry.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> brains(ToolResponse answer) {
        assertTrue(answer.isSuccess(), () -> String.valueOf(answer.getError()));
        return (List<Map<String, Object>>) ((Map<String, Object>) answer.getData()).get("brains");
    }

    @Test
    @DisplayName("28h D57 (c): one brain's models are its own session's, less its default")
    void oneBrainsModels(@TempDir Path seats, @TempDir Path agent) throws IOException {
        stored.put("jawata.agent.hireBrains", fakeAgent(agent, "default=Default,haiku=Haiku " + agent.getFileName() + ",opus=Opus"));

        List<Map<String, Object>> brains = brains(tool(seats).execute(JSON.createObjectNode().put("brain", "fake")));

        assertEquals(1, brains.size(), brains::toString);
        assertEquals("fake", brains.get(0).get("brain"));
        assertEquals(List.of("Haiku " + agent.getFileName(), "Opus"), brains.get(0).get("models"));
    }

    /** A seats door that answers the engine's check and test run with what it is given, so no program on this machine is started. */
    private static BrainsTool over(List<org.jawata.mcp.engine.SeatSettings.Brain> brains, java.util.function.BiFunction<String, String, org.jawata.mcp.engine.SeatSettings.Probe> probe) {
        org.jawata.mcp.engine.SeatSettings door = (org.jawata.mcp.engine.SeatSettings) java.lang.reflect.Proxy.newProxyInstance(
            org.jawata.mcp.engine.SeatSettings.class.getClassLoader(), new Class<?>[] {org.jawata.mcp.engine.SeatSettings.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "brains" -> brains;
                case "probe" -> probe.apply((String) args[0], (String) args[1]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return new BrainsTool(() -> door);
    }

    @Test
    @DisplayName("28h C26: the studio's brain check is the engine's: every program with its login, its models and what a hire may do with it")
    void statusIsTheEnginesCheck() {
        BrainsTool tool = over(List.of(new org.jawata.mcp.engine.SeatSettings.Brain("claude-code", "Claude Code", true, "loggedIn", Optional.empty(),
            "claude", Optional.empty(), true, true, false)), (brain, model) -> null);

        List<Map<String, Object>> rows = brains(tool.execute(JSON.createObjectNode().put("action", "status")));

        assertEquals(1, rows.size(), rows::toString);
        assertEquals("claude-code", rows.get(0).get("id"));
        assertEquals("loggedIn", rows.get(0).get("login"));
        assertEquals(true, rows.get(0).get("takesModel"));
        assertTrue(rows.get(0).containsKey("models") && rows.get(0).get("models") == null, "no models of its own is null, not an empty list: " + rows);
    }

    @Test
    @DisplayName("28h C26: the studio's one-word test run is the engine's, given the model as a Brain list shows it")
    void aProbeIsTheEnginesTestRun() {
        List<String> asked = new java.util.ArrayList<>();
        BrainsTool tool = over(List.of(), (brain, model) -> {
            asked.add(brain + "|" + model);
            return new org.jawata.mcp.engine.SeatSettings.Probe(brain, true, Optional.of("OK"), List.of("claude-haiku-5-5"), Optional.of(0.0006), 1.5,
                Optional.empty(), "claude");
        });

        ToolResponse answer = tool.execute(JSON.createObjectNode().put("action", "probe").put("brain", "claude-code").put("model", "Haiku 5.5"));

        assertTrue(answer.isSuccess(), () -> String.valueOf(answer.getError()));
        assertEquals(List.of("claude-code|Haiku 5.5"), asked, "the engine is given the name; its own probe gives the command line the value");
        @SuppressWarnings("unchecked")
        Map<String, Object> run = (Map<String, Object>) answer.getData();
        assertEquals(true, run.get("ok"));
        assertEquals(List.of("claude-haiku-5-5"), run.get("modelsUsed"));
        assertFalse(tool.execute(JSON.createObjectNode().put("action", "probe")).isSuccess(), "a test run with no brain is refused");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> runs(ToolResponse answer) {
        assertTrue(answer.isSuccess(), () -> String.valueOf(answer.getError()));
        return (List<Map<String, Object>>) ((Map<String, Object>) answer.getData()).get("runs");
    }

    @Test
    @DisplayName("28h z93: a timed probe runs the prompt as often as asked and reports each run's first text, answer and tokens; kept running, only the first start is paid")
    void aTimedProbeReportsEveryRun(@TempDir Path seats, @TempDir Path agent) throws IOException {
        ObjectNode registry = (ObjectNode) JSON.readTree(fakeAgent(agent, "default=Default,haiku=Haiku"));
        ((ObjectNode) registry.path("fake").path("env")).put("FAKE_ACP_SLOW_NEW", "600");
        stored.put("jawata.agent.hireBrains", registry.toString());
        Files.writeString(agent.resolve("usage"), "{\"inputTokens\":3,\"outputTokens\":7}");
        ObjectNode asked = JSON.createObjectNode().put("action", "probe").put("brain", "fake").put("model", "Haiku").put("prompt", "REMEMBER:z93").put("repeat", 2);

        List<Map<String, Object>> cold = runs(tool(seats).execute(asked.deepCopy()));
        List<Map<String, Object>> warm = runs(tool(seats).execute(asked.deepCopy().put("keepRunning", true).put("repeat", 3)));

        assertEquals(2, cold.size(), cold::toString);
        assertEquals(3, warm.size(), warm::toString);
        for (Map<String, Object> row : cold) {
            assertTrue((Double) row.get("seconds") >= 0.6, "a run of its own program did not count the start: " + row);
        }
        for (Map<String, Object> row : warm) {
            assertTrue((Double) row.get("seconds") < 0.6, "a kept-running program was started again, or its start was counted: " + row);
            assertTrue((Double) row.get("secondsToFirstText") <= (Double) row.get("seconds"), row::toString);
            assertEquals(7L, row.get("outputTokens"), row::toString);
            assertFalse(((String) row.get("answer")).isBlank(), row::toString);
        }
    }

    @Test
    @DisplayName("28h D57 (c): with no brain named, every ready chat brain is asked, which is the discovery a deploy runs")
    void everyReadyBrainsModels(@TempDir Path seats, @TempDir Path agent) throws IOException {
        stored.put("jawata.agent.hireBrains", fakeAgent(agent, "default=Default,sonnet=Sonnet " + agent.getFileName()));

        List<Map<String, Object>> brains = brains(tool(seats).execute(JSON.createObjectNode()));

        assertEquals(List.of("fake"), brains.stream().map(row -> row.get("brain")).toList(), "only the ready brains are asked: " + brains);
        assertEquals(List.of("Sonnet " + agent.getFileName()), brains.get(0).get("models"));
    }

    @Test
    @DisplayName("28h D57 (c): a brain the registry does not name is refused by name, never answered with an empty list")
    void anUnknownBrainIsRefused(@TempDir Path seats) {
        ToolResponse answer = tool(seats).execute(JSON.createObjectNode().put("brain", "nobody"));

        assertFalse(answer.isSuccess(), "an empty list would say the brain offers no models");
        assertTrue(String.valueOf(answer.getError()).contains("nobody"), String.valueOf(answer.getError()));
    }
}
