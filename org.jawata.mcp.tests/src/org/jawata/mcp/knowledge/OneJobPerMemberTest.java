package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S14 / D30 — a member's description is REPLACED, not added beside the old one.
 *
 * <p><b>The defect, measured rather than argued.</b> {@code record type=job} inserts, so a
 * member described twice ends up with two job rows and the older one describes code that
 * has since changed. Over {@code org.jawata.mcp.knowledge} alone, uncapped on 2026-10-07:
 * <b>73 members carry more than one job row, 75 surplus rows.</b> jawata-mcp#85 carries
 * the store-wide census and the two write patterns behind it.</p>
 *
 * <p><b>And the session that measured it had just caused some of it.</b> A stop hook handed
 * out a list of "undescribed" members, one description was recorded per member, and several
 * of those members already had a job — so {@code RecoveringExperienceStore#close} now holds
 * two rows whose texts say the same thing in different words. The store even DISPLAYED the
 * existing one as a recall nominee in the same call that wrote over it. That is why the fix
 * belongs in the store rather than in an instruction to look first: looking first was
 * available and did not help.</p>
 *
 * <p><b>The control is the point of this class.</b> Asserting that a replace leaves one row
 * proves nothing on its own — one row is also what a store that simply refused the second
 * write would show, and what an empty store shows. So each case here is paired with a plain
 * {@code put} of the same second description, which must produce TWO rows. The pair is what
 * distinguishes "replaced" from "never written".</p>
 */
class OneJobPerMemberTest {

    private static final String MEMBER = "com.example.d30.Gauge#reading";

    /** Job rows anchored to exactly this member, through the read that already existed. */
    private static List<StoredEntry> jobsFor(ExperienceStore store, String member) {
        return store.listEntries("job", null, member, null, 50).stream()
            .filter(e -> member.equals(e.symbolFqn()))
            .toList();
    }

    private static ExperienceEntry jobOn(String summary, String member) {
        return ExperienceEntry.of(
                SymbolFact.of("job", summary, Confidence.MEDIUM)
                    .symbol(member)
                    .build())
            .scopeKind("symbol")
            .build();
    }

    @Test
    @DisplayName("D30: a replaced job keeps its row id and its text changes — against a put that adds a second")
    void aReplacedJobKeepsItsIdAndTheOldTextIsGone(@TempDir Path replaced, @TempDir Path added) {
        try (H2ExperienceStore viaReplace = H2ExperienceStore.open(replaced);
                H2ExperienceStore viaPut = H2ExperienceStore.open(added)) {

            String first = "Answers the current reading.";
            String second = "Answers the gauge's last sampled reading, or zero before the "
                + "first sample, so a caller never sees a null it has to interpret.";

            String idA = viaReplace.put(jobOn(first, MEMBER));
            String writtenBack = viaReplace.replaceJob(idA, jobOn(second, MEMBER));

            // THE CONTROL: the same second description through the shipped write path.
            String idB = viaPut.put(jobOn(first, MEMBER));
            viaPut.put(jobOn(second, MEMBER));

            List<StoredEntry> afterReplace = jobsFor(viaReplace, MEMBER);
            List<StoredEntry> afterPut = jobsFor(viaPut, MEMBER);

            assertAll(
                () -> assertEquals(2, afterPut.size(),
                    () -> "THE CONTROL, and it must fail the day this is fixed in `put` "
                        + "instead: the shipped write path adds a second row, which is the "
                        + "defect 73 members of one package are in. Got: " + afterPut.size()),
                () -> assertEquals(1, afterReplace.size(),
                    () -> "and the replace leaves ONE — meaningless without the control "
                        + "above, because one row is also what an empty store shows. Got: "
                        + afterReplace),
                () -> assertEquals(idA, writtenBack,
                    "the call reports the id it wrote, so a caller can name it without "
                        + "re-deriving it"),
                () -> assertEquals(idA, afterReplace.get(0).id(),
                    "THE ID IS KEPT. Links point at it and the review ledger names it, so a "
                        + "replacement that minted a new id would orphan both while looking "
                        + "like a clean update"),
                () -> assertEquals(second, afterReplace.get(0).summary(),
                    "the surviving row carries the NEW description"),
                () -> assertNotEquals(first, afterReplace.get(0).summary(),
                    "and not the old one — which is the half that makes this a replacement "
                        + "rather than a second write that happened to land on one row"),
                () -> assertTrue(idB != null && !idB.isBlank(),
                    "proof of life for the control store: its first write really happened"));
        }
    }

    /**
     * The provenance half. {@code replaceJob} reuses the sourced-row rewrite, whose
     * statement deliberately does NOT write {@code source_ref} — so a row that came from a
     * file keeps saying so. Asserted rather than trusted, because the two null arguments it
     * passes LOOK like they would clear it.
     */
    @Test
    @DisplayName("D30: the rewrite leaves a row's provenance alone")
    void theSourceRefSurvivesTheRewrite(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            String ref = "memory:docs/knowledge/d30.md";
            String id = store.putWithSource(jobOn("the first description", MEMBER), ref);

            store.replaceJob(id, jobOn("a second, better description of the same member", MEMBER));

            StoredEntry after = jobsFor(store, MEMBER).get(0);
            assertAll(
                () -> assertEquals(ref, after.sourceRef(),
                    "the row still names the file it came from. The rewrite passes null for "
                        + "both source arguments, and this holds because `source_ref` is not "
                        + "in that statement's column list at all — a fact worth pinning, "
                        + "since the call reads as though it would erase it"),
                () -> assertTrue(after.summary().startsWith("a second"),
                    () -> "and the description really was replaced: " + after.summary()));
        }
    }

    /**
     * What the key IS, stated as a test because it is the decision the deliverable rests
     * on: the anchor AS WRITTEN. An overload described with its parameter types is a
     * different key and keeps its own row, which is what lets an overloaded member be
     * described at all.
     */
    @Test
    @DisplayName("D30: two overloads described with their parameter types keep separate rows")
    void anOverloadDescribedWithItsTypesIsItsOwnRow(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            String takesInt = "com.example.d30.Gauge#set(int)";
            String takesString = "com.example.d30.Gauge#set(java.lang.String)";

            store.put(jobOn("Sets the reading from a count.", takesInt));
            store.put(jobOn("Sets the reading by parsing a label.", takesString));

            assertAll(
                () -> assertEquals(1, jobsFor(store, takesInt).size(),
                    "the int overload has its own row"),
                () -> assertEquals(1, jobsFor(store, takesString).size(),
                    "and so does the String one — if the key dropped parameter types these "
                        + "two would collide, and a replace would destroy one of them. "
                        + "jawata-mcp#85 counts four such groups that must NOT be collapsed"),
                () -> assertNotEquals(
                    jobsFor(store, takesInt).get(0).id(),
                    jobsFor(store, takesString).get(0).id(),
                    "two rows, not one read twice"));
        }
    }
}
