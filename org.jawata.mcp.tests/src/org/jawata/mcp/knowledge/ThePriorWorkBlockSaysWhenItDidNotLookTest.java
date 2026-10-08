package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g D42 — <b>past the deadline the response says it did not look.</b> Silence there
 * would be read as "the store knows nothing about this target", which is a claim nobody made.
 *
 * <p>This is #37's distinction arriving on a new channel. That issue established it at the
 * {@code experience} verb: <i>"the store said nothing" and "the store could not answer" are
 * different answers, and the difference must be visible in the RESPONSE SHAPE.</i> D42 puts a
 * store read on EVERY tool response, so the same distinction has to survive one layer further
 * out — and it did not: {@code adviseBefore} returns a {@code List<String>}, which cannot say
 * which empty it is holding.</p>
 *
 * <p><b>It lives in this package rather than beside the rest of D42's tests, deliberately.</b>
 * {@link UnreadableStore} — a store that is open and whose read never returns — is a
 * package-private fixture here, and it is the thing that makes an outage reachable from a test
 * at all. Widening its visibility so a test in another package could reach it would change a
 * contract for a test's convenience; the registry, the advisor and the response are all public,
 * so the test can come to the fixture instead. Stated because a reader will otherwise wonder
 * why one build's cases are split across two classes.</p>
 *
 * <p><b>Written as PAIRS, for the reason that file gives:</b> a test that only asserts the
 * outage line cannot show the two cases are told apart, and the cheapest way to pass it would
 * be to print "we did not look" unconditionally — which would report an outage on every
 * ordinary call.</p>
 */
class ThePriorWorkBlockSaysWhenItDidNotLookTest {

    /** The lead sentence the block emits when the store WAS read and had something. */
    private static final String KNOWS = "What the store already knows about";

    /** The lead sentence the block emits when the store was never read to a conclusion. */
    private static final String DID_NOT = "The store was NOT consulted about";

    private static final String TARGET = "com.example.OrderService";

    private static final ObjectMapper OM = new ObjectMapper();

    /** A read that never returns, against a budget that does — #37's measured shape. */
    private static ExperienceAdvisor overAStalledStore() {
        return new ExperienceAdvisor(new UnreadableStore(60_000), () -> null);
    }

