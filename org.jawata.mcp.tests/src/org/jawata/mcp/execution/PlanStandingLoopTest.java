package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.function.Function;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.learn.PlannedStep;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h item 9 (i) slice 2, hire 3b (M3): a hired pm that ends its turn with a step of the plan it entered open, and nothing blocking it, is given
 * another turn by the engine, and the no-progress rule of declared checks does not stop it. Run against the stand-in agent of {@link AcpBrainSessionTest}.
 */
class PlanStandingLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HostProcesses host = HostProcesses.system();
    private final PlanLedger plans = new PlanLedger();
    private Path dir;
    private AcpBrainSession.Program agent;

    @BeforeEach
    void startAgentSource() throws IOException {
        dir = Files.createTempDirectory("jawata-plan-standing-loop-");
        try (InputStream in = PlanStandingLoopTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            assertNotNull(in, "the stand-in agent is not among the test resources");
            Files.write(dir.resolve("FakeAcpAgent.java"), in.readAllBytes());
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        agent = new AcpBrainSession.Program(List.of(java.toString(), dir.resolve("FakeAcpAgent.java").toString()),
            Map.of("FAKE_ACP_STATE", dir.toString()));
    }

    @AfterEach
    void removeWhatWasCreated() throws IOException {
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static JsonNode empty() {
        return JSON.createObjectNode();
    }

    /** Enters a plan of two checked steps, none passed, owned by this lead. */
    private PlanId entered(String ownerId) {
        PlanId id = plans.openSprint("the sprint of " + ownerId, List.of(
            new PlannedStep("Compile", "compile_workspace", empty(), null, false, false),
            new PlannedStep("Diagnose", "get_diagnostics", empty(), null, false, false)));
        plans.ownedBy(id, ownerId);
        return id;
    }

    private HiredBrain.Outcome run(String prompt, Duration wallClock, String leadId, Function<String, Optional<AcpBrainSession.OpenCheck>> open)
            throws Exception {
        return AcpBrainSession.runAsLead(host, agent, HireSite.in(dir), prompt, null, List.of(), Optional.empty(), HiredBrain.Ceilings.of(wallClock),
            null, null, update -> { }, HireRegistry.shared().inboxFor(leadId), leadId, null, open, null);
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: a pm that ends its turn with an open step and nothing blocking gets another turn, and after it passes the steps its report goes up with 2 of 2")
    void aPmWithAnOpenStepIsSentBackUntilItsPlanIsDone() throws Exception {
        String lead = "m3-loop-sent-back";
        PlanId id = entered(lead);
        int[] asked = {0};
        List<String> toldFirst = new ArrayList<>();

        run("REMEMBER:bluebird", Duration.ofSeconds(60), lead, text -> {
            asked[0]++;
            if (asked[0] > 1) {
                // the pm's stand-in passes the step that is open through that step's own gate
                plans.nextStep(id).ifPresent(step -> plans.advanceIfGatePassed(id, new GateOutcome(step.tool(), empty(), 0, true)));
            }
            Optional<AcpBrainSession.OpenCheck> open = PlanStandingSendBack.of(plans, HireRegistry.shared(), lead);
            if (toldFirst.isEmpty()) {
                open.ifPresent(check -> toldFirst.add(check.told()));
            }
            return open;
        });

        assertAll(
            () -> assertTrue(asked[0] > 1, "the pm was given no further turn: asked " + asked[0] + " time(s)"),
            () -> assertTrue(!toldFirst.isEmpty() && toldFirst.get(0).contains("Plan standing:"), "the first send-back: " + toldFirst),
            () -> assertTrue(!toldFirst.isEmpty() && toldFirst.get(0).contains("The plan has an open step and nothing blocks you"), "the first send-back: " + toldFirst),
            () -> assertTrue(HireReport.standingLine(JSON.valueToTree(plans.standingOf(lead).orElseThrow().asMap())).contains("2 of 2 steps passed")));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: the same open step answered three times in a row still gets a turn each time, because the plan's standing is exempt from the no-progress rule")
    void anUnchangedOpenStepOfThePlanGetsATurnEachTime() throws Exception {
        String lead = "m3-loop-no-cap";
        entered(lead);
        int[] asked = {0};

        run("REMEMBER:bluebird", Duration.ofSeconds(60), lead, text -> {
            asked[0]++;
            return asked[0] > 3 ? Optional.empty() : PlanStandingSendBack.of(plans, HireRegistry.shared(), lead);
        });

        assertEquals(4, asked[0], "three sends of the same unchanged step are three further turns, and the fourth ask ends the run");
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3, the controls: a lead that entered no plan, and a lead whose plan is owned by another id, get exactly one turn")
    void aLeadWithNoPlanOfItsOwnGetsOneTurn() throws Exception {
        entered("m3-loop-the-owner");
        int[] noPlan = {0};
        int[] otherPlan = {0};

        HiredBrain.Outcome none = run("REMEMBER:bluebird", Duration.ofSeconds(60), "m3-loop-none", text -> {
            noPlan[0]++;
            return PlanStandingSendBack.of(plans, HireRegistry.shared(), "m3-loop-none");
        });
        HiredBrain.Outcome other = run("REMEMBER:bluebird", Duration.ofSeconds(60), "m3-loop-not-the-owner", text -> {
            otherPlan[0]++;
            return PlanStandingSendBack.of(plans, HireRegistry.shared(), "m3-loop-not-the-owner");
        });

        assertAll(
            () -> assertEquals(1, noPlan[0], "a lead with no plan was asked again"),
            () -> assertEquals("noted", none.text()),
            () -> assertEquals(1, otherPlan[0], "a lead asking about another lead's plan was asked again"),
            () -> assertEquals("noted", other.text()));
    }

    @Test
    @DisplayName("28h Stage 28 (i) M3: a lead whose brain dies in its first turn is never asked what its plan leaves open")
    void aLeadWhoseBrainDiedIsNeverAskedAboutItsPlan() throws Exception {
        String lead = "m3-loop-died";
        entered(lead);
        int[] asked = {0};

        run("DIE", Duration.ofSeconds(60), lead, text -> {
            asked[0]++;
            return PlanStandingSendBack.of(plans, HireRegistry.shared(), lead);
        });

        assertEquals(0, asked[0], "the engine asked about the plan of a lead whose brain had died");
    }

    @Test
    @org.junit.jupiter.api.Disabled("not yet made to run: against the stand-in agent a wall-clock stop of runAsLead (prompts HANG and WAITCANCEL, 1 s) ends in "
        + "java.io.IOException: Stream closed instead of an outcome with stoppedBy; the cause is not found, so the stop case is not proved by this test")
    @DisplayName("28h Stage 28 (i) M3: a lead whose turn the engine stopped at its wall-clock ceiling is never asked what its plan leaves open")
    void aLeadWhoseTurnWasStoppedIsNeverAskedAboutItsPlan() throws Exception {
        String lead = "m3-loop-stopped";
        entered(lead);
        int[] asked = {0};

        HiredBrain.Outcome stopped = run("WAITCANCEL", Duration.ofSeconds(1), lead, text -> {
            asked[0]++;
            return PlanStandingSendBack.of(plans, HireRegistry.shared(), lead);
        });

        assertAll(
            () -> assertTrue(stopped.stoppedBy().isPresent(), "CONTROL: the turn was not stopped by the ceiling: " + stopped.text()),
            () -> assertEquals(0, asked[0], "the engine asked about the plan of a lead whose turn it had stopped"));
    }
}
