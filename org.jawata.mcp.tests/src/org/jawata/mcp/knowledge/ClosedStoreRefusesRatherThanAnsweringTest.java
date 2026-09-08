package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#38 — <b>a store closed by its owner must refuse, not quietly re-open and
 * answer from an empty database.</b>
 *
 * <p>{@code live()} re-opened the connection whenever it found it closed. For a FILE store
 * that is the intended resilience — an {@code AUTO_SERVER} client whose connection drops
 * re-attaches to the same database and loses nothing. For an IN-MEMORY store the same line
 * builds a <b>fresh, empty</b> database, so a closed store went on answering and every answer
 * was a clean absence from a corpus that never existed.</p>
 *
 * <p><b>Why the in-memory case is not merely a test construct:</b> it is the fallback
 * {@code RecoveringExperienceStore} serves while the real store is unavailable — which is
 * exactly the moment a confident "nothing known" is most expensive. The issue names it as the
 * same class of defect as mcp#37: <i>an answer shaped like an observation that was never
 * observed.</i></p>
 *
 * <p>The two cases are told apart by a {@code closed} flag rather than by inspecting the
 * connection, because the connection looks identical either way — that indistinguishability
 * IS the defect. Terminal by construction, not convention: every {@code open*} is a static
 * factory returning a NEW instance, so nothing re-opens this one, and {@code compact()}
 * reaches for {@code closeQuietly} directly and never passes through {@code close()}.</p>
 *
 * <p><b>What this does NOT cover, said rather than implied:</b> the DROP case — a connection
 * lost without {@code close()} — is not constructed here, because dropping one from outside
 * needs a second process attached to the same file. That branch is untouched by the fix
 * (`conn == null || isClosed() || !isValid(1)` is byte-identical), and the rest of the suite
 * exercises it on every store it opens.</p>
 */
class ClosedStoreRefusesRatherThanAnsweringTest {

    @Test
    @DisplayName("mcp#38: a closed in-memory store REFUSES — it does not answer from a fresh empty database")
    void aClosedStoreRefusesInsteadOfAnsweringAnAbsence() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();

        // THE CONTROL, and it runs FIRST: while open, the store answers. Without it a build
        // that had broken the store entirely would satisfy the refusal below.
        assertNotNull(store.all(), "the control: an OPEN store answers");
        long openCount = store.count();

        store.close();

        IllegalStateException refusal =
            assertThrows(IllegalStateException.class, store::count,
                "a store closed by its owner must REFUSE. Answering here means re-opening an"
                    + " empty in-memory database behind the caller's back, and reporting its"
                    + " emptiness as knowledge");

        assertAll(
            () -> assertTrue(openCount >= 0, "the control measured a real count"),
            // The refusal must say WHICH of the two cases it is, or a reader cannot tell it
            // from the connection genuinely having dropped — the whole point of the fix.
            () -> assertTrue(refusal.getMessage().contains("CLOSED by its owner"),
                "the refusal must name the case; got: " + refusal.getMessage()),
            // And it must say what it is NOT, in the vocabulary the caller is already given
            // for an unavailable store. A refusal read as an absence is the defect returning.
            () -> assertTrue(refusal.getMessage().contains("NOT an absence"),
                "the refusal must say it is not an absence; got: " + refusal.getMessage()));
    }

    @Test
    @DisplayName("mcp#38's control: an open store is untouched — the refusal is not unconditional")
    void anOpenStoreStillAnswers() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            // Two calls, because the first opens the connection and the SECOND is the one
            // that goes through live()'s re-open branch — which is where the flag is read.
            // A one-call test would pass against a flag checked in the wrong place.
            long first = store.count();
            long second = store.count();

            assertAll(
                () -> assertTrue(first >= 0, "an open store answers; got: " + first),
                () -> assertTrue(second >= 0,
                    "and answers again through live()'s re-open branch, which is where the"
                        + " closed flag is read — a refusal firing here would be"
                        + " unconditional and as wrong as the silent re-open it replaced"));
        } finally {
            store.close();
        }
    }
}
