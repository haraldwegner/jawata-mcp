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
import org.jawata.mcp.execution.Hirer;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

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
        return new HireTool(processes, artifacts, plans, () -> journal, () -> gates,
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
        return hireArgs(seat, "cataloguer");
    }

    private JsonNode hireArgs(Path seat, String seatName) {
        return JSON.createObjectNode()
            .put("hirer", "chief-engineer")
            .put("seat", seatName)
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
    @DisplayName("S12 (D9): a seat hired by someone other than its parent in the signed tree is refused before any brain starts")
    void aSeatIsHiredByItsParentOnly() throws Exception {
        StandsIn processes = new StandsIn(host, aBrainThatAnswers());
        HireTool hire = toolWith(processes, events, aRegistryWithACleanGate());
        ObjectNode args = (ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        args.put("hirer", "pm");

        ToolResponse response = hire.execute(args);

        assertAll(
            () -> assertFalse(response.isSuccess(), "the pm is not the cataloguer's parent"),
            () -> assertEquals("PRECONDITION_NOT_MET", response.getError().getCode()),
            () -> assertTrue(response.getError().getMessage().contains("chief-engineer"),
                "the refusal names the seat that does hire it: " + response.getError().getMessage()),
            () -> assertTrue(processes.asked().isEmpty(), "nothing was started: " + processes.asked()));
    }

    @Test
    @DisplayName("S18a (D51): a seat file that declares another parent moves the seat in the tree: the new parent hires it and the signed parent no longer does")
    void aSeatFileThatDeclaresAnotherParentMovesTheSeat() throws Exception {
        String moved = CATALOGUER_SEAT.replaceFirst("---\n", "---\nparent: pm\nrole: reviewer\n");
        java.nio.file.Path file = seatFile(moved);

        StandsIn byTheOldParent = new StandsIn(host, aBrainThatAnswers());
        ObjectNode old = (ObjectNode) hireArgs(file);
        old.put("hirer", "chief-engineer");
        ToolResponse refused = toolWith(byTheOldParent, events, aRegistryWithACleanGate()).execute(old);

        ObjectNode fresh = (ObjectNode) hireArgs(file);
        fresh.put("hirer", "pm");
        ToolResponse allowed = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithACleanGate()).execute(fresh);

        assertAll(
            () -> assertFalse(refused.isSuccess(), "the signed parent no longer hires the cataloguer"),
            () -> assertTrue(refused.getError().getMessage().contains("its hirer is pm"), refused.getError().getMessage()),
            () -> assertTrue(byTheOldParent.asked().isEmpty(), "nothing was started: " + byTheOldParent.asked()),
            () -> assertTrue(allowed.isSuccess(), String.valueOf(allowed.getError())));
    }

    @Test
    @DisplayName("S18a (D51): a seat tree that breaks a rule is refused as a whole: the hire goes by the signed tree and its answer names the broken rule")
    void aRefusedTreeIsNamedInTheHireAnswer() throws Exception {
        String lead = CATALOGUER_SEAT.replaceFirst("---\n", "---\nparent: pm\nrole: lead\ntools: [rename_symbol]\nchanges_code: true\n");
        ObjectNode args = (ObjectNode) hireArgs(seatFile(lead));
        args.put("hirer", "chief-engineer");

        ToolResponse response = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithACleanGate()).execute(args);

        assertTrue(response.isSuccess(), "the signed tree is in force, and by it the chief engineer hires the cataloguer: " + response.getError());
        assertTrue(String.valueOf(dataOf(response).get("seatTreeRefused")).contains("changes_code"), String.valueOf(dataOf(response)));
    }

    @Test
    @DisplayName("S18a (D51): a lead that lists a tool the engine publishes as changing code is refused on the hire path itself, and the answer names it")
    void aLeadListingTheEnginesCodeChangingToolIsRefused() throws Exception {
        ToolRegistry registry = aRegistryWithACleanGate();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "rename_symbol";
            }

            @Override
            public String getDescription() {
                return "a code-changing tool fixture";
            }

            @Override
            public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public boolean changesCode() {
                return true;
            }

            @Override
            public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }
        });
        // The fixture's own tools line is replaced: with two such lines the file's last one would win and this would test nothing.
        String lead = CATALOGUER_SEAT.replaceFirst("(?m)^tools:.*$", "tools: [rename_symbol]").replaceFirst("---\n", "---\nparent: pm\nrole: lead\n");
        assertTrue(lead.contains("tools: [rename_symbol]"), "premise: the fixture has a tools line to replace");
        ObjectNode args = (ObjectNode) hireArgs(seatFile(lead));
        args.put("hirer", "chief-engineer");

        ToolResponse response = toolWith(new StandsIn(host, aBrainThatAnswers()), events, registry).execute(args);

        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
        String refused = String.valueOf(dataOf(response).get("seatTreeRefused"));
        assertTrue(refused.contains("'rename_symbol', a tool that changes code"), refused);
    }

    @Test
    @DisplayName("S12 (D9), the control: a name the signed tree does not know is neither refused nor vouched for by it")
    void aHirerOutsideTheTreeIsNotRefusedByIt() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithACleanGate());
        ObjectNode args = (ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        args.put("hirer", "a-person-at-the-keyboard");

        ToolResponse response = hire.execute(args);

        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
    }

    /** The failing twin of the clean gate: it answers, and finds one error, so a hire that declares it cannot be complete. */
    private static ToolRegistry aRegistryWithAFailingGate() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "compile_workspace";
            }

            @Override
            public String getDescription() {
                return "a gate fixture that finds one error";
            }

            @Override
            public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of("errorCount", 1));
            }
        });
        return registry;
    }

    /** A folder holding one seat file per name, each named for its seat, as the studio keeps them side by side. */
    private Path seatsBeside(String... names) throws Exception {
        Path folder = Files.createTempDirectory(dir, "seats-");
        for (String name : names) {
            Files.writeString(folder.resolve(name + ".md"), CATALOGUER_SEAT);
        }
        return folder;
    }

    private ObjectNode hiringAs(String seatName, Path seatFile) {
        ObjectNode args = (ObjectNode) hireArgs(seatFile);
        args.put("seat", seatName);
        return args;
    }

    @Test
    @DisplayName("S12 (D11): a developer hire that fails its gate is answered by a senior-developer hire, from the seat file beside the developer's")
    void aFailedDeveloperHireIsAnsweredByTheSeniorDeveloper() throws Exception {
        Path seats = seatsBeside("developer", "senior-developer");
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithAFailingGate());

        Map<String, Object> first = dataOf(hire.execute(hiringAs("developer", seats.resolve("developer.md"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> escalation = (Map<String, Object>) first.get("escalation");
        assertAll(
            () -> assertEquals(Boolean.FALSE, first.get("complete"), "the first hire failed its gate"),
            () -> assertEquals(first.get("plan"), escalation.get("escalatedFrom")),
            () -> assertEquals(Boolean.TRUE, escalation.get("hired"), String.valueOf(escalation)),
            () -> assertEquals("senior-developer", ((Map<?, ?>) escalation.get("hire")).get("hired")),
            () -> assertTrue(!first.get("plan").equals(((Map<?, ?>) escalation.get("hire")).get("plan")),
                "the second hire is a run of its own, with its own plan"),
            () -> assertTrue(!((Map<?, ?>) escalation.get("hire")).containsKey("escalation"),
                "the senior developer is the top rung: nothing escalates past it"));
    }

    @Test
    @DisplayName("S12 (D11), the control: a developer hire that passes its gate is not escalated")
    void aDeveloperHirePassingItsGateIsNotEscalated() throws Exception {
        Path seats = seatsBeside("developer", "senior-developer");
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithACleanGate());

        Map<String, Object> first = dataOf(hire.execute(hiringAs("developer", seats.resolve("developer.md"))));

        assertAll(
            () -> assertEquals(Boolean.TRUE, first.get("complete")),
            () -> assertTrue(!first.containsKey("escalation"), "nothing failed, so nothing went up"));
    }

    @Test
    @DisplayName("S12 (D11), the control: only a developer escalates; a failed cataloguer hire stays what it is")
    void onlyADeveloperEscalates() throws Exception {
        Path seats = seatsBeside("cataloguer", "senior-developer");
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithAFailingGate());

        Map<String, Object> first = dataOf(hire.execute(hiringAs("cataloguer", seats.resolve("cataloguer.md"))));

        assertAll(
            () -> assertEquals(Boolean.FALSE, first.get("complete")),
            () -> assertTrue(!first.containsKey("escalation")));
    }

    @Test
    @DisplayName("S12 (D11): with no senior-developer seat file beside the developer's, the answer says nothing was hired and why")
    void aMissingSeniorSeatFileIsSaidNotGuessed() throws Exception {
        Path seats = seatsBeside("developer");
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithAFailingGate());

        Map<String, Object> first = dataOf(hire.execute(hiringAs("developer", seats.resolve("developer.md"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> escalation = (Map<String, Object>) first.get("escalation");
        assertAll(
            () -> assertEquals(Boolean.FALSE, escalation.get("hired")),
            () -> assertTrue(String.valueOf(escalation.get("because")).contains("senior-developer"), String.valueOf(escalation)));
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
            () -> assertEquals("chief-engineer", data.get("hirer")),
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
    @DisplayName("S10 (D18): a hire given the plan file draws the board itself once its gates have run, so the file says what the ledger says without anyone calling plan_board; a hire given none touches no file")
    void aHireDrawsTheBoardIntoThePlanFileItWasGiven() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithACleanGate());
        Path plan = dir.resolve("s-plan.md");
        String frame = "# Plan\n\n<!-- board:begin -->\nhand written\n<!-- board:end -->\n\nAfter.\n";
        Files.writeString(plan, frame);

        dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
        assertEquals(frame, Files.readString(plan), "a hire given no plan file wrote one");

        com.fasterxml.jackson.databind.node.ObjectNode args =
            ((com.fasterxml.jackson.databind.node.ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT))).put("boardFile", plan.toString());
        Map<String, Object> data = dataOf(hire.execute(args));

        String text = Files.readString(plan);
        assertTrue(text.contains("| " + data.get("plan") + " | closed | 1 of 1 | 0 compile_workspace: passed |"), text);
        assertTrue(text.startsWith("# Plan\n\n<!-- board:begin -->\n") && text.endsWith("<!-- board:end -->\n\nAfter.\n"), text);
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("hand written"), "the old text between the markers stayed");
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
                "out of how many steps"),
            // D48: a review's row says which vendor's agent (the brain column above) and which model gave it.
            () -> assertEquals("claude-sonnet-5", detail.path("model").asText(), "the model the seat's own file asked for"),
            // 28h item (c): what a cost view or a choice of model reads from the row, besides the model and the tokens above.
            () -> assertEquals(data.get("hired"), detail.path("seat").asText(), "which seat ran, so a cost can be read per seat"),
            () -> assertEquals(data.get("hirer"), detail.path("hirer").asText(), "who hired it, so the hires of one issuer can be summed"),
            // Stage 24 (D55): the first name the hire was given is in the answer and in the row, the same one.
            () -> org.junit.jupiter.api.Assertions.assertTrue(data.get("name") instanceof String name && !name.isBlank(), "the answer carries no first name: " + data),
            () -> assertEquals(data.get("name"), detail.path("name").asText(), "the row names the hire differently from the answer"),
            () -> org.junit.jupiter.api.Assertions.assertTrue(detail.path("elapsedMs").isNumber() && detail.path("elapsedMs").asLong() >= 0,
                "how long the brain ran: " + detail),
            // The stand-in brain always reports a cost, so the answer shows one and the row carries the same figure;
            // the case of a brain that sends none (absent, never 0) is HiredRunProposesTest's.
            () -> org.junit.jupiter.api.Assertions.assertTrue(data.containsKey("costUsd"), "the answer shows the cost the brain sent: " + data),
            () -> org.junit.jupiter.api.Assertions.assertTrue(detail.hasNonNull("costUsd"), "the row carries it: " + detail),
            () -> assertEquals(((Number) data.get("costUsd")).doubleValue(), detail.path("costUsd").asDouble(), 1e-9, "the cost the answer shows"));

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
    @DisplayName("28h item (b): a hire made with fixes names the hire it corrects in its answer and its journal row, and a hire without it names none")
    void aFixHireNamesTheHireItCorrects() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithACleanGate());
        com.fasterxml.jackson.databind.node.ObjectNode withFix = (com.fasterxml.jackson.databind.node.ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        withFix.put("fixes", "plan-earlier");

        Map<String, Object> fixData = dataOf(hire.execute(withFix));
        Map<String, Object> plainData = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
        JsonNode fixRow = JSON.readTree(events.eventsOfSession((String) fixData.get("plan")).orElseThrow().get(0).detailJson());
        JsonNode plainRow = JSON.readTree(events.eventsOfSession((String) plainData.get("plan")).orElseThrow().get(0).detailJson());

        assertAll(
            () -> assertEquals("plan-earlier", fixData.get("fixes"), "the answer names the hire it corrects"),
            () -> assertEquals("plan-earlier", fixRow.path("fixes").asText(), "and so does the journal row: " + fixRow),
            () -> org.junit.jupiter.api.Assertions.assertFalse(plainData.containsKey("fixes"), "a hire that corrects nothing names nothing"),
            () -> org.junit.jupiter.api.Assertions.assertFalse(plainRow.hasNonNull("fixes"), "and its row carries none: " + plainRow));
    }

    @Test
    @DisplayName("28h item (c): a hire that carries out another hire's instructions names that hire as its parent in its journal row, and a hire that carries out none names none")
    void aHireIssuedFromAnotherNamesItsParentInItsRow() throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithACleanGate());
        com.fasterxml.jackson.databind.node.ObjectNode issued = (com.fasterxml.jackson.databind.node.ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        issued.put("instructionsFrom", "plan-issuer");

        Map<String, Object> issuedData = dataOf(hire.execute(issued));
        Map<String, Object> plainData = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
        JsonNode issuedRow = JSON.readTree(events.eventsOfSession((String) issuedData.get("plan")).orElseThrow().get(0).detailJson());
        JsonNode plainRow = JSON.readTree(events.eventsOfSession((String) plainData.get("plan")).orElseThrow().get(0).detailJson());

        assertAll(
            () -> assertEquals("plan-issuer", issuedRow.path("parent").asText(), "the row names the hire whose instructions it carried out: " + issuedRow),
            () -> assertFalse(plainRow.hasNonNull("parent"), "a hire that carries out no instructions names no parent: " + plainRow));
    }

    @Test
    @DisplayName("S16-36: a claude hire with no resident endpoint is refused BEFORE anything is spawned")
    void aClaudeHireWithNoEndpointIsRefusedBeforeItSpawns() throws Exception {
        // The stdio resident has no endpoint to hand the brain, and without one the claude CLI
        // keeps the machine's own MCP servers, settings and hooks: measured $2.26 for a one-word
        // answer, and --tools "" (the editors-absent containment) is only sent WITH an endpoint.
        // An unbounded hire is refused rather than run and reported.
        HireTool hire = new HireTool(aHostThatMustNotSpawn(), artifacts, plans,
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
    @DisplayName("S17: what the hire tool answers is kept for the agent face, a refusal included, because a brain may echo back nothing but success")
    void theAnswerOfEveryHireIsKeptForTheAgentFace() throws Exception {
        HireTool hire = new HireTool(aHostThatMustNotSpawn(), artifacts, plans,
            () -> events, () -> aRegistryWithACleanGate());
        JsonNode args = hireArgs(seatFile(CATALOGUER_SEAT));

        ToolResponse response = hire.execute(args);

        JsonNode kept = org.jawata.mcp.execution.HireRegistry.shared().answerOf(args.path("seat").asText(), args.path("brief").asText()).orElse(null);
        assertFalse(response.isSuccess());
        assertTrue(kept != null, "the refused hire's answer was not kept");
        assertFalse(kept.path("cleanExit").asBoolean(true), kept.toString());
        assertEquals(response.getError().getMessage(), kept.path("error").asText(), kept.toString());
    }

    @Test
    @DisplayName("architect F1: a brain whose writes are held only by an INSTRUCTION is refused before it spawns, unless the seat opts in")
    void anInstructionOnlyBrainIsRefusedUnlessTheSeatOptsIn() throws Exception {
        HireTool hire = new HireTool(aHostThatMustNotSpawn(), artifacts, plans,
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
    @DisplayName("R7: a hire whose brain wrote a file in a git working directory answers with the change as text, beside the file names")
    void theAnswerCarriesTheChangeAsText() throws Exception {
        host.run(HostCommand.of(List.of("git", "init", "-q")).in(dir));
        Path brain = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(brain, "#!/bin/sh\necho written > '" + dir.resolve("proposed.txt") + "'\n"
            + "echo '{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.01}'\n");
        Files.setPosixFilePermissions(brain, PosixFilePermissions.fromString("rwxr-xr-x"));
        HireTool hire = toolWith(new StandsIn(host, HostCommand.of(brain.toString())), events, aRegistryWithACleanGate());

        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CURSOR_SEAT))));

        assertAll(
            () -> assertTrue(String.valueOf(data.get("changedFiles")).contains("proposed.txt"), String.valueOf(data.get("changedFiles"))),
            () -> assertTrue(String.valueOf(data.get("changes")).contains("path=proposed.txt")
                && String.valueOf(data.get("changes")).contains("newText=written"), String.valueOf(data.get("changes"))),
            // a file the census had no entry for was clean when the run began: the answer says so, because a rejected proposal puts back only
            // such a file (a link of the take-back the agent's own tests cannot see, since they hand it the answer as written text)
            () -> assertTrue(String.valueOf(data.get("changes")).contains("cleanBefore=true"), String.valueOf(data.get("changes"))));
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
        HireTool hire = new HireTool(new StandsIn(host, aBrainThatAnswers()), artifacts,
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

    @Test
    @DisplayName("S14 (D8): a lead's block that is not a whole instruction is named in the hire answer; a whole one leaves no such row")
    void aLeadsBrokenInstructionBlockIsNamedInTheHireAnswer() throws Exception {
        String broken = PM_ANSWER + "\nINSTRUCTION 2\nARGUMENTS: {\"symbol\":\"a.B#c\"}\n";

        Map<String, Object> whole = dataOf(toolWith(new StandsIn(host, aBrainSayingWithTokens(PM_ANSWER, 10, 5).withSeparateStderr()),
            events, aRegistryWithACleanGate()).execute(hireArgs(seatFile(CATALOGUER_SEAT))));
        Map<String, Object> faulty = dataOf(toolWith(new StandsIn(host, aBrainSayingWithTokens(broken, 10, 5).withSeparateStderr()),
            events, aRegistryWithACleanGate()).execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        // The journal row the hire wrote is where a later hire is scored from, so it names the fault too (HiredRun).
        String faultyRow = hiredRunRow((String) faulty.get("plan"));
        String wholeRow = hiredRunRow((String) whole.get("plan"));

        assertAll(
            () -> assertEquals(null, whole.get("instructionFaults"), "THE CONTROL: a whole block leaves no fault row: " + whole.get("instructionFaults")),
            () -> assertEquals(1, ((List<?>) faulty.get("instructionsIssued")).size(), "the whole block is still issued"),
            () -> assertTrue(String.valueOf(faulty.get("instructionFaults")).contains("instruction 2"),
                "the block with no TOOL: line is named, not lost: " + faulty.get("instructionFaults")),
            () -> assertTrue(faultyRow.contains("\"instructionFaults\":[\"instruction 2"),
                "the journal row names the fault: " + faultyRow),
            () -> assertTrue(wholeRow.contains("\"instructionFaults\":[]"),
                "THE CONTROL: a whole block leaves the journal's fault list empty, present and empty: " + wholeRow));
    }

    /** The detail of the newest hired-run row the journal holds for a plan. */
    private String hiredRunRow(String plan) {
        return events.eventsOfSession(plan).orElseThrow().stream()
            .filter(e -> org.jawata.mcp.learn.LearnerEvent.KIND_HIRED_RUN.equals(e.kind()))
            .reduce((earlier, later) -> later)
            .orElseThrow(() -> new AssertionError("the journal holds no hired-run row for " + plan))
            .detailJson();
    }

    private Map<String, Object> hiredAs(String seatName, String answer) throws Exception {
        HireTool hire = toolWith(new StandsIn(host, aBrainSayingWithTokens(answer, 10, 5).withSeparateStderr()), events, aRegistryWithACleanGate());
        com.fasterxml.jackson.databind.node.ObjectNode args = (com.fasterxml.jackson.databind.node.ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        args.put("seat", seatName);
        // The signed tree: the spec-auditor is hired by main, the cataloguer by the chief engineer.
        args.put("hirer", "spec-auditor".equals(seatName) ? "main" : "chief-engineer");
        return dataOf(hire.execute(args));
    }

    @Test
    @DisplayName("S11, D50b: a review routed to the spec-auditor seat comes back with its verdict read out: SIGN-OFF, REFUSE, or NO VERDICT when the answer holds neither")
    void aReviewRoutedToTheSpecAuditorComesBackWithItsVerdict() throws Exception {
        assertEquals("REFUSE", hiredAs("spec-auditor", "The spec drops a requirement of the raw.\nVERDICT: REFUSE").get("verdict"));
        assertEquals("SIGN-OFF", hiredAs("spec-auditor", "Every raw item is disposed.\nVERDICT: SIGN-OFF").get("verdict"));
        assertEquals("NO VERDICT", hiredAs("spec-auditor", "I read it and it looks fine.").get("verdict"),
            "an answer with no verdict line was taken for a sign-off");
    }

    @Test
    @DisplayName("S11, the control: a seat that is not the spec-auditor gets no verdict field, whatever its answer says")
    void anotherSeatGetsNoVerdictField() throws Exception {
        org.junit.jupiter.api.Assertions.assertFalse(hiredAs("cataloguer", "Described.\nVERDICT: SIGN-OFF").containsKey("verdict"));
    }

    /**
     * A brain with two lives, told apart by a file in the scratch folder. The first time it is started it is a worker that makes one
     * jawata rename with the given arguments; every later start is the observer seat answering with a halt that names the instruction.
     */
    private HostCommand aWorkerThenAnObserverWhoHalts(String argumentsJson) throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Path phase = dir.resolve("phase-" + file.getFileName());
        String init = "{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"mcp__jawata__rename_symbol\"]}";
        String call = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
            + "{\"type\":\"tool_use\",\"name\":\"mcp__jawata__rename_symbol\",\"input\":" + argumentsJson + "}]}}";
        String usage = "\"total_cost_usd\":0.01,\"usage\":{\"input_tokens\":50,\"output_tokens\":10}";
        Files.writeString(file, "#!/bin/sh\nif [ ! -f '" + phase + "' ]; then\n  touch '" + phase + "'\n"
            + "  cat <<'JAWATA_EVENT'\n" + init + "\n" + call + "\nJAWATA_EVENT\n"
            + "  echo '{\"type\":\"result\",\"result\":\"done\"," + usage + "}'\nelse\n"
            + "  echo '{\"type\":\"result\",\"result\":\"VERDICT: HALT 1: you renamed to the wrong name at call #1\"," + usage + "}'\nfi\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> observedWorker(String argumentsJson) throws Exception {
        HireTool pm = toolWith(new StandsIn(host, aBrainSayingWithTokens(PM_ANSWER, 1000, 500).withSeparateStderr()),
            events, aRegistryWithACleanGate());
        String issuerPlan = (String) dataOf(pm.execute(hireArgs(seatFile(CATALOGUER_SEAT)))).get("plan");
        HireTool hire = toolWith(new StandsIn(host, aWorkerThenAnObserverWhoHalts(argumentsJson).withSeparateStderr()),
            events, aRegistryWithACleanGate());
        com.fasterxml.jackson.databind.node.ObjectNode args =
            (com.fasterxml.jackson.databind.node.ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        args.put("instructionsFrom", issuerPlan);
        args.put("observerSeatFile", seatFile(CATALOGUER_SEAT).toString());
        Map<String, Object> score = (Map<String, Object>) dataOf(hire.execute(args)).get("instructionScore");
        return (Map<String, Object>) score.get("observer");
    }

    @Test
    @DisplayName("S11, D13: a worker that deviated from the issuer's instruction is shown to the observer seat, and the correction it halts with reaches the issuer carrying the instruction's number")
    @SuppressWarnings("unchecked")
    void aDeviatingWorkersCorrectionReachesTheIssuerWithTheInstructionNumber() throws Exception {
        Map<String, Object> observed = observedWorker("{\"symbol\":\"a.B#c\",\"newName\":\"SOMETHING ELSE\"}");

        List<Map<String, Object>> corrections = (List<Map<String, Object>>) observed.get("corrections");
        assertEquals(1, corrections.size(), String.valueOf(observed));
        assertEquals(1, corrections.get(0).get("instruction"), "the correction does not carry the instruction's number");
        assertTrue(String.valueOf(corrections.get(0).get("text")).contains("wrong name"), String.valueOf(corrections));
        assertTrue(((List<?>) observed.get("unjudged")).isEmpty(), "the flag was reported as unjudged although the seat answered");
    }

    @Test
    @DisplayName("S11, the control: a worker that ran the instruction as written raises no flag, so the observer seat is never hired and no correction is given")
    void aWorkerThatRanAsWrittenGetsNoCorrection() throws Exception {
        Map<String, Object> observed = observedWorker("{\"symbol\":\"a.B#c\",\"newName\":\"d\"}");

        assertTrue(((List<?>) observed.get("flagged")).isEmpty(), String.valueOf(observed));
        assertTrue(((List<?>) observed.get("corrections")).isEmpty(), String.valueOf(observed));
    }

    @Test
    @DisplayName("S10 (F7): the plan of the observer-judge hire belongs to the caller that issued the worker, so no other caller's turn waits on it")
    void aJudgesHirePlanBelongsToTheCallerThatIssuedTheWorker() throws Exception {
        // The judge's gate is health_check; read the ledger from inside it, while the judge's plan is open.
        java.util.List<Integer> issuerSees = new java.util.ArrayList<>();
        java.util.List<Integer> otherSees = new java.util.ArrayList<>();
        ToolRegistry registry = aRegistryWithACleanGate();
        registry.register(new Tool() {
            @Override public String getName() {
                return "health_check";
            }
            @Override public String getDescription() {
                return "a gate fixture that notes who the ledger says its plan is waiting for";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                issuerSees.add(plans.pendingFor("principal-f7-judge-issuer").size());
                otherSees.add(plans.pendingFor("principal-f7-someone-else").size());
                return ToolResponse.success(Map.of("errorCount", 0));
            }
        });
        HireTool pm = toolWith(new StandsIn(host, aBrainSayingWithTokens(PM_ANSWER, 1000, 500).withSeparateStderr()), events, aRegistryWithACleanGate());
        String issuerPlan = (String) dataOf(pm.execute(hireArgs(seatFile(CATALOGUER_SEAT)))).get("plan");
        HireTool hire = toolWith(new StandsIn(host, aWorkerThenAnObserverWhoHalts("{\"symbol\":\"a.B#c\",\"newName\":\"SOMETHING ELSE\"}").withSeparateStderr()),
            events, registry);
        com.fasterxml.jackson.databind.node.ObjectNode args = (com.fasterxml.jackson.databind.node.ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        args.put("instructionsFrom", issuerPlan);
        args.put("observerSeatFile", seatFile(CATALOGUER_SEAT).toString());

        org.jawata.mcp.models.CallerIdentity.supplied("principal-f7-judge-issuer").speaking(() -> hire.execute(args));

        assertAll(
            () -> assertTrue(!issuerSees.isEmpty(), "the judge's gate never ran, so nothing was observed"),
            () -> assertEquals(1, issuerSees.get(issuerSees.size() - 1), "the issuer is not told the judge's step is pending"),
            () -> assertEquals(0, otherSees.get(otherSees.size() - 1),
                "another caller was told about the judge's plan, which nobody owned, and would hold its turn on it"));
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

    /** The stand-in ACP agent the engine's own client tests use, copied into this test's folder; the program that starts it. */
    private Map<String, Object> theStandInAcpProgram() throws Exception {
        Path source = dir.resolve("FakeAcpAgent.java");
        try (java.io.InputStream in = HireToolTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, "the stand-in ACP agent is not among the test resources");
            Files.write(source, in.readAllBytes());
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return Map.of("command", List.of(java, source.toString()), "env", Map.of("FAKE_ACP_STATE", dir.toString()));
    }

    /** A host that records every command it is asked to start and runs the real one, or refuses it when told to. */
    private HostProcesses aHostRecording(List<List<String>> started, boolean refuse) {
        return new HostProcesses() {
            @Override
            public Process start(HostCommand command) throws IOException {
                started.add(command.argv());
                if (refuse) {
                    throw new IOException("no such brain on this machine");
                }
                return host.start(command);
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

    private HireTool toolWithBrainsSetting(HostProcesses processes, String registryJson) {
        return new HireTool(processes, artifacts, plans, () -> events, () -> aRegistryWithACleanGate(),
            () -> new SelfEndpoint("http://127.0.0.1:1/mcp", "test-token"), null,
            () -> key -> HireTool.BRAINS_SETTING.equals(key) && registryJson != null ? java.util.Optional.of(registryJson) : java.util.Optional.empty(),
            null);
    }

    @Test
    @DisplayName("S12: a brain the host setting gives an ACP program is started through the engine's ACP client, and its own command line is never run")
    void aBrainWithAnAcpProgramIsStartedOverAcp() throws Exception {
        Map<String, Object> program = theStandInAcpProgram();
        List<List<String>> started = new java.util.ArrayList<>();
        HireTool hire = toolWithBrainsSetting(aHostRecording(started, false), JSON.writeValueAsString(Map.of("claude-code", program)));

        Map<String, Object> data = dataOf(hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));

        assertAll(
            () -> assertEquals(Boolean.TRUE, data.get("complete"), String.valueOf(data)),
            () -> assertEquals(1, started.size(), "one brain was started: " + started),
            () -> assertEquals(program.get("command"), started.get(0), "the brain started was not the ACP program: " + started),
            () -> assertTrue(data.get("brainSessionId") instanceof String id && !id.isBlank(),
                "the brain's own session id is not in the hire's answer: " + data));
    }

    @Test
    @DisplayName("S13 (F2): a hire has its seat's tool list on its plan while it runs, where the choke reads it, whether started over ACP or by its dialect; a dialect hire whose seat lists no tools has none")
    void anAcpHiresSeatListIsKeptWhereTheChokeReads() throws Exception {
        // Read from inside the gate, which the hire runs before its plan closes.
        java.util.List<java.util.Optional<PlanLedger.ToolAllowance>> seen = new java.util.ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String getName() {
                return "compile_workspace";
            }
            @Override public String getDescription() {
                return "a gate fixture that notes what the ledger says the hire's session may call";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                for (PlanLedger.Pending open : plans.pending()) {
                    seen.add(plans.allowance(open.id().value()));
                }
                return ToolResponse.success(Map.of("errorCount", 0));
            }
        });
        String brains = JSON.writeValueAsString(Map.of("claude-code", theStandInAcpProgram()));
        HireTool overAcp = new HireTool(host, artifacts, plans, () -> events, () -> registry,
            () -> new SelfEndpoint("http://127.0.0.1:1/mcp", "test-token"), null,
            () -> key -> HireTool.BRAINS_SETTING.equals(key) ? java.util.Optional.of(brains) : java.util.Optional.empty(), null);
        dataOf(overAcp.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
        int afterTheAcpHire = seen.size();
        HireTool byDialect = toolWith(new StandsIn(host, aBrainThatAnswers()), events, registry);
        dataOf(byDialect.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
        int afterTheDialectHire = seen.size();
        dataOf(byDialect.execute(hireArgs(seatFile(CATALOGUER_SEAT.replace("tools: [inspect, search_symbols, analyze, experience]", "tools: []")))));

        assertAll(
            () -> assertTrue(afterTheAcpHire > 0, "the gate never ran in the ACP hire, so nothing was observed"),
            () -> assertEquals(java.util.Set.of("inspect", "search_symbols", "analyze", "experience"), seen.get(0).orElseThrow().tools(),
                "the seat's list was not on the ACP hire's plan"),
            () -> assertEquals(java.util.Optional.of("compile_workspace"), seen.get(0).orElseThrow().gate()),
            () -> assertTrue(seen.size() > afterTheAcpHire && seen.get(afterTheAcpHire).isPresent()
                    && seen.get(afterTheAcpHire).get().tools().equals(java.util.Set.of("inspect", "search_symbols", "analyze", "experience")),
                "a hire started by its dialect, whose seat lists tools, was left unbounded at the choke"),
            () -> assertTrue(seen.size() > afterTheDialectHire && seen.get(afterTheDialectHire).isEmpty(),
                "control: a dialect hire whose seat lists no tools was given a list (there an empty list means the brain keeps its own configuration)"));
    }

    @Test
    @DisplayName("S12: a Cursor seat whose registry entry is keyed by the product name, as the panel and the vendor rule key it, is started through the engine's ACP client and not by its print-mode dialect")
    void aCursorBrainRegisteredUnderItsProductNameIsStartedOverAcp() throws Exception {
        Map<String, Object> program = theStandInAcpProgram();
        List<List<String>> started = new java.util.ArrayList<>();
        HireTool hire = toolWithBrainsSetting(aHostRecording(started, false), JSON.writeValueAsString(Map.of("cursor", program)));

        hire.execute(hireArgs(seatFile(CURSOR_SEAT)));

        assertAll(
            () -> assertEquals(1, started.size(), "one brain was started: " + started),
            () -> assertEquals(program.get("command"), started.get(0),
                "the registry entry for \"cursor\" was not used, so the dialect's own command line ran: " + started));
    }

    /** The same hire as {@link #hireArgs(Path)} with the brief the test needs. */
    private JsonNode hireWithBrief(Path seat, String brief) {
        return ((com.fasterxml.jackson.databind.node.ObjectNode) hireArgs(seat)).put("brief", brief);
    }

    @Test
    @DisplayName("D4: a later hire of the same seat in the same principal session continues the brain's own session and answers what only the first hire saw")
    void aLaterHireOfTheSameSeatContinuesTheBrainsSession() throws Exception {
        Map<String, Object> program = theStandInAcpProgram();
        HireTool hire = toolWithBrainsSetting(host, JSON.writeValueAsString(Map.of("claude-code", program)));
        org.jawata.mcp.models.CallerIdentity principal = org.jawata.mcp.models.CallerIdentity.supplied("principal-d4-same");
        Path seat = seatFile(CATALOGUER_SEAT);

        Map<String, Object> first = dataOf(principal.speaking(() -> hire.execute(hireWithBrief(seat, "HIREREMEMBER:bluebird"))));
        Map<String, Object> second = dataOf(principal.speaking(() -> hire.execute(hireWithBrief(seat, "HIRERECALL"))));

        assertAll(
            () -> assertEquals("noted", first.get("answer"), String.valueOf(first)),
            () -> assertEquals(first.get("brainSessionId"), second.get("brainSessionId"), "the second hire did not continue the first's session"),
            () -> assertEquals("bluebird", second.get("answer"), "the second hire did not answer what only the first saw: " + second));
    }

    @Test
    @DisplayName("D4, control: a hire of the same seat from ANOTHER principal session starts a session of its own and recalls nothing")
    void aHireFromAnotherPrincipalSessionContinuesNothing() throws Exception {
        Map<String, Object> program = theStandInAcpProgram();
        HireTool hire = toolWithBrainsSetting(host, JSON.writeValueAsString(Map.of("claude-code", program)));
        Path seat = seatFile(CATALOGUER_SEAT);

        org.jawata.mcp.models.CallerIdentity.supplied("principal-d4-one").speaking(() -> hire.execute(hireWithBrief(seat, "HIREREMEMBER:bluebird")));
        Map<String, Object> other = dataOf(org.jawata.mcp.models.CallerIdentity.supplied("principal-d4-two")
            .speaking(() -> hire.execute(hireWithBrief(seat, "HIRERECALL"))));

        assertEquals("nothing", other.get("answer"), "another principal session's hire was handed the first one's conversation: " + other);
    }

    @Test
    @DisplayName("D4, control: a caller that sent no session id of its own has nothing remembered for it and nothing recalled")
    void aCallerWithNoSessionOfItsOwnContinuesNothing() throws Exception {
        Map<String, Object> program = theStandInAcpProgram();
        HireTool hire = toolWithBrainsSetting(host, JSON.writeValueAsString(Map.of("claude-code", program)));
        org.jawata.mcp.models.CallerIdentity anonymous = org.jawata.mcp.models.CallerIdentity.minted("http");
        Path seat = seatFile(CATALOGUER_SEAT);

        anonymous.speaking(() -> hire.execute(hireWithBrief(seat, "HIREREMEMBER:bluebird")));
        Map<String, Object> later = dataOf(anonymous.speaking(() -> hire.execute(hireWithBrief(seat, "HIRERECALL"))));

        assertEquals("nothing", later.get("answer"), "an anonymous caller's hire continued an earlier one: " + later);
    }

    @Test
    @DisplayName("S10 (F7): while a hire runs, its plan's waiting step is the issuing caller's to answer for and no other caller's")
    void aHiresPendingPlanBelongsToTheCallerThatIssuedIt() throws Exception {
        // A plan ends with its hire, so ownership is read while the hire is still running: from inside its own gate, which the hire calls
        // after the brain has answered and before the plan closes.
        java.util.List<Integer> issuerSees = new java.util.ArrayList<>();
        java.util.List<Integer> otherSees = new java.util.ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String getName() {
                return "compile_workspace";
            }
            @Override public String getDescription() {
                return "a gate fixture that notes who the ledger says its plan is waiting for";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                issuerSees.add(plans.pendingFor("principal-f7-issuer").size());
                otherSees.add(plans.pendingFor("principal-f7-someone-else").size());
                return ToolResponse.success(Map.of("errorCount", 0));
            }
        });
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, registry);
        org.jawata.mcp.models.CallerIdentity issuer = org.jawata.mcp.models.CallerIdentity.supplied("principal-f7-issuer");
        JsonNode arguments = hireArgs(seatFile(CATALOGUER_SEAT));

        dataOf(issuer.speaking(() -> hire.execute(arguments)));

        assertAll(
            () -> assertTrue(!issuerSees.isEmpty(), "the gate never ran, so nothing was observed"),
            () -> assertEquals(1, issuerSees.get(0), "the issuer is told its own hire's step is pending"),
            () -> assertEquals(0, otherSees.get(0), "another caller was told about a hire it did not issue, and would hold its turn on it"));
    }

    @Test
    @DisplayName("S24 (M3): the caller a hire serves is the one captured when the call was made, even when another thread carries the call out")
    void theCallerIsTheOneCapturedWhenTheCallWasMadeNotTheOneOnTheThreadThatRunsIt() throws Exception {
        java.util.List<Integer> issuerSees = new java.util.ArrayList<>();
        java.util.List<Integer> otherSees = new java.util.ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String getName() {
                return "compile_workspace";
            }
            @Override public String getDescription() {
                return "a gate fixture that notes whether the ledger says its plan is waiting for the captured caller or for another one";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                issuerSees.add(plans.pendingFor("principal-m3-captured").size());
                otherSees.add(plans.pendingFor("principal-m3-someone-else").size());
                return ToolResponse.success(Map.of("errorCount", 0));
            }
        });
        HireTool hire = toolWith(new StandsIn(host, aBrainThatAnswers()), events, registry);
        HireTool.HireCall call = new HireTool.HireCall(hireArgs(seatFile(CATALOGUER_SEAT)), null, "",
            java.util.Optional.of(org.jawata.mcp.models.CallerIdentity.supplied("principal-m3-captured")));

        // The thread that runs the call has no caller of its own: whatever the hire knows of who asked it, it was given in the call.
        java.util.concurrent.ExecutorService engineThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            dataOf(engineThread.submit(() -> hire.hire(call)).get(60, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            engineThread.shutdownNow();
        }

        assertAll(
            () -> assertTrue(!issuerSees.isEmpty(), "the gate never ran, so nothing was observed"),
            () -> assertEquals(1, issuerSees.get(0), "the captured caller was not told its own hire's step is pending"),
            // A plan nobody owns is listed to every caller, so the issuer seeing it proves nothing: the other caller seeing none does.
            () -> assertEquals(0, otherSees.get(0), "the plan was listed to another caller: the hire read who asked from the thread running it, which had none"));
    }

    /** A brain that takes this many seconds to answer, so a hire of it outlasts a caller that gives up sooner. */
    private HostCommand aBrainThatTakes(int seconds) throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, """
            #!/bin/sh
            sleep %d
            echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"I catalogued the execution package."}]}}'
            echo '{"type":"result","result":"done","total_cost_usd":0.07}'
            """.formatted(seconds));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    @Test
    @DisplayName("S24 (M7, T1 and T11): with the background setting on a long hire answers its caller at once with its identity and still ends done after the caller gave up; with it off the same call is still waiting when the caller gives up")
    void aBackgroundHireAnswersAtOnceAndEndsDoneWhateverTheCallerDid() throws Exception {
        String brief = "S24 background brief";
        java.util.concurrent.ExecutorService caller = java.util.concurrent.Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        try {
            // THE CONTROL: the setting is off, the brain takes four seconds, and the caller's own request gives up after one. The call is
            // still waiting then: this is the failure the long hire showed under Cursor's agent, a call that outlives its caller's patience.
            HireTool foreground = toolWithSettings(new StandsIn(host, aBrainThatTakes(4)), Map.of());
            java.util.concurrent.Future<ToolResponse> waiting = caller.submit(() -> foreground.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class, () -> waiting.get(1, java.util.concurrent.TimeUnit.SECONDS),
                "with the setting off the call was expected to still be running after a second");
            waiting.get(30, java.util.concurrent.TimeUnit.SECONDS);

            // THE HIRE ON THE ENGINE'S OWN THREAD: the same hire answers inside the caller's second with who it is and no result.
            HireTool background = toolWithSettings(new StandsIn(host, aBrainThatTakes(4)), Map.of(HireTool.BACKGROUND_SETTING, "true"));
            java.util.concurrent.Future<ToolResponse> answering = caller.submit(() -> background.execute(hireWithBrief(seatFile(CATALOGUER_SEAT), brief)));
            Map<String, Object> started = dataOf(answering.get(1, java.util.concurrent.TimeUnit.SECONDS));

            // AND IT ENDS DONE, not failed, although the caller has long since stopped waiting: its answer lands in the registry, under its brief.
            com.fasterxml.jackson.databind.JsonNode ended = null;
            for (int i = 0; i < 300 && ended == null; i++) {
                java.util.Optional<com.fasterxml.jackson.databind.JsonNode> answer = org.jawata.mcp.execution.HireRegistry.shared().answerOf("cataloguer", brief);
                if (answer.isPresent() && answer.get().has("plan")) {
                    ended = answer.get();
                } else {
                    Thread.sleep(100);
                }
            }
            com.fasterxml.jackson.databind.JsonNode recorded = ended;
            assertAll(
                () -> assertTrue(started.get("hireId") instanceof String id && !id.isBlank(), "the at-once answer has no plan id: " + started),
                () -> assertTrue(started.get("name") instanceof String name && !name.isBlank(), "the at-once answer has no name: " + started),
                () -> assertEquals("running", started.get("state"), "the at-once answer does not say the hire is running"),
                () -> assertEquals("cataloguer", started.get("hired")),
                () -> org.junit.jupiter.api.Assertions.assertNotNull(recorded, "the hire never recorded its answer after the caller stopped waiting"),
                () -> assertEquals(started.get("hireId"), recorded == null ? null : recorded.path("plan").asText(), "the answer the hire recorded is for another plan"),
                () -> assertTrue(recorded != null && recorded.path("cleanExit").asBoolean(true), "the long hire did not end done: " + recorded));
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    @DisplayName("S24 (M13, T5): a hire whose claim overlaps a running agent's is refused naming the holder before anything starts, and a hire whose claim overlaps nothing runs")
    void aClaimThatOverlapsIsRefusedBeforeAnythingStarts() throws Exception {
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (org.jawata.mcp.execution.HireRegistry.Registration registration =
                     org.jawata.mcp.execution.HireRegistry.shared().begin("developer", "m13 tool holder", "m13-tool-chat")) {
                registration.claim(List.of("org.m13.tool.held"));
                org.jawata.mcp.execution.HireRegistry.shared().planOpened("m13-tool-holder-plan");
                held.countDown();
                release.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        holder.setDaemon(true);
        holder.start();
        try {
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS), "the holder did not start");
            ObjectNode overlapping = (ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
            overlapping.putArray("claims").add("org.m13.tool.held.sub");
            ObjectNode apart = (ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
            apart.putArray("claims").add("org.m13.tool.other");

            ToolResponse refused = toolWith(aHostThatMustNotSpawn(), events, aRegistryWithACleanGate()).execute(overlapping);
            ToolResponse ran = toolWith(new StandsIn(host, aBrainThatAnswers()), events, aRegistryWithACleanGate()).execute(apart);

            assertAll(
                () -> assertFalse(refused.isSuccess(), "an overlapping claim was accepted"),
                () -> assertTrue(String.valueOf(refused.getError()).contains("developer") && String.valueOf(refused.getError()).contains("m13-tool-holder-plan"),
                    "the refusal does not name who holds the package: " + refused.getError()),
                () -> assertTrue(ran.isSuccess(), "the control: a claim that overlaps nothing was refused: " + ran.getError()));
        } finally {
            release.countDown();
            holder.join(5_000);
        }
    }

    /** A hire tool whose host settings are exactly these, by key. */
    private HireTool toolWithSettings(HostProcesses processes, Map<String, String> settings) {
        return new HireTool(processes, artifacts, plans, () -> events, () -> aRegistryWithACleanGate(),
            () -> new SelfEndpoint("http://127.0.0.1:1/mcp", "test-token"), null,
            () -> key -> java.util.Optional.ofNullable(settings.get(key)), null);
    }

    private static final String SEAT_WITHOUT_A_MODEL = CATALOGUER_SEAT.replace("model: claude-sonnet-5\n", "");

    private static String registryOf(String vendor, String model) throws Exception {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        if (vendor != null) {
            entry.put("vendor", vendor);
        }
        if (model != null) {
            entry.put("model", model);
        }
        return JSON.writeValueAsString(Map.of("claude-code", entry));
    }

    private static String modelOf(List<String> argv) {
        int at = argv.indexOf("--model");
        return at < 0 || at + 1 >= argv.size() ? null : argv.get(at + 1);
    }

    private ObjectNode consultantHireArgs() throws Exception {
        ObjectNode args = (ObjectNode) hireArgs(seatFile(CATALOGUER_SEAT));
        args.put("hirer", "main");
        args.put("seat", "consultant");
        return args;
    }

    @Test
    @DisplayName("S12 (D11): the registry setting gives a seat that names no model its brain's model, and changing the registry changes the seat's next hire")
    void theRegistryModelIsUsedWhereTheSeatNamesNone() throws Exception {
        StandsIn first = new StandsIn(host, aBrainThatAnswers());
        Map<String, Object> one = dataOf(toolWithSettings(first, Map.of(HireTool.BRAINS_SETTING, registryOf("anthropic", "model-one")))
            .execute(hireArgs(seatFile(SEAT_WITHOUT_A_MODEL))));
        StandsIn second = new StandsIn(host, aBrainThatAnswers());
        dataOf(toolWithSettings(second, Map.of(HireTool.BRAINS_SETTING, registryOf("anthropic", "model-two")))
            .execute(hireArgs(seatFile(SEAT_WITHOUT_A_MODEL))));

        assertAll(
            () -> assertEquals("model-one", modelOf(first.asked()), String.valueOf(first.asked())),
            () -> assertEquals("model-two", modelOf(second.asked()), "the registry changed and the seat's next hire did not: " + second.asked()),
            () -> assertEquals(Map.of("brain", "claude-code", "vendor", "anthropic", "model", "model-one"), one.get("brain"), String.valueOf(one)));
    }

    @Test
    @DisplayName("S12 (D11), the control: a model the seat file writes is the seat's own word and the registry's does not replace it; with no registry the brain's own default stands")
    void aSeatsOwnModelWinsOverTheRegistry() throws Exception {
        StandsIn own = new StandsIn(host, aBrainThatAnswers());
        toolWithSettings(own, Map.of(HireTool.BRAINS_SETTING, registryOf("anthropic", "registry-model"))).execute(hireArgs(seatFile(CATALOGUER_SEAT)));
        StandsIn none = new StandsIn(host, aBrainThatAnswers());
        Map<String, Object> data = dataOf(toolWithSettings(none, Map.of()).execute(hireArgs(seatFile(SEAT_WITHOUT_A_MODEL))));

        assertAll(
            () -> assertEquals("claude-sonnet-5", modelOf(own.asked()), String.valueOf(own.asked())),
            () -> assertEquals(null, modelOf(none.asked()), "a model was invented with no registry and no seat model: " + none.asked()),
            () -> assertEquals("THE BRAIN'S OWN DEFAULT", ((Map<?, ?>) data.get("brain")).get("model")));
    }

    @Test
    @DisplayName("D53 (i): a hire moved onto another brain is stopped and hired again on that brain with what was said; the answer says so and the seat's next hire is back on its own brain")
    void aReplacedHireRunsAgainOnTheNewBrainAndTheSeatDefaultStands() throws Exception {
        org.jawata.mcp.execution.HireRegistry running = org.jawata.mcp.execution.HireRegistry.shared();
        Path started = dir.resolve("replaced-brain-started");
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, """
            #!/bin/sh
            if [ -e '%1$s' ]; then
              echo '{"type":"result","result":"done","total_cost_usd":0.01}'
              exit 0
            fi
            echo '{"type":"result","result":"half way through the package","total_cost_usd":0.01}'
            touch '%1$s'
            sleep 60
            """.formatted(started));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        StandsIn processes = new StandsIn(host, HostCommand.of(file.toString()));
        HireTool hire = toolWithSettings(processes, Map.of());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        try {
            java.util.concurrent.Future<ToolResponse> response = pool.submit(() -> hire.execute(hireArgs(seatFile(CATALOGUER_SEAT))));
            for (int i = 0; i < 200 && !Files.exists(started); i++) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(started), "the brain never started");

            int replaced = running.replace("cataloguer", "Describe every member of org.jawata.mcp.execution.", org.jawata.mcp.execution.SeatHire.Brain.CLAUDE_CODE, "replaced-model");
            ToolResponse answer = response.get(30, java.util.concurrent.TimeUnit.SECONDS);
            Map<String, Object> data = dataOf(answer);

            assertAll(
                () -> assertEquals(1, replaced),
                () -> assertTrue(String.valueOf(data.get("replaced")).contains("hired again"), String.valueOf(data)),
                () -> assertEquals("replaced-model", ((Map<?, ?>) data.get("brain")).get("model"), String.valueOf(data)),
                () -> assertTrue(String.valueOf(processes.asked()).contains("replaced-model"), "the second hire was not on the new brain: " + processes.asked()),
                () -> assertTrue(String.valueOf(processes.asked()).contains("half way through the package"), "the new brain was not told what was said: " + processes.asked()));

            StandsIn next = new StandsIn(host, aBrainThatAnswers());
            toolWithSettings(next, Map.of()).execute(hireArgs(seatFile(CATALOGUER_SEAT)));
            assertFalse(String.valueOf(next.asked()).contains("replaced-model"), "the Replace changed the seat's default: " + next.asked());
        } finally {
            pool.shutdownNow();
        }
    }

    /** A brain that says one thing and then keeps running for a minute, so a hire of it is still going when something stops it. */
    private HostCommand aBrainThatBlocks(Path started) throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, """
            #!/bin/sh
            echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"working"}]}}'
            touch '%s'
            sleep 60
            """.formatted(started));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    @Test
    @DisplayName("S9: a hire stopped from the Agent Control panel ends at once, its answer says the person stopped it, and it is no longer listed as running")
    void aHireStoppedFromThePanelEndsAtOnceAndSaysSo() throws Exception {
        org.jawata.mcp.execution.HireRegistry running = org.jawata.mcp.execution.HireRegistry.shared();
        Path started = dir.resolve("brain-started");
        HireTool hire = toolWithSettings(new StandsIn(host, aBrainThatBlocks(started)), Map.of());
        // A daemon thread, so a hire that does not end cannot keep the test run from ending.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        try {
            // The thread's interrupt flag is read inside the same task: the pool clears it between tasks, so reading it afterwards would see nothing.
            boolean[] leftInterrupted = new boolean[1];
            java.util.concurrent.Future<ToolResponse> response = pool.submit(() -> {
                ToolResponse answer = hire.execute(hireArgs(seatFile(CATALOGUER_SEAT)));
                leftInterrupted[0] = Thread.currentThread().isInterrupted();
                return answer;
            });
            // The brain writes the marker once its process runs, which is after the hire has registered as running.
            for (int i = 0; i < 200 && !Files.exists(started); i++) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(started), "the brain never started");

            int stopped = running.stop("cataloguer", "Describe every member of org.jawata.mcp.execution.");
            ToolResponse answer = response.get(20, java.util.concurrent.TimeUnit.SECONDS);

            assertAll(
                () -> assertEquals(1, stopped),
                () -> assertFalse(answer.isSuccess(), "a stopped hire was answered as a success"),
                () -> assertTrue(String.valueOf(answer.getError()).contains("stopped from the Agent Control panel"), String.valueOf(answer.getError())),
                () -> assertEquals(0, running.stop("cataloguer", "Describe every member of org.jawata.mcp.execution."),
                    "the stopped hire is still listed as running"),
                () -> assertFalse(leftInterrupted[0], "the thread that carried the stopped hire was left interrupted for the next request"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("S12 (D48): the consultant is refused on main's own vendor, naming the rule, before any brain starts")
    void aConsultantOnMainsOwnVendorIsRefusedBeforeItStarts() throws Exception {
        Map<String, String> settings = Map.of(Hirer.MAIN_BRAIN_SETTING, "{\"command\":[\"main-brain\"],\"vendor\":\"Anthropic\"}",
            HireTool.BRAINS_SETTING, registryOf("anthropic", null));

        ToolResponse response = toolWithSettings(aHostThatMustNotSpawn(), settings).execute(consultantHireArgs());

        assertAll(
            () -> assertFalse(response.isSuccess(), "a consultant on main's own vendor was hired"),
            () -> assertEquals("PRECONDITION_NOT_MET", response.getError().getCode()),
            () -> assertTrue(response.getError().getMessage().contains("main's own vendor"), response.getError().getMessage()));
    }

    @Test
    @DisplayName("S12 (D48), audit C12 F1: the vendor rule holds for any hirer, so a name the signed tree does not know cannot hire the consultant on main's own vendor")
    void aHirerTheTreeDoesNotKnowCannotGetAroundTheVendorRule() throws Exception {
        Map<String, String> settings = Map.of(Hirer.MAIN_BRAIN_SETTING, "{\"command\":[\"main-brain\"],\"vendor\":\"Anthropic\"}",
            HireTool.BRAINS_SETTING, registryOf("anthropic", null));
        ObjectNode args = consultantHireArgs();
        args.put("hirer", "a-script-the-tree-has-never-heard-of");

        ToolResponse response = toolWithSettings(aHostThatMustNotSpawn(), settings).execute(args);

        assertAll(
            () -> assertFalse(response.isSuccess(), "an unknown hirer got the consultant on main's own vendor"),
            () -> assertTrue(response.getError().getMessage().contains("main's own vendor"), response.getError().getMessage()));
    }

    @Test
    @DisplayName("S12 (D48), the control: the consultant on another vendor is hired, and the answer names its vendor")
    void aConsultantOnAnotherVendorIsHired() throws Exception {
        Map<String, String> settings = Map.of(Hirer.MAIN_BRAIN_SETTING, "{\"command\":[\"main-brain\"],\"vendor\":\"anthropic\"}",
            HireTool.BRAINS_SETTING, registryOf("openai", "other-model"));

        ToolResponse response = toolWithSettings(new StandsIn(host, aBrainThatAnswers()), settings).execute(consultantHireArgs());

        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
        assertEquals("openai", ((Map<?, ?>) dataOf(response).get("brain")).get("vendor"));
    }

    @Test
    @DisplayName("S11 exit, journal precedes relay: a consultant's answer comes back only with its journal row already in the store, naming the vendor that gave it and the model")
    void aConsultantsJournalRowExistsWhenItsAnswerIsRelayed() throws Exception {
        Map<String, String> settings = Map.of(Hirer.MAIN_BRAIN_SETTING, "{\"command\":[\"main-brain\"],\"vendor\":\"anthropic\"}",
            HireTool.BRAINS_SETTING, registryOf("openai", "other-model"));

        Map<String, Object> data = dataOf(toolWithSettings(new StandsIn(host, aBrainThatAnswers()), settings).execute(consultantHireArgs()));

        // The tool has returned, so the answer is what would be relayed; the row is read back from the store by the plan id in it.
        List<LearnerEvent> rows = events.eventsOfSession((String) data.get("plan")).orElseThrow(
            () -> new AssertionError("the journal could not be read back at all"));
        assertAll(
            () -> assertEquals(1, rows.size(), "the row was not in the store when the answer was given: " + rows),
            () -> assertEquals("landed", data.get("journal"), String.valueOf(data.get("journalBasis"))),
            () -> assertEquals("openai", ((Map<?, ?>) data.get("brain")).get("vendor")),
            () -> assertEquals(LearnerEvent.KIND_HIRED_RUN, rows.get(0).kind()));
    }

    @Test
    @DisplayName("S12 (D48): a consultant whose vendor, or main's, is not named is refused and not assumed to differ, and the refusal says where to name it")
    void aConsultantWithAnUnnamedVendorIsRefused() throws Exception {
        ToolResponse noRegistryVendor = toolWithSettings(aHostThatMustNotSpawn(),
            Map.of(Hirer.MAIN_BRAIN_SETTING, "{\"command\":[\"main-brain\"],\"vendor\":\"anthropic\"}", HireTool.BRAINS_SETTING, registryOf(null, "m")))
            .execute(consultantHireArgs());
        ToolResponse noMainVendor = toolWithSettings(aHostThatMustNotSpawn(),
            Map.of(Hirer.MAIN_BRAIN_SETTING, "{\"command\":[\"main-brain\"]}", HireTool.BRAINS_SETTING, registryOf("openai", null)))
            .execute(consultantHireArgs());

        assertAll(
            () -> assertFalse(noRegistryVendor.isSuccess()),
            () -> assertTrue(noRegistryVendor.getError().getMessage().contains(Hirer.MAIN_BRAIN_SETTING), noRegistryVendor.getError().getMessage()),
            () -> assertFalse(noMainVendor.isSuccess()),
            () -> assertTrue(noMainVendor.getError().getMessage().contains("hire-brains"), noMainVendor.getError().getMessage()));
    }

    @Test
    @DisplayName("S12 (D48), the control: the vendor rule is the consultant's alone, so a working seat on main's own vendor is hired")
    void aWorkingSeatOnMainsVendorIsNotHeldToTheConsultantRule() throws Exception {
        Map<String, String> settings = Map.of(Hirer.MAIN_BRAIN_SETTING, "{\"command\":[\"main-brain\"],\"vendor\":\"anthropic\"}",
            HireTool.BRAINS_SETTING, registryOf("anthropic", null));

        ToolResponse response = toolWithSettings(new StandsIn(host, aBrainThatAnswers()), settings).execute(hireArgs(seatFile(CATALOGUER_SEAT)));

        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
    }

    @Test
    @DisplayName("S12, the control: the same hire with no program for its brain is started by the brain's own dialect, so the test above sees the setting and nothing else")
    void aBrainWithoutAnAcpProgramIsStartedByItsDialect() throws Exception {
        Map<String, Object> program = theStandInAcpProgram();
        List<List<String>> started = new java.util.ArrayList<>();
        HireTool hire = toolWithBrainsSetting(aHostRecording(started, true), null);

        ToolResponse response = hire.execute(hireArgs(seatFile(CATALOGUER_SEAT)));

        assertAll(
            () -> assertFalse(response.isSuccess(), "the brain's own command was refused by the host, so the hire must say it failed"),
            () -> assertEquals(1, started.size(), String.valueOf(started)),
            () -> assertFalse(started.get(0).equals(program.get("command")), "the ACP program was started with no setting naming it: " + started));
    }

}
