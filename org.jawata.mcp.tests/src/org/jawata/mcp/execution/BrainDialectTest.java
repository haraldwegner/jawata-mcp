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
        List<String> argv = claude.command("do the thing\nwith \"quotes\" and $PATH");
        assertAll(
            () -> assertEquals("claude", argv.get(0)),
            () -> assertEquals("-p", argv.get(1)),
            () -> assertEquals("do the thing\nwith \"quotes\" and $PATH", argv.get(2),
                "the prompt travels as ONE argument, verbatim"),
            () -> assertTrue(argv.contains("stream-json"), "the dialect this class reads"),
            () -> assertTrue(argv.contains("--max-turns")),
            () -> assertFalse(argv.contains("--model"), "no model asked for means none passed"));
        assertTrue(new ClaudeCodeDialect("claude", 4, "opus").command("x").contains("opus"),
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
        List<String> argv = new ScriptDialect("/bin/sh", List.of("-c", "cat")).command("brief");
        assertEquals(List.of("/bin/sh", "-c", "cat", "brief"), argv);
    }
}
