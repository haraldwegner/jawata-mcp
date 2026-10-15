package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A seat says which brain it is hired as, and under what bounds (Sprint 28g S16, D37).
 *
 * <p><b>The frontmatter below is the SHIPPED text of jawata-studio's own seats</b>, copied verbatim
 * on 2026-10-08 — multi-line comments, blank tool list, quoted cron and all. It is not a fixture
 * written to match the reader: these four files are the whole population this code has to read
 * today, so a reader that cannot take them is not a reader, and a change to one of them that breaks
 * the hire path breaks here.</p>
 */
class SeatHireTest {

    /** jawata-studio/seats/pm.md, verbatim — note the FOUR-LINE comment inside the frontmatter. */
    private static final String PM = """
        ---
        name: pm
        model: claude-sonnet-5
        kind: code
        # tier justification (the product is a JUDGEMENT — is this stage done, is this instruction
        # unambiguous, may this slice have another attempt — the same family as the architect's and
        # /main's, and wrong here costs a whole stage rather than one finding. No schedule: a human's
        # message or a seat's hand-back is the trigger.)
        effort: high
        tools: [compile_workspace, get_diagnostics, search_symbols, find_references, inspect, analyze, find_quality_issue, experience]
        gates: []
        ttl_secs: 900
        max_iterations: 1
        cost_budget_usd: 2.0
        max_tool_steps: 60
        ---

        You are the PM seat.
        """;

    /** jawata-studio/seats/spec-auditor.md, verbatim — the seat whose tool list is EMPTY. */
    private static final String SPEC_AUDITOR = """
        ---
        name: spec-auditor
        model: claude-sonnet-5
        kind: text
        # tier justification (POLICY, UNMEASURED: adversarial judgment work assigned the strong tier by rule, not by a measured table — no auditing-fixture probe exists yet)
        effort: high
        tools: []
        gates: []
        ttl_secs: 600
        max_iterations: 1
        cost_budget_usd: 2.0
        max_tool_steps: 30
        ---

        You are the spec-auditor seat.
        """;

    /** jawata-studio/seats/architect.md, verbatim — the one with a quoted cron. */
    private static final String ARCHITECT = """
        ---
        name: architect
        model: claude-sonnet-5
        kind: code
        # tier justification (judgment IS the product + the small-tier protocol-adherence weakness family)
        effort: high
        schedule: "0 6 * * *"
        tools: [find_quality_issue, find_references, inspect, analyze, compile_workspace, experience, refactoring]
        gates: []
        ttl_secs: 600
        max_iterations: 1
        cost_budget_usd: 2.0
        max_tool_steps: 80
        ---

        You are the architect seat.
        """;

    /** jawata-studio/seats/test-manager.md, verbatim — the small-tier seat. */
    private static final String TEST_MANAGER = """
        ---
        name: test-manager
        model: claude-haiku-4-5
        kind: code
        # tier justification (every question this seat answers is MECHANICAL — the engine computes
        # reach, collision and mutation survival, and the seat's job is to drive the instrument and
        # report what it said.)
        effort: low
        tools: [run_tests, compile_workspace, get_diagnostics, find_references, get_call_hierarchy, inspect, experience]
        gates: []
        ttl_secs: 600
        max_iterations: 1
        cost_budget_usd: 1.0
        max_tool_steps: 60
        ---

        You are the test-manager seat.
        """;

    @Test
    @DisplayName("a shipped seat's declared bounds reach the hire instead of being read by nothing")
    void theSeatsOwnLimitsBecomeTheHiresCeilings() {
        SeatHire pm = SeatHire.read(PM);

        assertAll(
            () -> assertEquals("pm", pm.seat()),
            () -> assertEquals("claude-sonnet-5", pm.model()),
            // The three limits the seat wrote down and that nothing read before this existed.
            () -> assertEquals(Duration.ofSeconds(900), pm.ceilings().wallClock()),
            () -> assertEquals(60, pm.ceilings().maxToolSteps()),
            () -> assertEquals(2.0, pm.ceilings().maxCostUsd(), 1e-9),
            // And the tool list, in the seat's own order, which is what the dialect attaches.
            () -> assertEquals(List.of("compile_workspace", "get_diagnostics", "search_symbols",
                "find_references", "inspect", "analyze", "find_quality_issue", "experience"),
                pm.tools()));
    }

