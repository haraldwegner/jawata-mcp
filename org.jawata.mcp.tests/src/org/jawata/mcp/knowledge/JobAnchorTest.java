package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 28f Stage 7 — a job's anchor FOLLOWS the member it points at.
 *
 * <p>A job is the one kind of row whose subject can be renamed out from under it. An
 * experience survives a rename — it is about something that happened — but a job says
 * what {@code Foo#bar} is FOR, and once {@code bar} becomes {@code baz} the row points
 * at a name the workspace no longer has. It does not fail loudly: it resolves to
 * nothing, drops out of every symbol recall, and reads exactly like a job nobody
 * wrote.</p>
 *
 * <h2>Why this is a NEW verb and not the one that was already there</h2>
 *
 * <p>{@code updateSymbolAnchor(id, symbolFqn)} exists and is not this. It sets ONE row's
 * anchor, addressed by row id, and its three callers are the auto-anchor and the
 * backfill — the paths that GIVE an unanchored row a pointer. This is a MOVE: the caller
 * knows a name changed and does not know, or want to know, which rows were pointing at
 * it.</p>
 *
 * <p>They must not share a name. Both are {@code (String, String)}, so an overload pair
 * would be chosen by what the caller MEANT rather than by anything the compiler can
 * check — and picking the wrong one silently rewrites one row's anchor to a
 * fully-qualified name, or moves every row anchored at a row id, neither of which fails
 * where it happened. That is the by-name-key defect this sprint has already paid for
 * twice.</p>
 *
 * <p><b>Scope, stated so the gap is not read as an oversight:</b> this moves the
 * {@code symbol_fqn} column, which is where an anchor lives. Symbols named in an entry's
 * SCOPE are a second population with their own shape and are not moved here.</p>
 */
class JobAnchorTest {

    private static final String OLD = "com.example.Reports#renderMonthly";
    private static final String NEW = "com.example.Reports#renderMonthlySummary";
    private static final String OTHER = "com.example.Reports#renderDaily";

    private static ExperienceEntry job(String summary, String symbol) {
        return ExperienceEntry.of(
            SymbolFact.of("job", summary, Confidence.MEDIUM).symbol(symbol).build()).build();
    }

    private static String anchorOf(ExperienceStore store, String id) {
        return store.all().stream()
            .filter(e -> id.equals(e.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no row " + id))
            .symbolFqn();
    }

    @Test
    void a_renamed_member_takes_its_job_with_it() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String moved = store.put(
                job("Renders the month's figures for the billing run.", OLD));
            String untouched = store.put(
                job("Renders one day's figures for the same run.", OTHER));
            assertNotNull(moved);
            assertNotNull(untouched);

            assertEquals(1, store.moveSymbolAnchor(OLD, NEW),
                "exactly the one row anchored at the old name moves, and the COUNT says so"
                    + " — a mover that cannot say how many rows it moved cannot be told"
                    + " from one that moved none");

            assertEquals(NEW, anchorOf(store, moved),
                "the job now points at the member's new name");
            assertEquals(OTHER, anchorOf(store, untouched),
                "THE CONTROL: a sibling job on the same type is untouched. Without it, an"
                    + " implementation that rewrote every anchor in the store would satisfy"
                    + " every assertion above");
        }
    }

    @Test
    void a_name_the_store_has_no_job_for_moves_nothing() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String kept = store.put(
                job("Renders one day's figures for the billing run.", OTHER));

            assertEquals(0, store.moveSymbolAnchor("com.example.Nothing#here", NEW),
                "renaming something nobody described is not an error — zero is the answer");
            assertEquals(OTHER, anchorOf(store, kept), "and nothing moved");
        }
    }

    /**
     * THE WRAPPER MUST FORWARD IT. A store running behind the recovering wrapper is the
     * ordinary case, not an edge one, and a verb it silently drops means renames stop
     * following with nothing to say so. Incomplete delegation is the decay pattern this
     * codebase fights first, and a newly added method is exactly where it starts.
     *
     * <p>Forced degraded on purpose — the reopener throws — so the wrapper serves from
     * its own fallback and the test turns on the forwarding rather than on a race with a
     * recovery thread.</p>
     */
    @Test
    void the_recovering_wrapper_forwards_the_move() {
        ExperienceStore store = new RecoveringExperienceStore("test: forced degrade",
            () -> {
                throw new IllegalStateException("stays down for this test");
            }, 3600_000);
        String id = store.put(job("Renders the month's figures for billing.", OLD));
        assertNotNull(id);

        assertEquals(1, store.moveSymbolAnchor(OLD, NEW),
            "the wrapper passes the move through to whatever it is serving from");
        assertEquals(NEW, anchorOf(store, id));
    }
}
