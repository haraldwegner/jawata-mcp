package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S13 — the two reads this stage added answer THROUGH THE WRAPPER, which is
 * what production serves.
 *
 * <p><b>Both shipped broken and the hollow-wiring gate is what found it.</b> Each arrived
 * as a {@code default} on {@link ExperienceStore} — {@code appliedSampleCount} returning
 * 0 and {@code headingShapedSummaries} returning empty. {@link RecoveringExperienceStore}
 * declares only what it means to forward, so for both of these the DEFAULT answered in
 * its place: zero samples for every pattern and a clean heading audit, whatever the
 * wrapped store actually held.</p>
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
                    () -> "the one heading row, from the store behind the wrapper. EMPTY is "
                        + "what the interface default used to answer, and an empty audit "
                        + "reads as a clean store — which is the whole reason this is a "
                        + "defect rather than a gap: " + offending),
                () -> assertTrue(String.valueOf(offending).contains(HEADING),
                    () -> "and it carries the offending text: " + offending));
        }
    }
}
