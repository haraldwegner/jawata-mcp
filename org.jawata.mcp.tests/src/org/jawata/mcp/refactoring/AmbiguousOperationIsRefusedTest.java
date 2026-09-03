package org.jawata.mcp.refactoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CONTROL FOR THE AMBIGUITY REFUSAL.
 *
 * <p>Three front doors publish a kind called {@code method} and two publish
 * {@code class}. The registry used to keep one tool per operation, so the last to
 * register silently won and a cure naming a bare {@code method} would have rendered an
 * invocation against whichever tool happened to be constructed last — a real call, to
 * the wrong tool, with nothing failing.</p>
 *
 * <p>The refusal that replaced it was shipped without a test, and it needed one for a
 * specific reason: it has exactly one production reader, and the registry that reader is
 * handed in every existing test is synthesised one-tool-per-operation, so nothing in it
 * can ever BE ambiguous. Deleting the whole refusal left the suite green. A check whose
 * removal changes nothing is not yet a check.</p>
 *
 * <p>Every case here builds its own registry rather than the shared one. The singleton
 * is application state, and a test that empties it to make a point leaves every later
 * test in the same JVM reading a registry that no longer describes the product.</p>
 */
class AmbiguousOperationIsRefusedTest {

    /** Two tools publishing one kind name — the shape the whole class is about. */
    private static OperationRegistry twoToolsOneKind() {
        OperationRegistry registry = new OperationRegistry();
        registry.register("extract", List.of("method", "class"));
        registry.register("move", List.of("class", "package"));
        return registry;
    }

    @Test
    @DisplayName("a name two tools publish is ambiguous, and says which two")
    void ambiguityIsNamedRatherThanResolved() {
        OperationRegistry registry = twoToolsOneKind();

        assertTrue(registry.has("class"), "it is published — that is not in question");
        assertTrue(registry.ambiguous("class"),
            "and it is published by two tools, which is what makes a bare mention unclear");
        assertEquals(Set.of("extract", "move"), registry.toolsFor("class"),
            "both publishers must be named; a caller cannot choose between tools it"
                + " cannot see");
        assertNull(registry.toolFor("class"),
            "and the single-tool answer must be NULL rather than a guess. Returning"
                + " either one would be a real invocation against the wrong tool.");
    }

    @Test
    @DisplayName("a name only one tool publishes is not ambiguous — the control for the control")
    void anUnsharedKindIsStillAnswered() {
        OperationRegistry registry = twoToolsOneKind();
        assertFalse(registry.ambiguous("package"),
            "only `move` publishes it, so there is nothing to be unclear about");
        assertEquals("move", registry.toolFor("package"));
        assertEquals("move kind=package", registry.invocationOf("package"),
            "and an unambiguous kind renders through its front door");
    }

    @Test
    @DisplayName("the qualified form is registered, so an ambiguous kind can still be named")
    void theQualifiedFormIsUnambiguousByConstruction() {
        OperationRegistry registry = twoToolsOneKind();
        assertTrue(registry.has("extract kind=class"),
            "the qualified spelling is registered alongside the bare one — a cure table"
                + " has to be able to declare something the refusal will accept");
        assertFalse(registry.ambiguous("extract kind=class"));
        assertEquals("extract kind=class", registry.invocationOf("extract kind=class"),
            "and it renders as itself rather than being qualified twice");
    }

    @Test
    @DisplayName("an ambiguous bare kind renders unchanged rather than picking a tool")
    void renderingRefusesToGuess() {
        OperationRegistry registry = twoToolsOneKind();
        assertEquals("class", registry.invocationOf("class"),
            "there is no single right answer here, so the text stays as it was."
                + " Composing `extract kind=class` or `move kind=class` would be the"
                + " confident wrong answer the registry exists to stop.");
    }

    @Test
    @DisplayName("an operation nothing publishes is not invented")
    void anUnknownOperationIsReturnedUnchanged() {
        OperationRegistry registry = twoToolsOneKind();
        assertFalse(registry.has("teleport"));
        assertEquals("teleport", registry.invocationOf("teleport"),
            "an unknown name gets no front door attached to it");
    }

    @Test
    @DisplayName("what a tool declares about its operations is what the registry answers")
    void classificationTravelsWithRegistration() {
        OperationRegistry registry = new OperationRegistry();
        registry.register("move", List.of("class", "package", "method"),
            /* mechanical */ true, /* structural */ false, Set.of("method"));
        registry.register("find_quality_issue", List.of(), false, false, Set.of());

        assertTrue(registry.isMechanical("move"), "a refactoring preserves behaviour");
        assertTrue(registry.isStructural("move kind=method"),
            "moving a method rewrites its call sites — the declaration the architect"
                + " gate reads");
        assertFalse(registry.isStructural("move kind=package"),
            "relocating a package changes no signature");
        assertFalse(registry.isStructural("method"),
            "and the BARE kind is never classified: three tools publish `method`, so"
                + " classifying it would make an extracted local method structural"
                + " because a moved instance method is");
        assertFalse(registry.isMechanical("find_quality_issue"),
            "a tool that answers questions changes nothing");
        assertFalse(registry.isMechanical("teleport"),
            "and an unregistered name is not mechanical — the safe direction, because"
                + " the coverage advisory then ASKS for a test rather than exempting an"
                + " operation it knows nothing about");
    }
}