    @Test
    @DisplayName("a four-line prose comment above the real fields does not disturb them")
    void aCommentDoesNotDisturbTheFieldsBelowIt() {
        SeatHire pm = SeatHire.read(PM);

        // Every shipped seat carries a prose tier-justification comment, and three of its four
        // lines contain a colon.
        //
        // THIS CASE IS WEAKER THAN IT LOOKS AND SAYS SO. A mutation deleting the comment skip left
        // it GREEN, which is the finding: a comment CANNOT shadow a real field, because
        // `# model: x` splits at its own colon into the key "# model" rather than "model". So what
        // keeps the fields below intact is the `#` landing inside the key, not the skip — and an
        // earlier version of this comment claimed the opposite. The clauses below are therefore a
        // REGRESSION LOCK on reading a real seat whole, not a proof that the skip is load-bearing.
        assertAll(
            () -> assertEquals("claude-sonnet-5", pm.model(),
                "the real model survived the comment above it"),
            () -> assertEquals(8, pm.tools().size(),
                "and the real tool list did too: " + pm.tools()),
            () -> assertFalse(pm.tools().stream().anyMatch(t -> t.startsWith("#")),
                "no comment fragment arrived as a tool: " + pm.tools()));
    }

    @Test
    @DisplayName("an EMPTY tool list is empty, not a seat with one blank tool")
    void anEmptyToolListReadsAsNoTools() {
        SeatHire auditor = SeatHire.read(SPEC_AUDITOR);

        // tools: [] is how the spec-auditor says it uses none. A reader splitting "[]" naively
        // yields one empty-string tool, which would reach the CLI as a blank argument after
        // --allowedTools and ask for a tool whose name is nothing.
        assertAll(
            () -> assertEquals(List.of(), auditor.tools()),
            () -> assertEquals("spec-auditor", auditor.seat()),
            () -> assertEquals(30, auditor.ceilings().maxToolSteps()));
    }

    @Test
    @DisplayName("all four shipped seats read, and every one is bounded")
    void everyShippedSeatReads() {
        List<SeatHire> seats = List.of(SeatHire.read(PM), SeatHire.read(SPEC_AUDITOR),
            SeatHire.read(ARCHITECT), SeatHire.read(TEST_MANAGER));

        assertAll(
            () -> assertEquals(List.of("pm", "spec-auditor", "architect", "test-manager"),
                seats.stream().map(SeatHire::seat).toList()),
            // NONE of them may come back unbounded, which is what the whole hire path is for.
            () -> assertTrue(seats.stream().allMatch(s ->
                !s.ceilings().wallClock().isZero() && s.ceilings().maxToolSteps() > 0),
                "every seat carries a wall clock and a step ceiling"),
            // Every shipped seat is a Claude Code seat today, by omission rather than by saying so.
            () -> assertTrue(seats.stream().allMatch(s -> s.brain() == SeatHire.Brain.CLAUDE_CODE),
                "none of them declares a brain, and claude-code is what they all mean"),
            // The architect's quoted cron sits in the same frontmatter and must not disturb it.
            () -> assertEquals(80, seats.get(2).ceilings().maxToolSteps()));
    }

