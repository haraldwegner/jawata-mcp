package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.ExperienceAdvisor;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g D42 (M4 + M5) — what the store already knows about this target rides the
 * response of EVERY tool, not just the one verb an agent has to remember to call.
 *
 * <p>The build it pins is a CONNECTION rather than a mechanism: the advisor, the
 * response envelope and the retrieval deadline all existed and reached exactly one tool
 * of forty-two. So the claim under test is not "advice can be computed" — that is
 * {@code ExperienceAdvisorTest}'s — but "the registry asks, and the answer reaches the
 * caller through the steering channel the studio read already depends on".</p>
 *
 * <p><b>The two controls are what make the positive case mean anything.</b> A response
 * carrying a steering line proves nothing on its own: this registry has three other lanes
 * that append to the same field, and a block that fired for every call would satisfy a
 * bare containment assertion. So the same positive target is driven with NO advisor wired
 * (the block must be absent), and the same wired advisor is driven with a target the store
 * has never heard of (the block must be absent). Each control differs from the positive in
 * exactly one thing, which is what lets the positive attribute the block to that thing.</p>
 */
class PriorWorkRidesTheResponseTest {

    /** The lead sentence only this block emits — the needle no other lane prints. */
    private static final String LEAD = "What the store already knows about";

    /** The anchored entry's own summary, which is what the rendered advice carries. */
    private static final String KNOWN =
        "extract rolled back here: the body reads a field two callers write";

    private static final String KNOWN_TARGET = "com.example.OrderService";

    private static final ObjectMapper OM = new ObjectMapper();

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /** A minimal tool: the registry is the subject, so the tool must contribute nothing. */
    private static Tool mock(String name, Function<JsonNode, ToolResponse> exec) {
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
                return exec.apply(arguments);
            }
        };
    }

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** What the store knows before the call — anchored to a symbol, which is the cue. */
    private void storeKnows(String symbol) {
        store.put(ExperienceEntry.of(
            SymbolFact.of("failure_mode", KNOWN, Confidence.HIGH).symbol(symbol).build())
            .operation("extract").build());
    }

    private ToolRegistry registry(boolean withAdvisor) {
        ToolRegistry reg = new ToolRegistry();
        if (withAdvisor) {
            reg.setExperienceAdvisor(new ExperienceAdvisor(store, () -> null));
        }
        reg.register(mock("analyze", a -> ToolResponse.success(Map.of("ok", true))));
        return reg;
    }

    private static String steeringOf(ToolResponse r) {
        return r.getMeta() == null ? null : r.getMeta().getSteering();
    }

    private static ToolResponse analyze(ToolRegistry reg, String typeName) throws Exception {
        return reg.callTool("analyze", json("{\"typeName\":\"" + typeName + "\"}"),
            CallerIdentity.supplied("s1"));
    }

    @Test
    void what_the_store_knows_about_the_target_rides_an_ordinary_tool_answer() throws Exception {
        storeKnows(KNOWN_TARGET);

        String steer = steeringOf(analyze(registry(true), KNOWN_TARGET));

        assertNotNull(steer, "the block rides the response of a tool that asked for nothing");
        assertTrue(steer.contains(LEAD), steer);
        assertTrue(steer.contains(KNOWN_TARGET),
            "it names the target it is about, so a reader can tell it is theirs: " + steer);
        assertTrue(steer.contains("field two callers write"),
            "and it carries the entry's own words rather than a count of them: " + steer);
    }

    @Test
    void with_no_advisor_the_same_call_carries_no_block() throws Exception {
        storeKnows(KNOWN_TARGET);

        String steer = steeringOf(analyze(registry(false), KNOWN_TARGET));

        // Deliberately NOT asserted null: three other lanes append to this same field, and
        // a null assertion here would be measuring their silence rather than this block's.
        assertFalse(steer != null && steer.contains(LEAD),
            "the store holds the entry and the registry was never given the advisor, "
                + "so the block cannot be what any steering came from: " + steer);
    }

    @Test
    void a_target_the_store_has_never_heard_of_carries_no_block() throws Exception {
        storeKnows(KNOWN_TARGET);

        String steer = steeringOf(analyze(registry(true), "com.example.NothingKnownHere"));

        assertFalse(steer != null && steer.contains(LEAD),
            "an absence stays an absence — the block appears for a target the store "
                + "answers on, not for every call: " + steer);
    }

    @Test
    void the_tools_own_steering_survives_beside_the_block() throws Exception {
        storeKnows(KNOWN_TARGET);
        ToolRegistry reg = new ToolRegistry();
        reg.setExperienceAdvisor(new ExperienceAdvisor(store, () -> null));
        reg.register(mock("analyze", a -> {
            ToolResponse r = ToolResponse.success(Map.of("ok", true));
            r.applySteering("TOOL-OWN: the next grounded step");
            return r;
        }));

        String steer = steeringOf(analyze(reg, KNOWN_TARGET));

        assertNotNull(steer);
        assertTrue(steer.contains("TOOL-OWN: the next grounded step"),
            "the channel is additive: the tool's own line is never replaced: " + steer);
        assertTrue(steer.contains(LEAD), "and the block is APPENDED alongside it: " + steer);
    }

    /**
     * The deadline is an ARGUMENT this call site has to supply, which is the whole of
     * <i>M4 before M5</i>: a block riding every response cannot inherit the fifteen-second
     * default a caller that ASKED for advice is willing to wait out. Pinned here as the
     * floor the store's own clamp permits, because the number is the one thing about this
     * build that a reader cannot see from the response.
     */
    @Test
    void the_block_buys_the_fastest_answer_the_store_allows() {
        storeKnows(KNOWN_TARGET);

        List<String> advice = new ExperienceAdvisor(store, () -> null)
            .adviseBefore(null, KNOWN_TARGET,
                org.jawata.mcp.knowledge.ExperienceRetrieval.MIN_RETRIEVAL_BUDGET_MILLIS);

        // NOT assertNotNull: `adviseBefore` answers with an empty list and never with null,
        // so a null check here could not fail whatever the budget did. What CAN fail is the
        // thing the floor is suspected of — a deadline so tight the read is abandoned and
        // the honest answer is the empty one.
        assertFalse(advice.isEmpty(),
            "the floor budget still answers: past the deadline the recall cancels and "
                + "returns RESULT_UNAVAILABLE, which renders nothing");
        assertTrue(advice.get(0).contains("field two callers write"), advice.get(0));
    }
}
