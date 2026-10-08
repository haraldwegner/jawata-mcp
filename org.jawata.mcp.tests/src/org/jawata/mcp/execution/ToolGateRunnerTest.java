package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Supplier;

import org.jawata.mcp.learn.GateOutcome;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The production gate runner (Sprint 28g S16): a hired plan's declared gate, run against the real
 * tool registry.
 *
 * <p>Every case here is one of the five ways a gate run ends, and the PAIR at the centre is what
 * the class exists for: a gate that answered "zero errors" and a gate that did not answer produce
 * the SAME error count, and only {@link GateOutcome#succeeded()} tells them apart. Reading the
 * second as the first certifies a step nothing checked and lets the ledger advance a plan on it.</p>
 */
class ToolGateRunnerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A tool that does whatever the case needs, so the runner's branches are all reachable. */
    private record FakeGate(String name, Supplier<ToolResponse> answer) implements Tool {

        @Override
        public String getName() {
            return name;
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
            return answer.get();
        }
    }

    private static ToolRegistry registryWith(Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static GatedStep step(String tool) {
        return new GatedStep(0, tool, JSON.createObjectNode());
    }

    private static ToolResponse data(String json) {
        try {
            return ToolResponse.success(JSON.readValue(json, Map.class));
        } catch (Exception e) {
            throw new IllegalStateException("fixture json is not json: " + json, e);
        }
    }

    @Test
    @DisplayName("THE PAIR: a reported zero PASSES the gate, and an unreadable response does not")
    void aReportedZeroAndAnUnreadableResponseAreNotTheSameAnswer() {
        ToolRegistry registry = registryWith(
            new FakeGate("clean_gate", () -> data("{\"errorCount\": 0}")),
            new FakeGate("mute_gate", () -> data("{\"operation\": \"something_else\"}")));
        ToolGateRunner runner = new ToolGateRunner(registry);

        GateOutcome clean = runner.run(step("clean_gate"));
        GateOutcome mute = runner.run(step("mute_gate"));

        assertAll(
            // Identical error counts...
            () -> assertEquals(0, clean.errorCount()),
            () -> assertEquals(0, mute.errorCount()),
            // ...and opposite verdicts. This is the only thing standing between "it ran and found
            // nothing" and "nothing checked this step", and the ledger reads the second component.
            () -> assertTrue(clean.succeeded(), "it ran and published a count of zero"),
            () -> assertFalse(mute.succeeded(),
                "it succeeded in a shape this runner cannot read, which is not a pass"));
    }

    @Test
    @DisplayName("a step naming a tool that does not exist has been gated by nothing")
    void anUnknownToolDoesNotAnswer() {
        ToolGateRunner runner = new ToolGateRunner(registryWith(
            new FakeGate("compile_workspace", () -> data("{\"errorCount\": 0}"))));

        // A misspelled gate is the quietest way to gate nothing at all: the plan declares a step,
        // the step names a tool, and no tool runs.
        //
        // WHICH branch answers this is worth naming, because a mutation settled it. The runner
        // used to ask the registry first; disabling that question changed nothing any test could
        // see, so it is gone and the registry's own {@code ToolNotFoundException} is what answers
        // here. That the exception is CAUGHT at all is the compiler's doing — it is checked — but
        // what the catch then reports is not, which is what the assertions below pin.
        GateOutcome typo = runner.run(step("compile_wrokspace"));

        assertAll(
            () -> assertFalse(typo.succeeded()),
            () -> assertEquals(0, typo.errorCount()),
            () -> assertEquals("compile_wrokspace", typo.tool(),
                "and the outcome still names the step's own tool, so a record can quote it"));
    }

    @Test
    @DisplayName("a gate that threw knows less than one that refused, and neither answers")
    void aCrashingOrRefusingGateDoesNotAnswer() {
        ToolGateRunner runner = new ToolGateRunner(registryWith(
            new FakeGate("crashing_gate", () -> {
                throw new IllegalStateException("the gate fell over");
            }),
            new FakeGate("refusing_gate",
                () -> ToolResponse.error("PROJECT_NOT_LOADED", "no project", null)),
            // THE REFUSAL THAT CARRIES A COUNT, and it is the only shape that reaches the
            // refusal branch. A mutation disabling that branch stayed GREEN against the bare
            // refusal above, because a refusal with no data publishes no count either, so the
            // unreadable-shape case answered in its place. But ToolResponse.error has a
            // four-argument form that sets the response's own data — for the reason its javadoc
            // gives, that a refusal caused by something in the user's world must say WHICH thing
            // — and ResponseFacts reads that data without asking whether the call succeeded. So
            // a compile gate refusing with a diagnosis map is a refusal publishing errorCount 0,
            // and reading it would certify a step that never ran.
            new FakeGate("refusing_gate_with_a_count", () -> ToolResponse.error(
                "PROJECT_NOT_LOADED", "no project", null, Map.of("errorCount", 0))),
            new FakeGate("empty_gate", () -> null)));

        assertAll(
            // A gate's crash must not become the hire's crash: the run already happened and its
            // record is still owed to a reader.
            () -> assertFalse(runner.run(step("crashing_gate")).succeeded()),
            // A refusal answers about the TOOL, not about the tree, so it has no count to give.
            () -> assertFalse(runner.run(step("refusing_gate")).succeeded()),
            // And it has none EVEN WHEN IT PUBLISHES ONE. This is the discriminating case: the
            // response carries errorCount 0 as its diagnosis, so every later line of the runner
            // would read it as a clean gate. Only the refusal branch can tell that the tool
            // declined rather than ran.
            () -> assertFalse(runner.run(step("refusing_gate_with_a_count")).succeeded(),
                "a refusal publishing a count has still not looked at the tree"),
            () -> assertEquals(0, runner.run(step("refusing_gate_with_a_count")).errorCount()),
            () -> assertFalse(runner.run(step("empty_gate")).succeeded()),
            // All three report zero, which is exactly why the boolean beside it is load-bearing.
            () -> assertEquals(0, runner.run(step("crashing_gate")).errorCount()),
            () -> assertEquals(0, runner.run(step("refusing_gate")).errorCount()));
    }

    @Test
    @DisplayName("an answering gate's count is the gate's verdict, under either measured key")
    void anAnsweringGateReportsItsCount() {
        ToolGateRunner runner = new ToolGateRunner(registryWith(
            new FakeGate("compiler", () -> data("{\"errorCount\": 3}")),
            new FakeGate("runner", () -> data("{\"failed\": 2}"))));

        GateOutcome compiled = runner.run(step("compiler"));
        GateOutcome tested = runner.run(step("runner"));

        assertAll(
            () -> assertEquals(3, compiled.errorCount()),
            () -> assertTrue(compiled.succeeded()),
            // The test runner's own key, which is the other half of the measured pair — a runner
            // reading only errorCount would report this gate as unanswered.
            () -> assertEquals(2, tested.errorCount()),
            () -> assertTrue(tested.succeeded()),
            // Whether a non-zero count STOPS the plan is the ledger's call. This class reports
            // what the gate said and judges nothing.
            () -> assertEquals("compiler", compiled.tool()));
    }

    @Test
    @DisplayName("a runner with no registry is refused, rather than failing every step honestly")
    void aRunnerWithNoRegistryIsRefused() {
        // It could answer "did not answer" for everything and be truthful, which is the trap: a
        // plan would then report every step unanswered and a reader would hunt the gates rather
        // than the wiring.
        IllegalArgumentException refused =
            assertThrows(IllegalArgumentException.class, () -> new ToolGateRunner(null));

        assertTrue(refused.getMessage().contains("honest and useless"), refused.getMessage());
    }
}
