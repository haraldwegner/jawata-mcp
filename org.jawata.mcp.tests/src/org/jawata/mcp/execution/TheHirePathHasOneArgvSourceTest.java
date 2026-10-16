package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.jawata.core.host.HostCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hire path takes its argv from the brain's dialect and from nothing else (Sprint 28g S16-13).
 *
 * <p>The hire tool used to hand the run a {@link HostCommand} built around a placeholder argv, and
 * the run threw that argv away and asked the dialect. Two sources for one thing meant a caller could
 * set an argv that was silently discarded, which is how S16-12 (a removed {@code binary} input that a
 * caller kept sending) survived a green unit test: a spy that replaces the PROCESS cannot tell that
 * the base's argv was ignored, because it starts a stand-in whatever argv[0] said.</p>
 *
 * <p>The cure is in the type system and not in a check: the hire path's parameter carries where and
 * with what environment, and has no argv to discard. This pins that on the signatures, so a base
 * with an argv cannot come back through a convenient overload.</p>
 */
class TheHirePathHasOneArgvSourceTest {

    @Test
    @DisplayName("no way into a hired run takes a HostCommand, whose argv the run would discard")
    void noEntryIntoAHiredRunTakesAnArgvItWouldDiscard() {
        List<Method> entries = Arrays.stream(HiredRun.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("issue"))
            .toList();
        List<Method> hires = Arrays.stream(HiredBrain.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("hire"))
            .toList();

        // The proof of life: the loop below means nothing over an empty list.
        assertFalse(entries.isEmpty(), "HiredRun declares no issue(...) — this test is looking at nothing");
        assertFalse(hires.isEmpty(), "HiredBrain declares no hire(...) — this test is looking at nothing");

        List<String> offenders = Arrays.asList(entries, hires).stream()
            .flatMap(List::stream)
            .filter(m -> Arrays.asList(m.getParameterTypes()).contains(HostCommand.class))
            .map(m -> m.getDeclaringClass().getSimpleName() + "#" + m.getName())
            .toList();
        assertEquals(List.of(), offenders,
            "these take a HostCommand, so a caller can set an argv the run silently replaces");
    }

    @Test
    @DisplayName("the type that replaced it has no place to put an argv either")
    void theSiteHasNoPlaceForAnArgv() {
        // The test above bans one TYPE. An argv field added to HireSite would put the second source
        // back through the very type that was made to have none, and stay green there.
        List<String> carriers = Arrays.stream(HireSite.class.getRecordComponents())
            .filter(c -> List.class.isAssignableFrom(c.getType()) || c.getType().isArray()
                || c.getName().toLowerCase().contains("argv")
                || c.getName().toLowerCase().contains("command"))
            .map(c -> c.getName() + ":" + c.getType().getSimpleName())
            .toList();
        assertEquals(List.of(), carriers, "a HireSite component that could carry an argv");
    }
}
