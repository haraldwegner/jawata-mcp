package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D13 — the PM's upward gate refuses the three shapes {@code pm.md} names, and ONLY those.
 *
 * <p>Each refusal is paired with a control that is the same message made whole, so a gate that
 * refuses everything cannot pass this class.</p>
 */
class UpwardMessageGateTest {

    private static final String WHOLE = """
        The brief is above.

        **Upward message**

        - **Fact.** The edit is not done; the change is safe and executes in three calls.
        - **Cause.** This seat has no code-changing operation, which is the enforcement of the
          no-writing rule and not a preference.
        - **Cost.** One hand-off of a three-call brief.
        """;

    @Test
    @DisplayName("an answer with no upward message owes none, so the gate passes")
    void noMessageIsNoRefusal() {
        assertEquals(List.of(), UpwardMessageGate.refusals("Here is the brief. Nothing to escalate."));
        assertEquals(List.of(), UpwardMessageGate.refusals(null));
    }

    @Test
    @DisplayName("the control: a message with fact, cause and cost, no count reason and no loose relation word")
    void aWholeMessagePasses() {
        assertEquals(List.of(), UpwardMessageGate.refusals(WHOLE));
    }

    @Test
    @DisplayName("a message with no cost is refused for exactly that")
    void aMissingCostIsRefused() {
        String noCost = WHOLE.replaceAll("(?s)- \\*\\*Cost\\.\\*\\*.*", "");
        List<String> refusals = UpwardMessageGate.refusals(noCost);

        assertEquals(1, refusals.size(), String.valueOf(refusals));
        assertTrue(refusals.get(0).contains("COST"), refusals.get(0));
    }

    @Test
    @DisplayName("a message with none of the three labelled elements is refused three times")
    void aMessageWithNoElementsIsRefusedForEach() {
        List<String> refusals = UpwardMessageGate.refusals(
            "## Upward\n\nThe edit did not happen and I am not sure what you want me to do about it.");

        assertEquals(3, refusals.size(), String.valueOf(refusals));
        assertTrue(refusals.stream().anyMatch(r -> r.contains("FACT")));
        assertTrue(refusals.stream().anyMatch(r -> r.contains("CAUSE")));
        assertTrue(refusals.stream().anyMatch(r -> r.contains("COST")));
    }

    @Test
    @DisplayName("a cause that opens with a number is a count given as the reason")
    void aCountLedCauseIsRefused() {
        List<String> refusals = UpwardMessageGate.refusals(
            WHOLE.replace("This seat has no code-changing operation, which is the enforcement of the\n"
                + "  no-writing rule and not a preference.", "9 findings are open and 4 are old."));

        assertEquals(1, refusals.size(), String.valueOf(refusals));
        assertTrue(refusals.get(0).contains("COUNT"), refusals.get(0));
    }

    @Test
    @DisplayName("a cause given as 'because <number> <things>' is a count given as the reason")
    void aBecauseCountIsRefused() {
        List<String> refusals = UpwardMessageGate.refusals(
            WHOLE.replace("This seat has no code-changing operation", "It stopped because 3 tests were red"));

        assertTrue(refusals.stream().anyMatch(r -> r.contains("COUNT")), String.valueOf(refusals));
    }

    @Test
    @DisplayName("a relation word with no relation named is refused; the same word naming its relation passes")
    void anUnnamedRelationWordIsRefusedANamedOneIsNot() {
        List<String> unnamed = UpwardMessageGate.refusals(
            WHOLE.replace("The edit is not done", "A similar defect was seen. The edit is not done"));
        List<String> named = UpwardMessageGate.refusals(
            WHOLE.replace("The edit is not done", "The text is the same as linux:55. The edit is not done"));

        assertEquals(1, unnamed.size(), String.valueOf(unnamed));
        assertTrue(unnamed.get(0).contains("RELATION WORD") && unnamed.get(0).contains("similar"),
            unnamed.get(0));
        assertEquals(List.of(), named, "'same as linux:55' names the relation");
    }
}
