package org.jawata.mcp.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a session header becomes as an identity. Found live with Cursor's agent (Sprint 28h, Stage 24, M17): it sends the header several times, the
 * engine receives the values joined with commas, and an identity that was that whole string never matched the key the agent presents.
 */
class CallerIdentityTest {

    @Test
    @DisplayName("a session header sent several times, which arrives joined with commas, is one identity: the first value")
    void aHeaderSentSeveralTimesIsItsFirstValue() {
        CallerIdentity caller = CallerIdentity.supplied("principal-abc, principal-abc, principal-abc, principal-abc");

        assertEquals("principal-abc", caller.id());
        assertEquals(CallerIdentity.Origin.CLIENT_SUPPLIED, caller.origin());
    }

    @Test
    @DisplayName("a header with one value is left as it came, and a blank one is still refused")
    void aSingleValueIsUnchangedAndABlankOneIsRefused() {
        assertEquals("principal-abc", CallerIdentity.supplied("principal-abc").id());
        assertThrows(IllegalArgumentException.class, () -> CallerIdentity.supplied("  "));
        assertThrows(NullPointerException.class, () -> CallerIdentity.supplied(null));
    }
}
