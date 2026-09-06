package org.jawata.mcp.tools.smell;

import org.jawata.mcp.refactoring.OperationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CONTROL FOR THE BOOT REFUSAL — reproduced, rather than described.
 *
 * <p>{@code CureCatalog.validateAgainst} refuses a declared cure step that more than one
 * tool publishes, because a bare mention of such a step renders an invocation against
 * whichever tool registered last. That refusal fired on its first real boot: the
 * lifecycle front door republished six operations {@code refactor_to_pattern} already
 * published, so {@code long_method}'s cure was ambiguous and the application exited
 * before serving.</p>
 *
 * <p>It had no test. Every registry the other tests hand it is synthesised one tool per
 * operation, so nothing in those registries can BE ambiguous — deleting the refusal
 * outright left a 2274-test suite green. This builds the shape that took the boot down
 * and asserts the refusal happens, which is the only way the check is worth anything.</p>
 *
 * <p>Its own registries are local. The refusal reads a registry it is handed, so no test
 * here needs to touch the singleton the application wires.</p>
 */
class TheCureTableRefusesAnAmbiguousStepTest {

    /**
     * The real front doors, as the application registers them — not one synthetic tool
     * per step.
     *
     * <p>The first version of this helper registered each declared step as a tool named
     * after itself, which made every step trivially unambiguous AND made the refusal's
     * message name a publisher that does not exist. A control has to be built on the
     * shape production has, or it proves something about a registry nobody runs.</p>
     */
    private static OperationRegistry theRealFrontDoors() {
        OperationRegistry registry = new OperationRegistry();
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        java.util.function.Supplier<org.jawata.core.IJdtService> none = () -> null;
        // EVERY door DERIVED, not five of six hand-listed. The apply_cleanup entry was
        // already derived, with a comment saying a hand-written copy "cost a red suite"
        // when row 60 shipped a kind the mirror had not been told about. Stage 6 then
        // shipped ELEVEN kinds across extract, inline and move — and the mirror had not
        // been told about any of them, so the same defect fired again on the same file,
        // one tool over. A list that is right by construction cannot go stale; the
        // comment beside the one derived entry was the whole fix and it was not applied
        // to its neighbours.
        // AND THE SAME DEFECT WAS ONE LEVEL UP, which C8 found the hard way. The comment
        // above is about each door's KINDS being derived rather than typed out — and it is
        // correct — but the LIST OF DOORS beside it stayed hand-written, and by C8 it was
        // short by THREE: `data` (ten kinds), `generate` (seven) and `change_method_signature`
        // (eleven) had all become front doors since it was last touched. So this mirror was
        // missing 28 kinds while asserting that the shipped cure table validates against "the
        // real front doors", and an architect watch named it: it would pass a widening of the
        // operation surface WITHOUT HAVING LOOKED.
        //
        // It bit immediately. Qualifying three bare `data` recipes — which fixed an
        // instruction the product refuses — turned this file red, because the mirror
        // published `data` with no kinds at all and so could not back `data kind=…`.
        //
        // The three are added here. The list is STILL hand-written, and that is the remaining
        // half: deriving it needs the registration seam M6c extracts out of the application,
        // so the population comes from the same source production uses. Until then this
        // comment is the marker, and the lesson is the one this sprint keeps re-learning —
        // deriving the inner list while hand-writing the outer one moves the staleness up a
        // level rather than removing it.
        for (org.jawata.mcp.tools.AbstractTool door : List.of(
                new org.jawata.mcp.tools.RefactorToPatternTool(none, cache),
                new org.jawata.mcp.tools.ExtractTool(none, cache),
                new org.jawata.mcp.tools.MoveTool(none, cache),
                new org.jawata.mcp.tools.InlineTool(none, cache),
                new org.jawata.mcp.tools.HierarchyTool(none, cache),
                new org.jawata.mcp.tools.DataTool(none, cache),
                new org.jawata.mcp.tools.codegen.GenerateTool(none, cache),
                new org.jawata.mcp.tools.ChangeMethodSignatureTool(none, cache),
                new org.jawata.mcp.tools.ApplyCleanupTool(none, cache))) {
            registry.register(door.getName(), kindsOrFail(door));
        }
        registry.register("data", List.of());
        return registry;
    }

