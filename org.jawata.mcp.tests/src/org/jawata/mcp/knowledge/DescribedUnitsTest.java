package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 7 deliverable 3 — the queue an agent describing a codebase walks.
 *
 * <p>The four cases are the plan's own: resume at the next unit, repeat none, a changed unit
 * re-queues, and {@code limit} stops. They are the four properties that make a token-bounded
 * job over a whole bundle finishable — without any one of them the second half of a large
 * bundle is unreachable, because every run starts at the first file again.</p>
 *
 * <p>No project is loaded and none is needed: enumerating a scope's source files is JDT's job
 * and the caller's, and this ledger answers only which of the units it is HANDED are
 * outstanding. That split is what lets these cases be a list of strings rather than a
 * workspace.</p>
 */
class DescribedUnitsTest {

    private static final String BUNDLE = "org.jawata.mcp";
    private static final String A = "src/org/jawata/mcp/A.java";
    private static final String B = "src/org/jawata/mcp/B.java";
    private static final String C = "src/org/jawata/mcp/C.java";

    private H2ExperienceStore store;
    private DescribedUnits ledger;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        ledger = new DescribedUnits(() -> store);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private static DescribedUnits.Unit unit(String path, String text) {
        return DescribedUnits.Unit.of(path, text, BUNDLE);
    }

    private static List<String> pathsOf(List<DescribedUnits.Unit> units) {
        return units.stream().map(DescribedUnits.Unit::path).toList();
    }

    @Test
    @DisplayName("the queue RESUMES at the next unit rather than starting over")
    void the_queue_resumes_at_the_next_unit() {
        List<DescribedUnits.Unit> all =
            List.of(unit(A, "class A {}"), unit(B, "class B {}"), unit(C, "class C {}"));

        assertEquals(List.of(A, B, C), pathsOf(ledger.outstanding(all, 10)),
            "PROOF OF LIFE: nothing is described yet, so everything is outstanding —"
                + " without this the assertion below could pass against a ledger that"
                + " always answers empty");

        assertTrue(ledger.done(A, DescribedUnits.hash("class A {}"), BUNDLE),
            "the write must report that it happened");

        assertEquals(List.of(B, C), pathsOf(ledger.outstanding(all, 10)),
            "the described unit drops out and the walk continues where it left off");
    }

    @Test
    @DisplayName("a fully described scope repeats NOTHING")
    void a_fully_described_scope_repeats_nothing() {
        List<DescribedUnits.Unit> all = List.of(unit(A, "class A {}"), unit(B, "class B {}"));
        for (DescribedUnits.Unit u : all) {
            ledger.done(u.path(), u.contentHash(), u.bundle());
        }

        assertEquals(List.of(), pathsOf(ledger.outstanding(all, 10)),
            "every unit is described at the text it was described at, so there is nothing"
                + " to do — and a queue that re-offered them would spend a whole budget"
                + " re-describing what the store already holds");
    }

    /**
     * THE CASE THE HASH EXISTS FOR. A unit described and then EDITED is not described any
     * more: the jobs derived from it are about text that has changed. Keyed on the path alone
     * this ledger would answer "done" forever — and that answer looks exactly like the
     * correct one, which is why the property is asserted rather than assumed.
     */
    @Test
    @DisplayName("an EDITED unit re-queues, and the hash is what notices")
    void an_edited_unit_re_queues() {
        ledger.done(A, DescribedUnits.hash("class A {}"), BUNDLE);

        List<DescribedUnits.Unit> unchanged = List.of(unit(A, "class A {}"));
        assertEquals(List.of(), pathsOf(ledger.outstanding(unchanged, 10)),
            "the control: the SAME text is still described");

        List<DescribedUnits.Unit> edited = List.of(unit(A, "class A { void added() {} }"));
        assertNotEquals(unchanged.get(0).contentHash(), edited.get(0).contentHash(),
            "the two texts must actually hash differently, or the case below measures"
                + " nothing");
        assertEquals(List.of(A), pathsOf(ledger.outstanding(edited, 10)),
            "the edited unit is outstanding again, by the row disagreeing with the file"
                + " rather than by a sweep somebody has to remember to run");
    }

    @Test
    @DisplayName("limit STOPS the walk, and keeps the caller's order")
    void limit_stops_the_walk() {
        List<DescribedUnits.Unit> all =
            List.of(unit(A, "class A {}"), unit(B, "class B {}"), unit(C, "class C {}"));

        assertEquals(List.of(A, B), pathsOf(ledger.outstanding(all, 2)),
            "two, and the FIRST two the caller listed — the caller enumerated the units and"
                + " knows what a sensible reading order is; a ledger that re-sorted them"
                + " would be choosing the agent's route from a table that knows only what"
                + " is finished");
        assertEquals(List.of(), pathsOf(ledger.outstanding(all, 0)),
            "a limit of zero asks for nothing and gets nothing");
    }

    @Test
    @DisplayName("coverage counts per bundle, and an unattributed unit is NAMED")
    void coverage_counts_per_bundle_and_names_the_unattributed() {
        ledger.done(A, DescribedUnits.hash("class A {}"), BUNDLE);
        ledger.done(B, DescribedUnits.hash("class B {}"), BUNDLE);
        ledger.done(C, DescribedUnits.hash("class C {}"), null);

        Map<String, Long> perBundle = ledger.describedPerBundle();

        assertEquals(2L, perBundle.get(BUNDLE), "two units described in that bundle");
        assertEquals(1L, perBundle.get("unattributed"),
            "a unit described before anyone said whose it was is a REAL state and is named,"
                + " rather than folded into whichever group happens to sort first: "
                + perBundle);
        assertEquals(0L, ledger.failedWrites(),
            "and no write was lost — a count taken over silently dropped rows reads as"
                + " 'these units were never described' when the truth is 'we failed to"
                + " write it down'");
    }
}
