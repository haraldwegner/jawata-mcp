package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.step.GateOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h S12 slice 4 (D18): on the ACP start path a plan's step moves while the brain works.
 *
 * <p>The brain is the stand-in ACP agent, which reports N completed editing calls, waits 700 ms, drops a marker file and only then answers.
 * A gate that sees the marker ran after the answer; one that does not, ran while the brain was still working. That is the whole observation,
 * read from the world and not from a flag the run keeps about itself.</p>
 */
class HiredRunLiveGatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SEAT = "---\nname: cataloguer\nbrain: cursor\naccepts_uncontained_writes: true\nttl_secs: 60\nmax_tool_steps: 8\n---\n\nbody\n";

    private H2ExperienceStore store;
    private Path dir;
    private PlanLedger plans;
    private HiredRun run;
    private AcpBrainSession.Program agent;
    private final List<String> gateCalls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("jawata-live-gates-");
        store = H2ExperienceStore.openMemory();
        plans = new PlanLedger();
        run = new HiredRun(HostProcesses.system(), new RuntimeArtifactStore(dir.resolve("artifacts")), plans, new LearnerEventStore(store));
        try (InputStream in = HiredRunLiveGatesTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            assertNotNull(in, "the stand-in agent is not among the test resources");
            Files.write(dir.resolve("FakeAcpAgent.java"), in.readAllBytes());
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        agent = new AcpBrainSession.Program(List.of(java.toString(), dir.resolve("FakeAcpAgent.java").toString()),
            Map.of("FAKE_ACP_STATE", dir.toString()));
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static GatedStep step(int index, String tool) {
        return new GatedStep(index, tool, JSON.createObjectNode());
    }

    /** A gate that records whether the brain had already answered when it ran, and answers with {@code errors} errors. */
    private HiredRun.GateRunner gates(int errors) {
        return gate -> {
            gateCalls.add(gate.tool() + ":" + (Files.exists(dir.resolve("answered.marker")) ? "after" : "during"));
            return new GateOutcome(gate.tool(), gate.gateArguments(), errors, true);
        };
    }

    private HiredRun.Proposal hire(String brief, HiredRun.GateRunner gateRunner) throws Exception {
        return run.issue(SeatHire.read(SEAT), HireSite.in(dir), brief,
            List.of(step(0, "compile_workspace"), step(1, "run_tests")), gateRunner, List.of(), Optional.of(agent));
    }

    @Test
    @DisplayName("S12 D18: each step's gate runs while the brain is still working, the plan moves at that moment, and the proposal lists both steps in order")
    void aStepsGateRunsWhileTheBrainWorks() throws Exception {
        HiredRun.Proposal proposal = hire("EDITS:2", gates(0));

        assertAll(
            () -> assertEquals(List.of("compile_workspace:during", "run_tests:during"), gateCalls,
                "a gate saw the brain's answer already written, so it ran after the brain, not while it worked"),
            () -> assertEquals(List.of("compile_workspace", "run_tests"), proposal.gates().stream().map(GateOutcome::tool).toList()),
            () -> assertEquals(2, proposal.stepsPassed()),
            () -> assertTrue(proposal.complete()),
            () -> assertTrue(proposal.outcome().text().contains("edited"), "the brain did answer: " + proposal.outcome().text()));
    }

    @Test
    @DisplayName("S12 D18, resume: a step passed live is not run again, the end of the hire runs only the steps after it")
    void theEndOfTheHireResumesAfterTheLiveSteps() throws Exception {
        HiredRun.Proposal proposal = hire("EDITS:1", gates(0));

        assertAll(
            () -> assertEquals(List.of("compile_workspace:during", "run_tests:after"), gateCalls,
                "step 0 passes live, step 1 has no edit to follow and runs once, at the end"),
            () -> assertEquals(List.of("compile_workspace", "run_tests"), proposal.gates().stream().map(GateOutcome::tool).toList()),
            () -> assertEquals(2, proposal.stepsPassed()));
    }

    @Test
    @DisplayName("S12 D18, the control: a gate that fails while the brain edits does not move the plan, and leaves one failed row, not one per edit")
    void aFailingGateLeavesOneRowNotOnePerEdit() throws Exception {
        HiredRun.Proposal proposal = hire("EDITS:3", gates(1));

        assertAll(
            () -> assertEquals(3, gateCalls.stream().filter(c -> c.endsWith(":during")).count(), "the gate was tried after each of the three edits: " + gateCalls),
            () -> assertEquals(1, proposal.gates().size(), "only the end-of-hire attempt is recorded: " + proposal.gates()),
            () -> assertEquals(0, proposal.stepsPassed()),
            () -> assertFalse(proposal.complete()));
    }

    @Test
    @DisplayName("S12 D18: the per-step ceiling is the seat's run ceiling spread over the plan's steps, rounded up, and nothing when the seat set none")
    void theCeilingIsTheRunCeilingSpreadOverTheSteps() {
        assertAll(
            () -> assertEquals(4, HiredRun.perStepCeiling(new HiredBrain.Ceilings(java.time.Duration.ofSeconds(1), 8, null), 2)),
            () -> assertEquals(3, HiredRun.perStepCeiling(new HiredBrain.Ceilings(java.time.Duration.ofSeconds(1), 5, null), 2)),
            () -> assertEquals(0, HiredRun.perStepCeiling(new HiredBrain.Ceilings(java.time.Duration.ofSeconds(1), 0, null), 2)),
            () -> assertEquals(0, HiredRun.perStepCeiling(null, 2)));
    }
}
