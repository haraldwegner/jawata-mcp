package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jawata.mcp.fixtures.ShippedGates;
import org.jawata.mcp.learn.EventTap;
import org.jawata.mcp.learn.ServerChecks;
import org.jawata.mcp.learn.SessionLedger;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28g S9a M3 — acceptance runs BEFORE the act, and this class is the only
 * thing in the suite that can tell.
 *
 * <h2>Why it had to be written before the step could be judged</h2>
 *
 * <p>Measured before a line of M3 was changed: {@code ToolRegistry#setServerChecks}
 * had exactly ONE reference in the whole workspace and it was the application —
 * <b>zero test sites</b>. Every one of the thirteen non-production calls into the
 * checks called them DIRECTLY, bypassing {@code callTool} entirely. So the move
 * this step exists to make — consume acceptance before {@code tool.execute}
 * instead of forty-seven lines after it — could have been done, half-done or
 * reverted and the suite would have been identical.</p>
 *
 * <p>The claim under test is therefore not "it refuses" but <em>"it refuses
 * BEFORE"</em>, and the only assertion that can tell those apart is a count of
 * how many times the tool actually ran.</p>
 */
class ToolRegistryOrderingTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** How many times the registered tool's body actually executed. */
    private final AtomicInteger executed = new AtomicInteger();

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("fixture json is not json: " + s, e);
        }
    }

    /**
     * A tool named for the verb a seat outcome is recorded through, whose body
     * COUNTS. "The act did not happen" is unobservable without this counter.
     */
    private Tool countingExperienceTool() {
        return new Tool() {
            @Override public String getName() {
                return "experience";
            }
            @Override public String getDescription() {
                return "a counting stand-in for the experience verb";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                executed.incrementAndGet();
                return ToolResponse.success(Map.of("recorded", true));
            }
        };
    }

    /**
     * A registry wired the way the application wires it — BOTH collaborators,
     * because the consumption site is guarded on the two of them together and
     * omitting either makes the whole check silently absent.
     */
    private ToolRegistry registryWiredLikeProduction(SessionLedger ledger) {
        ToolRegistry registry = new ToolRegistry();
        registry.setEventTap(new EventTap(ledger, null));
        registry.setServerChecks(new ServerChecks(null, null, ShippedGates.registry()));
        registry.register(countingExperienceTool());
        return registry;
    }

    private static void seed(SessionLedger ledger, CallerIdentity caller, String tool) {
        ledger.record(caller, new SessionLedger.CallRecord(tool, true, 0,
            System.currentTimeMillis(), null));
    }

    private static JsonNode seatOutcome() {
        return json("{\"kind\":\"record\",\"operation\":\"seat:cover\","
            + "\"summary\":\"outcome\"}");
    }

    @Test
    @DisplayName("a seat outcome over a session that ran NO gate is refused AND the tool never runs")
    void anUngatedSeatOutcomeIsRefusedBeforeTheToolRuns() throws Exception {
        SessionLedger ledger = new SessionLedger();
        ToolRegistry registry = registryWiredLikeProduction(ledger);
        CallerIdentity caller = CallerIdentity.supplied("ordering-ungated");
        seed(ledger, caller, "search_symbols");

        ToolResponse response = registry.callTool("experience", seatOutcome(), caller);

        assertAll(
            () -> assertFalse(response.isSuccess(),
                "a REFUSAL, not a success carrying a remark about itself"),
            () -> assertNotNull(response.getError(), "and it arrives on the refusal channel"),
            () -> assertEquals("SEAT_GATE_NOT_RUN", response.getError().getCode(),
                "a code a caller can branch on"),
            () -> assertEquals(0, executed.get(),
                "THE ORDERING CLAUSE, and the only assertion that can see it: the tool's body"
                    + " never ran. Every other assertion here is equally true of a refusal"
                    + " produced AFTER the act, which is what this step changed"));
    }

    @Test
    @DisplayName("THE CONTROL: a session that DID run its gate is accepted and the tool runs")
    void aGatedSeatOutcomeIsAcceptedAndTheToolRuns() throws Exception {
        // Without this, every assertion above passes against an acceptance path
        // that refuses EVERYTHING — which is indistinguishable from the product
        // being broken, and is the shape this sprint has shipped repeatedly.
        SessionLedger ledger = new SessionLedger();
        ToolRegistry registry = registryWiredLikeProduction(ledger);
        CallerIdentity caller = CallerIdentity.supplied("ordering-gated");
        seed(ledger, caller, "compile_workspace");

        ToolResponse response = registry.callTool("experience", seatOutcome(), caller);

        assertAll(
            () -> assertTrue(response.isSuccess(),
                "the gate ran, so the outcome is recorded rather than refused"),
            () -> assertEquals(1, executed.get(), "and the tool's body did run, exactly once"));
    }

    @Test
    @DisplayName("an ordinary call is untouched by acceptance — it is not a gate on everything")
    void anOrdinaryCallIsNotRefused() throws Exception {
        // The third shape, because the two above only ever send the ONE verb that
        // acceptance looks at. A check that refused any call it did not recognise
        // would pass both of them.
        SessionLedger ledger = new SessionLedger();
        ToolRegistry registry = registryWiredLikeProduction(ledger);
        CallerIdentity caller = CallerIdentity.supplied("ordering-ordinary");

        ToolResponse response = registry.callTool("experience",
            json("{\"kind\":\"recall\",\"symbol\":\"com.foo.Bar\"}"), caller);

        assertAll(
            () -> assertTrue(response.isSuccess(), "a recall is not a seat outcome"),
            () -> assertEquals(1, executed.get(), "and it reached the tool"));
    }
}
