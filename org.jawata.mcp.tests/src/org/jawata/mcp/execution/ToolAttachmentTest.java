package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28g S16, D36 — a brain is told which tools to attach, and a brain that CANNOT be told says
 * so rather than being left out of the count.
 *
 * <p>The clause this class exists for is the last one, and it is narrow: <b>"cannot be told which
 * tools" is not the same claim as "cannot be contained"</b>. cursor-agent cannot be handed a tool
 * list and CAN be told read-only; a report that stops at <i>unsupported</i> would have a reader
 * conclude the brain is unusable. So the refusal below is not input validation — it is the one
 * place the narrower claim is forced to be stated.</p>
 *
 * <p>Pure: no process, no network, no POSIX fixture. Every case is a fact about the record or about
 * a shipped dialect's measured answer.</p>
 */
class ToolAttachmentTest {

    @Test
    @DisplayName("a brain that cannot be told must say what DOES contain it")
    void cannotBeToldRefusesABlankContainment() {
        // All three spellings, because an implementor who did not check leaves behind whichever
        // their language reaches for first — and a single-case test would pass against a rule that
        // only caught that one.
        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                () -> ToolAttachment.cannotBeTold(null),
                "null is what an implementor leaves behind when they skipped the field"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ToolAttachment.cannotBeTold(""),
                "an empty string is the same omission, spelled differently"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ToolAttachment.cannotBeTold("   "),
                "whitespace is an empty answer that passes a null check"));
    }

    @Test
    @DisplayName("...and the measured nothing IS admissible, written as a word")
    void cannotBeToldAcceptsTheWordNone() {
        // THE CONTROL FOR THE CASE ABOVE, and it is what makes that refusal mean something. If a
        // negative answer were also refused, the rule would read as "every brain must be
        // containable", which is a claim about the world rather than about the report. What is
        // refused is the ABSENCE of an answer; "none" is an answer.
        ToolAttachment none = ToolAttachment.cannotBeTold("none");

        assertAll(
            () -> assertEquals(ToolAttachment.Reach.NONE, none.reach()),
            () -> assertTrue(none.report().contains("none"),
                "the report carries the measured answer rather than dropping it"));
    }

    @Test
    @DisplayName("a brain reported as TOLD must name what told it")
    void aSettableReachRefusesABlankMechanism() {
        // A run's record is read by someone deciding whether to trust an unattended proposal. "its
        // tools were set" with no flag, file or subcommand named cannot be verified by them, so it
        // is worth nothing and is refused rather than printed.
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> ToolAttachment.perRun(null)),
            () -> assertThrows(IllegalArgumentException.class, () -> ToolAttachment.perRun("  ")),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ToolAttachment.outOfBand(null)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ToolAttachment.outOfBand("")));
    }

    @Test
    @DisplayName("only PER_RUN is settable by the hirer — OUT_OF_BAND is settable by somebody else")
    void onlyPerRunIsTheHirersToDecide() {
        // This is why the two are not one enum constant. Both mean "the tools CAN be set"; only one
        // means the hirer can set them for THIS brief. Folding them would make a promise the
        // out-of-band case cannot keep — it can REPORT what is attached and cannot change it.
        assertAll(
            () -> assertTrue(ToolAttachment.perRun("--allowedTools").settableByTheHirer()),
            () -> assertFalse(ToolAttachment.outOfBand("codex mcp").settableByTheHirer(),
                "configured before the hire is not the same as decided per brief"),
            () -> assertFalse(ToolAttachment.cannotBeTold("none").settableByTheHirer()));
    }

    @Test
    @DisplayName("the three reaches cannot be confused by a reader skimming a journal")
    void eachReachReportsItselfDistinctly() {
        String perRun = ToolAttachment.perRun("--allowedTools").report();
        String outOfBand = ToolAttachment.outOfBand("codex mcp").report();
        String none = ToolAttachment.cannotBeTold("--mode plan, which is read-only").report();

        assertAll(
            () -> assertEquals(3, List.of(perRun, outOfBand, none).stream().distinct().count(),
                "three reaches, three sentences"),
            // The out-of-band line must not read as a choice the hirer made, which is the one way
            // that reach can mislead: the tools ARE set, and by somebody else.
            () -> assertTrue(outOfBand.contains("reported, not chosen"),
                "an out-of-band attachment is reported rather than chosen: " + outOfBand),
            // AND THE CLAUSE THIS CLASS IS FOR. The NONE line has to carry the containment, or it
            // says "unsupported" and a reader downgrades a usable brain to an unusable one.
            () -> assertTrue(none.contains("--mode plan"),
                "the NONE report must name the containment it DOES offer: " + none));
    }

    @Test
    @DisplayName("the two shipped dialects answer with their measured reach")
    void theShippedDialectsReportWhatWasMeasured() {
        // Measured 2026-10-08 from each CLI's own --help on the development machine, not assumed.
        ToolAttachment claude = new ClaudeCodeDialect().toolAttachment();
        ToolAttachment script = new ScriptDialect("/bin/true", List.of()).toolAttachment();

        assertAll(
            () -> assertEquals(ToolAttachment.Reach.PER_RUN, claude.reach()),
            () -> assertTrue(claude.mechanism().contains("--allowedTools"),
                "the flag is the verifiable part of the claim: " + claude.mechanism()),
            // A script's argv was fixed when somebody configured it, so the hirer cannot name its
            // tools — and the hire's own ceilings still bound it, which is what it must say.
            () -> assertEquals(ToolAttachment.Reach.NONE, script.reach()),
            () -> assertFalse(script.containmentInstead().isBlank()),
            () -> assertTrue(script.containmentInstead().contains("ceiling"),
                "a script IS bounded, by the hire rather than by a flag: "
                    + script.containmentInstead()));
    }

    @Test
    @DisplayName("a told brain gets the tool names as separate arguments, never comma-joined")
    void theAllowlistTravelsAsSeparateArguments() {
        List<String> argv = new ClaudeCodeDialect()
            .command("a brief", List.of("Read", "Grep", "compile_workspace"));

        int flag = argv.indexOf("--allowedTools");
        assertAll(
            () -> assertTrue(flag >= 0, "the flag is present when tools were asked for: " + argv),
            // A comma-joined single argument is the shape that silently attaches ONE tool named
            // "Read,Grep,compile_workspace" — accepted by the CLI, wrong, and invisible.
            () -> assertEquals(List.of("Read", "Grep", "compile_workspace"),
                argv.subList(flag + 1, argv.size()),
                "each name is its own argument: " + argv),
            () -> assertFalse(String.join(" ", argv).contains("Read,Grep"),
                "nothing joined them: " + argv));
    }

    @Test
    @DisplayName("...and an empty list adds no flag at all, which is not the same as an empty set")
    void anEmptyListAsksForNothingInParticular() {
        // Two different instructions, and the difference is not cosmetic: `--allowedTools` with
        // nothing after it hands the CLI a flag with a missing value. Asking for nothing in
        // particular leaves a brain its configured tools; asking for an empty SET would not.
        assertAll(
            () -> assertFalse(new ClaudeCodeDialect().command("a brief", List.of())
                .contains("--allowedTools"), "no tools asked for, so no flag"),
            () -> assertFalse(new ClaudeCodeDialect().command("a brief", null)
                .contains("--allowedTools"), "and null is treated as the same ask"));
    }

    @Test
    @DisplayName("a dialect that cannot be told DROPS the list rather than inventing a flag")
    void aScriptIgnoresTheToolsItCannotPass() {
        ScriptDialect script = new ScriptDialect("/bin/true", List.of("--fixed"));

        List<String> withTools = script.command("a brief", List.of("Read", "Grep"));
        List<String> without = script.command("a brief", List.of());

        assertAll(
            () -> assertEquals(without, withTools,
                "the argv is the same either way — the list is dropped, not encoded"),
            () -> assertEquals(List.of("/bin/true", "--fixed", "a brief"), withTools),
            // And the dropping is REPORTED, which is the whole point: a dialect that silently
            // dropped the list while the run reported the tools as attached is the defect D36's
            // reporting clause exists against.
            () -> assertFalse(script.toolAttachment().settableByTheHirer()));
    }
}
