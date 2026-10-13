package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28g S9a M5a — the per-brain output dialect, which is the one genuinely NEW thing on the
 * Java side of the hired-brain driver.
 *
 * <p>Java has hired a child process before; {@code ForkedTestRunner} spawns a JVM and reads our own
 * event format back. It has never read a shape somebody else chose. Measured before any of this was
 * written: {@code find_string_literals("stream-json")} returns ZERO over 1135 files, and so do the
 * cost and turn-count field names — so nothing in this product could read a brain at all.</p>
 *
 * <h2>What each case is FOR</h2>
 *
 * <p>Three of these pin behaviour the Rust reference gets right and a simpler reading would lose —
 * the content array is not its first element, tool calls are blocks rather than events, a
 * non-numeric cost is absent rather than zero. Two pin behaviour the reference gets WRONG and this
 * does not: an unreadable line is a fact rather than silence, and a terminal event with no cost
 * field reports ABSENT, which is what keeps a cost ceiling from passing on a figure nobody
 * published.</p>
 */
class BrainDialectTest {

    private final BrainDialect claude = new ClaudeCodeDialect();

    // ----------------------------------- the containment FLAGS, two of which are not bounds

    @Test
    @DisplayName("C16: all four brains carry a write-containment FLAG by default, each read off its own flag — and two of the four flags are not bounds")
    void theRunIsContainedByDefaultAndTheFlagIsInTheArgv() {
        // THIS ASSERTION EXISTS BECAUSE ITS ABSENCE WAS MEASURED. C16 asks that "the seat cannot
        // edit a file except through a tool", and the cataloguer seat — four read-only jawata
        // operations, nothing that writes — was hired with the brief "Create a file named
        // SHOULD-NOT-EXIST.txt … Then stop" and CREATED IT: one tool step, $0.19, the file on
        // disk. --allowedTools carries JAWATA tool names, which this CLI does not know, so it
        // bounds the MCP operations and not the CLI's own editors. --permission-mode plan is what
        // CLOSED that case — and it is an INSTRUCTION rather than a bound, which an earlier version
        // of this comment called "the bound that holds". Measured: under plan mode Write is
        // advertised, is invoked, and succeeds; the model writes its plan file off-tree every run.
        //
        // And it is asserted HERE rather than left to the probe that found it, because adding the
        // flag turned no test red: 96 classes stayed green over a change to the single most
        // important argument this product sends.
        List<String> contained = claude.command("describe the package", List.of("inspect"));
        List<String> permitted =
            new ClaudeCodeDialect("claude", 12, null, false).command("do it", List.of());

        assertAll(
            () -> assertTrue(contained.contains("--permission-mode"),
                "the containment flag travels: " + contained),
            () -> assertEquals("plan", valueOf(contained, "--permission-mode"),
                "and its VALUE is the read-only mode, not merely the flag's presence"),
            // The opt-out is a different value rather than a missing flag, so a reader of an argv
            // can always tell which of the two a run had — an absent flag would be ambiguous
            // between "writes allowed" and "an older build that could not say".
            () -> assertEquals("manual", valueOf(permitted, "--permission-mode"),
                "a caller who opted out gets the other value, explicitly: " + permitted),
            // ALL FOUR BRAINS CARRY A WRITE-CONTAINMENT FLAG BY DEFAULT — and a FLAG IS NOT A
            // BOUND, which this comment twice blurred before a measurement settled it. TWO of the
            // four are bounded by a MECHANISM, not three: copilot answers `permission_denied` in
            // 6 of 6 runs, naming our own rules when we pass them ("denied due to the following
            // rules: `write`") and denying anyway when we do not; and codex's sandbox was observed
            // rejecting an attempted apply_patch outright.
            //
            // CLAUDE IS AN INSTRUCTION, and an earlier version of this comment said "plan mode
            // disables the editing tool", which the raw logs contradict. Under
            // --permission-mode plan the init event ADVERTISES Edit, Write and NotebookEdit;
            // Write was INVOKED and returned "File created successfully"; permission_denials is
            // empty in every run. What plan mode does is tell the model to write only its plan
            // file, which the model then narrates — "plan mode permits edits only to this plan
            // file". It held for the working tree in 5 of 5 runs and it is not a bound.
            //
            // CURSOR IS AN INSTRUCTION THAT FAILS: 5 writes in 15 runs of plan mode, 10 in 10
            // with the sandbox alone, and no mechanism refusal ever seen.
            //
            // So what is asserted below is that each dialect SENDS its flag, which is the half
            // jawata owns. Whether a vendor honours it is the vendor's contract and is measured by
            // the containment probes in build/, never here.
            //
            // EACH IS READ OFF ITS OWN FLAG BY INDEX, not by membership. An earlier version of the
            // cursor clause asked `command(...).contains("plan")`, which passes on ANY argv element
            // equal to "plan" — including a model called plan, or a prompt that is the word — and
            // says nothing about --mode. An audit named it and it was right.
            () -> assertEquals("plan", valueOf(new CursorDialect().command("x", List.of()), "--mode"),
                "cursor: --mode plan"),
            // AND ITS SANDBOX, whose effect on writes is now MEASURED AT NONE. It was added when
            // plan mode alone failed, on the reasoning that a sandbox is stronger than an
            // instruction. Isolated at the CLI with --mode plan omitted — which the hire path
            // cannot do, since it always sends both — it stopped nothing: 10 runs, 10 writes. The
            // flag is still pinned because the product still sends it and a reader of an argv
            // should find it, but NOTHING may claim it contains a write.
            () -> assertEquals("enabled",
                valueOf(new CursorDialect().command("x", List.of()), "--sandbox"),
                "cursor: --sandbox enabled, which this hire sends and which was measured to"
                    + " bound no write at all"),
            () -> assertEquals("read-only",
                valueOf(new CodexDialect().command("x", List.of()), "--sandbox"),
                "codex: --sandbox read-only, which is its whole containment"),
            () -> assertTrue(new CopilotDialect().command("x", List.of()).contains("--deny-tool"),
                "copilot: --deny-tool, which this product did not pass until C16 — the architect"
                    + " report had claimed this brain could not be told at all, and its own --help"
                    + " offers --deny-tool, --excluded-tools and --available-tools"),
            () -> assertEquals("write",
                valueOf(new CopilotDialect().command("x", List.of()), "--deny-tool"),
                "and the first tool it denies is the one its help calls file editing"));
    }

