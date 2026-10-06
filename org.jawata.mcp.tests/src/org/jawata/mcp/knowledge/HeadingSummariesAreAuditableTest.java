package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S13 / D28 — the store can be ASKED which of its rows hand out a
 * section heading as knowledge.
 *
 * <p>D28 has two measures and only one of them was delivered. The first — that
 * the gate refusing a heading at {@code record} applies to LOADED files too —
 * shipped in Sprint 28c (D3) and is covered by
 * {@code ExperienceMaintenanceTest#a_heading_shaped_memory_file_is_refused_and_the_refusal_is_reported}.
 * This class covers the second: <em>after a reload no row's summary is a section
 * heading</em>. That is a claim about the STORE, not about the door, and nothing
 * could answer it — the gate stops new ones arriving and says nothing about the
 * 49 rows already in when it shipped, which a reload supersedes rather than
 * removes.</p>
 *
 * <p><b>The fixture is planted through {@code put}, which is how the real rows
 * got in.</b> Measured rather than assumed: {@code AdmissionPolicy.check} has
 * exactly two callers — {@code EntryForm}, the authoring door, and the audit
 * under test. So the store layer never refused a heading and a fixture that
 * went through the tool layer could not reproduce the state being audited.</p>
 */
class HeadingSummariesAreAuditableTest {

    /** Hash form — the pattern's first alternative. */
    private static final String HASH_HEADING = "# The case";
    /** Short-fragment form — its second. Two forms, so catching one is visible. */
    private static final String COLON_HEADING = "The cure:";

    /**
     * THE DISCRIMINATOR for "ends with a colon". The shipped rule is a SHORT
     * fragment ending in one ({@code ^.{0,60}:\s*$}), so a long sentence that
     * happens to end in a colon is admitted — and an audit built on
     * {@code endsWith(":")} rather than on the shipped predicate would refuse it.
     */
    private static final String LONG_FRAGMENT_ENDING_IN_A_COLON =
        "the broker's cancel confirmation is the only thing that frees the slot, and here is why:";

    /** And a colon MID-sentence, which no reading of the rule should catch. */
    private static final String COLON_MID_SENTENCE =
        "the amend quantity is the total: not the remainder, which the broker silently ignores";

    private static ExperienceEntry entry(String summary) {
        SymbolFact fact = SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
            .scope(List.of("com.example.audit"), List.of())
            .details("fixture for D28's heading audit")
            .build();
        return ExperienceEntry.of(fact).scopeKind("package").build();
    }

    @Test
    @DisplayName("D28: the audit names the heading rows and leaves the sentences alone")
    void theAuditNamesTheHeadingRowsOnly() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String hash = store.put(entry(HASH_HEADING));
            String colon = store.put(entry(COLON_HEADING));
            store.put(entry(LONG_FRAGMENT_ENDING_IN_A_COLON));
            store.put(entry(COLON_MID_SENTENCE));

            List<Map<String, Object>> offending = store.headingShapedSummaries();
            List<Object> ids = offending.stream().map(r -> r.get("id")).toList();
            List<Object> summaries = offending.stream().map(r -> r.get("summary")).toList();

            assertAll(
                () -> assertEquals(2, offending.size(),
                    "both heading FORMS are caught and neither sentence is — THREE would mean "
                        + "the audit read 'ends with a colon' instead of the shipped rule, and "
                        + "ONE would mean it caught a single alternative of the pattern; got: "
                        + offending),
                () -> assertTrue(ids.contains(hash), "the hash form, got: " + offending),
                () -> assertTrue(ids.contains(colon), "the short-fragment form, got: " + offending),
                () -> assertTrue(summaries.contains(HASH_HEADING),
                    "and the row carries the offending text, so a reader can judge it without a "
                        + "second lookup; got: " + offending),
                () -> assertTrue(
                    !summaries.contains(LONG_FRAGMENT_ENDING_IN_A_COLON)
                        && !summaries.contains(COLON_MID_SENTENCE),
                    "neither sentence is named; got: " + offending));
        }
    }

    @Test
    @DisplayName("D28: a clean store answers EMPTY — an absence is an answer, not an error")
    void aCleanStoreAnswersEmpty() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.put(entry(COLON_MID_SENTENCE));

            assertEquals(List.of(), store.headingShapedSummaries(),
                "a store with nothing to report says so, which is what lets a caller read an "
                    + "empty answer as clean rather than as a check that did not run");
        }
    }

    @Test
    @DisplayName("D28: the audit and the write gate are ONE rule, asked from two sides")
    void theAuditAgreesWithTheGateThatRefusesTheSameText() {
        // The gate's own verdict on the two fixtures, through the shipped entry
        // point rather than through a restatement of the pattern. Without this
        // pair the audit could drift from the door and both halves stay green:
        // the door would refuse what the audit admits, and D28 would be met on
        // one measure and silently broken on the other.
        assertAll(
            () -> assertTrue(AdmissionPolicy.check(HASH_HEADING, null).isPresent(),
                "the door refuses the hash form"),
            () -> assertTrue(AdmissionPolicy.check(COLON_HEADING, null).isPresent(),
                "and the short-fragment form"),
            () -> assertTrue(AdmissionPolicy.check(LONG_FRAGMENT_ENDING_IN_A_COLON, null).isEmpty(),
                "and ADMITS the long fragment, which is the half that makes the audit's own "
                    + "'two, not three' assertion mean the shipped rule rather than a guess"),
            () -> assertTrue(AdmissionPolicy.check(COLON_MID_SENTENCE, null).isEmpty(),
                "and the mid-sentence colon"));
    }

    /**
     * THE PREMISE THE AUDIT RESTS ON, asserted because a mutation proved nothing
     * else does. {@code headingShapedSummaries} keeps only refusals whose shape is
     * {@code HEADING}; dropping that filter changes NOTHING today, measured — the
     * three tests above stay green with it gone. It cannot fire because
     * {@code check} checks a summary for heading shape and nothing else, so a
     * summary carrying a path or a call is admitted and no second shape can reach
     * the audit.
     *
     * <p>So the filter is a bound on a FUTURE change rather than dead code, and
     * this is where that change is noticed: the day {@code check} starts refusing
     * a summary for any other reason, these assertions go red and the filter stops
     * being unreachable. Asserting the premise is the only way to cover a guard
     * whose own branch no fixture can construct.</p>
     */
    @Test
    @DisplayName("D28: a summary is checked for HEADING shape and nothing else")
    void theDoorJudgesASummaryOnHeadingShapeAlone() {
        assertAll(
            () -> assertTrue(AdmissionPolicy.check("org.jawata.mcp.knowledge.ExperienceStore", null)
                    .isEmpty(),
                "a dotted identifier is a CODE shape in a symptom and is fine in a summary, "
                    + "which a summary legitimately names"),
            () -> assertTrue(
                AdmissionPolicy.check("src/main/java/com/example/Widget.java", null).isEmpty(),
                "and a path"),
            () -> assertTrue(AdmissionPolicy.check("`appliedSampleCount(target)` returns zero for "
                    + "a pattern nobody reached for", null).isEmpty(),
                "and a backticked call inside a real sentence"),
            () -> assertEquals(AdmissionPolicy.Shape.HEADING,
                AdmissionPolicy.check(HASH_HEADING, null).orElseThrow().shape(),
                "so every refusal a summary can draw carries exactly one shape, which is what "
                    + "makes the audit's shape filter unreachable rather than wrong"));
    }
}
