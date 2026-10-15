package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jawata.mcp.execution.ToolGateRunner;
import org.jawata.mcp.learn.GateOutcome;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PrecedentLedger;
import org.jawata.mcp.learn.ToolExperienceRecorder;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code ToolRegistry#callTool} answers whatever its own machinery does (Sprint 28g S16-11).
 *
 * <p>The gate runner kept a {@code catch (RuntimeException)} that no test could reach. A mutation
 * that made it rethrow stayed green, and the explanation recorded was that {@code callTool} turns a
 * throwing TOOL into an {@code internalError} before the runner sees it, leaving the registry's own
 * machinery before that dispatch (the precedent charge, the acceptance check, the in-flight ticket)
 * as what the catch guarded. The architect's watch called the unreachable backstop the symptom and
 * said the smaller design was to make {@code callTool} total, conditional on a fault-injection seam
 * that had not been checked.</p>
 *
 * <p>It was checked. Each of those collaborators already catches its own failure and fails OPEN
 * (the precedent charge, the acceptance check, the prior-work lookup, the disk sync, the store
 * notice), and what is left (stripping the override argument, the discriminator read, the in-flight
 * ticket) cannot throw on any input. So the registry is already total over the faults a runner could
 * meet, and a boundary catch added to make it so would be the same unreachable backstop moved one
 * class. The runner's catch is gone instead; this pins the property it was a backstop for, through
 * the seams that exist (a precedent ledger whose clock throws, a store-notice supplier that throws).
 * Each test also proves its fault was REACHED, because a seam never called pins nothing.</p>
 */
class ToolRegistryAnswersWhateverItsOwnMachineryDoesTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final CallerIdentity CALLER = CallerIdentity.supplied("total-1");
    private static final String TARGETED = "{\"filePath\":\"Foo.java\"}";

    private final AtomicInteger executed = new AtomicInteger();
    private final AtomicInteger faultsReached = new AtomicInteger();

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("fixture json is not json: " + s, e);
        }
    }

    /** A tool whose body COUNTS, so "it still ran" is observable. */
    private Tool counting(String name) {
        return new Tool() {
            @Override public String getName() {
                return name;
            }
            @Override public String getDescription() {
                return "a counting stand-in";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                executed.incrementAndGet();
                return ToolResponse.success(Map.of("errorCount", 0));
            }
        };
    }

    /**
     * A registry whose precedent ledger holds a warned pair for {@code tool} and whose CLOCK then
     * throws, so the charge is evaluated against a pair that exists and its lookup fails.
     */
    private ToolRegistry registryWhosePrecedentChargeThrows(String tool) {
        AtomicBoolean armed = new AtomicBoolean();
        PrecedentLedger ledger = new PrecedentLedger(() -> {
            if (armed.get()) {
                faultsReached.incrementAndGet();
                throw new IllegalStateException("injected: the ledger's clock is broken");
            }
            return 0L;
        });
        String target = ToolExperienceRecorder.target(tool, json(TARGETED));
        // Both callers: a client call arrives as CALLER, and a gate run by the runner arrives as the
        // in-process identity, and a charge belongs to whoever was warned.
        ledger.warn(CALLER, tool, target);
        ledger.warn(CallerIdentity.IN_PROCESS, tool, target);
        armed.set(true);
        ToolRegistry registry = new ToolRegistry();
        registry.setPrecedentLedger(ledger);
        registry.register(counting(tool));
        return registry;
    }

    @Test
    @DisplayName("a precedent charge that throws does not stop the call: it answers, and the tool runs")
    void aBrokenPrecedentChargeIsNotAnException() throws Exception {
        ToolRegistry registry = registryWhosePrecedentChargeThrows("get_diagnostics");

        ToolResponse response = registry.callTool("get_diagnostics", json(TARGETED), CALLER);

        assertAll(
            () -> assertTrue(faultsReached.get() > 0, "the injected fault was reached"),
            () -> assertTrue(response.isSuccess(), "and the call answered"),
            () -> assertEquals(1, executed.get(), "the work was done, exactly once"));
    }

    @Test
    @DisplayName("a store notice that throws does not stop the call")
    void aBrokenStoreNoticeIsNotAnException() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setStoreNotice(() -> {
            faultsReached.incrementAndGet();
            throw new IllegalStateException("injected: the store notice is broken");
        });
        registry.register(counting("get_diagnostics"));

        ToolResponse response = registry.callTool("get_diagnostics", json(TARGETED), CALLER);

        assertAll(
            () -> assertTrue(faultsReached.get() > 0, "the injected fault was reached"),
            () -> assertTrue(response.isSuccess(), "and the call answered"),
            () -> assertEquals(1, executed.get(), "the work was done, exactly once"));
    }

    @Test
    @DisplayName("so a gate run through the same registry still ANSWERS: nothing was left for a runner to back-stop")
    void aGateThroughABrokenPrecedentChargeStillAnswers() {
        // get_diagnostics, not compile_workspace: a precedent charge is levied against a TARGET, and
        // the compile gate names none, so the fault would never be reached.
        ToolRegistry registry = registryWhosePrecedentChargeThrows("get_diagnostics");

        GateOutcome outcome = new ToolGateRunner(registry)
            .run(new GatedStep(0, "get_diagnostics", json(TARGETED)));

        assertAll(
            () -> assertTrue(faultsReached.get() > 0, "the injected fault was reached"),
            () -> assertTrue(outcome.succeeded(), "the gate answered: " + outcome.notAnsweredBecause()),
            () -> assertEquals(0, outcome.errorCount()));
    }
}
