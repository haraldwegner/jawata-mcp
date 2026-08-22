package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c S3 — the record-time form gate.
 *
 * <p>An entry is an EXPERIENCE: a <i>situation</i> saying when it applies, a
 * <i>principle</i> that is one judgeable sentence, and an <i>outcome</i>. The
 * store has accepted heading-shaped and outcome-less text for as long as it has
 * existed, and the cost is not theoretical — a recall that answers with
 * "Required follow-up" or "Test plan" has spent the agent's attention and told
 * it nothing.</p>
 *
 * <p>These tests are written BEFORE the gate exists. Each one names the refusal
 * it expects and the reason a reader must be given, because a gate that refuses
 * without teaching just moves the failure from the store to the author.</p>
 */
class EntryFormTest {

    private static final String GOOD_SITUATION = "when amending an order that is already partially filled";
    private static final String GOOD_PRINCIPLE =
        "Amending a partially filled order replaces the remaining quantity, not the original quantity.";

    /** A lesson — the form binds here. */
    private static Optional<EntryForm.Refusal> check(String summary, String situation, String verdict) {
        return EntryForm.check("lesson", summary, List.of(), situation, verdict);
    }

    /** A fact — the form does NOT bind; only the shape checks do. */
    private static Optional<EntryForm.Refusal> checkFact(String summary, String situation, String verdict) {
        return EntryForm.check("domain_fact", summary, List.of(), situation, verdict);
    }

    @Test
    void a_well_formed_entry_is_admitted() {
        assertTrue(check(GOOD_PRINCIPLE, GOOD_SITUATION, "worked").isEmpty(),
            "situation + judgeable principle + outcome is exactly the form");
    }

    @Test
    void an_entry_with_no_outcome_is_refused() {
        Optional<EntryForm.Refusal> refusal = check(GOOD_PRINCIPLE, GOOD_SITUATION, null);
        assertTrue(refusal.isPresent(), "an experience without an outcome is a note, not an experience");
        assertEquals("verdict", refusal.get().field());
        assertTrue(refusal.get().message().contains("RULE:"),
            "the refusal must teach the rule, not just say no: " + refusal.get().message());
        assertTrue(refusal.get().message().contains("unproven"),
            "and must name the escape hatch — an entry whose outcome is not yet known "
                + "says 'unproven' rather than being turned away: " + refusal.get().message());
    }

    @Test
    void unproven_is_a_real_outcome_and_is_admitted() {
        assertTrue(check(GOOD_PRINCIPLE, GOOD_SITUATION, "unproven").isEmpty(),
            "'unproven' is an outcome that is present, not an absent outcome");
    }

    // ---- Not every entry is a lesson (Harald, 2026-08-21) ----
    //
    // "You cannot just form everything upfront into lessons." The store records
    // domain facts, API contracts and naming conventions beside lessons, and
    // those did not turn out any way at all. Demanding an outcome from them
    // would either turn away true knowledge or teach authors to attach a
    // verdict they never earned — and the store would then rank on fiction.

    @Test
    void a_domain_fact_needs_neither_a_situation_nor_an_outcome() {
        assertTrue(checkFact("The experience store is one file at ~/.local/share/jawata/experience.mv.db.",
                null, null).isEmpty(),
            "a fact about where something lives has no outcome, and requiring one "
                + "would either lose the fact or invent a verdict for it");
    }

    @Test
    void a_fact_is_still_held_to_the_shape_checks() {
        // The relaxation is about the FORM, not about quality. A heading is not
        // knowledge whatever its type — and headings are exactly what filled the
        // store in the first place.
        Optional<EntryForm.Refusal> refusal = checkFact("## Summary table", null, null);
        assertTrue(refusal.isPresent(), "a heading is not a fact either");
        assertEquals("summary", refusal.get().field());
    }

    @Test
    void a_fact_that_does_offer_a_situation_must_still_offer_a_real_one() {
        // Optional does not mean unchecked: a situation that is a location
        // matches everything in that package and distinguishes nothing, and a
        // wrong condition is worse than an absent one because it matches
        // confidently.
        Optional<EntryForm.Refusal> refusal =
            checkFact("The store keeps one row per entry.", "org.jawata.mcp.knowledge", null);
        assertTrue(refusal.isPresent(), "a package name is a place, not a condition");
        assertEquals("situation", refusal.get().field());
    }

