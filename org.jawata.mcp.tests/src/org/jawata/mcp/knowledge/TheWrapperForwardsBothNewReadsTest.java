package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S13 — the two reads this stage added answer THROUGH THE WRAPPER, which is
 * what production serves.
 *
 * <p><b>ONE of them shipped broken, not both, and the C13 audit is what corrected that.</b>
 * Each arrived as a {@code default} on {@link ExperienceStore}, and
 * {@link RecoveringExperienceStore} declares only what it means to forward — so the
 * default answered in its place for both. But the two defaults were not alike.
 * {@code appliedSampleCount} was a hard {@code return 0}: through the wrapper every
 * pattern had zero samples whatever the wrapped store held, and the hollow-wiring gate
 * is what found it. {@code headingShapedSummaries} looped over an UNQUALIFIED
 * {@code all()}, which dispatches to the wrapper's own override and reaches the
 * delegate's rows — so it answered CORRECTLY, and the version of this paragraph that
 * said it returned a clean audit was false.</p>
 *
 * <p><b>Which makes the two cases below different kinds of test, and saying so is the
 * point.</b> The sample count's case pins a repaired defect. The heading audit's pins a
 * forward that was never wrong — and under an abstract interface member it cannot fail
 * for a MISSING forward at all, because deleting the override is a compile error. It is
 * a regression lock on the forward's BODY, which is a real thing to hold and is not the
 * discriminator its first message claimed to be.</p>
 *
 * <p>That is the defect {@code RecoveringExperienceStore#degradedNotice}'s own javadoc
 * records, in its own words — <em>"the one method of twenty here that answered for itself
 * instead of asking the delegate … inert while the H2 store had nothing to say, and it
 * would have gone live silently the moment it did"</em>. Mine were worse: not present at
 * all, so nothing in this class even looked wrong.</p>
 *
 * <p>The cure is that neither has a default any more. Abstract is what made the compiler
 * ask every store for an answer instead of supplying one, which is why
 * {@code degradedNotice} has never had a default either.</p>
 *
 * <p><b>And the irony is worth keeping rather than tidying away:</b> D25's own javadoc,
 * written in the same commit as the default, says a method reachable only by casting to
 * the concrete type "silently does nothing where it matters — the lesson {@code byIds}
 * and {@code degradedNotice} are each on that interface for". The sentence was right and
 * the member it described did exactly that.</p>
 */
class TheWrapperForwardsBothNewReadsTest {

    private static final String PATTERN = "design:step-builder";
    private static final String HEADING = "# The case";

    /**
     * A RECOVERED wrapper — one that has swapped in the real store. Asserted rather than
     * waited for: while still degraded the wrapper serves an in-memory fallback, which is
     * a different object, and anything below would be about that instead of the forward.
     */
    private static RecoveringExperienceStore recoveredOnto(Path dir) throws Exception {
        RecoveringExperienceStore wrapped = new RecoveringExperienceStore(
            "test: the real open failed once", () -> H2ExperienceStore.openAt(dir), 20L);
        long deadline = System.currentTimeMillis() + 10_000L;
        while (wrapped.degradedNotice() != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(wrapped.degradedNotice() == null,
            "the control: this test is about the RECOVERED wrapper, and it never recovered "
                + "— anything asserted below would be about the in-memory fallback");
        return wrapped;
    }

    private static ExperienceEntry entry(String summary, String rel, String target) {
        SymbolFact fact = SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
            .scope(List.of("com.example.wrapper"), List.of())
            .details("fixture for the forwarding hole")
            .build();
        ExperienceEntry.Builder b = ExperienceEntry.of(fact).scopeKind("package");
        return rel == null ? b.build() : b.addLink(rel, target).build();
    }

    @Test
    @DisplayName("the wrapper reports the DELEGATE's sample count, not a default zero")
    void theSampleCountIsForwarded(@TempDir Path dir) throws Exception {
        try (RecoveringExperienceStore wrapped = recoveredOnto(dir)) {
            wrapped.put(entry("the builder kept the half-built object unreachable",
                "applied", PATTERN));
            wrapped.put(entry("a second place where the same shape fitted",
                "applied", PATTERN));

            assertEquals(2, wrapped.appliedSampleCount(PATTERN),
                "TWO, from the store behind the wrapper. ZERO is the exact value the "
                    + "interface default used to answer here, so a zero means the forward "
                    + "is gone and the wrapper is speaking for a store it never asked");
        }
    }

    @Test
    @DisplayName("the wrapper reports the DELEGATE's heading audit, not a default empty")
    void theHeadingAuditIsForwarded(@TempDir Path dir) throws Exception {
        try (RecoveringExperienceStore wrapped = recoveredOnto(dir)) {
            wrapped.put(entry(HEADING, null, null));
            wrapped.put(entry("a real sentence of knowledge that a reader could act on",
                null, null));

            List<?> offending = wrapped.headingShapedSummaries();
            assertAll(
                () -> assertEquals(1, offending.size(),
                    () -> "the one heading row, read from the store behind the wrapper. This "
                        + "is a REGRESSION LOCK on the forward's body, not a reproduction of a "
                        + "defect: the old default looped over an unqualified all(), which "
                        + "dispatches to this wrapper's own override, so it already answered "
                        + "correctly. What would break this is a forward rewritten to answer "
                        + "locally: " + offending),
                () -> assertTrue(String.valueOf(offending).contains(HEADING),
                    () -> "and it carries the offending text: " + offending));
        }
    }

    /**
     * D40c's three, added after the architect's C13 F1 found them STILL answering from
     * their defaults when the two above had already been fixed.
     *
     * <p>They are the third member of this interface to ship that way in one stage, and
     * the one nobody looked for: the enumeration that cures the first two is exactly the
     * one that finds the third. Through the wrapper, {@code recordStoryFolder} was a
     * no-op, so the loader's recording was discarded and the story folder was empty on
     * every machine — D40c's deliverable replaced a wrong answer with none.</p>
     */
    @Test
    @DisplayName("the wrapper RECORDS and REPORTS the story folder, not a default empty")
    void theStoryFolderIsForwarded(@TempDir Path dir, @TempDir Path stories) throws Exception {
        try (RecoveringExperienceStore wrapped = recoveredOnto(dir)) {
            wrapped.recordStoryFolder(stories);

            assertAll(
                () -> assertEquals(stories.toAbsolutePath().normalize(),
                    wrapped.storyFolder().orElseThrow(),
                    "the folder the loader recorded. An EMPTY answer is what the interface "
                        + "default used to give here, and it is indistinguishable from a "
                        + "store that was never told"),
                () -> assertFalse(wrapped.storyFolderAmbiguous(),
                    "and one folder is not several — a default FALSE would agree with this "
                        + "by accident, which the next case is for"));
        }
    }

    @Test
    @DisplayName("and the wrapper reports AMBIGUITY, which a default false cannot")
    void theAmbiguityFlagIsForwarded(@TempDir Path dir, @TempDir Path a, @TempDir Path b)
            throws Exception {
        // THE DISCRIMINATOR for the clause above. `storyFolderAmbiguous` defaulted to
        // false, which is the correct answer in the one-folder case — so that case alone
        // would pass against a wrapper that forwards nothing. Two different roots is the
        // only input where the default and the truth disagree.
        try (RecoveringExperienceStore wrapped = recoveredOnto(dir)) {
            wrapped.recordStoryFolder(a);
            wrapped.recordStoryFolder(b);

            assertAll(
                () -> assertTrue(wrapped.storyFolderAmbiguous(),
                    "two different roots leave no single story folder, and the wrapper must "
                        + "say so rather than answering the default FALSE"),
                () -> assertTrue(wrapped.storyFolder().isEmpty(),
                    "and no folder is reported, rather than their shared ancestor"));
        }
    }
}