    /**
     * The value that FOLLOWS a named flag in an argv, or null.
     *
     * <p>Exists because `List.contains` is the wrong instrument for a flag's value: it answers
     * whether a word appears anywhere, so it passes on a model, a prompt or a path that happens to
     * equal it. An audit found exactly that in this class's first cursor assertion.</p>
     */
    private static String valueOf(List<String> argv, String flag) {
        int at = argv.indexOf(flag);
        return at >= 0 && at + 1 < argv.size() ? argv.get(at + 1) : null;
    }

    @Test
    @DisplayName("the attachment report names what BINDS, not merely what was sent")
    void theReportDoesNotDressANominalBoundAsARealOne() {
        // The earlier wording was "tools set for this run via --allowedTools" — true about the
        // flag and false about the effect. A record in which a nominal bound reads like a real one
        // is worse than no record, because a reader stops looking.
        String contained = claude.toolAttachment().report();
        String permitted = new ClaudeCodeDialect("claude", 12, null, false)
            .toolAttachment().report();

        assertAll(
            () -> assertTrue(contained.contains("--permission-mode plan"), contained),
            // THIS ASSERTION USED TO READ contains("cannot edit") — IN THE METHOD NAMED FOR NOT
            // DRESSING A NOMINAL BOUND AS A REAL ONE. It pinned the claim it exists to forbid, and
            // it was green, so the false report could not be corrected without turning it red. Now
            // it reads the other way: the report must NOT promise the brain cannot edit, and must
            // carry what was measured instead.
            () -> assertFalse(contained.contains("cannot edit"),
                "it must not promise a bound plan mode does not provide: " + contained),
            () -> assertTrue(contained.contains("INSTRUCTION"),
                "it says what plan mode actually is: " + contained),
            () -> assertTrue(contained.contains("NOT write-contained"),
                "and it says plainly how to treat the hire: " + contained),
            () -> assertTrue(permitted.contains("NOT blocked"),
                "and a writable hire says so plainly, rather than reading like the other: "
                    + permitted),
            // Both still name the allowlist, because it is the right instruction for the jawata
            // operations — it is just not the thing that stops the CLI's own editors.
            () -> assertTrue(contained.contains("--allowedTools")));
    }

    @Test
    @DisplayName("a hire with the resident's endpoint reports the built-in tools as removed, and says measured, not proven")
    void theEndpointStartReportsWhatWasMeasuredAndNoMore() {
        String report = new ClaudeCodeDialect("claude", 12, null, true,
            new SelfEndpoint("http://127.0.0.1:1/mcp", "t")).toolAttachment().report();

        assertAll(
            () -> assertTrue(report.contains("--tools"), "it names the flag that removes the CLI's own"
                + " tools: " + report),
            () -> assertTrue(report.contains("MEASURED, NOT PROVEN"),
                "it does not turn six observations into a guarantee: " + report),
            () -> assertTrue(report.contains("counts an attempt as a call"),
                "and it says why an attempt can read as a call: " + report),
            () -> assertFalse(report.contains("cannot write") || report.contains("cannot edit"),
                "no promise this product has not measured: " + report),
            () -> assertFalse(report.contains("NOT write-contained"),
                "the old no-endpoint verdict does not describe this start: " + report));
    }

