package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S13 / D24 — how an entry turned out travels with it WHEREVER it is
 * offered, so an untested idea cannot read like a proven one.
 *
 * <p><b>The gap was found live, in this product's own injected text, not by
 * reading.</b> The exact-recall line has carried the verdict for some time
 * (" · worked"), and the nominate candidate map has carried {@code outcome}. The
 * ANALOGY — the "In a similar situation:" line, which is the one a hook injects
 * most often — carried the summary, the cause, the basis and the provenance, and
 * no outcome at all. So one row offered through two doors told a reader two
 * different things, and the door that asks them to <em>judge whether it
 * transfers</em> was the one withholding the fact that judgement most needs.</p>
 *
 * <p>These tests drive the RENDERERS directly rather than a store. The defect is
 * in what the map carries and what the line prints, and a store round trip would
 * put a ranking engine between the assertion and its subject — so a failure could
 * mean the analogy never surfaced rather than that it surfaced without its
 * outcome.</p>
 */
class OutcomeTravelsWithTheAdviceTest {

    private static Map<String, Object> analogy(String outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", "the cancel is done when the broker says so, not when it is sent");
        m.put("cause", "the confirmation is asynchronous and the slot is freed on the send");
        if (outcome != null) {
            m.put("outcome", outcome);
        }
        m.put("basis", List.of("meaning-near"));
        return m;
    }

    @Test
    @DisplayName("D24: the analogy line shows how it turned out")
    void theAnalogyLineCarriesTheOutcome() {
        String line = ExperienceRetrieval.renderAnalogyLine(analogy("worked"));

        assertAll(
            () -> assertTrue(line.contains("worked"),
                () -> "the outcome is on the line a reader is actually handed: " + line),
            // THE DISCRIMINATOR between "it turned out" and "it was tried". An entry
            // nobody has settled is the case D24 exists for: it must be offered AND
            // visibly unsettled, never quietly offered as though it held.
            () -> assertTrue(
                ExperienceRetrieval.renderAnalogyLine(analogy("unproven")).contains("unproven"),
                "and an UNPROVEN one says so rather than looking like one that held"),
            () -> assertTrue(
                ExperienceRetrieval.renderAnalogyLine(analogy("failed_avoid"))
                    .contains("failed_avoid"),
                "and one that failed says that"));
    }

    @Test
    @DisplayName("D24: two entries that differ ONLY in outcome render differently")
    void entriesDifferingOnlyInOutcomeAreDistinguishable() {
        // The measure's own words: an untested entry is DISTINGUISHABLE from one that
        // held, at the point of use. Asserted as a comparison rather than as two
        // containments, because containment of a word somewhere in a line is satisfied
        // by prose that happens to use it.
        String held = ExperienceRetrieval.renderAnalogyLine(analogy("worked"));
        String untested = ExperienceRetrieval.renderAnalogyLine(analogy("unproven"));

        assertFalse(held.equals(untested),
            () -> "identical advice with different outcomes must not render identically, "
                + "which is the whole of D24: " + held);
    }

    @Test
    @DisplayName("D24: a row that legitimately has no outcome adds no empty marker")
    void aRowWithNoOutcomeSaysNothingAboutOne() {
        String line = ExperienceRetrieval.renderAnalogyLine(analogy(null));

        assertAll(
            () -> assertTrue(line.contains("the cancel is done"),
                () -> "the entry still renders: " + line),
            // A domain_fact never turned out any way at all. Printing "null", or an
            // empty marker, would be a value invented to satisfy a rule — which is
            // exactly what the form gate refuses at the door.
            () -> assertFalse(line.contains("null"),
                () -> "and nothing is invented for it: " + line));
    }

    /**
     * THE HALF THE RENDERER TESTS CANNOT REACH, and without it they prove nothing
     * that matters: they hand {@code renderAnalogyLine} a map carrying {@code outcome}
     * because the test put it there. If the ASSEMBLY never writes that key, every
     * assertion above still passes and no reader ever sees an outcome.
     */
    @Test
    @DisplayName("D24: the analogy MAP carries the outcome, so the line has one to print")
    void theAssembledAnalogyMapCarriesTheOutcome() {
        StoredEntry held = entryWithVerdict("worked");
        StoredEntry unsettled = entryWithVerdict("unproven");
        StoredEntry neverTurnedOut = entryWithVerdict(null);

        List<Map<String, Object>> maps = ExperienceAnalogies.toMaps(List.of(
            new ExperienceAnalogies.Analogy(held, List.of("meaning-near"), null),
            new ExperienceAnalogies.Analogy(unsettled, List.of("meaning-near"), null),
            new ExperienceAnalogies.Analogy(neverTurnedOut, List.of("meaning-near"), null)));

        assertAll(
            () -> assertEquals("worked", maps.get(0).get("outcome"),
                () -> "the assembly writes it: " + maps.get(0)),
            () -> assertEquals("unproven", maps.get(1).get("outcome"),
                () -> "including the unsettled one, which is the case D24 exists for: "
                    + maps.get(1)),
            () -> assertFalse(maps.get(2).containsKey("outcome"),
                () -> "and a row that never turned out any way carries NO key, rather than a "
                    + "null pretending to be one: " + maps.get(2)),
            // End to end, so the two halves cannot pass separately while disagreeing.
            () -> assertTrue(ExperienceRetrieval.renderAnalogyLine(maps.get(0)).contains("worked"),
                () -> "and the line built from the ASSEMBLED map shows it: "
                    + ExperienceRetrieval.renderAnalogyLine(maps.get(0))));
    }

    private static StoredEntry entryWithVerdict(String verdict) {
        return new StoredEntry("id-" + verdict, "lesson", null, "com.example.d24", null,
            "accepted", "medium", "java", null,
            "the cancel is done when the broker says so, not when it is sent",
            List.of(), null, "package", null, java.time.Instant.now(), Map.of(),
            new StoredEntry.Facets(null, null, verdict, null, null, null, null, null, null,
                null));
    }
}
