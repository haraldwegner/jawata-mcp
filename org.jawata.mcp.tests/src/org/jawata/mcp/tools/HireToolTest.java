package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One seat hires another, through the published tool (Sprint 28g S16, C16).
 *
 * <p>C16's first clause asks that the PM seat hires the cataloguer — <i>named hirer, named hired,
 * not a script</i> — and that the run goes end to end. What makes it meetable is that
 * {@code HiredRun.issue} finally has a production caller; until this tool existed it had seven and
 * every one was a test, filed as S16-5. These cases drive that caller.</p>
 *
 * <p><b>Not a script</b> is asserted the only way a unit test can assert it: the seat's OWN
 * frontmatter decides the brain, so the dialect under test is claude's and the stand-in must speak
 * claude's shape. A stand-in emitting the script dialect's declarations would read as zero tool
 * steps, which is how the sibling test in {@code org.jawata.mcp.execution} discovered that the spy
 * replaces the PROCESS and not the DIALECT.</p>
 */
class HireToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The cataloguer seat's real frontmatter, verbatim from {@code jawata-studio/seats/cataloguer.md}.
     *
     * <p>Verbatim on purpose: the clause is that a seat's own file IS the hire, so a fixture
     * paraphrasing it would prove the reader works on text shaped the way its author expected
     * rather than on what the product ships. The body is cut because {@code SeatHire} reads the
     * frontmatter and nothing else.</p>
     */
    private static final String CATALOGUER_SEAT = """
        ---
        name: cataloguer
        model: claude-sonnet-5
        kind: code
        # tier justification (the product is a JUDGEMENT repeated many times — "what is this
        # member FOR" is the whole content, and a summary that restates the signature is
        # refused by the store's own gate, so a cheaper tier spends the run on rejections)
        effort: medium
        tools: [inspect, search_symbols, analyze, experience]
        gates: []
        ttl_secs: 900
        max_iterations: 1
        cost_budget_usd: 2.0
        max_tool_steps: 80
        ---
        You are the cataloguer seat.
        """;

    /** The same seat, switched to the brain that CANNOT be told which tools to attach. */
    private static final String CURSOR_SEAT = CATALOGUER_SEAT
        .replace("name: cataloguer", "name: cataloguer\nbrain: cursor");

    private Path dir;
    private H2ExperienceStore store;
    private LearnerEventStore events;
    private RuntimeArtifactStore artifacts;
    private PlanLedger plans;
    private HostProcesses host;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("jawata-hire-tool-");
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
        artifacts = new RuntimeArtifactStore(dir.resolve("artifacts"));
        plans = new PlanLedger();
        host = HostProcesses.system();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best effort; the temp dir is the OS's problem after this
                    }
                });
            }
        }
    }

    /**
     * Starts a harmless stand-in instead of a real vendor CLI.
     *
     * <p>jawata-duplicate: the sibling of this class in {@code org.jawata.mcp.execution} holds the
     * same idea as a package-private nested class, and Java package access is not hierarchical, so
     * it cannot be reached from here. Promoting it to a shared test helper would edit a class whose
     * every assertion is already mutation-proved, to save fifteen lines — so the duplication is
     * declared rather than taken. A THIRD instance is when the shared helper pays for itself.</p>
     */
    private static final class StandsIn implements HostProcesses {

        private final HostProcesses real;
        private final HostCommand standIn;

        StandsIn(HostProcesses real, HostCommand standIn) {
            this.real = real;
            this.standIn = standIn;
        }

        @Override
        public Process start(HostCommand command) throws IOException {
            return real.start(standIn);
        }

        @Override
        public HostProcessOutcome run(HostCommand command) throws InterruptedException {
            return real.run(command);
        }

        @Override
        public List<String> executableCandidates(String base) {
            return real.executableCandidates(base);
        }

        @Override
        public List<String> wrapperNames(String base) {
            return real.wrapperNames(base);
        }
    }

    /** Claude's own measured event shape: one turn with two tool uses, then the terminal cost. */
    private HostCommand aBrainThatAnswers() throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, """
            #!/bin/sh
            echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"I catalogued the execution package."},{"type":"tool_use"},{"type":"tool_use"}]}}'
            echo '{"type":"result","result":"done","total_cost_usd":0.07}'
            """);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    private HireTool toolWith(HostProcesses processes, LearnerEventStore journal,
            ToolRegistry gates) {
        return new HireTool(() -> null, processes, artifacts, plans, () -> journal, () -> gates);
    }

    /** A registry holding one gate that answers a clean compile. */
    private static ToolRegistry aRegistryWithACleanGate() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "compile_workspace";
            }

            @Override
            public String getDescription() {
                return "a gate fixture";
            }

            @Override
            public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of("errorCount", 0));
            }
        });
        return registry;
    }

    private Path seatFile(String markdown) throws Exception {
        Path file = Files.createTempFile(dir, "seat-", ".md");
        Files.writeString(file, markdown);
        return file;
    }

    private JsonNode hireArgs(Path seat) {
        return JSON.createObjectNode()
            .put("hirer", "pm")
            .put("seat", "cataloguer")
            .put("seatFile", seat.toString())
            .put("brief", "Describe every member of org.jawata.mcp.execution.")
            .put("workingDirectory", dir.toString())
            .set("steps", JSON.createArrayNode().add(
                JSON.createObjectNode().put("tool", "compile_workspace")
                    .set("arguments", JSON.createObjectNode())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse response) {
        return (Map<String, Object>) response.getData();
    }

    @Test
    @DisplayName("C16: the PM seat hires the cataloguer end to end, and the record names both")
    void thePmSeatHiresTheCataloguer() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events,
            aRegistryWithACleanGate());

        ToolResponse response = hire.execute(hireArgs(seatFile(CATALOGUER_SEAT)));

        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
        Map<String, Object> data = dataOf(response);
        assertAll(
            // NAMED HIRER, NAMED HIRED — C16's own words, and the two a record without them
            // cannot attribute a run to anyone.
            () -> assertEquals("pm", data.get("hirer")),
            () -> assertEquals("cataloguer", data.get("hired")),
            // NOT A SCRIPT: the stand-in spoke claude's shape and claude's reader counted it, which
            // only happens if the dialect came from the seat's own frontmatter.
            () -> assertEquals(2, data.get("toolSteps"),
                "two tool_use blocks, read by the brain the SEAT chose"),
            () -> assertEquals(0.07, data.get("costUsd"),
                "claude is the one brain of four that publishes a dollar cost"),
            // THE GATES RAN, and each row says whether it ANSWERED as well as what it counted.
            () -> assertEquals(1, ((List<?>) data.get("gates")).size()),
            () -> assertEquals(Boolean.TRUE,
                ((Map<?, ?>) ((List<?>) data.get("gates")).get(0)).get("answered")),
            () -> assertEquals(0L,
                ((Map<?, ?>) ((List<?>) data.get("gates")).get(0)).get("errorCount")),
            () -> assertEquals(1, data.get("stepsPassed")),
            () -> assertEquals(Boolean.TRUE, data.get("complete")),
            // THE RESULT IS JOURNALLED, and the record is a file a human opens rather than a blob
            // in this answer — so what a reader reads and what the journal points at cannot drift.
            () -> assertTrue(Files.exists(Path.of((String) data.get("record"))),
                "the record a human reads is on disk at the path the response gives"),
            () -> assertTrue(data.get("artifactId") instanceof String id && !id.isBlank()),
            () -> assertTrue(data.get("plan") instanceof String plan && !plan.isBlank()),
            // AND THE SEAT'S BRAIN CAN BE TOLD WHICH TOOLS, which is the other half of the clause
            // the cursor case below exercises.
            () -> assertEquals(Boolean.TRUE, data.get("toolsSettableByTheHirer")));
    }

    @Test
    @DisplayName("C16: a brain that cannot be told which tools is REPORTED, not left out")
    void aBrainThatCannotBeToldIsReported() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events,
            aRegistryWithACleanGate());

        ToolResponse response = hire.execute(hireArgs(seatFile(CURSOR_SEAT)));

        // IT STILL RAN. That is the clause: a brain that cannot be told is not a brain that is
        // dropped from the count, so the hire succeeds and the limitation is in the answer.
        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
        Map<String, Object> data = dataOf(response);
        assertAll(
            () -> assertEquals(Boolean.FALSE, data.get("toolsSettableByTheHirer")),
            () -> assertTrue(String.valueOf(data.get("toolAttachment")).toLowerCase()
                .contains("cannot"), "the report says so in words: " + data.get("toolAttachment")),
            // And the honest report is NARROWER than "cannot be contained" — measured, cursor
            // takes --mode plan, so read-only containment was applied and is named.
            () -> assertTrue(String.valueOf(data.get("toolAttachment")).contains("plan"),
                "the containment that WAS applied is named: " + data.get("toolAttachment")));
    }

    @Test
    @DisplayName("a resident with no journal refuses the hire and says why, rather than vanishing")
    void aResidentWithNoJournalRefuses() throws Exception {
        // The degraded path: the journal rides the experience store's H2 file, so a non-H2 store
        // has none. Registering the tool only where a journal exists was the alternative, and it
        // hides the degradation on the one channel a client cannot inspect.
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), null,
            aRegistryWithACleanGate());

        ToolResponse response = hire.execute(hireArgs(seatFile(CATALOGUER_SEAT)));

        assertAll(
            () -> assertFalse(response.isSuccess()),
            () -> assertTrue(String.valueOf(response.getError()).contains("journal"),
                String.valueOf(response.getError())));
    }

    @Test
    @DisplayName("the refusals: no seat file, no gates, and a step naming no tool")
    void itRefusesTheThingsItCannotRunOn() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events,
            aRegistryWithACleanGate());
        Path seat = seatFile(CATALOGUER_SEAT);

        JsonNode missingFile = hireArgs(seat).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingFile)
            .put("seatFile", dir.resolve("no-such-seat.md").toString());

        JsonNode noGates = hireArgs(seat).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noGates)
            .set("steps", JSON.createArrayNode());

        JsonNode namelessStep = hireArgs(seat).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) namelessStep)
            .set("steps", JSON.createArrayNode().add(JSON.createObjectNode()));

        // THE CONTROL FOR A FIX THAT COST REAL MONEY. workingDirectory used to default to the
        // resident's own directory, so a hire ran a brain wherever the server happened to sit —
        // which is how a probe spawned the real claude in the developer's repository. A hire that
        // does not state its blast radius is refused.
        JsonNode nowhere = hireArgs(seat).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) nowhere).remove("workingDirectory");

        assertAll(
            () -> assertFalse(hire.execute(nowhere).isSuccess(),
                "a hire that names no working directory must not pick one"),
            () -> assertTrue(String.valueOf(hire.execute(nowhere).getError())
                .contains("workingDirectory"), "and it names the input it is missing"),
            () -> assertFalse(hire.execute(missingFile).isSuccess(),
                "a seat file that is not there cannot be the hire"),
            // A hire with no gates would report a run nobody checked — and PlanLedger.open refuses
            // an empty list one layer down, with a message about a plan rather than about gates.
            () -> assertFalse(hire.execute(noGates).isSuccess()),
            () -> assertTrue(String.valueOf(hire.execute(noGates).getError()).contains("gate"),
                "and it says what is missing in the caller's own vocabulary"),
            () -> assertFalse(hire.execute(namelessStep).isSuccess(),
                "a step that names no tool names no gate"));
    }

    @Test
    @DisplayName("a hire does not require a loaded project, so its gates answer for themselves")
    void aHireDoesNotRequireALoadedProject() throws Exception {
        // The service supplier answers null throughout this class, which is a resident with no
        // project. Requiring one would refuse the whole hire before the brain ran and tell a
        // reader nothing about the brief or the gates — one refusal standing in for a run.
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events,
            aRegistryWithACleanGate());

        assertTrue(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))).isSuccess(),
            "a hire ran with no project loaded");
    }
}