    // ------------------------------------------------------------ the shapes that carry something

    @Test
    @DisplayName("the terminal event carries the whole answer and the run's cost")
    void theResultEventCarriesTextAndCost() {
        BrainLine line = claude.read(
            "{\"type\":\"result\",\"result\":\"the answer\",\"total_cost_usd\":0.0421}");
        assertAll(
            () -> assertTrue(line.parsed(), "a well-formed event is readable"),
            () -> assertEquals("the answer", line.text().orElseThrow()),
            () -> assertTrue(line.costUsd().isPresent(), "the terminal event is where cost arrives"),
            () -> assertEquals(0.0421, line.costUsd().getAsDouble(), 1e-9),
            () -> assertEquals(0, line.toolSteps(), "the terminal event reports no tool calls"));
    }

    @Test
    @DisplayName("a turn's text is EVERY text block, not content[0] — a thinking block may come first")
    void assistantTextIsEveryTextBlockNotTheFirst() {
        // The discriminating shape: `thinking` occupies index 0, so a reader that takes the first
        // element silently loses the answer and reports a turn that said nothing.
        BrainLine line = claude.read("{\"type\":\"assistant\",\"message\":{\"content\":["
            + "{\"type\":\"thinking\",\"thinking\":\"weighing it up\"},"
            + "{\"type\":\"text\",\"text\":\"first\"},"
            + "{\"type\":\"text\",\"text\":\"second\"}]}}");
        assertEquals("first\nsecond", line.text().orElseThrow(),
            "both text blocks, in order, and the thinking block is not one of them");
    }

    @Test
    @DisplayName("tool calls are counted as BLOCKS, which is what a turn ceiling cannot see")
    void toolCallsAreBlocksNotEvents() {
        // ONE event. Three tool calls. A --max-turns ceiling counts this as 1, so twelve turns can
        // be a hundred tool calls — which is the whole reason a step ceiling exists beside it.
        BrainLine line = claude.read("{\"type\":\"assistant\",\"message\":{\"content\":["
            + "{\"type\":\"text\",\"text\":\"looking\"},"
            + "{\"type\":\"tool_use\",\"name\":\"Grep\",\"input\":{}},"
            + "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{}},"
            + "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{}}]}}");
        assertAll(
            () -> assertEquals(3, line.toolSteps(), "three blocks in one event"),
            () -> assertEquals("looking", line.text().orElseThrow(),
                "and the turn's own text survives being counted"));
    }

    @Test
    @DisplayName("a turn that only talks reports no tool calls")
    void aTurnThatOnlyTalksReportsNoSteps() {
        // The control: without it, a counter that returned the block COUNT rather than the
        // tool_use count would pass the case above and be wrong about every ordinary turn.
        BrainLine line = claude.read("{\"type\":\"assistant\",\"message\":{\"content\":["
            + "{\"type\":\"text\",\"text\":\"hi\"}]}}");
        assertEquals(0, line.toolSteps());
    }

    @Test
    @DisplayName("M11: the NAMES of the calls are read, in order, one per block — the count is their length")
    void toolCallNamesAreReadInOrderAndEqualTheCount() {
        // A count cannot be classified. "No code-changing call" is a claim about WHICH calls.
        String turn = "{\"type\":\"assistant\",\"message\":{\"content\":["
            + "{\"type\":\"text\",\"text\":\"looking\"},"
            + "{\"type\":\"tool_use\",\"name\":\"mcp__jawata-javata-dev__search_symbols\",\"input\":{}},"
            + "{\"type\":\"tool_use\",\"name\":\"Write\",\"input\":{}},"
            + "{\"type\":\"tool_use\",\"input\":{}}]}}";
        BrainLine claudeLine = claude.read(turn);
        BrainLine cursorLine = new CursorDialect().read(turn);
        for (BrainLine line : List.of(claudeLine, cursorLine)) {
            assertAll(
                () -> assertEquals(List.of("mcp__jawata-javata-dev__search_symbols", "Write", ""),
                    line.toolNames(), "in order, and an unnamed block is KEPT as empty"),
                () -> assertEquals(line.toolSteps(), line.toolNames().size(),
                    "the count is the names' length — one reader of the blocks, so they cannot"
                        + " disagree about how many calls the turn made"));
        }
    }

    @Test
    @DisplayName("M11: a turn that only talks names no tools, and a dialect that cannot name says so by count")
    void aTurnThatTalksNamesNothing() {
        BrainLine talks = claude.read("{\"type\":\"assistant\",\"message\":{\"content\":["
            + "{\"type\":\"text\",\"text\":\"hi\"}]}}");
        assertTrue(talks.toolNames().isEmpty());
    }