    /**
     * A door's kind enum — ASKED OF THE DOOR (Stage 6a, M2).
     *
     * <p>This copy read {@code action} as well as {@code kind} and {@code direction}, which
     * none of the six doors below publishes — only the lifecycle door does, and it is not in
     * the list — so that third spelling never fired. It also ACCUMULATED across
     * discriminators where the door returns at the first one present; no door here declares
     * two, so the two agree. Both differences were latent, and both are gone with the copy.</p>
     *
     * <p>The emptiness guard below is NOT part of the copy and stays: it is this test's own
     * proof of life, and it is the reason a door that silently stopped publishing would fail
     * here rather than quietly mirror nothing.</p>
     */
    private static List<String> kindsOrFail(org.jawata.mcp.tools.AbstractTool tool) {
        List<String> kinds = tool.publishedKinds();
        if (kinds.isEmpty()) {
            throw new IllegalStateException(
                "PROOF OF LIFE: " + tool.getName() + " publishes no kind enum, so this"
                    + " registry mirrors nothing for it and the validation proves nothing");
        }
        return kinds;
    }

    @Test
    @DisplayName("the shipped table validates against a registry that publishes its steps")
    void theShippedTableIsClean() {
        assertDoesNotThrow(() -> CureCatalog.validateAgainst(theRealFrontDoors()),
            "every declared step must be backed by a published operation, or boot fails");
    }

    @Test
    @DisplayName("a step TWO tools publish is refused, and the refusal names both and the way out")
    void aSecondPublisherOfAStepIsRefused() {
        OperationRegistry registry = theRealFrontDoors();
        // Exactly what the lifecycle front door did: republish an operation another tool
        // already publishes. Under the old registry the second write simply won.
        registry.register("a_second_front_door", List.of("compose_method"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> CureCatalog.validateAgainst(registry),
            "a cure step published by two tools cannot be rendered as one invocation,"
                + " and guessing which was the defect this refusal replaced");

        String message = thrown.getMessage();
        assertTrue(message.contains("compose_method"), "it must name the step: " + message);
        assertTrue(message.contains("a_second_front_door") && message.contains("refactor_to_pattern"),
            "and BOTH publishers — a reader cannot choose between tools it cannot see."
                + " refactor_to_pattern is the real one; a_second_front_door is the"
                + " republisher this test adds, which is exactly what the lifecycle"
                + " front door was doing when it took the boot down. Got: " + message);
        assertTrue(message.contains("kind="),
            "and it must name the qualified form, which is the way to declare the cure"
                + " so this refusal accepts it: " + message);
    }

    @Test
    @DisplayName("a step nothing publishes is refused too, and that is a different message")
    void anUnbackedStepIsRefused() {
        OperationRegistry empty = new OperationRegistry();
        empty.register("something_else", List.of("unrelated"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> CureCatalog.validateAgainst(empty));
        assertTrue(thrown.getMessage().contains("no registered"),
            "an absent step and an ambiguous one are different faults and must not read"
                + " the same: " + thrown.getMessage());
    }

    @Test
    @DisplayName("the qualified spelling cannot itself go ambiguous")
    void theQualifiedFormIsSafeByConstruction() {
        OperationRegistry registry = theRealFrontDoors();
        registry.register("a_second_front_door", List.of("class"));
        // `extract kind=class` is derived from the tool's own name, so a second tool
        // publishing `class` produces `a_second_front_door kind=class` — a different
        // key. That is why the table can always be written in a form the refusal takes.
        assertTrue(registry.ambiguous("class"), "the BARE kind is now shared");
        assertDoesNotThrow(() -> CureCatalog.validateAgainst(registry),
            "and the table, which declares the qualified form, is unaffected");
    }
}
