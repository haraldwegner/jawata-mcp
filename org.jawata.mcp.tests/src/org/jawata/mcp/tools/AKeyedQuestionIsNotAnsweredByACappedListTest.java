package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.StoredEntry;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g, Stage 14 / D30 — <b>a question about one exact anchor must not be answered by
 * filtering a capped, time-ordered list</b>, because a cap and a writing instruction cannot
 * coexist.
 *
 * <h2>The defect</h2>
 *
 * <p>Four callers asked {@code listEntries(type, null, scope, null, limit)} whether a
 * particular anchor carried a row, and re-filtered the page in Java. That read's scope clause
 * is {@code symbol_fqn = ? OR symbol_fqn LIKE scope + ".%"} plus the same pair on
 * {@code package_name}, ordered {@code created_at DESC} under a {@code LIMIT} — so a
 * prefix-shaped scope fills the page with OTHER anchors' rows and the one asked about can sit
 * outside it. Every caller then read "not on the page" as "does not exist".</p>
 *
 * <p><b>And all three keyed callers act on the absence.</b> Two tell an agent to describe the
 * member or the package; the third is D30's own write gate, which REPLACES an existing row and
 * INSERTS when it finds none. <b>Reachable rather than observed</b>, and the distinction is the
 * point: an anchor comes from the caller and nothing validates its shape, so a type- or
 * package-shaped one fills the page — but the duplicates measured in this store are
 * member-anchored, which cannot prefix-match, and predate that gate. This paragraph claimed the
 * gate "minted them itself"; a round-2 audit struck it, because the fold that corrected that
 * tense elsewhere left it standing here.</p>
 *
 * <h2>Why the fix is a new read and not a bigger window</h2>
 *
 * <p>Three repairs shipped before this one and each only moved which rows the window hid: a
 * corpus-wide window, then a per-member window, then that same window plus an
 * {@code undetermined} state for the case where it was full. The third is the tell and it was
 * shipped as a fix — a state meaning <i>the cap hid this</i> is an admission that the read
 * cannot answer the question. {@link ExperienceStore#anchoredAt} is bounded by the anchors
 * ASKED ABOUT, so there is no window, no third state, and an absent key is an absence.</p>
 *
 * <h2>Which cases carry a control, stated per case because the blanket claim was false</h2>
 *
 * <p>This section read <i>"Every case here is paired with the OLD read over the identical store,
 * asserted to return a full page that does NOT contain the row"</i>. An audit measured it:
 * <b>three of the six called the old read and two asserted a full page</b>, and the
 * {@code jobsFor} case had no precondition at all, so it passed against the defective code the
 * moment the fixture stopped burying. The claim was written in the paragraph whose subject is
 * that an unfalsifiable assertion is worthless. The missing precondition is now in place; what
 * each case actually carries is listed here rather than asserted as a uniform property:</p>
 *
 * <ul>
 *   <li><b>FOUR cases call the old read; THREE of them assert the page was FULL</b> — so a
 *       fixture that stops reaching the defect fails loudly instead of reading as "nothing is
 *       there". The fourth, the write-gate case, asserts the narrower clause that the row is
 *       OUTSIDE the page, which is as strong here: under this fixture a row can only be absent
 *       from a fifty-row page if fifty newer rows filled it.
 *       <br><b>This bullet said all four assert a full page.</b> A round-2 audit counted them
 *       and it was three — the second consecutive round in which this list's own arithmetic was
 *       wrong, in the paragraph written to correct the first wrong version of it. The count is
 *       now per clause rather than per group, because grouping is what hid it both times.</li>
 *   <li><b>{@code everyAnchorAskedAboutIsAnswered} has no pairing and cannot have one</b>: its
 *       subject is a window on the ANSWER rather than on the matched rows, which the old read
 *       had no form of. Its discriminator is the mutation that re-adds a {@code LIMIT}.</li>
 *   <li><b>{@code anAbsenceIsStillAnAbsence} passes identically against old and new code by
 *       design</b> — it is the opposite control, and without it every assertion here is
 *       satisfied by a read that claims everything is described.</li>
 * </ul>
 */
class AKeyedQuestionIsNotAnsweredByACappedListTest {

    /**
     * The old per-anchor window. Hard-coded rather than imported, deliberately: the constant it
     * mirrors was DELETED by this fold, and a test that read the live value would start passing
     * for the wrong reason the day someone reintroduced one.
     */
    private static final int OLD_WINDOW = 50;

    /** Enough prefix-matching noise to fill that window and leave the target outside it. */
    private static final int NOISE = 60;

    /** A TYPE-shaped anchor, whose nested members' jobs prefix-match it. */
    private static final String TYPE_ANCHOR = "com.example.keyed.Big";

    /** A package-shaped anchor — the shape the live `describe action=area` defect was measured on. */
    private static final String PACKAGE_ANCHOR = "com.example.keyed";

    private static final String THE_JOB =
        "Settles the ledger against the broker and refuses to trade while they disagree";

    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------------ fixtures

    private static ExperienceEntry jobOn(String summary, String symbol) {
        return ExperienceEntry.of(SymbolFact.of("job", summary, Confidence.MEDIUM)
                .symbol(symbol).build())
            .scopeKind("symbol")
            .status(ExperienceEntry.ACCEPTED)
            .build();
    }

    private static ExperienceEntry areaOn(String summary, String pkg) {
        return ExperienceEntry.of(SymbolFact.of("area", summary, Confidence.MEDIUM)
                .scope(List.of(pkg), List.of()).build())
            .scopeKind("package")
            .status(ExperienceEntry.ACCEPTED)
            .build();
    }

    /**
     * Make the target the OLDEST row by a margin, rather than by luck.
     *
     * <p>The control below asserts the target falls off the end of a newest-first page, which is
     * only exact if its timestamp is strictly older than every noise row's. Written in one tight
     * loop they can share a millisecond, and the control would then pass or fail by scheduling —
     * the shape this repository already recorded as a Windows-only prune flake. Ten milliseconds
     * makes the ordering a fact instead of a probability, and each control states its own
     * precondition so a tie fails loudly rather than reading as a pass.</p>
     */
    private static void thenLater() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The target job at a TYPE anchor, then enough nested-member jobs to bury it. */
    private static void buryAJobUnderItsOwnNestedMembers(ExperienceStore store) {
        store.put(jobOn(THE_JOB, TYPE_ANCHOR));
        thenLater();
        for (int i = 0; i < NOISE; i++) {
            store.put(jobOn("Describes nested member " + i + " of the same type.",
                TYPE_ANCHOR + ".Inner#m" + i));
        }
    }

    /** The target area at a PACKAGE anchor, then enough sub-package areas to bury it. */
    private static void buryAnAreaUnderItsOwnSubPackages(ExperienceStore store) {
        store.put(areaOn("What this package is FOR.", PACKAGE_ANCHOR));
        thenLater();
        for (int i = 0; i < NOISE; i++) {
            store.put(areaOn("A sub-package " + i + ".", PACKAGE_ANCHOR + ".sub" + i));
        }
    }

    /** The old read, verbatim, including the Java-side re-filter every caller performed. */
    private static List<StoredEntry> theOldListSift(ExperienceStore store, String type,
            String anchor) {
        return store.listEntries(type, null, anchor, null, OLD_WINDOW);
    }

    // ------------------------------------------------------------------ the store read

    @Test
    @DisplayName("D30: the keyed read finds a row the capped list read provably cannot see")
    void theKeyedReadFindsARowTheListReadCannotSee(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            buryAJobUnderItsOwnNestedMembers(store);

            List<StoredEntry> page = theOldListSift(store, "job", TYPE_ANCHOR);
            boolean onThePage = page.stream().anyMatch(e -> TYPE_ANCHOR.equals(e.symbolFqn()));

            Map<String, List<StoredEntry>> keyed = store.anchoredAt("job",
                ExperienceStore.Anchor.SYMBOL, List.of(TYPE_ANCHOR));

            assertAll(
                // THE CONTROL, and its two clauses are separate claims. The page being FULL is
                // what makes the second clause mean "truncated" rather than "the store is empty".
                () -> assertEquals(OLD_WINDOW, page.size(),
                    () -> "THE CONTROL's precondition: the old read's page is FULL, so the row"
                        + " below is absent because it was truncated and not because nothing is"
                        + " there. A short page here means the fixture stopped reaching the"
                        + " defect and every assertion in this class is measuring nothing."
                        + " Got: " + page.size()),
                () -> assertFalse(onThePage,
                    () -> "THE CONTROL: the old read cannot see the row. " + NOISE + " nested"
                        + " members' jobs prefix-match the type anchor and are newer, so they"
                        + " fill the window of " + OLD_WINDOW + " and the type's own row sits"
                        + " outside it. This is the defect, measured on the identical store the"
                        + " keyed read is asked about below"),
                () -> assertTrue(keyed.containsKey(TYPE_ANCHOR),
                    () -> "and the keyed read finds it. Its result is bounded by the ONE anchor"
                        + " asked about, so there is no window for the row to fall outside of."
                        + " Got keys: " + keyed.keySet()),
                () -> assertEquals(THE_JOB, keyed.get(TYPE_ANCHOR).get(0).summary(),
                    "with the row's own words, which is what lets a reader see the work already"
                        + " exists rather than merely that something does"),
                () -> assertEquals(1, keyed.get(TYPE_ANCHOR).size(),
                    () -> "and EXACTLY the row anchored there — none of the " + NOISE + " that"
                        + " merely share its prefix, which the old read returned and every"
                        + " caller had to filter out by hand: " + keyed.get(TYPE_ANCHOR)),
                // THE EXACTNESS OF THE CLAUSE, which the assertion above does NOT reach: the
                // map is keyed by each row's OWN anchor, so a prefix-matching filter would put
                // the noise under its own keys and leave the line above reading 1. One key is
                // what says the clause matched the anchor rather than its descendants — and
                // what makes every caller's Java-side re-filter unnecessary rather than merely
                // redundant.
                () -> assertEquals(1, keyed.size(),
                    () -> "ONE KEY, for the one anchor asked about. " + NOISE + " keys here"
                        + " means the clause is matching a prefix again: " + keyed.keySet()));
        }
    }

    @Test
    @DisplayName("D30: an area row outside the old window is still found — the live `area` instance")
    void anAreaRowOutsideTheOldWindowIsStillFound(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            buryAnAreaUnderItsOwnSubPackages(store);

            List<StoredEntry> page = theOldListSift(store, "area", PACKAGE_ANCHOR);
            boolean onThePage = page.stream()
                .anyMatch(e -> PACKAGE_ANCHOR.equals(e.packageName()));

            Map<String, List<StoredEntry>> keyed = store.anchoredAt("area",
                ExperienceStore.Anchor.PACKAGE, List.of(PACKAGE_ANCHOR));

            assertAll(
                () -> assertEquals(OLD_WINDOW, page.size(),
                    () -> "the page is full, so the absence below is truncation: " + page.size()),
                () -> assertFalse(onThePage,
                    "THE CONTROL on the PACKAGE column. (This message cited `describe"
                        + " action=area` answering with five rows against 5996 matched as 'the"
                        + " live instance' of THIS defect. An audit measured that it is not:"
                        + " that verb goes through `ExperienceRetrieval.recall`, whose cap is a"
                        + " five-row ranked terminal — a DIFFERENT read with a different cap,"
                        + " which does at least declare itself through `capped_from`. The"
                        + " package-column defect this case pins is the one in `listEntries`,"
                        + " reached by `describe action=next`'s areas block.)"),
                () -> assertTrue(keyed.containsKey(PACKAGE_ANCHOR),
                    () -> "and the keyed read on the package column finds it: " + keyed.keySet()),
                () -> assertEquals(1, keyed.get(PACKAGE_ANCHOR).size(),
                    () -> "exactly the package asked about, not its sub-packages — which the"
                        + " prefix clause returned: " + keyed.get(PACKAGE_ANCHOR)));
        }
    }

    /**
     * MANY ANCHORS AT ONCE, which is the shape the cataloguer actually asks in — and the case a
     * MUTATION had to find, because the rest of this class could not reach it.
     *
     * <p>Putting a {@code LIMIT 50} back on the keyed read left every other case here GREEN: they
     * ask about ONE anchor, so a window of fifty is never reached and the "no window" claim was
     * verified by nothing. The production caller derives EVERY member of the files it was handed
     * ({@code collectMembersInto}), which for an ordinary class is routinely more than fifty — so
     * with a window, members past the fiftieth would be reported missing and instructed for.</p>
     *
     * <p>That is the same defect this class is about, reached from the other side: the first form
     * truncated the ROWS a scope matched, this one would truncate the ANSWERS to the question
     * asked. The read is bounded by the question only if every anchor in the question is in the
     * answer.</p>
     */
    @Test
    @DisplayName("D30: every one of 75 described members is answered — the window has no size at all")
    void everyAnchorAskedAboutIsAnswered(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            int members = 75;
            List<String> asked = new java.util.ArrayList<>();
            for (int i = 0; i < members; i++) {
                String m = "com.example.keyed.Wide#m" + i;
                asked.add(m);
                store.put(jobOn("Describes member " + i + ".", m));
            }

            Map<String, List<StoredEntry>> keyed = store.anchoredAt("job",
                ExperienceStore.Anchor.SYMBOL, asked);

            assertAll(
                () -> assertTrue(members > OLD_WINDOW,
                    () -> "the fixture must exceed any window for this to measure anything: "
                        + members + " vs " + OLD_WINDOW),
                () -> assertEquals(members, keyed.size(),
                    () -> "EVERY member asked about is answered. A window of any size truncates"
                        + " this, and the members it drops are reported missing and instructed"
                        + " for — the same false absence, reached by truncating the ANSWER"
                        + " rather than the rows a scope matched. Got: " + keyed.size()),
                () -> assertEquals(List.of(), asked.stream()
                        .filter(m -> !keyed.containsKey(m)).toList(),
                    "and none is silently absent"));
        }
    }

    // ------------------------------------------------------------------ the write gate

    private ToolResponse record(ExperienceStore store, String summary, String symbol) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("summary", summary);
        a.put("symbol", symbol);
        return new ExperienceTool(() -> null, store).execute(a);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    /**
     * THE CASE THAT MATTERS. The write gate is the one-job-per-member invariant: it replaces the
     * existing row and inserts when it sees none. While it found that row by sifting a capped
     * page, a buried row meant an INSERT — the duplicate D30 exists to prevent, reachable
     * through an anchor whose shape nothing validates. (This said the gate "minted the
     * duplicates D30 exists to prevent"; struck at round 2, because the measured duplicates are
     * member-anchored — which cannot prefix-match — and predate the gate. The fold that
     * corrected that tense at the gate itself left it standing here.) And no test could see the
     * hazard, because D30's own tests verified through the same read.
     */
    @Test
    @DisplayName("D30: the write gate REPLACES a buried row rather than inserting beside it")
    void theWriteGateReplacesABuriedRowRatherThanInsertingBesideIt(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            buryAJobUnderItsOwnNestedMembers(store);
            assertFalse(
                theOldListSift(store, "job", TYPE_ANCHOR).stream()
                    .anyMatch(e -> TYPE_ANCHOR.equals(e.symbolFqn())),
                "precondition: the row the gate must find is outside the old window");

            String second = "Settles the ledger against the broker, and refuses to trade at all"
                + " while the two disagree about the position.";
            Map<String, Object> answer = data(record(store, second, TYPE_ANCHOR));

            @SuppressWarnings("unchecked")
            Map<String, Object> replaced = (Map<String, Object>) answer.get("replaced");
            List<StoredEntry> after = store.anchoredAt("job",
                ExperienceStore.Anchor.SYMBOL, List.of(TYPE_ANCHOR))
                .getOrDefault(TYPE_ANCHOR, List.of());

            assertAll(
                () -> assertNotNull(replaced,
                    () -> "the gate SAW the buried row and replaced it. A null here is the"
                        + " defect: the gate found nothing, inserted, and reported a clean"
                        + " write. Answer: " + answer),
                () -> assertEquals(THE_JOB, replaced.get("summary"),
                    "and names the description it replaced, which is the only thing that lets"
                        + " a reader check it replaced the right one"),
                () -> assertEquals(1, after.size(),
                    () -> "ONE row at that anchor. Two is the duplicate — and it is the gate"
                        + " that writes it, not an agent ignoring an instruction: " + after),
                () -> assertEquals(second, after.get(0).summary(),
                    "carrying the new description"));
        }
    }

    // ------------------------------------------------------------------ the answer on the wire

    private ToolResponse jobsFor(ExperienceStore store, String... symbols) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "jobs_for");
        for (String s : symbols) {
            a.withArray("symbols").add(s);
        }
        return new ExperienceTool(() -> null, store).execute(a);
    }

    @Test
    @DisplayName("D30: jobs_for reports a buried row as DESCRIBED, and offers no recording instruction")
    @SuppressWarnings("unchecked")
    void jobsForReportsABuriedRowAsDescribed(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            buryAJobUnderItsOwnNestedMembers(store);
            // THE PRECONDITION, added 2026-10-08 after an audit measured that this case had
            // NONE — and it was the case the class javadoc's "every one is paired" claim was
            // most wrong about. Without it, a fixture that stopped burying (NOISE dropped below
            // the old window) makes every assertion below pass against the defective read, and
            // this case's discriminating power was entirely borrowed from a sibling method.
            List<StoredEntry> page = theOldListSift(store, "job", TYPE_ANCHOR);
            assertEquals(OLD_WINDOW, page.size(),
                () -> "precondition: the old read's page is FULL, so the row this case is about"
                    + " is one it could not see. Got: " + page.size());
            assertFalse(page.stream().anyMatch(e -> TYPE_ANCHOR.equals(e.symbolFqn())),
                "precondition: and the row is outside that page");

            Map<String, Object> out = data(jobsFor(store, TYPE_ANCHOR));
            List<Map<String, Object>> described =
                (List<Map<String, Object>>) out.get("described");

            assertAll(
                () -> assertEquals(1, described.size(),
                    () -> "the member is DESCRIBED. It landed in `missing` while the read was a"
                        + " capped sift, and `missing` is what the recording instruction is"
                        + " offered for: " + out),
                () -> assertEquals(THE_JOB, described.get(0).get("job"),
                    "with the existing job's own words"),
                () -> assertEquals(List.of(), out.get("missing"),
                    () -> "nothing is missing: " + out),
                () -> assertNull(out.get("next"),
                    () -> "and NO recording instruction is rendered — the instruction is the"
                        + " thing that turned a truncated page into a second description: " + out),
                () -> assertNull(out.get("undetermined"),
                    () -> "nor an `undetermined` bucket. It was this response's third state and"
                        + " it is gone rather than better reported: with no window there is no"
                        + " case in which the answer cannot tell an absence from a hidden"
                        + " row: " + out),
                () -> assertNull(out.get("undeterminedNote"),
                    () -> "and its note with it: " + out));
        }
    }

    /**
     * THE OPPOSITE CONTROL, and nothing above means anything without it: a member the store has
     * no row for must still be reported missing WITH the instruction. Every assertion in this
     * class is otherwise satisfied by a read that claims every member is described.
     */
    @Test
    @DisplayName("D30: an absence is still an absence, and still gets the instruction")
    @SuppressWarnings("unchecked")
    void anAbsenceIsStillAnAbsence(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            buryAJobUnderItsOwnNestedMembers(store);
            String never = TYPE_ANCHOR + "#neverDescribed";

            Map<String, Object> out = data(jobsFor(store, never));

            assertAll(
                () -> assertEquals(List.of(never), out.get("missing"),
                    () -> "a member with no row of its own is MISSING: " + out),
                () -> assertTrue(((List<Map<String, Object>>) out.get("described")).isEmpty(),
                    () -> "nothing is described: " + out),
                () -> assertNotNull(out.get("next"),
                    () -> "and the recording instruction IS offered — which is what makes the"
                        + " absence of it in the buried case a finding rather than a tool that"
                        + " stopped instructing: " + out),
                () -> assertFalse(String.valueOf(out).contains(THE_JOB),
                    () -> "and the prefix-matching sibling's job does not travel with this"
                        + " answer: " + out));
        }
    }
}