    @Test
    @DisplayName("the brain is NOT derived from the model name, and copilot is why")
    void theBrainIsItsOwnFieldRatherThanTheModelsPrefix() {
        // THE MEASURED CASE. Running copilot on 2026-10-08 produced
        // session.auto_mode_resolved with "chosenModel":"claude-haiku-4.5" — a claude MODEL on the
        // copilot BRAIN, picked by copilot's own router. A reader deriving the brain from the
        // model's prefix would spawn `claude` for this seat and never notice.
        SeatHire copilotSeat = SeatHire.read("""
            ---
            name: router-seat
            brain: copilot
            model: claude-haiku-4.5
            ttl_secs: 600
            max_tool_steps: 20
            ---
            """);

        assertAll(
            () -> assertEquals(SeatHire.Brain.COPILOT, copilotSeat.brain(),
                "the declared brain wins over a model name that looks like another vendor's"),
            () -> assertInstanceOf(CopilotDialect.class, copilotSeat.dialect(null, List.of())),
            // And the model is NOT passed to copilot, because copilot routes its own — passing one
            // would be a flag invented rather than measured.
            () -> assertEquals("claude-haiku-4.5", copilotSeat.model(),
                "the seat's declaration is still carried, for the record"),
            () -> assertFalse(copilotSeat.dialect(null, List.of()).command("brief", List.of())
                .contains("claude-haiku-4.5"),
                "but it does not reach the argv: " + copilotSeat.dialect(null, List.of()).command("brief",
                    List.of())));
    }

