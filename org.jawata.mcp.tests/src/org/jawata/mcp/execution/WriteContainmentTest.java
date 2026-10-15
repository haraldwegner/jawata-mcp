package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jawata.mcp.execution.WriteContainment.Basis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What stops a hired brain writing, as each dialect answers it (Sprint 28g S16, the architect's F1).
 *
 * <p>Five rows (S16-19, -25, -29, -32, -35) were one failure: a property asserted about a MECHANISM
 * that was really an INSTRUCTION to the model, running the flattering way. The discriminator they
 * converge on is whether something other than the model can refuse a write. Each dialect already
 * holds the fact that answers it, so each now answers it, with no default — a fifth brain cannot
 * ship without saying.</p>
 */
class WriteContainmentTest {

    private static final SelfEndpoint SELF = new SelfEndpoint("http://127.0.0.1:1/mcp", "t");

    @Test
    @DisplayName("each basis owes the sentence that makes it checkable, and a blank one is refused")
    void eachBasisOwesItsSentence() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                () -> new WriteContainment(Basis.MECHANISM, null, null),
                "a mechanism that names no refusal point is a claim nobody can check"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new WriteContainment(Basis.MECHANISM, "  ", null)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new WriteContainment(Basis.INSTRUCTION, null, null),
                "an instruction must say what bounds the run instead"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new WriteContainment(Basis.NONE, null, " "),
                "and so must a brain with no containment at all"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new WriteContainment(null, "x", "y")),
            () -> assertEquals(Basis.MECHANISM,
                new WriteContainment(Basis.MECHANISM, "a flag", null).basis()));
    }

    @Test
    @DisplayName("only a mechanism is containment; an instruction and nothing are not")
    void onlyAMechanismContains() {
        assertAll(
            () -> assertTrue(WriteContainment.mechanism("--x").isContained()),
            () -> assertEquals(false, WriteContainment.instruction("--mode plan", "n").isContained()),
            () -> assertEquals(false, WriteContainment.none("by request").isContained()));
    }

    @Test
    @DisplayName("claude: the editors are absent WITH the resident's endpoint; without it, an instruction")
    void claude() {
        assertAll(
            () -> assertEquals(Basis.MECHANISM,
                new ClaudeCodeDialect("claude", 5, null, true, SELF).writeContainment().basis()),
            () -> assertTrue(new ClaudeCodeDialect("claude", 5, null, true, SELF)
                    .writeContainment().enforcedBy().contains("resident's own tools stay attached"),
                "the mechanism is scoped to the CLI's own editors: the resident's code-changing"
                    + " tools remain, and the report must not read as if all writes were refused"),
            () -> assertEquals(Basis.INSTRUCTION,
                new ClaudeCodeDialect().writeContainment().basis(),
                "--permission-mode plan alone was measured to let Write succeed"),
            () -> assertEquals(Basis.NONE,
                new ClaudeCodeDialect("claude", 5, null, false).writeContainment().basis()));
    }

    @Test
    @DisplayName("codex: the sandbox is the mechanism, and only while it is read-only")
    void codex() {
        assertAll(
            () -> assertEquals(Basis.MECHANISM, new CodexDialect().writeContainment().basis()),
            () -> assertEquals(Basis.NONE,
                new CodexDialect("codex", "workspace-write").writeContainment().basis()));
    }

    @Test
    @DisplayName("cursor: --mode plan is an instruction (5 breaches in 15), and a writable cursor has nothing")
    void cursor() {
        assertAll(
            () -> assertEquals(Basis.INSTRUCTION, new CursorDialect().writeContainment().basis()),
            () -> assertEquals(Basis.NONE,
                new CursorDialect("cursor-agent", false, null).writeContainment().basis()));
    }

    @Test
    @DisplayName("copilot: the vendor's headless denial is the mechanism; our deny flags state intent only")
    void copilot() {
        WriteContainment readOnly = new CopilotDialect().writeContainment();
        assertAll(
            () -> assertEquals(Basis.MECHANISM, readOnly.basis()),
            () -> assertTrue(readOnly.enforcedBy().contains("headless"),
                "it names the vendor's default as what refuses: " + readOnly.enforcedBy()),
            () -> assertEquals(Basis.NONE,
                new CopilotDialect("copilot", true, List.of(), false).writeContainment().basis()));
    }

    @Test
    @DisplayName("a script has no containment of the hire's making")
    void script() {
        assertEquals(Basis.NONE, new ScriptDialect("/bin/sh", List.of()).writeContainment().basis());
    }

    @Test
    @DisplayName("THE SWEEP (S16-35): every --flag an answer cites is in that dialect's argv")
    void everyFlagAnAnswerCitesIsInTheArgv() {
        record Case(String label, BrainDialect dialect) { }
        List<Case> cases = List.of(
            new Case("claude+endpoint", new ClaudeCodeDialect("claude", 5, null, true, SELF)),
            new Case("claude", new ClaudeCodeDialect()),
            new Case("codex", new CodexDialect()),
            new Case("cursor", new CursorDialect()),
            new Case("copilot", new CopilotDialect()));
        Pattern flag = Pattern.compile("--[a-z][a-z-]*");
        for (Case c : cases) {
            WriteContainment answer = c.dialect().writeContainment();
            String cited = String.valueOf(answer.enforcedBy()) + " " + answer.boundedInstead();
            List<String> argv = c.dialect().command("brief", List.of());
            Matcher m = flag.matcher(cited);
            while (m.find()) {
                assertTrue(argv.contains(m.group()), c.label() + " cites " + m.group()
                    + " as what bounds a write, and its argv does not carry it: " + argv);
            }
        }
    }
}
