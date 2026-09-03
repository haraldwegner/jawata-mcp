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
        registry.register("refactor_to_pattern", List.of(
            "inline_singleton", "compose_method", "replace_type_code_with_class",
            "refactor_to_state", "refactor_to_command_dispatcher", "form_template_method",
            "refactor_to_visitor", "replace_pattern_with_idiom",
            "replace_constructor_with_factory", "replace_conditional_with_polymorphism"));
        registry.register("extract", List.of(
            "method", "variable", "constant", "interface", "superclass", "class",
            "replace_inline_code"));
        registry.register("move", List.of("class", "package", "method"));
        registry.register("inline", List.of("method", "variable"));
        registry.register("data", List.of());
        registry.register("hierarchy", List.of("up", "down"));
        return registry;
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