    @Test
    @DisplayName("a brain this product cannot start is REFUSED, never defaulted")
    void anUnknownBrainRefusesRatherThanBecomingClaude() {
        // The whole point of the default is that an ABSENT brain means claude-code. A MISSPELLED
        // one must not land in the same place: a seat hired as a brain it did not ask for is worse
        // than one that will not start, because it runs and reports success.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> SeatHire.read("""
                ---
                name: typo-seat
                brain: cursor-agnet
                ---
                """));

        assertAll(
            () -> assertTrue(refused.getMessage().contains("cursor-agnet"),
                "it quotes what was written: " + refused.getMessage()),
            () -> assertTrue(refused.getMessage().contains("claude-code"),
                "and names what it could have meant: " + refused.getMessage()));
    }

    @Test
    @DisplayName("...while the two spellings of one brain are the same brain")
    void aBrainMayBeNamedByItsProductOrItsBinary() {
        // A seat is written by a human who will reasonably write either name. Refusing one would
        // be a rule about our vocabulary rather than about the vendor's.
        assertAll(
            () -> assertEquals(SeatHire.Brain.CURSOR,
                SeatHire.read("---\nname: a\nbrain: cursor\n---\n").brain()),
            () -> assertEquals(SeatHire.Brain.CURSOR,
                SeatHire.read("---\nname: a\nbrain: cursor-agent\n---\n").brain()),
            () -> assertEquals(SeatHire.Brain.CURSOR,
                SeatHire.read("---\nname: a\nbrain:   CURSOR  \n---\n").brain(),
                "case and space are the writer's, not a declaration"),
            () -> assertEquals(SeatHire.Brain.CLAUDE_CODE,
                SeatHire.read("---\nname: a\nbrain: claude\n---\n").brain()));
    }

    @Test
    @DisplayName("S16-6: a seat can hire the script escape hatch, and names the executable it runs")
    void aSeatCanHireAScriptByNamingIt() {
        // ScriptDialect's own javadoc says it exists so a CLI with no dialect is still hireable —
        // and until S16-6 no seat could name it, so the escape hatch was unreachable. The seat
        // file is where an executable may be named (S16-12 removed every other place).
        SeatHire seat = SeatHire.read("""
            ---
            name: fixture-seat
            brain: script
            script: /opt/brains/answer.sh
            ---
            """);

        assertAll(
            () -> assertEquals(SeatHire.Brain.SCRIPT, seat.brain()),
            () -> assertInstanceOf(ScriptDialect.class, seat.dialect(null, List.of())),
            () -> assertEquals(List.of("/opt/brains/answer.sh", "the brief"),
                seat.dialect(null, List.of()).command("the brief", List.of()),
                "the declared executable is what runs, with the prompt as its last argument"));

        // A script reports a cost only by printing COST-USD: lines, so its budget report must
        // say whose the force is — not "cannot fire", which is the answer for cursor and codex.
        SeatHire budgeted = SeatHire.read(
            "---\nname: a\nbrain: script\nscript: /opt/x.sh\ncost_budget_usd: 1.5\n---\n");
        assertTrue(budgeted.budgetReport().orElseThrow().contains("COST-USD"),
            budgeted.budgetReport().orElseThrow());
    }

    @Test
    @DisplayName("S16-6: a script brain with no executable, or an executable on another brain, is refused")
    void aScriptDeclarationThatCannotTakeEffectIsRefused() {
        IllegalArgumentException noExecutable = assertThrows(IllegalArgumentException.class,
            () -> SeatHire.read("---\nname: a\nbrain: script\n---\n"));
        // The S16-12 shape: a declaration the hire would IGNORE lets its writer believe they chose
        // what runs. A script named on a claude seat would be silently dropped, so it is refused.
        IllegalArgumentException ignored = assertThrows(IllegalArgumentException.class,
            () -> SeatHire.read("---\nname: a\nbrain: claude-code\nscript: /opt/x.sh\n---\n"));

        assertAll(
            () -> assertTrue(noExecutable.getMessage().contains("script:"),
                "it says which line is missing: " + noExecutable.getMessage()),
            () -> assertTrue(ignored.getMessage().contains("claude-code"),
                "and names the brain that would ignore it: " + ignored.getMessage()));
    }

    @Test
    @DisplayName("a declared cost budget says whether it can actually fire on the chosen brain")
    void aBudgetReportsWhetherTheBrainCanEnforceIt() {
        // Measured by running all four: only Claude Code publishes a dollar cost. So the same
        // written budget is a real bound on one brain and a dead letter on the other three — and
        // the seat's declaration is legitimate either way, which is why this REPORTS rather than
        // refusing.
        String budgeted = """
            ---
            name: s
            brain: %s
            cost_budget_usd: 2.0
            ---
            """;

        String claude = SeatHire.read(budgeted.formatted("claude")).budgetReport().orElseThrow();
        String cursor = SeatHire.read(budgeted.formatted("cursor")).budgetReport().orElseThrow();

        assertAll(
            () -> assertTrue(claude.contains("ENFORCEABLE"), claude),
            () -> assertTrue(cursor.contains("CANNOT FIRE"), cursor),
            // The run is NOT unbounded on the three that publish no cost, and the report says so
            // rather than leaving a reader to conclude the hire is uncontrolled.
            () -> assertTrue(cursor.contains("wall clock"), cursor),
            // A seat that declared NO budget has nothing to report, which is a different state
            // from a budget that cannot fire — and conflating them would invent a warning.
            () -> assertTrue(SeatHire.read("---\nname: s\nbrain: cursor\n---\n")
                .budgetReport().isEmpty(), "no budget declared, nothing to say"));
    }

    @Test
    @DisplayName("a malformed limit declares NO ceiling rather than a ceiling of zero")
    void anUnreadableNumberIsAbsentNotZero() {
        SeatHire broken = SeatHire.read("""
            ---
            name: s
            ttl_secs: soon
            max_tool_steps: lots
            cost_budget_usd: cheap
            ---
            """);

        assertAll(
            // Zero would be a ceiling of nothing, firing on the first tool call and the first
            // reported cent — a bound nobody wrote, applied as though they had.
            () -> assertEquals(0, broken.ceilings().maxToolSteps(),
                "which HiredBrain reads as 'no step ceiling', per its own contract"),
            () -> assertNull(broken.ceilings().maxCostUsd(),
                "and no cost ceiling at all, rather than $0.00"),
            () -> assertTrue(broken.budgetReport().isEmpty(),
                "so there is no budget to report on"),
            // The wall clock is the one bound that MUST exist, because a run without one never
            // ends. A default is right here and absent is not.
            () -> assertEquals(Duration.ofMinutes(10), broken.ceilings().wallClock(),
                "a hire with no wall clock is an unattended run nobody can stop"));
    }

    @Test
    @DisplayName("each brain gets its own dialect, and only the two that take a model are told one")
    void theDialectFollowsTheDeclaredBrain() {
        String seat = "---\nname: s\nbrain: %s\nmodel: some-model\n---\n";

        assertAll(
            () -> assertInstanceOf(ClaudeCodeDialect.class,
                SeatHire.read(seat.formatted("claude")).dialect(null, List.of())),
            () -> assertInstanceOf(CursorDialect.class,
                SeatHire.read(seat.formatted("cursor")).dialect(null, List.of())),
            () -> assertInstanceOf(CopilotDialect.class,
                SeatHire.read(seat.formatted("copilot")).dialect(null, List.of())),
            () -> assertInstanceOf(CodexDialect.class,
                SeatHire.read(seat.formatted("codex")).dialect(null, List.of())),
            // claude and cursor both accept --model; the model reaches their argv.
            () -> assertTrue(SeatHire.read(seat.formatted("claude")).dialect(null, List.of())
                .command("b", List.of()).contains("some-model")),
            () -> assertTrue(SeatHire.read(seat.formatted("cursor")).dialect(null, List.of())
                .command("b", List.of()).contains("some-model")),
            // codex's model is config, out of band — so nothing here invents a flag for it.
            () -> assertFalse(SeatHire.read(seat.formatted("codex")).dialect(null, List.of())
                .command("b", List.of()).contains("some-model")));
    }

    @Test
    @DisplayName("4.4.1: the seat's own rules are asked of the brain ahead of the brief, and its frontmatter is not")
    void theStanceIsPartOfWhatTheBrainIsAsked() {
        String asked = SeatHire.read(PM).prompt("Decide whether stage 3 is done.");

        assertAll(
            () -> assertTrue(asked.startsWith("You are the PM seat."), asked),
            () -> assertTrue(asked.endsWith("Decide whether stage 3 is done."), asked),
            () -> assertFalse(asked.contains("cost_budget_usd"),
                "the frontmatter is the hire's bounds, not the brain's instructions: " + asked),
            () -> assertFalse(asked.contains("tier justification"),
                "nor its comments: " + asked));
    }

    @Test
    @DisplayName("...a seat with no rules is asked the brief alone, and an unclosed fence holds no rules")
    void noStanceMeansTheBriefAlone() {
        assertAll(
            () -> assertEquals("the brief",
                SeatHire.read("---\nname: s\n---\n").prompt("the brief")),
            // What follows an opening fence that never closes is frontmatter nobody finished, not
            // rules anybody wrote — sending it would hand the brain half a settings block.
            () -> assertEquals("the brief",
                SeatHire.read("---\nname: s\nmodel: x\n").prompt("the brief")));
    }

    @Test
    @DisplayName("a file with no frontmatter at all still refuses for the right reason")
    void aFileWithNoFrontmatterFailsOnItsName() {
        // An empty map rather than a throw inside the reader, so the CALLER decides which absent
        // field is fatal — and only the name and the brain are. This pins which complaint arrives.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> SeatHire.read("You are a seat with no frontmatter.\n"));

        assertTrue(refused.getMessage().contains("no name"), refused.getMessage());
    }

    @Test
    @DisplayName("C11-6: the seat file's on/off switch is read, absent means on, and a misspelling is refused")
    void theOnOffSwitchIsReadStrictlyFromTheSeatFile() {
        // studio writes `enabled: false` under the name line when the user switches a seat off.
        String off = PM.replace("name: pm\n", "name: pm\nenabled: false\n");
        assertAll(
            () -> assertFalse(SeatHire.read(off).enabled(), "enabled: false reads as off"),
            () -> assertTrue(SeatHire.read(PM).enabled(),
                "a shipped seat with no line is on — a seat nobody switched off is on"),
            () -> assertTrue(SeatHire.read(PM.replace("name: pm\n", "name: pm\nenabled: true\n"))
                .enabled(), "and true is on"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> SeatHire.read(PM.replace("name: pm\n", "name: pm\nenabled: no\n")),
                "a switch that is neither true nor false is refused, not read as on"));
    }
}