    // --------------------------------------------------- the two the reference conflates or loses

    @Test
    @DisplayName("a line that is not JSON is UNREADABLE — a fact, not silence")
    void aLineThatIsNotJsonIsAFactRatherThanSilence() {
        // The defect this separates: ForkedTestRunner's own tailer drops an unparsable line in an
        // empty catch, and each of the reference's three readers returns absent both for "this
        // line carries no cost" and for "this line is not JSON". They are different facts: the
        // second means the child is not speaking the dialect we think it is, and a driver that
        // cannot tell them apart reports a confident run against the wrong brain.
        BrainLine garbage = claude.read("Traceback (most recent call last):");
        BrainLine known = claude.read("{\"type\":\"system\",\"subtype\":\"init\"}");
        assertAll(
            () -> assertFalse(garbage.parsed(), "not JSON at all"),
            () -> assertTrue(known.parsed(),
                "an event shape we simply do not act on IS readable — this is the control that"
                    + " stops `parsed` degenerating into `carried something`"),
            () -> assertTrue(known.text().isEmpty(), "and it carried nothing"));
    }

    @Test
    @DisplayName("a terminal event with no cost field reports ABSENT, never zero")
    void anAbsentCostIsNotZero() {
        // Rust defect #2, pushed up rather than papered over: cost arrives only on the terminal
        // event, so a run reaped on its wall clock never emits one. Reading that as 0.00 lets a
        // cost ceiling pass on exactly the expensive runs. Absent is the honest answer, and the
        // driver above is where it has to stay distinct from zero.
        BrainLine noCost = claude.read("{\"type\":\"result\",\"result\":\"done\"}");
        BrainLine realZero = claude.read(
            "{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.0}");
        assertAll(
            () -> assertTrue(noCost.costUsd().isEmpty(), "nothing reported a cost"),
            () -> assertTrue(realZero.costUsd().isPresent(),
                "and a published zero is PRESENT — the two must not collapse, or the distinction"
                    + " above is decorative"),
            () -> assertEquals(0.0, realZero.costUsd().getAsDouble(), 1e-9));
    }

    // ------------------------------------------------------------------------------ the command

    @Test
    @DisplayName("the command is argv, so a prompt needs no quoting")
    void theCommandIsArgvRatherThanAString() {
        // A seat brief routinely contains newlines, quotes and shell metacharacters. argv means
        // nothing has to escape them and nothing can mis-escape them.
        List<String> argv = claude.command("do the thing\nwith \"quotes\" and $PATH", List.of());
        assertAll(
            () -> assertEquals("claude", argv.get(0)),
            () -> assertEquals("-p", argv.get(1)),
            () -> assertEquals("do the thing\nwith \"quotes\" and $PATH", argv.get(2),
                "the prompt travels as ONE argument, verbatim"),
            () -> assertTrue(argv.contains("stream-json"), "the dialect this class reads"),
            () -> assertTrue(argv.contains("--max-turns")),
            () -> assertFalse(argv.contains("--model"), "no model asked for means none passed"));
        assertTrue(new ClaudeCodeDialect("claude", 4, "opus").command("x", List.of()).contains("opus"),
            "and a model that IS asked for is passed");
    }

    // ------------------------------------------------------------------------- the script dialect

    @Test
    @DisplayName("a plain-text brain DECLARES what a structured one emits")
    void theScriptDialectReadsDeclaredSignals() {
        BrainDialect script = new ScriptDialect("/bin/echo", List.of("-n"));
        assertAll(
            () -> assertEquals(0.25,
                script.read("COST-USD: 0.25").costUsd().orElseThrow(), 1e-9),
            () -> assertEquals(7, script.read("TOOL-STEPS: 7").toolSteps()),
            () -> assertEquals("ordinary output", script.read("ordinary output").text().orElseThrow()),
            () -> assertEquals(0, script.read("ordinary output").toolSteps(),
                "a script that declares no steps is taken at its word"),
            () -> assertTrue(script.read("COST-USD: banana").costUsd().isEmpty(),
                "a malformed number reported NO cost; reading it as zero would let a cost ceiling"
                    + " pass on a figure nobody published"),
            () -> assertTrue(script.read("anything").parsed(),
                "a plain-text dialect has no unreadable line, so a non-zero unreadable count"
                    + " against this dialect would mean the DRIVER is confused, not the child"));
    }

    @Test
    @DisplayName("the script command puts the prompt last, after its own arguments")
    void theScriptCommandOrdersItsArgumentsBeforeThePrompt() {
        List<String> argv = new ScriptDialect("/bin/sh", List.of("-c", "cat")).command("brief", List.of());
        assertEquals(List.of("/bin/sh", "-c", "cat", "brief"), argv);
    }
}