    @Test
    void a_failure_mode_is_an_experience_and_is_held_to_the_form() {
        Optional<EntryForm.Refusal> refusal =
            EntryForm.check("failure_mode", GOOD_PRINCIPLE, List.of(), GOOD_SITUATION, null);
        assertTrue(refusal.isPresent(),
            "a failure mode is something that HAPPENED — the outcome is the point of it");
        assertEquals("verdict", refusal.get().field());
    }

    @Test
    void an_invented_outcome_is_refused_and_the_real_ones_are_named() {
        Optional<EntryForm.Refusal> refusal = check(GOOD_PRINCIPLE, GOOD_SITUATION, "probably-fine");
        assertTrue(refusal.isPresent(), "the outcome vocabulary is closed");
        assertEquals("verdict", refusal.get().field());
        for (String valid : new String[] {"worked", "failed_avoid", "unproven"}) {
            assertTrue(refusal.get().message().contains(valid),
                "a closed vocabulary must be listed when it is enforced, or the author "
                    + "is guessing; missing '" + valid + "' in: " + refusal.get().message());
        }
    }

    @Test
    void an_entry_with_no_situation_is_refused() {
        Optional<EntryForm.Refusal> refusal = check(GOOD_PRINCIPLE, "   ", "worked");
        assertTrue(refusal.isPresent(),
            "without a situation nothing can decide WHEN the entry applies, so it can only "
                + "ever be retrieved by resemblance");
        assertEquals("situation", refusal.get().field());
        assertTrue(refusal.get().message().contains("RULE:"), refusal.get().message());
    }

    @Test
    void a_situation_that_is_package_geography_is_refused() {
        // The spec is explicit: applicability is a CONDITION ("when amending
        // Alpaca orders"), never a location. A package name matches every call
        // in that package and teaches nothing about when the lesson applies.
        Optional<EntryForm.Refusal> refusal =
            check(GOOD_PRINCIPLE, "org.jawata.mcp.knowledge.H2ExperienceStore", "worked");
        assertTrue(refusal.isPresent(), "a symbol is a place, not a condition");
        assertEquals("situation", refusal.get().field());
        assertTrue(refusal.get().message().toLowerCase().contains("when"),
            "the refusal must point at the shape that WOULD work: " + refusal.get().message());
    }

    @Test
    void a_heading_shaped_principle_is_still_refused_in_admission_policys_own_voice() {
        // The gate COMPOSES the existing shape checks rather than re-deriving
        // them: AdmissionPolicy's regexes mirror a committed derivation script,
        // and a second copy would drift from it silently.
        Optional<EntryForm.Refusal> refusal = check("## Required follow-up", GOOD_SITUATION, "worked");
        assertTrue(refusal.isPresent(), "a heading is not an experience");
        assertEquals("summary", refusal.get().field());
        assertTrue(refusal.get().message().contains("RULE:")
                && refusal.get().message().contains("REPHRASE:"),
            "the composed message must keep AdmissionPolicy's teaching shape: "
                + refusal.get().message());
    }

    @Test
    void a_misplaced_symptom_is_still_refused_through_the_composed_gate() {
        Optional<EntryForm.Refusal> refusal = EntryForm.check("lesson",
            GOOD_PRINCIPLE, List.of("src/main/java/com/example/Thing.java"), GOOD_SITUATION, "worked");
        assertTrue(refusal.isPresent(), "a path is an artifact, not an observation");
        assertEquals("symptoms", refusal.get().field());
    }

    /**
     * A location-shaped situation is refused whatever the type, and that is
     * deliberately NOT symmetric with the form: the type decides what an entry
     * OWES, never what it is ALLOWED to get wrong. A wrong condition is worse
     * than a missing one, because it matches confidently and so outranks the
     * entry that actually fits.
     */
    @Test
    void a_location_shaped_situation_is_refused_for_every_type() {
        String path = "src/main/java/com/example/Retry.java";
        assertEquals("situation", EntryForm.check("lesson", GOOD_PRINCIPLE, List.of(), path,
            "worked").orElseThrow().field(), "an experience may not anchor on a path");
        assertEquals("situation", EntryForm.check("domain_fact", GOOD_PRINCIPLE, List.of(), path,
            null).orElseThrow().field(),
            "and neither may a fact, which owes no situation but may not supply a bad one");
    }
}
