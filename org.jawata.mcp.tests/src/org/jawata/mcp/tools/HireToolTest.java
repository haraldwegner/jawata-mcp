package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostOS;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.learn.LearnerEvent;
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
        // Every case here hires a brain that is a shell script made executable with POSIX bits,
        // the same fixture as the sibling HiredRunProposesTest, and with the same reason in the
        // same words so one budget line in build/expected-aborts.windows covers both classes.
        assumeTrue(!HostOS.current().isWindows(), "the brain fixture is a POSIX shell script");
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

    /** A brain that makes exactly the named calls in one turn, then ends. */
    private HostCommand aBrainCalling(String... names) throws Exception {
        StringBuilder blocks = new StringBuilder("{\"type\":\"text\",\"text\":\"working\"}");
        for (String name : names) {
            blocks.append(",{\"type\":\"tool_use\",\"name\":\"").append(name).append("\"}");
        }
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, "#!/bin/sh\necho '{\"type\":\"assistant\",\"message\":{\"role\":"
            + "\"assistant\",\"content\":[" + blocks + "]}}'\n"
            + "echo '{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.01}'\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    /** The clean-gate registry plus an operation the ENGINE flags as changing code. */
    private static ToolRegistry aRegistryWithAChangingOperation() {
        ToolRegistry registry = aRegistryWithACleanGate();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "extract";
            }

            @Override
            public String getDescription() {
                return "a code-changing fixture";
            }

            @Override
            public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }

            @Override
            public boolean isRevertible() {
                return true;
            }
        });
        return registry;
    }

    @Test
    @DisplayName("M11: a hire classifies the run's calls by the ENGINE's flag, and a zero that hides a shell or an unnamed call says it is incomplete")
    void aRunIsClassifiedByWhatTheEngineSays() throws Exception {
        // The brain calls a revertible engine operation (through the mcp__ prefix a vendor adds),
        // a registered read, a shell, and a name nothing classifies.
        HireTool hire = toolWith(new StandsIn(host,
                aBrainCalling("mcp__jawata-javata-dev__extract", "compile_workspace", "Bash",
                    "some_vendor_tool")),
            events, aRegistryWithAChangingOperation());
        Map<String, Object> dirty = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        // The CONTROL: the same seat, a brain that only reads.
        HireTool reader = toolWith(new StandsIn(host, aBrainCalling("compile_workspace")),
            events, aRegistryWithAChangingOperation());
        Map<String, Object> clean = dataOf(reader.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        assertAll(
            () -> assertEquals(List.of("mcp__jawata-javata-dev__extract"), dirty.get("codeChangingCalls")),
            () -> assertEquals(List.of("Bash"), dirty.get("shellCalls")),
            () -> assertEquals(List.of("some_vendor_tool"), dirty.get("unclassifiedCalls")),
            () -> assertEquals(Boolean.FALSE, dirty.get("performedNoCodeChangingOperation")),
            () -> assertEquals(Boolean.FALSE, dirty.get("noCodeChangeIsComplete")),
            () -> assertEquals(4, ((List<?>) dirty.get("toolsCalled")).size()),
            () -> assertEquals(Boolean.TRUE, clean.get("performedNoCodeChangingOperation")),
            () -> assertEquals(Boolean.TRUE, clean.get("noCodeChangeIsComplete"),
                "only the registered, non-revertible read: nothing hides behind the zero"),
            () -> assertEquals(List.of(), clean.get("codeChangingCalls")));
    }

    /** A tool that WRITES with no undo: not revertible, but it changes code. */
    private static ToolRegistry aRegistryWithAWriterThatHasNoUndo() {
        ToolRegistry registry = aRegistryWithACleanGate();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "format";
            }

            @Override
            public String getDescription() {
                return "a writer with no undo";
            }

            @Override
            public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }

            @Override
            public boolean changesCode() {
                return true;
            }
        });
        return registry;
    }

    @Test
    @DisplayName("M11: a tool that writes with NO undo is code-changing, which the revertible flag cannot say")
    void aWriterWithNoUndoIsStillCodeChanging() throws Exception {
        HireTool hire = toolWith(new StandsIn(host,
                aBrainCalling("mcp__jawata-javata-dev__format")),
            events, aRegistryWithAWriterThatHasNoUndo());
        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        assertAll(
            () -> assertEquals(List.of("mcp__jawata-javata-dev__format"), data.get("codeChangingCalls"),
                "format is not revertible; reading that flag as 'changes code' classed it read-only"),
            () -> assertEquals(Boolean.FALSE, data.get("performedNoCodeChangingOperation")),
            () -> assertEquals(Boolean.FALSE, data.get("noCodeChangeIsComplete")));
    }

    @Test
    @DisplayName("M11: a brain whose dialect does not show every call is never reported as a complete zero")
    void aDialectThatDoesNotShowEveryCallIsNeverComplete() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainCalling("compile_workspace")),
            events, aRegistryWithACleanGate());
        Map<String, Object> cursor = dataOf(hire.execute(hireArgs(seatFile(CURSOR_SEAT))));
        Map<String, Object> claude = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        assertAll(
            () -> assertEquals(List.of(), cursor.get("codeChangingCalls")),
            () -> assertEquals(Boolean.FALSE, cursor.get("noCodeChangeIsComplete"),
                "cursor's stream was never measured on a run that uses tools"),
            () -> assertTrue(String.valueOf(cursor.get("noCodeChangeIncompleteBecause"))
                .contains("does not show every call"), String.valueOf(cursor)),
            () -> assertEquals(Boolean.TRUE, claude.get("noCodeChangeIsComplete"),
                "the control: the same run through the dialect that was measured"),
            () -> assertEquals(List.of(), claude.get("noCodeChangeIncompleteBecause")));
    }

    @Test
    @DisplayName("M11: a run that did not end on its own is not a complete zero, whatever its calls were")
    void aRunThatFailedIsNotAComplete() throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, "#!/bin/sh\necho '{\"type\":\"assistant\",\"message\":{\"role\":"
            + "\"assistant\",\"content\":[{\"type\":\"tool_use\",\"name\":\"compile_workspace\"}]}}'\n"
            + "exit 3\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        HireTool hire = toolWith(new StandsIn(host, HostCommand.of(file.toString())),
            events, aRegistryWithACleanGate());
        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        assertAll(
            () -> assertEquals(Boolean.FALSE, data.get("cleanExit")),
            () -> assertEquals(List.of(), data.get("codeChangingCalls")),
            () -> assertEquals(Boolean.FALSE, data.get("noCodeChangeIsComplete"),
                "the calls seen were all reads, but the run did not end cleanly"),
            () -> assertTrue(String.valueOf(data.get("noCodeChangeIncompleteBecause"))
                .contains("did not end on its own"), String.valueOf(data)));
    }

    @Test
    @DisplayName("M11: a line the reader could not parse may have held a call, so the zero is not complete")
    void anUnreadableLineMakesTheZeroIncomplete() throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, "#!/bin/sh\necho 'this is not json at all'\n"
            + "echo '{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":"
            + "[{\"type\":\"tool_use\",\"name\":\"compile_workspace\"}]}}'\n"
            + "echo '{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.01}'\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        HireTool hire = toolWith(new StandsIn(host, HostCommand.of(file.toString())),
            events, aRegistryWithACleanGate());
        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        assertAll(
            () -> assertEquals(Boolean.TRUE, data.get("cleanExit"), "premise: the run ended on its own"),
            () -> assertEquals(List.of(), data.get("codeChangingCalls")),
            () -> assertEquals(Boolean.FALSE, data.get("noCodeChangeIsComplete")),
            () -> assertTrue(String.valueOf(data.get("noCodeChangeIncompleteBecause"))
                .contains("could not be read"), String.valueOf(data)));
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
    @DisplayName("C16: the result is JOURNALLED — a row is read back, not a record file inspected")
    void theRunLandsInTheJournal() throws Exception {
        // THE CLAUSE THIS CLASS COULD NOT PREVIOUSLY FAIL ON. The evidence the C16 record CITED
        // for "result journalled" was a probe asserting proposal.md exists on disk — which is the
        // ARTIFACT, a different thing — a counter in another script literally named `journalled`
        // that incremented on the tool's own success flag, and a javadoc. None of the three reads
        // a journal row, and `HiredRun` appends inside a try whose catch swallows, so a journal
        // that threw still returned a successful proposal.
        //
        // AN EARLIER VERSION OF THIS COMMENT WENT ON TO CLAIM "nothing in the repository reddened
        // if the append stopped happening", AND THAT WAS FALSE WHEN WRITTEN. A C16 audit found it:
        // `HiredRunProposesTest.oneHiredRunEndToEnd` has asserted the same row delta against a
        // real store since before this class existed — confirmed at revision 7d791453, where it
        // reads `countByKind().getOrDefault(KIND_HIRED_RUN, 0L)` under the message "one row for
        // one run". The repository did redden; the record's evidence could not.
        //
        // So what this case adds is narrower and still worth having: that assertion drives
        // `HiredRun.issue` DIRECTLY, and this one drives the published `hire` tool — the
        // production caller, which nothing covered.
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events,
            aRegistryWithACleanGate());

        // BEFORE, so the assertion is about THIS run and not about a store that happened to hold
        // a row already.
        long hiredRunsBefore = events.countByKind().getOrDefault(LearnerEvent.KIND_HIRED_RUN, 0L);
        long totalBefore = events.totalEvents();

        ToolResponse response = hire.execute(hireArgs(seatFile(CATALOGUER_SEAT)));
        assertTrue(response.isSuccess(), String.valueOf(response.getError()));

        assertAll(
            () -> assertEquals(hiredRunsBefore + 1,
                events.countByKind().getOrDefault(LearnerEvent.KIND_HIRED_RUN, 0L),
                "one hired-run row landed in the journal, counted from the store itself"),
            () -> assertEquals(totalBefore + 1, events.totalEvents()),
            // AND THE WRITE REALLY SUCCEEDED. The store counts its own failures, so a row that
            // threw on the way in would leave the count above unchanged AND this non-zero — two
            // different failures that must not be read as one.
            () -> assertEquals(0, events.failedWrites(),
                "and no journal write failed, which the store counts for itself"));

        // THE BOUND, and it is the store's rather than this test's: LearnerEventStore publishes
        // `append`, `countByKind`, `totalEvents` and `failedWrites` — and NO ROW-LEVEL READ. So
        // the clause "result journalled" is assertable as "a row of this kind landed" and NOT as
        // "the row says which brain, which plan and how far it got", even though `HiredRun` writes
        // all three into the payload. Nobody — not this test and not a human asking what a run
        // recorded — can read that payload back through the published API. Filed as a finding
        // rather than worked around here by reaching into the schema, which would pin a test to
        // SQL the store exists to own.
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
