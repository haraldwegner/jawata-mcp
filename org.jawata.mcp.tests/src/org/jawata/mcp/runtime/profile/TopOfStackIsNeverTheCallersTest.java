package org.jawata.mcp.runtime.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C15-1 — what a top-of-stack dimension (cpu, alloc, lock) attributes one event to.
 *
 * <p>Two readers of a stack disagreed on an unresolvable TOP frame: {@code topFrameSymbol}
 * answered null, {@code readableFrames} skipped it and kept walking. The divergence is RIGHT,
 * and this pins why: an execution sample says the top frame was ON the CPU, so giving the sample
 * to the next readable frame would bill CPU time to a caller that was not running; the wall
 * dimension's blocking events want the innermost PROGRAM frame, where skipping is the point.</p>
 *
 * <p>What was wrong is what the null was then called. An unreadable top frame and an event with
 * no stack at all were counted together as {@code samplesWithoutStack} — a stack that WAS
 * recorded reported as one that was not. The C15 audit removed the same fold from the wall
 * dimension's idle time; this removes it here. A {@code RecordedEvent} cannot be built in a
 * test, so — as for {@link JfrParser#innermostProgramFrame} — the decision is a pure function
 * over the frame list, with {@code null} for a frame whose method could not be resolved.</p>
 */
class TopOfStackIsNeverTheCallersTest {

    @Test
    @DisplayName("C15-1: a readable top frame is the answer")
    void aReadableTopIsTheAnswer() {
        assertEquals(new JfrParser.TopOfStack(JfrParser.TopOfStack.Kind.READ, "a.B#run"),
            JfrParser.topOfStack(List.of("a.B#run", "a.C#call")));
    }

    @Test
    @DisplayName("C15-1: an unreadable top frame is NOT given to its caller, and is not 'no stack'")
    void anUnreadableTopIsNeitherTheCallerNorNoStack() {
        assertEquals(new JfrParser.TopOfStack(JfrParser.TopOfStack.Kind.UNREADABLE_TOP, null),
            JfrParser.topOfStack(Arrays.asList(null, "a.C#call")),
            "the sample was on the CPU in a frame nobody can name; billing it to a.C#call would"
                + " report a caller as running, and calling it stackless would deny a stack that"
                + " was recorded");
    }

    @Test
    @DisplayName("C15-1: an empty stack is the only 'no stack'")
    void anEmptyStackIsNoStack() {
        assertEquals(new JfrParser.TopOfStack(JfrParser.TopOfStack.Kind.NO_STACK, null),
            JfrParser.topOfStack(List.of()));
    }
}
