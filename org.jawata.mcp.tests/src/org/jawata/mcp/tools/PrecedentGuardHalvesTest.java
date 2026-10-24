package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.learn.PrecedentLedger;
import org.jawata.mcp.learn.ToolExperience;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D8b (feedback D8): THE PRECEDENT GUARD WAS WRONG AS A CLASS, in four halves, and each half is
 * a separate case here because each has a different cause and a different fix.
 *
 * <p>The guard says: a tool that was reverted or errored on a target warns the next caller, and
 * using it anyway costs a written reason. In Sprint 8 it refused {@code inspect} twice on a
 * target because an earlier {@code inspect} had answered "not found", and once refused
 * {@code experience record}. It announced "{@code inspect} was reverted/errored 11×" and
 * "{@code experience} 19×" — a count that grew with every refusal the guard itself produced.</p>
 *
 * <ol>
 *   <li><b>whose precedent</b> — a tool that cannot be reverted has no precedent to steer by;</li>
 *   <li><b>what counts as one</b> — a refusal is not a failed attempt (see
 *       {@code ToolExperienceCaptureTest});</li>
 *   <li><b>what pays</b> — a payment must say something about the case;</li>
 *   <li><b>who owes it, and for how long</b> — a charge belongs to the agent that earned it.</li>
 * </ol>
 */
class PrecedentGuardHalvesTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Tool tool(String name, boolean revertible, boolean[] ran) {
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
                ran[0] = true;
                return ToolResponse.success(Map.of("ok", true));
            }
            @Override public boolean isRevertible() {
                return revertible;
            }
        };
    }

    /** A registry whose retriever reports {@code warned} as having reverted on com.foo.Bar before. */
    private static ToolRegistry registryWarningAbout(String warned, boolean revertible,
            boolean[] ran) {
        ToolRegistry reg = new ToolRegistry();
        reg.setPrecedentRetriever((query, limit) -> List.of(
            new ToolExperience("s1", warned + " com.foo.Bar", warned,
                ToolExperience.OUTCOME_REVERTED, "{}")));
        reg.register(tool(warned, revertible, ran));
        reg.register(tool("analyze", false, new boolean[1]));
        return reg;
    }

    private static final JsonNode WARM = json("{\"typeName\":\"com.foo.Bar\"}");
    private static final JsonNode THE_CALL = json("{\"symbol\":\"com.foo.Bar\"}");

    private static JsonNode paying(String reason) {
        return json("{\"symbol\":\"com.foo.Bar\",\"precedentOverride\":\"" + reason + "\"}");
    }

    // ── half 1: whose precedent ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("half 1: a tool that cannot be reverted is never charged on its own precedent")
    void aReadOnlyToolAndTheStoreAreNeverCharged() throws Exception {
        for (String readOnly : List.of("inspect", "experience")) {
            boolean[] ran = {false};
            ToolRegistry reg = registryWarningAbout(readOnly, false, ran);
            ToolResponse warm = reg.callTool("analyze", WARM, CallerIdentity.supplied("s1"));
            assertFalse(steeringOf(warm).contains("⚠ PRECEDENT"),
                readOnly + " cannot be reverted, so a precedent against it is not one to steer"
                    + " by. Steering: " + steeringOf(warm));

            ToolResponse response = reg.callTool(readOnly, THE_CALL, CallerIdentity.supplied("s1"));
            assertTrue(response.isSuccess() && ran[0],
                readOnly + " was refused for an earlier failure of a call that costs nothing to"
                    + " repeat. Got: " + response.getError());
        }
    }

    @Test
    @DisplayName("half 1, the control: the same precedent against a REVERTIBLE tool still charges")
    void aRevertibleToolIsStillCharged() throws Exception {
        boolean[] ran = {false};
        ToolRegistry reg = registryWarningAbout("extract", true, ran);
        reg.callTool("analyze", WARM, CallerIdentity.supplied("s1"));
        ToolResponse refused = reg.callTool("extract", THE_CALL, CallerIdentity.supplied("s1"));
        assertFalse(refused.isSuccess() || ran[0],
            "without this, the case above would be satisfied by a guard that never charges");
    }

    /**
     * HALF 1, BOUND TO WHAT SHIPS. The cases above use stand-in tools that DECLARE they are
     * revertible, so they pass whether or not any real tool does. The first version of this
     * fix was exactly that: it overrode the declaration on the two refactoring bases and the
     * lifecycle door, and {@code extract}, {@code inline}, {@code move}, {@code hierarchy},
     * {@code generate} and {@code refactor_to_pattern} extend {@code AbstractTool} directly, so
     * the guard never warned or charged on the tools it exists for. Found by the C4 audit.
     */
    @Test
    @DisplayName("half 1, against the shipped tools: every refactoring tool is revertible, readers are not")
    void theShippedRefactoringToolsAreAllSubjectsOfThePrecedent() {
        org.jawata.mcp.change.RefactoringChangeCache cache =
            new org.jawata.mcp.change.RefactoringChangeCache();
        List<AbstractTool> shipped = new java.util.ArrayList<>(
            RefactoringDoors.all(() -> null, cache));
        shipped.addAll(RefactoringDoors.standalone(() -> null, cache, () -> null));
        shipped.add(new RefactoringTool(() -> null, cache));

        assertFalse(shipped.isEmpty(), "proof of life: the derivation yields tools");
        for (AbstractTool tool : shipped) {
            assertTrue(tool.isRevertible(),
                tool.getName() + " changes the workspace and has an undo, so a precedent against"
                    + " it is exactly what the guard is for — and it answers false");
        }
        assertFalse(new InspectTool(() -> null).isRevertible(),
            "inspect only reads: the guard must never charge it");
        assertFalse(new ExperienceTool(() -> null, null).isRevertible(),
            "the knowledge store is not a subject of precedent");
    }

    // ── half 3: what pays ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("half 3: a payment that says nothing about the case does not pay")
    void aPaymentMustSaySomething() throws Exception {
        boolean[] ran = {false};
        ToolRegistry reg = registryWarningAbout("extract", true, ran);
        reg.callTool("analyze", WARM, CallerIdentity.supplied("s1"));

        // A token, the name of the tool, the name of the target: none of them is a reason.
        // And a SENTENCE that is about nothing: three words clear a length floor and say
        // nothing about the precedent charged ("about the precedent" is the spec's phrase).
        for (String nothing : List.of("x", "ok", "extract", "com.foo.Bar", "retry",
                "just try it again", "i really want this")) {
            ToolResponse refused = reg.callTool("extract", paying(nothing),
                CallerIdentity.supplied("s1"));
            assertFalse(refused.isSuccess() || ran[0],
                "'" + nothing + "' was accepted as the reason for defecting from a precedent");
            assertNotNull(refused.getError());
            assertTrue(refused.getError().getMessage().contains("reason"),
                "the refusal must say what a payment is: " + refused.getError().getMessage());
        }

        // The charge was NOT cleared by the failed payments, or paying after them would be free.
        ToolResponse paid = reg.callTool("extract",
            paying("the earlier revert was a bad range; this is a clean block"),
            CallerIdentity.supplied("s1"));
        assertTrue(paid.isSuccess() && ran[0],
            "a sentence about why this case differs pays the cost: " + paid.getError());

        // Naming the TARGET is also being about the precedent, without the word "revert".
        boolean[] ran2 = {false};
        ToolRegistry other = registryWarningAbout("extract", true, ran2);
        other.callTool("analyze", WARM, CallerIdentity.supplied("s1"));
        ToolResponse byTarget = other.callTool("extract",
            paying("Bar now has a second caller so the block is safe"),
            CallerIdentity.supplied("s1"));
        assertTrue(byTarget.isSuccess() && ran2[0],
            "a sentence naming the target is about it: " + byTarget.getError());
    }

    // ── half 4: who owes it, and for how long ───────────────────────────────────────────

    @Test
    @DisplayName("half 4: a caller whose history is a transport's, not an agent's, is never charged")
    void aSharedBucketIsNeverCharged() throws Exception {
        boolean[] ran = {false};
        ToolRegistry reg = registryWarningAbout("extract", true, ran);
        CallerIdentity anyone = CallerIdentity.minted("http");
        ToolResponse warm = reg.callTool("analyze", WARM, anyone);
        // Surfacing is a statement and may stay; the CHARGE is the cost, and a header-less
        // transport's bucket is shared by every agent that came through it.
        ToolResponse response = reg.callTool("extract", THE_CALL, anyone);
        assertTrue(response.isSuccess() && ran[0],
            "one agent's warned call blocked another's first attempt: the minted identity is"
                + " shared by every caller that sent no session header. Steering was: "
                + steeringOf(warm));
    }

    @Test
    @DisplayName("half 4: a charge expires, so it does not outlive the agent that earned it")
    void aChargeExpires() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000L);
        boolean[] ran = {false};
        ToolRegistry reg = registryWarningAbout("extract", true, ran);
        reg.setPrecedentLedger(new PrecedentLedger(now::get));
        reg.callTool("analyze", WARM, CallerIdentity.supplied("s1"));

        // Just inside the lifetime it still charges — or the case below would pass for a
        // ledger that never charged at all.
        now.addAndGet(PrecedentLedger.OUTSTANDING_TTL_MS - 1);
        ToolResponse inside = reg.callTool("extract", THE_CALL, CallerIdentity.supplied("s1"));
        assertFalse(inside.isSuccess() || ran[0], "the control: still outstanding inside its life");

        now.addAndGet(2);
        ToolResponse after = reg.callTool("extract", THE_CALL, CallerIdentity.supplied("s1"));
        assertTrue(after.isSuccess() && ran[0],
            "a charge earned a session ago blocked this attempt. The agent that earned it is gone.");
    }

    private static String steeringOf(ToolResponse response) {
        return response.getMeta() == null || response.getMeta().getSteering() == null
            ? "" : response.getMeta().getSteering();
    }
}
