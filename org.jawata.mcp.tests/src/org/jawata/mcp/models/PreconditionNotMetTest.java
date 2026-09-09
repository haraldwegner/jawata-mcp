package org.jawata.mcp.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRECONDITION_NOT_MET — the refusal shape for arguments that were VALID and RESOLVED.
 *
 * <p>Found by dogfooding v4.2.0 and corroborated by the field recording, which ranks it the
 * largest CURRENT failure class: 17 of the 52 failures in the three days to 2026-09-09 are
 * {@code INVALID_PARAMETER} on refactoring doors, and {@code inline/class} and
 * {@code inline/subclass} both fired that day. The code was wrong and the hint was worse —
 * it told the caller to re-read the inputSchema for arguments that were already correct.</p>
 */
class PreconditionNotMetTest {

    @Test
    @DisplayName("the code is its own, so a caller can tell a domain refusal from a bad argument")
    void theCodeIsDistinct() {
        ErrorInfo e = ErrorInfo.preconditionNotMet("X holds no field of type Y", "NO_FIELD");
        assertEquals(ErrorInfo.PRECONDITION_NOT_MET, e.getCode());
        assertEquals("NO_FIELD", e.getReason(),
            "the reason is what a test or an agent branches on, per this class's own doctrine");
    }

    @Test
    @DisplayName("the hint does NOT send the caller back to the inputSchema")
    void theHintDoesNotPointAtTheSchema() {
        ErrorInfo e = ErrorInfo.preconditionNotMet("X holds no field of type Y", "NO_FIELD");
        // THE DEFECT, ASSERTED AS AN ABSENCE. invalidParameter's hint reads "Check the tool's
        // inputSchema (tools/list) for the required parameters and their expected shapes."
        // An agent that follows it re-reads a schema in which nothing is wrong, and calls
        // again. The control below proves the needle is real rather than merely missing.
        // THE NEEDLE IS THE DIRECTIVE, NOT THE WORD, and the first version of this assertion
        // got that wrong — it searched for "inputSchema" and went red on this hint's own
        // sentence saying that re-reading the schema will NOT help. A substring cannot tell
        // an instruction from its negation, which is the defect class this repository has
        // recorded repeatedly; the phrase below occurs only in the directive.
        assertFalse(e.getHint().contains("Check the tool's inputSchema"),
            "a domain refusal must not send the caller to fix arguments that were correct: "
                + e.getHint());
        assertTrue(ErrorInfo.invalidParameter("p", "r").getHint().contains("Check the tool's inputSchema"),
            "CONTROL: the needle must actually occur in the hint this one is distinguished"
                + " from, or the assertion above passes on a typo");
        assertTrue(e.getHint().contains("valid and resolved"),
            "and it must say WHY the schema is beside the point: " + e.getHint());
    }

    @Test
    @DisplayName("a refusal that names no precondition is refused at construction")
    void theReasonCodeIsRequired() {
        // The whole point of this code is to be branched on. A refusal carrying none leaves a
        // caller searching prose, which is the defect ErrorInfo's own javadoc records shipping
        // in Stage 5 — a test that passed while the branch it named never ran.
        assertThrows(IllegalArgumentException.class,
            () -> ErrorInfo.preconditionNotMet("something declined", null));
        assertThrows(IllegalArgumentException.class,
            () -> ErrorInfo.preconditionNotMet("something declined", "  "));
    }

    @Test
    @DisplayName("it carries the step that DOES apply, and absence is a real answer")
    void itCarriesItsNextStep() {
        NextStep step = new NextStep("inline kind=class",
            new CodeAddress(null, -1, -1, "com.foo.Bar"), null);
        ErrorInfo with = ErrorInfo.preconditionNotMet("extends nothing", "NO_SUPERCLASS", step);
        assertNotNull(with.getNextStep());
        assertEquals("inline kind=class", with.getNextStep().operation());

        // NULL IS DELIBERATE AND IS NOT AN OVERSIGHT: pointing at an operation that also
        // refuses is the round trip this change exists to end.
        assertEquals(null, ErrorInfo.preconditionNotMet("no smaller step", "NONE").getNextStep());
    }
}
