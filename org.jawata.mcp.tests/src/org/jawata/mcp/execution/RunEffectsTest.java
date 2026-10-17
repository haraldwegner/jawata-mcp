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
 * dragging the registry in. The CLI's own tool names ARE a fixed list in {@code RunEffects}, and
 * the cases below pin its editor, shell and reader sets by example. The zero-cases are paired with
 * a control only where the pair is named: {@code aRunOfReadsIsACompleteZero} with
 * {@code oneRevertibleOperationIsNotAZero}, and the shell, unnamed and unknown cases each assert
 * their own incompleteness directly — a classifier that returned "none" for everything would
 * fail those.</p>
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
        RunEffects.Classification c = RunEffects.classify(List.of("mcp__jawata-javata-dev__search_symbols", "find_references", "Read", "Grep"), 0, ASK, Optional.empty());
        assertAll(
            () -> assertTrue(c.performedNoCodeChangingOperation()),
            () -> assertTrue(c.incompleteBecause().isEmpty()),
            () -> assertEquals(4, c.readOnly().size()));
    }

    @Test
    @DisplayName("the CONTROL: the same run plus one engine-flagged operation is not a zero")
    void oneRevertibleOperationIsNotAZero() {
        RunEffects.Classification c = RunEffects.classify(List.of("search_symbols", "mcp__jawata-javata-dev__extract"), 0, ASK, Optional.empty());
        assertAll(
            () -> assertFalse(c.performedNoCodeChangingOperation()),
            () -> assertEquals(List.of("mcp__jawata-javata-dev__extract"), c.changesCode(),
                "classified by the ENGINE's flag after the mcp__server__ prefix is stripped"));
    }

    @Test
    @DisplayName("a call to a tool the CLI never OFFERED is an attempt that could not run, set aside and listed")
    void anAttemptAtAnUnofferedToolIsNotACall() {
        // MEASURED 2026-10-13: a PM hired with `--tools ""` (the CLI's init listed no built-in tool)
        // still named Write, Bash and Read in its turns; every one was refused, none ran, and the
        // record read "the run made 1 code-changing call(s)".
        Optional<List<String>> offered = Optional.of(List.of("mcp__jawata__search_symbols"));
        RunEffects.Classification c = RunEffects.classify(
            List.of("mcp__jawata__search_symbols", "Write", "Bash", "Read"), 0, ASK, offered);

        assertAll(
            () -> assertEquals(List.of("Write", "Bash", "Read"), c.notOffered(),
                "listed, never dropped"),
            () -> assertTrue(c.changesCode().isEmpty(), "a refused Write is not a code change"),
            () -> assertTrue(c.shell().isEmpty(), "and a refused Bash is not a shell that ran"),
            () -> assertTrue(c.performedNoCodeChangingOperation()),
            () -> assertTrue(c.incompleteBecause().isEmpty(),
                "so the zero is COMPLETE: " + c.incompleteBecause()));
    }

    @Test
    @DisplayName("the CONTROL: a Write the CLI DID offer still changes code, and no offer list changes nothing")
    void anOfferedWriteStillCountsAndNoOfferListKeepsTheOldCounting() {
        RunEffects.Classification offeredWrite = RunEffects.classify(List.of("Write"), 0, ASK,
            Optional.of(List.of("Write")));
        RunEffects.Classification noList = RunEffects.classify(List.of("Write", "Bash"), 0, ASK,
            Optional.empty());
        RunEffects.Classification emptyOffer = RunEffects.classify(List.of(""), 0, ASK,
            Optional.of(List.of()));

        assertAll(
            () -> assertEquals(List.of("Write"), offeredWrite.changesCode()),
            () -> assertTrue(offeredWrite.notOffered().isEmpty()),
            // A dialect that reads no offer (codex, copilot, cursor) keeps exactly the old reading.
            () -> assertEquals(List.of("Write"), noList.changesCode()),
            () -> assertEquals(List.of("Bash"), noList.shell()),
            () -> assertTrue(noList.notOffered().isEmpty()),
            // An unnamed block cannot be matched against an offer, so it stays unclassified.
            () -> assertEquals(List.of("(unnamed block)"), emptyOffer.unknown()));
    }

    @Test
    @DisplayName("a brain CLI's own editor changes code even though the engine has never heard of it")
    void aCliEditorChangesCode() {
        for (String editor : List.of("Write", "Edit", "MultiEdit", "NotebookEdit", "apply_patch")) {
            assertEquals(List.of(editor), RunEffects.classify(List.of(editor), 0, ASK, Optional.empty()).changesCode(),
                editor);
        }
    }

    @Test
    @DisplayName("the worker's measure: the share of code changes made through a tool, a hand edit counting against it")
    void theShareThroughToolsCountsAHandEditAgainstTheWorker() {
        // Three changes through the engine's tool, one by the CLI's editor: 3 of 4, and the reads change nothing.
        RunEffects.Classification mixed = RunEffects.classify(
            List.of("mcp__jawata-javata-dev__extract", "extract", "Read", "extract", "Edit", "search_symbols"), 0, ASK, Optional.empty());
        // All through the tool: 1.0. All by hand: 0.0. These are the two ends a raw call count cannot tell apart from reads.
        RunEffects.Classification allTool = RunEffects.classify(List.of("extract", "search_symbols", "search_symbols"), 0, ASK, Optional.empty());
        RunEffects.Classification allHand = RunEffects.classify(List.of("Write", "search_symbols"), 0, ASK, Optional.empty());
        // No change at all has NO share, which is neither 1.0 nor 0.0.
        RunEffects.Classification none = RunEffects.classify(List.of("search_symbols", "Read"), 0, ASK, Optional.empty());
        // A shell changes code by a command this class cannot see: counted on neither side, named beside the figure.
        RunEffects.Classification withShell = RunEffects.classify(List.of("extract", "Bash"), 0, ASK, Optional.empty());
        assertAll(
            () -> assertEquals(3, mixed.changesThroughTools()),
            () -> assertEquals(1, mixed.changesByHand()),
            () -> assertEquals(0.75, mixed.shareThroughTools().getAsDouble(), 1e-9),
            () -> assertEquals(1.0, allTool.shareThroughTools().getAsDouble(), 1e-9),
            () -> assertEquals(0.0, allHand.shareThroughTools().getAsDouble(), 1e-9),
            () -> assertTrue(none.shareThroughTools().isEmpty(), "no code change means no share"),
            () -> assertEquals(1.0, withShell.shareThroughTools().getAsDouble(), 1e-9),
            () -> assertFalse(withShell.incompleteBecause().isEmpty(), "but the shell is named beside the share"));
    }

    @Test
    @DisplayName("a shell is NEITHER code-changing NOR harmless: it is reported on its own, and the zero is incomplete")
    void aShellIsNotFoldedIntoEitherSide() {
        RunEffects.Classification c = RunEffects.classify(List.of("Bash", "search_symbols"), 0, ASK, Optional.empty());
        assertAll(
            () -> assertEquals(List.of("Bash"), c.shell()),
            () -> assertTrue(c.performedNoCodeChangingOperation(),
                "by the engine's classification alone nothing changed code"),
            () -> assertFalse(c.incompleteBecause().isEmpty(),
                "but that zero cannot be trusted while a shell ran, which the flag says"));
    }

    @Test
    @DisplayName("an unclassifiable name and an unnamed call each make the zero incomplete")
    void whatCouldNotBeClassifiedIsNotAbsorbedIntoTheZero() {
        RunEffects.Classification unknownName =
            RunEffects.classify(List.of("some_vendor_tool"), 0, ASK, Optional.empty());
        RunEffects.Classification unnamed = RunEffects.classify(List.of("search_symbols"), 2, ASK, Optional.empty());
        RunEffects.Classification emptyName = RunEffects.classify(List.of(""), 0, ASK, Optional.empty());
        assertAll(
            () -> assertEquals(List.of("some_vendor_tool"), unknownName.unknown()),
            () -> assertFalse(unknownName.incompleteBecause().isEmpty()),
            () -> assertTrue(unnamed.performedNoCodeChangingOperation()),
            () -> assertFalse(unnamed.incompleteBecause().isEmpty(),
                "two calls were counted and never named — a dialect that cannot name its calls"
                    + " must not read as one whose calls were harmless"),
            () -> assertEquals(List.of("(unnamed block)"), emptyName.unknown(),
                "a tool_use block with no name stays visible instead of vanishing"));
    }

    @Test
    @DisplayName("a FOREIGN server's tool is never matched to a jawata tool of the same bare name")
    void aForeignServersToolDoesNotInheritAJawataFlag() {
        // "search_symbols" is a read the engine knows. Under a foreign server it must not be
        // looked up by its bare name: a foreign tool of that name could write.
        RunEffects.Classification c = RunEffects.classify(List.of("mcp__some-other-server__search_symbols",
		"mcp__jawata-patterns__search_symbols"), 0, ASK, Optional.empty());
        assertAll(
            () -> assertEquals(List.of("mcp__some-other-server__search_symbols"), c.unknown()),
            () -> assertEquals(List.of("mcp__jawata-patterns__search_symbols"), c.readOnly(),
                "the control: a jawata server's prefix IS stripped"),
            () -> assertFalse(c.incompleteBecause().isEmpty()));
    }

    @Test
    @DisplayName("the sub-agent tool is unknown: what a sub-agent does is not in this stream")
    void aSubAgentIsNotAHarmlessRead() {
        RunEffects.Classification c = RunEffects.classify(List.of("Task"), 0, ASK, Optional.empty());
        assertAll(
            () -> assertEquals(List.of("Task"), c.unknown()),
            () -> assertFalse(c.incompleteBecause().isEmpty()));
    }

    @Test
    @DisplayName("the engine's answer wins over the CLI sets: a jawata tool named like a CLI tool is the engine's")
    void theEngineIsAskedFirst() {
        Function<String, Optional<Boolean>> engineSaysWriteIsReadOnly =
            name -> "Write".equals(name) ? Optional.of(false) : Optional.empty();
        assertEquals(List.of("Write"),
            RunEffects.classify(List.of("Write"), 0, engineSaysWriteIsReadOnly, Optional.empty()).readOnly());
    }
}
