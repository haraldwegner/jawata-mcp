package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.engine.EngineState;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.execution.AcpBrainSession;
import org.jawata.mcp.execution.HireSite;
import org.jawata.mcp.execution.HiredBrain;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.EngineSettings;
import org.jawata.spi.host.HostCapabilities;
import org.jawata.spi.host.McpEndpoint;
import org.jawata.spi.host.PersonChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 28h, S7a (D1): the principal seat answers a host's prompt through a hired brain. The brain here is the stand-in ACP agent (the test
 * resource {@code FakeAcpAgent.java.txt}); that the same holds against a real vendor's brain is the stage's live exit, run separately.
 */
class PrincipalBrainTest {

    private Path dir;
    private AcpBrainSession.Program fake;

    @BeforeEach
    void putTheStandInAgentOnDisk() throws IOException {
        dir = Files.createTempDirectory("jawata-principal-");
        try (InputStream in = PrincipalBrainTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            assertNotNull(in, "the stand-in agent is not among the test resources");
            Files.write(dir.resolve("FakeAcpAgent.java"), in.readAllBytes());
        }
        fake = new AcpBrainSession.Program(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            dir.resolve("FakeAcpAgent.java").toString()), Map.of("FAKE_ACP_STATE", dir.toString()));
    }

    @AfterEach
    void removeWhatWasCreated() throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private PrincipalBrain brain(Optional<AcpBrainSession.Program> program) {
        return new PrincipalBrain(HostProcesses.system(), program, HireSite.in(dir), null, List.of("inspect", "rename_symbol"),
            new Hirer(SeatTree.standard(), Map.of(), java.util.Set.of("rename_symbol")), HiredBrain.Ceilings.of(Duration.ofSeconds(60)));
    }

    private static String ask(PrincipalBrain brain, String prompt, List<String> pieces) {
        brain.answer(prompt, pieces::add);
        return String.join("", pieces);
    }

    @Test
    @DisplayName("a prompt reaches the hired brain, its answer reaches the host as text, and a hire record names the program that ran")
    void aPromptIsAnsweredByTheHiredBrainAndRecorded() {
        PrincipalBrain brain = brain(Optional.of(fake));
        List<String> pieces = new ArrayList<>();

        String answer = ask(brain, "REMEMBER:bluebird", pieces);

        assertEquals("noted", answer);
        assertFalse(answer.contains("no brain"), "the no-brain sentence was given in place of an answer");
        assertEquals(1, brain.records().size());
        PrincipalBrain.HireRecord record = brain.records().get(0);
        assertEquals("main", record.seat());
        assertTrue(record.command().get(1).endsWith("FakeAcpAgent.java"), "the record does not name the program that ran: " + record.command());
        assertTrue(record.brainSessionId().isPresent() && record.clean(), record.toString());
    }

    @Test
    @DisplayName("the second prompt of the same brain continues the first one's session")
    void theSecondPromptKnowsWhatTheFirstSaw() {
        PrincipalBrain brain = brain(Optional.of(fake));

        ask(brain, "REMEMBER:bluebird", new ArrayList<>());
        String recalled = ask(brain, "RECALL", new ArrayList<>());
        String fresh = ask(brain(Optional.of(fake)), "RECALL", new ArrayList<>());

        assertEquals("bluebird", recalled);
        assertEquals("nothing", fresh, "CONTROL: a brain that was not continued knew it anyway");
    }

    @Test
    @DisplayName("the principal is a lead: its brain is refused a code-changing tool and allowed a reading one")
    void theLeadCannotChangeCode() {
        PrincipalBrain brain = brain(Optional.of(fake));

        String changing = ask(brain, "CALL:mcp__jawata__rename_symbol", new ArrayList<>());
        String reading = ask(brain, "CALL:mcp__jawata__inspect", new ArrayList<>());

        assertEquals("permission:reject_once", changing);
        assertEquals("permission:allow_once", reading);
    }

    @Test
    @DisplayName("the answer reaches the host while the turn is still running, not all at once at its end")
    void theAnswerArrivesBeforeTheTurnEnds() {
        PrincipalBrain brain = brain(Optional.of(fake));
        List<String> pieces = new ArrayList<>();
        long[] firstAt = {0};

        brain.answer("STREAM", piece -> {
            if (firstAt[0] == 0) {
                firstAt[0] = System.nanoTime();
            }
            pieces.add(piece);
        });
        long returnedAt = System.nanoTime();

        assertEquals("firstsecond", String.join("", pieces));
        assertTrue(firstAt[0] != 0 && Duration.ofNanos(returnedAt - firstAt[0]).toMillis() >= 1000,
            "the first piece arrived only when the turn ended: it was held back instead of streamed");
    }

    @Test
    @DisplayName("the tools a lead is refused are the ones the published tools say rewrite source, not a list kept beside them")
    void theCodeChangingSetComesFromWhatToolsSay() {
        org.jawata.mcp.change.OperationRegistry operations = org.jawata.mcp.change.OperationRegistry.theRegistry();
        org.jawata.mcp.change.OperationRegistry.Snapshot borrowed = operations.snapshot();
        List<Map<String, Object>> published;
        try {
            org.jawata.mcp.tools.ToolRegistry registry = new org.jawata.mcp.tools.ToolRegistry();
            org.jawata.mcp.fixtures.InertShippedTools.tools().forEach(registry::register);
            published = registry.getToolDefinitions();
        } finally {
            operations.restore(borrowed);
        }

        java.util.Set<String> derived = PrincipalBrain.codeChangingTools(published);

        assertTrue(derived.containsAll(List.of("rename_symbol", "extract", "move", "change_method_signature", "organize_imports")),
            derived.toString());
        assertTrue(derived.containsAll(List.of("format", "quick_fix", "refactoring", "dependency")),
            "a tool that changes code but carries no destructiveHint was left to the lead: " + derived);
        assertTrue(java.util.Collections.disjoint(derived, List.of("inspect", "find_references", "search_symbols", "compile_workspace", "experience")),
            "a reading or checking tool was counted as rewriting source: " + derived);
        assertEquals(java.util.Set.of("x"), PrincipalBrain.codeChangingTools(List.of(
            Map.of("name", "x", "_meta", Map.of("jawata/changesCode", true)),
            Map.of("name", "y", "_meta", Map.of("jawata/changesCode", false)),
            Map.of("name", "w", "annotations", Map.of("destructiveHint", true)),
            Map.of("name", "z"))), "CONTROL: the derivation reads the published changesCode flag and nothing else");
    }

    @Test
    @DisplayName("with no brain named the agent says so and leaves no hire record")
    void withNoBrainItSaysSo() {
        PrincipalBrain brain = brain(Optional.empty());

        assertEquals(AgentFace.NO_BRAIN, ask(brain, "hello", new ArrayList<>()));
        assertEquals(List.of(), brain.records());
    }

    @Test
    @DisplayName("the setting names the brain: a command line is read, text that is not one names none")
    void theSettingNamesTheBrain() {
        Optional<AcpBrainSession.Program> read = PrincipalBrain.parseProgram("{\"command\":[\"agent\",\"acp\"],\"env\":{\"K\":\"v\"}}");

        assertEquals(List.of("agent", "acp"), read.orElseThrow().command());
        assertEquals(Map.of("K", "v"), read.orElseThrow().environment());
        assertEquals(Optional.empty(), PrincipalBrain.parseProgram("not json"));
        assertEquals(Optional.empty(), PrincipalBrain.parseProgram("{\"command\":[]}"));
    }

    @Test
    @DisplayName("built from the engine, the principal brain reads the host setting and the engine's tools and address")
    void builtFromTheEngine() {
        String setting = "{\"command\":[\"" + fake.command().get(0) + "\",\"" + fake.command().get(1) + "\"],\"env\":{\"FAKE_ACP_STATE\":\""
            + dir + "\"}}";
        List<String> pieces = new ArrayList<>();
        List<String> none = new ArrayList<>();

        PrincipalBrain.over(engineWith(Map.of(PrincipalBrain.SETTING, setting)), dir).answer("REMEMBER:x", pieces::add);
        PrincipalBrain.over(engineWith(Map.of()), dir).answer("REMEMBER:x", none::add);

        assertEquals("noted", String.join("", pieces));
        assertEquals(List.of(AgentFace.NO_BRAIN), none);
    }

    private static EngineApi engineWith(Map<String, String> settings) {
        return new EngineApi() {
            @Override public ToolCalls tools() {
                return new ToolCalls() {
                    @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                        throw new UnsupportedOperationException();
                    }
                    @Override public List<Map<String, Object>> getToolDefinitions() {
                        return List.of(Map.of("name", "inspect"), Map.of("name", "rename_symbol"));
                    }
                };
            }
            @Override public EngineState state() {
                return null;
            }
            @Override public PersonChannel person() {
                return null;
            }
            @Override public EngineSettings settings() {
                return key -> Optional.ofNullable(settings.get(key));
            }
            @Override public HostCapabilities host() {
                return null;
            }
            @Override public Optional<McpEndpoint> mcp() {
                return Optional.empty();
            }
        };
    }
}