    private static Tool mock(String name) {
        return new Tool() {
            @Override public String getName() {
                return name;
            }
            @Override public String getDescription() {
                return name;
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of("ok", true));
            }
        };
    }

    private static String steeringOf(ExperienceAdvisor advisor) throws Exception {
        ToolRegistry reg = new ToolRegistry();
        reg.setExperienceAdvisor(advisor);
        reg.register(mock("analyze"));
        ToolResponse r = reg.callTool("analyze",
            OM.readTree("{\"typeName\":\"" + TARGET + "\"}"), CallerIdentity.supplied("s1"));
        return r.getMeta() == null ? null : r.getMeta().getSteering();
    }

    @Test
    void a_store_that_could_not_be_read_is_reported_as_not_looked() {
        long start = System.currentTimeMillis();
        ExperienceAdvisor.PriorWork asked = overAStalledStore()
            .priorWork(null, TARGET, ExperienceRetrieval.MIN_RETRIEVAL_BUDGET_MILLIS,
                QualityLedger.SURFACE_CHOKE_PRIOR_WORK);
        long waited = System.currentTimeMillis() - start;

        assertFalse(asked.looked(),
            "a stalled read is not an absence — the caller must be able to tell");
        assertTrue(asked.advice().isEmpty(), "and it carries no half-read advice");
        assertEquals(ExperienceRetrieval.RESULT_UNAVAILABLE, asked.result());
        assertNotNull(asked.reason(), "the recall's own sentence about why, not one composed here");
        assertTrue(waited < 5_000,
            "released on the budget rather than on the read; waited " + waited + "ms");
    }

    /**
     * THE PAIR. The same question against a healthy, EMPTY store. If this reported "not
     * looked" too, the outage line above would be unconditional and would mean nothing — and
     * the product would be announcing an outage on every ordinary call.
     */
    @Test
    void a_healthy_empty_store_is_an_absence_and_not_an_outage() {
        try (H2ExperienceStore healthy = H2ExperienceStore.openMemory()) {
            ExperienceAdvisor.PriorWork asked = new ExperienceAdvisor(healthy, () -> null)
                .priorWork(null, TARGET, ExperienceRetrieval.MIN_RETRIEVAL_BUDGET_MILLIS,
                    QualityLedger.SURFACE_CHOKE_PRIOR_WORK);

            assertTrue(asked.looked(),
                "the store WAS read; it simply held nothing, which is an observed absence");
            assertTrue(asked.advice().isEmpty());
        }
    }

    @Test
    void the_response_says_it_did_not_look_rather_than_falling_silent() throws Exception {
        String steer = steeringOf(overAStalledStore());

        assertNotNull(steer, "an outage must reach the caller, not vanish into a log line");
        assertTrue(steer.contains(DID_NOT), steer);
        assertTrue(steer.contains(TARGET), "it names what was not looked up: " + steer);
        assertTrue(steer.contains("not an absence"),
            "and it refuses the reading that silence would have invited: " + steer);
        assertFalse(steer.contains(KNOWS),
            "it must not also claim to be telling them what the store knows: " + steer);
    }

    /**
     * THE REGISTRY BUYS THE FLOOR, and this is the only assertion that says so — the C14a
     * review found the previous one attached to the wrong half.
     *
     * <p>`PriorWorkRidesTheResponseTest` asserts the ADVISOR answers at the floor, which it
     * does; it calls the advisor directly, so changing the registry's own call to the
     * fifteen-second default left it green. That is the defect this project keeps finding: a
     * correct assertion about a path nobody takes.</p>
     *
     * <p>What this measures instead is time through the REGISTRY, which is the thing the clause
     * is about: a block riding every response must not hold a caller while a wedged store is
     * walked. Against a read that never returns, the floor comes back in roughly its own
     * milliseconds and the default would take fifteen seconds — so the two are far enough apart
     * that a generous bound still discriminates.</p>
     */
    @Test
    void the_registry_buys_the_floor_and_not_the_fifteen_second_default() throws Exception {
        long start = System.currentTimeMillis();
        steeringOf(overAStalledStore());
        long waited = System.currentTimeMillis() - start;

        assertTrue(waited < 5_000,
            () -> "the registry's own call must buy the floor; a caller waited " + waited
                + "ms, which is the fifteen-second default's territory");
    }

    /**
     * B3 — THE BLOCK RIDES A REFUSAL, and before the C14a audit it did not.
     *
     * <p>`appendSteering` opens with `if (!success ...) return;` — correctly, because steering
     * is next-step advice and a refused call has no next step. Both D42 emissions used it, so
     * the block and the outage line were dropped on exactly the answers where they matter most:
     * a caller whose tool just declined is the one most likely to go and do the thing by hand,
     * which is the duplicate this deliverable exists to prevent. `mcp#12` records the identical
     * defect 120 lines above the method, for the degradation notice.</p>
     */
    @Test
    void a_refused_response_still_carries_what_the_store_knows() throws Exception {
        try (H2ExperienceStore knowing = H2ExperienceStore.openMemory()) {
            knowing.put(ExperienceEntry.of(
                SymbolFact.of("failure_mode", "this target bit somebody before",
                    Confidence.HIGH).symbol(TARGET).build())
                .situation("when touching this target")
                .verdict("failed_avoid")
                .build());

            ToolRegistry reg = new ToolRegistry();
            reg.setExperienceAdvisor(new ExperienceAdvisor(knowing, () -> null));
            reg.register(new Tool() {
                @Override public String getName() {
                    return "analyze";
                }
                @Override public String getDescription() {
                    return "analyze";
                }
                @Override public Map<String, Object> getInputSchema() {
                    return Map.of();
                }
                @Override public ToolResponse execute(JsonNode arguments) {
                    return ToolResponse.error("DELIBERATE_REFUSAL",
                        "this tool declined, which is the case the block must survive",
                        "none");
                }
            });

            ToolResponse r = reg.callTool("analyze",
                OM.readTree("{\"typeName\":\"" + TARGET + "\"}"), CallerIdentity.supplied("s1"));
            String steer = r.getMeta() == null ? null : r.getMeta().getSteering();

            assertFalse(r.isSuccess(), "PROOF OF LIFE: the tool really did refuse");
            assertNotNull(steer, "and the block survived the refusal rather than being dropped");
            assertTrue(steer.contains("bit somebody before"),
                () -> "carrying what the store knows: " + steer);
        }
    }

    /**
     * C14a — THE BLOCK RIDES A REFUSAL <b>THE REGISTRY ITSELF MINTS</b>, and until this
     * checkpoint it did not.
     *
     * <p>The sibling above drives a refusal the TOOL returns, which leaves {@code callTool}
     * by its NORMAL path — the one line the emission used to sit on. That method has four
     * other exits: an unpaid precedent charge, a failed acceptance check, and two catch
     * blocks. None of them passed that line, so the block was absent from every one, and
     * {@code priorWork}'s own javadoc argues a refusal is where it matters MOST.</p>
     *
     * <p><b>The repair had been half-made, which is why the sibling above did not catch
     * this.</b> The emission was moved onto {@code appendStoreAnswer}, which rides a
     * refusal — and its single call site still reached no refusal this registry produces.
     * A method able to speak on a refusal and never called on one reads identical to a
     * method that cannot.</p>
     *
     * <p>This drives the cheapest of the four exits — a tool that THROWS — because it needs
     * no collaborator wired. <b>The bound is stated rather than left to read as coverage:</b>
     * {@code serverChecks} and {@code precedentLedger} are null on a bare registry, so the
     * other two refusals are unreachable from here by construction and this case says
     * nothing about them.</p>
     */
    @Test
    void a_crash_the_registry_turned_into_an_answer_carries_what_the_store_knows()
            throws Exception {
        try (H2ExperienceStore knowing = H2ExperienceStore.openMemory()) {
            knowing.put(ExperienceEntry.of(
                SymbolFact.of("failure_mode", "this target bit somebody before",
                    Confidence.HIGH).symbol(TARGET).build())
                .situation("when touching this target")
                .verdict("failed_avoid")
                .build());

            ToolRegistry reg = new ToolRegistry();
            reg.setExperienceAdvisor(new ExperienceAdvisor(knowing, () -> null));
            reg.register(new Tool() {
                @Override public String getName() {
                    return "analyze";
                }
                @Override public String getDescription() {
                    return "analyze";
                }
                @Override public Map<String, Object> getInputSchema() {
                    return Map.of();
                }
                @Override public ToolResponse execute(JsonNode arguments) {
                    throw new IllegalStateException(
                        "a tool that throws — the registry turns this into the answer");
                }
            });

            ToolResponse r = reg.callTool("analyze",
                OM.readTree("{\"typeName\":\"" + TARGET + "\"}"), CallerIdentity.supplied("s1"));
            String steer = r.getMeta() == null ? null : r.getMeta().getSteering();

            assertFalse(r.isSuccess(),
                "PROOF OF LIFE: the throw really did become a structured refusal");
            assertNotNull(steer,
                "the block must ride the answer the REGISTRY built, not only the tool's own");
            assertTrue(steer.contains("bit somebody before"),
                () -> "carrying what the store knows: " + steer);
        }
    }

    /**
     * THE PAIR, at the response. A healthy empty store produces NEITHER line: there is nothing
     * to report and no outage to announce. Without this, "the outage line appears" could be
     * satisfied by a registry that appends it to every response it ever builds.
     */
    @Test
    void a_healthy_empty_store_adds_no_block_to_the_response() throws Exception {
        try (H2ExperienceStore healthy = H2ExperienceStore.openMemory()) {
            String steer = steeringOf(new ExperienceAdvisor(healthy, () -> null));

            // Not asserted null: other lanes append to this same field, and asserting null
            // here would be measuring their silence rather than this block's.
            assertFalse(steer != null && steer.contains(DID_NOT),
                "no outage happened, so none is announced: " + steer);
            assertFalse(steer != null && steer.contains(KNOWS),
                "and nothing is known, so nothing is claimed: " + steer);
        }
    }
}
