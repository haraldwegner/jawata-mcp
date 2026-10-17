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
import org.jawata.mcp.execution.SelfEndpoint;
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
    /** A brain whose writes are held only by an instruction: refused unless the seat opts in. */
    private static final String CURSOR_SEAT_WITHOUT_OPT_IN = CATALOGUER_SEAT
        .replace("name: cataloguer", "name: cataloguer\nbrain: cursor");

    private static final String CURSOR_SEAT = CURSOR_SEAT_WITHOUT_OPT_IN
        .replace("brain: cursor", "brain: cursor\naccepts_uncontained_writes: true");

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
        /** The argv the product ASKED to start, which the stand-in replaces. */
        private volatile List<String> asked = List.of();

        StandsIn(HostProcesses real, HostCommand standIn) {
            this.real = real;
            this.standIn = standIn;
        }

        List<String> asked() {
            return asked;
        }

        @Override
        public Process start(HostCommand command) throws IOException {
            this.asked = command.argv();
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
        // WITH the resident's endpoint, as a resident serving HTTP has. A claude hire without one
        // is refused (S16-36); the case that pins that builds its tool without one on purpose.
        return new HireTool(() -> null, processes, artifacts, plans, () -> journal, () -> gates,
            () -> new SelfEndpoint("http://127.0.0.1:1/mcp", "test-token"));
    }

    /** A host that fails the test if anything is spawned through it. */
    private HostProcesses aHostThatMustNotSpawn() {
        return new HostProcesses() {
            @Override
            public Process start(HostCommand command) {
                throw new AssertionError("a refused hire must not spawn a process: " + command);
            }

            @Override
            public HostProcessOutcome run(HostCommand command) throws InterruptedException {
                return host.run(command);
            }

            @Override
            public List<String> executableCandidates(String base) {
                return host.executableCandidates(base);
            }

            @Override
            public List<String> wrapperNames(String base) {
                return host.wrapperNames(base);
            }
        };
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

    /** The seat as filed, plus the one declaration under test: a check over its own answer. */
    private static final String SEAT_WITH_UPWARD_GATE = CATALOGUER_SEAT
        .replace("gates: []", "gates: []\nanswer_gates: [upward_message]");

    private static final String WHOLE_UPWARD_MESSAGE = """
        The brief is above.

        **Upward message**

        - **Fact.** The edit is not done.
        - **Cause.** This seat has no code-changing operation, which enforces the no-writing rule.
        - **Cost.** One hand-off of a three-call brief.
        """;

    /** A brain whose one answer is the given text, delivered the way claude's stream delivers it. */
    private HostCommand aBrainSaying(String text) throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        String event = JSON.writeValueAsString(Map.of("type", "assistant", "message",
            Map.of("role", "assistant", "content", List.of(Map.of("type", "text", "text", text)))));
        Files.writeString(file, "#!/bin/sh\ncat <<'JAWATA_EVENT'\n" + event + "\nJAWATA_EVENT\n"
            + "echo '{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.01}'\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    @Test
    @DisplayName("D13: an upward message missing its cost is refused by the seat's own gate, and the plan stops there")
    @SuppressWarnings("unchecked")
    void anUpwardMessageMissingItsCostIsRefusedByTheSeatsOwnGate() throws Exception {
        String noCost = WHOLE_UPWARD_MESSAGE.replaceAll("(?s)- \\*\\*Cost\\.\\*\\*.*", "");
        HireTool hire = toolWith(new StandsIn(host, aBrainSaying(noCost).withSeparateStderr()),
            events, aRegistryWithACleanGate());
        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(SEAT_WITH_UPWARD_GATE))));

        List<Map<String, Object>> gates = (List<Map<String, Object>>) data.get("gates");
        assertAll(
            () -> assertEquals(Boolean.FALSE, data.get("complete"), String.valueOf(data)),
            () -> assertEquals(2, data.get("stepsDeclared"),
                "the seat's check is a step of the plan, ahead of the hirer's compile gate"),
            () -> assertEquals(List.of("answer_gate:upward_message"),
                gates.stream().map(g -> g.get("tool")).toList(),
                "the plan stopped at the refused message: the compile gate never ran"),
            () -> assertTrue(String.valueOf(data.get("answerGateRefusals")).contains("COST"),
                String.valueOf(data.get("answerGateRefusals"))));
    }

    @Test
    @DisplayName("D13, the control: the same message made whole passes its gate and the hirer's gate runs")
    @SuppressWarnings("unchecked")
    void aWholeUpwardMessagePassesTheSeatsOwnGate() throws Exception {
        HireTool hire = toolWith(
            new StandsIn(host, aBrainSaying(WHOLE_UPWARD_MESSAGE).withSeparateStderr()),
            events, aRegistryWithACleanGate());
        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(SEAT_WITH_UPWARD_GATE))));

        List<Map<String, Object>> gates = (List<Map<String, Object>>) data.get("gates");
        assertAll(
            () -> assertEquals(Boolean.TRUE, data.get("complete"), String.valueOf(data)),
            () -> assertEquals(List.of("answer_gate:upward_message", "compile_workspace"),
                gates.stream().map(g -> g.get("tool")).toList()),
            () -> assertEquals(List.of(), data.get("answerGateRefusals")));
    }

    @Test
    @DisplayName("a seat that declares no answer gate is hired exactly as before: no extra step, no extra field")
    void aSeatWithNoAnswerGateIsUnchanged() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainSaying(WHOLE_UPWARD_MESSAGE)),
            events, aRegistryWithACleanGate());
        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        assertAll(
            () -> assertEquals(1, data.get("stepsDeclared")),
            () -> assertFalse(data.containsKey("answerGateRefusals")));
    }

    /** A brain that makes exactly the named calls in one turn, then ends. */
    private HostCommand aBrainCalling(String... names) throws Exception {
        return aBrainOfferedThenCalling(null, names);
    }

    /**
     * As above, but the CLI first announces what it offers (claude's init event) — {@code offered}
     * is the JSON array text of tool names, or null to announce nothing.
     */
    private HostCommand aBrainOfferedThenCalling(String offered, String... names) throws Exception {
        StringBuilder blocks = new StringBuilder("{\"type\":\"text\",\"text\":\"working\"}");
        for (String name : names) {
            blocks.append(",{\"type\":\"tool_use\",\"name\":\"").append(name).append("\"}");
        }
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, "#!/bin/sh\n"
            + (offered == null ? "" : "echo '{\"type\":\"system\",\"subtype\":\"init\",\"tools\":"
                + offered + "}'\n")
            + "echo '{\"type\":\"assistant\",\"message\":{\"role\":"
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

    @Test
    @DisplayName("a brain whose CLI offered no built-in tools but which NAMED Write and Bash made attempts, not calls, and the zero is complete")
    void anAttemptAtAnUnofferedToolIsReportedAsAnAttemptNotACall() throws Exception {
        // MEASURED 2026-10-13: --tools "" gave init tools [] and the PM still named Write; the CLI
        // refused it, no file was written, and the record read "1 code-changing call(s)".
        HireTool refused = toolWith(new StandsIn(host,
                aBrainOfferedThenCalling("[\"compile_workspace\"]", "compile_workspace", "Write", "Bash")),
            events, aRegistryWithAChangingOperation());
        Map<String, Object> data = dataOf(refused.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        // THE CONTROL: the same three names with NO offer announced keep the old reading.
        HireTool silent = toolWith(new StandsIn(host,
                aBrainCalling("compile_workspace", "Write", "Bash")),
            events, aRegistryWithAChangingOperation());
        Map<String, Object> noOffer = dataOf(silent.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        assertAll(
            () -> assertEquals(List.of("compile_workspace"), data.get("toolsOffered")),
            () -> assertEquals(List.of("Write", "Bash"), data.get("attemptedButNotOffered")),
            () -> assertEquals(List.of(), data.get("codeChangingCalls")),
            () -> assertEquals(List.of(), data.get("shellCalls")),
            () -> assertEquals(Boolean.TRUE, data.get("performedNoCodeChangingOperation")),
            () -> assertEquals(Boolean.TRUE, data.get("noCodeChangeIsComplete"),
                String.valueOf(data.get("noCodeChangeIncompleteBecause"))),
            // The control: nothing announced, so a Write is a code change and a Bash is a shell.
            () -> assertEquals(List.of("Write"), noOffer.get("codeChangingCalls")),
            () -> assertEquals(List.of("Bash"), noOffer.get("shellCalls")),
            () -> assertEquals(List.of(), noOffer.get("attemptedButNotOffered")),
            () -> assertFalse(noOffer.containsKey("toolsOffered")));
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
            + "echo 'the brain fell over here' >&2\n"
            + "exit 3\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        // Stderr kept APART, as a real hire starts it: the stand-in replaces the whole command, so
        // without this the line below is merged into stdout and never reaches the stderr reader.
        HireTool hire = toolWith(
            new StandsIn(host, HostCommand.of(file.toString()).withSeparateStderr()),
            events, aRegistryWithACleanGate());
        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        assertAll(
            () -> assertEquals(Boolean.FALSE, data.get("cleanExit")),
            () -> assertEquals(3, data.get("exitCode"),
                "a cleanExit of false must say WHY: the exit status is in the answer, not only in"
                    + " a record file a cleanup can remove"),
            () -> assertTrue(String.valueOf(data.get("stderrTail")).contains("the brain fell over here"),
                "and the last of what it said on stderr"),
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

        // What the row SAYS is the next case's: this one counts, and a count cannot tell a row
        // carrying the wrong artifact from the right one (S16-27).
    }

    @Test
    @DisplayName("S16-27: the run's journal row is READ BACK and says which brain, which artifact and how far the plan got")
    void theRunsOwnRowSaysWhatTheRunWas() throws Exception {
        // The journal used to publish `append` and three counters and NO ROW-LEVEL READ, so the
        // case above could assert "a row of this kind landed" and never "the row says which brain,
        // which artifact and how far it got" — which `HiredRun` writes and nobody could read back.
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events,
            aRegistryWithACleanGate());

        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
        String plan = (String) data.get("plan");

        // FROM THE STORE, by the identity the run was written under: the reading a human or a
        // later tool has, knowing nothing about how this run went.
        List<LearnerEvent> rows = events.eventsOfSession(plan).orElseThrow(
            () -> new AssertionError("the journal could not be read back at all"));
        assertEquals(1, rows.size(), "one row for one run: " + rows);
        LearnerEvent row = rows.get(0);
        JsonNode detail = JSON.readTree(row.detailJson());
        assertAll(
            () -> assertEquals(LearnerEvent.KIND_HIRED_RUN, row.kind()),
            () -> assertEquals("claude-code", row.tool(),
                "the brain the seat's own file chose, which defaults to Claude Code"),
            () -> assertEquals(data.get("artifactId"), detail.path("artifactId").asText(),
                "the artifact the run wrote, which the answer names too"),
            () -> assertEquals(data.get("stepsPassed"), detail.path("stepsPassed").asInt(),
                "how far the plan got"),
            () -> assertEquals(data.get("stepsDeclared"), detail.path("ofSteps").asInt(),
                "out of how many steps"));

        // AND THE HIRER IS TOLD, in the answer it already reads, what the journal holds — so
        // "journalled" is something the run's answer shows rather than something a caller assumes.
        @SuppressWarnings("unchecked")
        Map<String, Object> shown = (Map<String, Object>) data.get("journalRow");
        assertAll(
            () -> assertEquals("landed", data.get("journal"), String.valueOf(data.get("journalBasis"))),
            () -> assertEquals(row.tool(), shown.get("brain")),
            () -> assertEquals(data.get("artifactId"),
                ((Map<?, ?>) shown.get("detail")).get("artifactId")));
    }

    @Test
    @DisplayName("S16-36: a claude hire with no resident endpoint is refused BEFORE anything is spawned")
    void aClaudeHireWithNoEndpointIsRefusedBeforeItSpawns() throws Exception {
        // The stdio resident has no endpoint to hand the brain, and without one the claude CLI
        // keeps the machine's own MCP servers, settings and hooks: measured $2.26 for a one-word
        // answer, and --tools "" (the editors-absent containment) is only sent WITH an endpoint.
        // An unbounded hire is refused rather than run and reported.
        HireTool hire = new HireTool(() -> null, aHostThatMustNotSpawn(), artifacts, plans,
            () -> events, () -> aRegistryWithACleanGate());

        ToolResponse response = hire.execute(hireArgs(seatFile(CATALOGUER_SEAT)));

        assertAll(
            () -> assertFalse(response.isSuccess()),
            () -> assertEquals("PRECONDITION_NOT_MET", response.getError().getCode()),
            () -> assertTrue(response.getError().getMessage().contains("endpoint"),
                response.getError().getMessage()),
            () -> assertEquals(0L, events.countByKind().getOrDefault(LearnerEvent.KIND_HIRED_RUN, 0L),
                "and nothing was journalled, because nothing ran"));
    }

    @Test
    @DisplayName("architect F1: a brain whose writes are held only by an INSTRUCTION is refused before it spawns, unless the seat opts in")
    void anInstructionOnlyBrainIsRefusedUnlessTheSeatOptsIn() throws Exception {
        HireTool hire = new HireTool(() -> null, aHostThatMustNotSpawn(), artifacts, plans,
            () -> events, () -> aRegistryWithACleanGate());

        ToolResponse response = hire.execute(hireArgs(seatFile(CURSOR_SEAT_WITHOUT_OPT_IN)));

        assertAll(
            () -> assertFalse(response.isSuccess()),
            () -> assertEquals("PRECONDITION_NOT_MET", response.getError().getCode()),
            () -> assertTrue(response.getError().getMessage().contains("INSTRUCTION"),
                response.getError().getMessage()),
            () -> assertTrue(String.valueOf(response.getError().getHint())
                    .contains("accepts_uncontained_writes"),
                "the way out is named: " + response.getError().getHint()),
            () -> assertEquals(0L, events.countByKind().getOrDefault(LearnerEvent.KIND_HIRED_RUN, 0L),
                "and nothing was journalled, because nothing ran"));
    }

    @Test
    @DisplayName("architect F1: with the seat's opt-in the same hire runs, and its answer states the basis it ran on")
    void theOptInRunsAndTheAnswerStatesTheBasis() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events,
            aRegistryWithACleanGate());

        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CURSOR_SEAT))));

        assertAll(
            () -> assertEquals("INSTRUCTION", data.get("writeContainmentBasis")),
            () -> assertTrue(String.valueOf(data.get("writeContainment")).contains("INSTRUCTION"),
                String.valueOf(data.get("writeContainment"))));
    }

    @Test
    @DisplayName("REL44-6: the hire hides the engine's code-changing tools from claude, except the ones a seat that changes code names")
    void aHiredClaudeIsNotOfferedTheEnginesCodeChangingToolsTheSeatDidNotAskFor() throws Exception {
        StandsIn spy = new StandsIn(host, aBrainThatAnswers());
        HireTool hire = toolWith(spy, events, aRegistryWithAChangingOperation());

        hire.execute(hireArgs(seatFile(CATALOGUER_SEAT)));
        List<String> argv = spy.asked();
        int hidden = argv.indexOf("--disallowedTools");

        // THE CONTROL: a seat that says it changes code and names the changing tool itself keeps it.
        StandsIn spy2 = new StandsIn(host, aBrainThatAnswers());
        toolWith(spy2, events, aRegistryWithAChangingOperation()).execute(hireArgs(seatFile(
            CATALOGUER_SEAT.replace("tools: [inspect,", "tools: [extract, inspect,")
                .replace("name: cataloguer", "name: cataloguer\nchanges_code: true"))));

        // ... and one that does NOT say so loses it even though its list names it (architect watch, C10:
        // the seat answers for what it may change, so a tool list cannot grant it).
        StandsIn spy3 = new StandsIn(host, aBrainThatAnswers());
        toolWith(spy3, events, aRegistryWithAChangingOperation()).execute(hireArgs(seatFile(
            CATALOGUER_SEAT.replace("tools: [inspect,", "tools: [extract, inspect,"))));

        assertAll(
            () -> assertTrue(spy3.asked().indexOf("--disallowedTools") >= 0
                    && spy3.asked().lastIndexOf("mcp__jawata__extract") > spy3.asked().indexOf("--disallowedTools"),
                "a seat not declared as changing code loses the tool it names: " + spy3.asked()),
            () -> assertTrue(hidden >= 0, "the changing fixture 'extract' is hidden: " + argv),
            () -> assertEquals("mcp__jawata__extract", argv.get(hidden + 1), argv.toString()),
            () -> assertFalse(argv.contains("mcp__jawata__compile_workspace")
                    && argv.indexOf("mcp__jawata__compile_workspace") > hidden,
                "a read-only tool is not hidden: " + argv),
            () -> assertFalse(spy2.asked().contains("--disallowedTools"),
                "a seat declared as changing code keeps the tool it names: " + spy2.asked()));
    }

    @Test
    @DisplayName("architect F1: a brain contained by a mechanism needs no opt-in")
    void aMechanismContainedBrainNeedsNoOptIn() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events,
            aRegistryWithACleanGate());

        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(
            CATALOGUER_SEAT.replace("name: cataloguer", "name: cataloguer\nbrain: codex")))));

        assertEquals("MECHANISM", data.get("writeContainmentBasis"));
    }

    @Test
    @DisplayName("S16-36: the refusal is claude's alone — a brain that does not attach the resident still hires without an endpoint")
    void aBrainThatDoesNotUseTheEndpointStillHiresWithoutOne() throws Exception {
        HireTool hire = new HireTool(() -> null, new StandsIn(host, aBrainThatAnswers()), artifacts,
            plans, () -> events, () -> aRegistryWithACleanGate());

        ToolResponse response = hire.execute(hireArgs(seatFile(CURSOR_SEAT)));

        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
    }

    @Test
    @DisplayName("S16-10: a gate that did not answer says WHY, in the answer and in the record a human opens")
    void aGateThatDidNotAnswerSaysWhyInTheAnswer() throws Exception {
        // A registry with NO compile_workspace: the plan names a gate and no tool answers it.
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events,
            new ToolRegistry());

        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gates = (List<Map<String, Object>>) data.get("gates");
        Map<String, Object> compile = gates.stream()
            .filter(row -> "compile_workspace".equals(row.get("tool")))
            .findFirst().orElseThrow(() -> new AssertionError("no compile gate row: " + gates));
        String record = Files.readString(Path.of((String) data.get("record")));

        assertAll(
            () -> assertEquals(Boolean.FALSE, compile.get("answered")),
            () -> assertTrue(String.valueOf(compile.get("notAnsweredBecause"))
                .contains("no tool named 'compile_workspace'"), String.valueOf(compile)),
            () -> assertTrue(record.contains("no tool named 'compile_workspace'"),
                "the record says which of the four it was, not only DID NOT ANSWER: " + record));
    }

    @Test
    @DisplayName("S16-27: the journal part of the answer has three states, and 'could not read it' is not 'not there'")
    void theJournalPartOfTheAnswerHasThreeStates() {
        LearnerEvent run = new LearnerEvent("plan-1", LearnerEvent.KIND_HIRED_RUN, "codex",
            "{\"artifactId\":\"proposal-9\",\"stepsPassed\":1,\"ofSteps\":2}");
        LearnerEvent unrelated = new LearnerEvent("plan-1", LearnerEvent.KIND_GATE_CALL,
            "compile_workspace", "{}");

        Map<String, Object> landed = HireTool.journalReport(
            java.util.Optional.of(List.of(unrelated, run)), 0);
        Map<String, Object> missing = HireTool.journalReport(
            java.util.Optional.of(List.of(unrelated)), 3);
        Map<String, Object> unread = HireTool.journalReport(java.util.Optional.empty(), 0);

        assertAll(
            () -> assertEquals("landed", landed.get("journal")),
            () -> assertEquals(Map.of("kind", LearnerEvent.KIND_HIRED_RUN, "brain", "codex",
                    "detail", Map.of("artifactId", "proposal-9", "stepsPassed", 1, "ofSteps", 2)),
                landed.get("journalRow"), "the run's row, not the gate call beside it"),
            () -> assertEquals("not landed", missing.get("journal")),
            () -> assertFalse(missing.containsKey("journalRow")),
            () -> assertTrue(String.valueOf(missing.get("journalBasis")).contains("3 dropped"),
                "and the store's own count of dropped writes is named: "
                    + missing.get("journalBasis")),
            () -> assertEquals("unknown", unread.get("journal"),
                "a journal that could not be read has not said the row is missing"),
            () -> assertFalse(unread.containsKey("journalRow")),
            () -> assertTrue(String.valueOf(unread.get("journalBasis")).contains("could not be read"),
                String.valueOf(unread.get("journalBasis"))));
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
    @DisplayName("C11-6: a seat its owner switched OFF is refused before any brain starts")
    void aSwitchedOffSeatIsRefusedBeforeAnyBrainStarts() throws Exception {
        HostCommand brain = aBrainThatAnswers();
        java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        HostProcesses counting = new HostProcesses() {
            @Override
            public Process start(HostCommand command) throws IOException {
                starts.incrementAndGet();
                return host.start(brain);
            }

            @Override
            public HostProcessOutcome run(HostCommand command) throws InterruptedException {
                return host.run(command);
            }

            @Override
            public List<String> executableCandidates(String base) {
                return host.executableCandidates(base);
            }

            @Override
            public List<String> wrapperNames(String base) {
                return host.wrapperNames(base);
            }
        };
        HireTool hire = toolWith(counting, events, aRegistryWithACleanGate());

        ToolResponse off = hire.execute(hireArgs(seatFile(
            CATALOGUER_SEAT.replace("name: cataloguer\n", "name: cataloguer\nenabled: false\n"))));
        assertAll(
            () -> assertFalse(off.isSuccess(), "an off seat is not hired"),
            () -> assertTrue(String.valueOf(off.getError()).contains("switched off"),
                "and the refusal says why, in the user's own terms: " + off.getError()),
            () -> assertEquals(0, starts.get(), "no brain was started for it — a hire is a paid run"));

        // The control: the same seat switched on is hired, through the same counting host.
        ToolResponse on = hire.execute(hireArgs(seatFile(
            CATALOGUER_SEAT.replace("name: cataloguer\n", "name: cataloguer\nenabled: true\n"))));
        assertAll(
            () -> assertTrue(on.isSuccess(), "an on seat is hired: " + on.getError()),
            () -> assertEquals(1, starts.get(), "and its brain started once"));
    }

    @Test
    @DisplayName("S16-12: an argument the hire does not read is refused before any brain starts")
    void anArgumentTheHireDoesNotReadIsRefusedBeforeAnyBrainStarts() throws Exception {
        // The incident: the first hire tool published a `binary` input and then ignored it, so a
        // probe that pointed it at a shell stand-in ran the REAL claude in the developer's
        // repository. Deleting the input from the schema does not stop a caller that still sends
        // it — an ignored argument is the same inert input. Which executable a brain is comes
        // from the seat file and from nothing a caller passes.
        HostCommand brain = aBrainThatAnswers();
        java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        HostProcesses counting = new HostProcesses() {
            @Override
            public Process start(HostCommand command) throws IOException {
                starts.incrementAndGet();
                return host.start(brain);
            }

            @Override
            public HostProcessOutcome run(HostCommand command) throws InterruptedException {
                return host.run(command);
            }

            @Override
            public List<String> executableCandidates(String base) {
                return host.executableCandidates(base);
            }

            @Override
            public List<String> wrapperNames(String base) {
                return host.wrapperNames(base);
            }
        };
        HireTool hire = toolWith(counting, events, aRegistryWithACleanGate());
        JsonNode withBinary = hireArgs(seatFile(CATALOGUER_SEAT)).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) withBinary).put("binary", "/bin/sh");

        ToolResponse refused = hire.execute(withBinary);

        assertAll(
            () -> assertFalse(refused.isSuccess(),
                "an argument the hire ignores lets a caller believe it chose what runs"),
            () -> assertTrue(String.valueOf(refused.getError()).contains("binary"),
                "and the refusal names it: " + refused.getError()),
            () -> assertEquals(0, starts.get(), "no brain was started — a hire is a paid run"));

        // The control: the same hire without the extra argument runs, through the same host.
        ToolResponse ran = hire.execute(hireArgs(seatFile(CATALOGUER_SEAT)));
        assertAll(
            () -> assertTrue(ran.isSuccess(), "the plain hire runs: " + ran.getError()),
            () -> assertEquals(1, starts.get(), "and its brain started once"));
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

    // ---- S10-8: the PM's measure, read from two hire records ---------------------------------------------

    /** A brain that says the given text and then reports the given tokens in its final event. */
    private HostCommand aBrainSayingWithTokens(String text, int in, int out) throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        String event = JSON.writeValueAsString(Map.of("type", "assistant", "message",
            Map.of("role", "assistant", "content", List.of(Map.of("type", "text", "text", text)))));
        Files.writeString(file, "#!/bin/sh\ncat <<'JAWATA_EVENT'\n" + event + "\nJAWATA_EVENT\n"
            + "echo '{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.01,\"usage\":{\"input_tokens\":"
            + in + ",\"output_tokens\":" + out + "}}'\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    /** A worker brain: makes one call to {@code rename_symbol} with the given arguments, then reports tokens. */
    private HostCommand aWorkerThatCalls(String argumentsJson) throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        String event = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\","
            + "\"name\":\"rename_symbol\",\"input\":" + argumentsJson + "}]}}";
        Files.writeString(file, "#!/bin/sh\ncat <<'JAWATA_EVENT'\n" + event + "\nJAWATA_EVENT\n"
            + "echo '{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.01,\"usage\":{\"input_tokens\":50,\"output_tokens\":10}}'\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    private static final String PM_ANSWER = "Reading Foo.java, the change is one rename.\n\n"
        + "INSTRUCTION 1\nTOOL: rename_symbol\nARGUMENTS: {\"symbol\":\"a.B#c\",\"newName\":\"d\"}\n";

    @SuppressWarnings("unchecked")
    private Map<String, Object> workerHiredAgainst(String issuerPlan, String workerCallArguments) throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aWorkerThatCalls(workerCallArguments).withSeparateStderr()),
            events, aRegistryWithACleanGate());
        com.fasterxml.jackson.databind.node.ObjectNode args =
            (com.fasterxml.jackson.databind.node.ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        args.put("instructionsFrom", issuerPlan);
        return dataOf(hire.execute(args));
    }

    @Test
    @DisplayName("S10-8: the issuer's tokens per instruction that ran as written are read from the two hire records")
    @SuppressWarnings("unchecked")
    void theIssuersTokensPerInstructionAreReadFromTheTwoRecords() throws Exception {
        // The PM: its answer carries one INSTRUCTION block and its brain reports 1000 in, 500 out.
        HireTool pm = toolWith(new StandsIn(host, aBrainSayingWithTokens(PM_ANSWER, 1000, 500).withSeparateStderr()),
            events, aRegistryWithACleanGate());
        Map<String, Object> issued = dataOf(pm.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
        String issuerPlan = (String) issued.get("plan");
        Map<String, Object> worker = workerHiredAgainst(issuerPlan, "{\"symbol\":\"a.B#c\",\"newName\":\"d\"}");

        Map<String, Object> score = (Map<String, Object>) worker.get("instructionScore");
        assertAll(
            () -> assertEquals(Boolean.TRUE, issued.get("tokensReported"), String.valueOf(issued)),
            () -> assertEquals(1, ((List<?>) issued.get("instructionsIssued")).size(), "the PM's answer is parsed into its record"),
            () -> assertEquals(Boolean.TRUE, score.get("computable"), String.valueOf(score)),
            () -> assertEquals(1, score.get("ranAsWritten")),
            () -> assertEquals(1500L, ((Number) score.get("issuerTokensSpent")).longValue(),
                "the issuer's tokens come from ITS final event, read back from the journal row"),
            () -> assertEquals(1500.0, ((Number) score.get("issuerTokensPerInstruction")).doubleValue(), 1e-9),
            () -> assertEquals(Boolean.TRUE, worker.get("tokensReported"), "the worker's own tokens are on its record too"));
    }

    @Test
    @DisplayName("S10-8, the control: a worker that passed other arguments ran nothing as written, and the figure says it is not computable rather than zero")
    @SuppressWarnings("unchecked")
    void aWorkerThatPassedOtherArgumentsRanNothingAsWritten() throws Exception {
        HireTool pm = toolWith(new StandsIn(host, aBrainSayingWithTokens(PM_ANSWER, 1000, 500).withSeparateStderr()),
            events, aRegistryWithACleanGate());
        String issuerPlan = (String) dataOf(pm.execute(hireArgs(seatFile(CATALOGUER_SEAT)))).get("plan");
        Map<String, Object> worker = workerHiredAgainst(issuerPlan, "{\"symbol\":\"a.B#c\",\"newName\":\"SOMETHING ELSE\"}");

        Map<String, Object> score = (Map<String, Object>) worker.get("instructionScore");
        assertAll(
            () -> assertEquals(0, score.get("ranAsWritten"), String.valueOf(score)),
            () -> assertTrue(String.valueOf(score.get("notAsWritten")).contains("not with the arguments written")),
            () -> assertTrue(String.valueOf(score.get("issuerTokensPerInstruction")).startsWith("NOT COMPUTABLE"),
                "no instruction ran as written, so there is no per-instruction figure and no zero stands in for it"));
    }

    /**
     * A worker brain whose CLI offered only the jawata rename (the init event says so): it makes the right
     * call, then ATTEMPTS the CLI's own Write, which was never offered and so cannot have run.
     */
    private HostCommand aWorkerThatAlsoAttemptsAWriteItWasNeverOffered(String argumentsJson) throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        String init = "{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"mcp__jawata__rename_symbol\"]}";
        String calls = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
            + "{\"type\":\"tool_use\",\"name\":\"mcp__jawata__rename_symbol\",\"input\":" + argumentsJson + "},"
            + "{\"type\":\"tool_use\",\"name\":\"Write\",\"input\":{\"file_path\":\"/x\",\"content\":\"y\"}}]}}";
        Files.writeString(file, "#!/bin/sh\ncat <<'JAWATA_EVENT'\n" + init + "\n" + calls + "\nJAWATA_EVENT\n"
            + "echo '{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.01,\"usage\":{\"input_tokens\":50,\"output_tokens\":10}}'\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    @Test
    @DisplayName("S10-8: a call to a tool the CLI never offered is a refused attempt, so it is neither rework nor an extra code change")
    @SuppressWarnings("unchecked")
    void aRefusedAttemptIsNotAnExtraCodeChange() throws Exception {
        HireTool pm = toolWith(new StandsIn(host, aBrainSayingWithTokens(PM_ANSWER, 1000, 500).withSeparateStderr()),
            events, aRegistryWithACleanGate());
        String issuerPlan = (String) dataOf(pm.execute(hireArgs(seatFile(CATALOGUER_SEAT)))).get("plan");
        HireTool hire = toolWith(new StandsIn(host,
            aWorkerThatAlsoAttemptsAWriteItWasNeverOffered("{\"symbol\":\"a.B#c\",\"newName\":\"d\"}").withSeparateStderr()),
            events, aRegistryWithACleanGate());
        com.fasterxml.jackson.databind.node.ObjectNode args =
            (com.fasterxml.jackson.databind.node.ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        args.put("instructionsFrom", issuerPlan);
        Map<String, Object> worker = dataOf(hire.execute(args));

        Map<String, Object> score = (Map<String, Object>) worker.get("instructionScore");
        assertAll(
            () -> assertEquals(List.of("Write"), worker.get("attemptedButNotOffered"), String.valueOf(worker)),
            () -> assertEquals(1, score.get("ranAsWritten"), String.valueOf(score)),
            () -> assertEquals(List.of(), score.get("extraCodeChangingCalls"),
                "the share-through-tools measure sets the refused Write aside; the instruction score must agree with it: " + score));
    }

    @Test
    @DisplayName("S10-8: a score against a plan the journal does not hold says so instead of inventing one")
    @SuppressWarnings("unchecked")
    void aScoreAgainstAnUnknownPlanIsNotComputable() throws Exception {
        Map<String, Object> worker = workerHiredAgainst("no-such-plan", "{\"symbol\":\"a.B#c\",\"newName\":\"d\"}");
        Map<String, Object> score = (Map<String, Object>) worker.get("instructionScore");
        assertAll(
            () -> assertEquals(Boolean.FALSE, score.get("computable"), String.valueOf(score)),
            () -> assertTrue(String.valueOf(score.get("because")).contains("holds no hired-run row")));
    }

}
