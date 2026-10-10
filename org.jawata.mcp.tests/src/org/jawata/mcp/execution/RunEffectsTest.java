package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M11 — "the PM seat performs 0 operations the engine classifies as changing code", read off a
 * run's tool calls.
 *
 * <p>The engine's own flag is a PARAMETER here, so a case can say what the engine says without
 * dragging the registry in, and the class is held to holding no tool list of its own. Every case
 * that expects a zero also builds the same run with one call that should not be zero, because a
 * classifier that returned "none" for everything would pass every zero-case alone.</p>
 */
class RunEffectsTest {

    /** What the engine says: {@code extract} changes code and has an undo; the others do not. */
    private static final Map<String, Boolean> ENGINE = Map.of(
        "extract", true,
        "search_symbols", false,
        "find_references", false,
        "experience", false);

    private static final Function<String, Optional<Boolean>> ASK =
        name -> Optional.ofNullable(ENGINE.get(name));

    @Test
    @DisplayName("a run of jawata reads is a COMPLETE zero")
    void aRunOfReadsIsACompleteZero() {
        RunEffects.Classification c = RunEffects.classify(
            List.of("mcp__jawata-javata-dev__search_symbols", "find_references", "Read", "Grep"),
            0, ASK);
        assertAll(
            () -> assertTrue(c.performedNoCodeChangingOperation()),
            () -> assertTrue(c.zeroIsComplete()),
            () -> assertEquals(4, c.readOnly().size()));
    }

    @Test
    @DisplayName("the CONTROL: the same run plus one engine-flagged operation is not a zero")
    void oneRevertibleOperationIsNotAZero() {
        RunEffects.Classification c = RunEffects.classify(
            List.of("search_symbols", "mcp__jawata-javata-dev__extract"), 0, ASK);
        assertAll(
            () -> assertFalse(c.performedNoCodeChangingOperation()),
            () -> assertEquals(List.of("mcp__jawata-javata-dev__extract"), c.changesCode(),
                "classified by the ENGINE's flag after the mcp__server__ prefix is stripped"));
    }

    @Test
    @DisplayName("a brain CLI's own editor changes code even though the engine has never heard of it")
    void aCliEditorChangesCode() {
        for (String editor : List.of("Write", "Edit", "MultiEdit", "NotebookEdit", "apply_patch")) {
            assertEquals(List.of(editor), RunEffects.classify(List.of(editor), 0, ASK).changesCode(),
                editor);
        }
    }

    @Test
    @DisplayName("a shell is NEITHER code-changing NOR harmless: it is reported on its own, and the zero is incomplete")
    void aShellIsNotFoldedIntoEitherSide() {
        RunEffects.Classification c = RunEffects.classify(List.of("Bash", "search_symbols"), 0, ASK);
        assertAll(
            () -> assertEquals(List.of("Bash"), c.shell()),
            () -> assertTrue(c.performedNoCodeChangingOperation(),
                "by the engine's classification alone nothing changed code"),
            () -> assertFalse(c.zeroIsComplete(),
                "but that zero cannot be trusted while a shell ran, which the flag says"));
    }

    @Test
    @DisplayName("an unclassifiable name and an unnamed call each make the zero incomplete")
    void whatCouldNotBeClassifiedIsNotAbsorbedIntoTheZero() {
        RunEffects.Classification unknownName =
            RunEffects.classify(List.of("some_vendor_tool"), 0, ASK);
        RunEffects.Classification unnamed = RunEffects.classify(List.of("search_symbols"), 2, ASK);
        RunEffects.Classification emptyName = RunEffects.classify(List.of(""), 0, ASK);
        assertAll(
            () -> assertEquals(List.of("some_vendor_tool"), unknownName.unknown()),
            () -> assertFalse(unknownName.zeroIsComplete()),
            () -> assertTrue(unnamed.performedNoCodeChangingOperation()),
            () -> assertFalse(unnamed.zeroIsComplete(),
                "two calls were counted and never named — a dialect that cannot name its calls"
                    + " must not read as one whose calls were harmless"),
            () -> assertEquals(List.of("(unnamed block)"), emptyName.unknown(),
                "a tool_use block with no name stays visible instead of vanishing"));
    }

    @Test
    @DisplayName("the engine's answer wins over the CLI sets: a jawata tool named like a CLI tool is the engine's")
    void theEngineIsAskedFirst() {
        Function<String, Optional<Boolean>> engineSaysWriteIsReadOnly =
            name -> "Write".equals(name) ? Optional.of(false) : Optional.empty();
        assertEquals(List.of("Write"),
            RunEffects.classify(List.of("Write"), 0, engineSaysWriteIsReadOnly).readOnly());
    }
}
