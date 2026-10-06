package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S14 / D32 — a description of a MEMBER that names no member is refused, an area is
 * not, and what tells them apart is a property of the kind rather than a list of type names.
 *
 * <p><b>The start state, measured before any of this was written.</b>
 * {@code EntryForm.checkDerived} decided it applied by asking
 * {@code KnowledgeLane.CODE_TYPES.contains(type)} over {@code Set.of("job", "area")} — a name
 * list, and one holding BOTH kinds, so it could not separate them even in principle while their
 * anchor requirements genuinely differ. And it returned empty for an anchorless job, with the
 * comment <em>"an anchorless job is refused by nothing here"</em>. That reasoning is sound for
 * the RESTATEMENT rule, which has nothing to compare against — and it is not an argument that
 * the row should exist.</p>
 *
 * <p><b>The distinction this class exists to pin is NOT "job versus area" but
 * "absent versus unstated".</b> The five-argument form means the caller is not telling us about
 * the anchor; the six-argument form means it is. For a member-anchored kind those are different
 * facts, and conflating them would either admit the hole or break three callers that hold no
 * anchor by construction. {@code JobFormTest} pins the five-argument side; this class pins the
 * six.</p>
 */
class AnAnchorlessDescriptionIsRefusedTest {

    private static final String GOOD = "Answers the gauge's last sampled reading in degrees.";

    @Test
    @DisplayName("D32: a member-anchored description naming no member is refused, and says both ways out")
    void aJobWithNoAnchorIsRefusedByName() {
        Optional<EntryForm.Refusal> refused =
            EntryForm.check("job", GOOD, List.of(), null, null, null);

        assertTrue(refused.isPresent(), "a job that names no member points at nothing");
        String message = refused.get().message();
        assertAll(
            () -> assertEquals("symbol", refused.get().field(),
                "refused on the field that is missing, so a caller knows what to supply"),
            () -> assertTrue(message.contains("pkg.Type#member"),
                () -> "and it shows the form of the thing to pass: " + message),
            () -> assertTrue(message.contains("area"),
                () -> "and names the OTHER way out, because an author whose row is really about "
                    + "a package has a correct home for it rather than only a refusal: "
                    + message));
    }

    /**
     * THE CONTROL FOR THE KIND. Without it the assertion above is satisfied by a rule that
     * refuses every code-lane row with no anchor, which would break the area path entirely.
     */
    @Test
    @DisplayName("D32: an area naming no member is admitted — it anchors to a package")
    void anAreaWithNoMemberIsAdmitted() {
        assertTrue(EntryForm.check("area", GOOD, List.of(), null, null, null).isEmpty(),
            "an area says what a PACKAGE is for, so having no member anchor is its normal "
                + "state, not a defect — and a rule that could not tell the two kinds apart "
                + "would have to refuse this too");
    }

    /**
     * THE CONTROL FOR THE SILENCE, and it is the one that keeps three existing callers working.
     * {@code review}, {@code set_form} and the md ingest hold no anchor by construction.
     */
    @Test
    @DisplayName("D32: the form that does not state an anchor stays silent, which is not the same as absent")
    void notStatingAnAnchorIsNotTheSameAsHavingNone() {
        assertAll(
            () -> assertTrue(
                EntryForm.check("job", GOOD, List.of(), null, null).isEmpty(),
                "the five-argument form means 'I am not telling you about the anchor'. Three "
                    + "production callers hold none by construction, and refusing them would "
                    + "reject correct rows for an absence of context their authors do not have"),
            () -> assertTrue(
                EntryForm.check("job", GOOD, List.of(), null, null, null).isPresent(),
                "while the six-argument form with a null anchor is SAYING there is none — the "
                    + "same 'I could not check' against 'it is not there' line this store "
                    + "draws at every other gate"));
    }

    /**
     * The property itself, asserted directly. D32's measure names it: the discriminator must be
     * a property of the TYPE and not a list of type names — so the property has to exist and be
     * askable, not merely be implied by how the refusal happens to behave.
     */
    @Test
    @DisplayName("D32: each code kind carries what it anchors to, and the lane set is derived from them")
    void theKindCarriesItsAnchorAndTheLaneSetIsDerived() {
        assertAll(
            () -> assertEquals(CodeKind.Anchor.MEMBER, CodeKind.JOB.anchorsTo(),
                "a job hangs on one member"),
            () -> assertEquals(CodeKind.Anchor.PACKAGE, CodeKind.AREA.anchorsTo(),
                "an area hangs on a package — which is the difference the old name list could "
                    + "not express, because it contained both"),
            () -> assertTrue(CodeKind.of("JOB").isPresent(),
                "the lookup is case-insensitive, as the wire strings reach it"),
            () -> assertTrue(CodeKind.of("lesson").isEmpty(),
                "and a non-code type answers EMPTY, so one question replaces asking whether "
                    + "the lane applies and then asking which kind"),
            // DERIVED, not written twice. Two lists of one population drift, and this
            // codebase has closed that shape repeatedly.
            () -> assertEquals(CodeKind.wires(), KnowledgeLane.CODE_TYPES,
                "the lane's membership set is the kinds' own wire strings, so adding a third "
                    + "code kind is a one-place change"),
            () -> assertFalse(KnowledgeLane.CODE_TYPES.isEmpty(),
                "proof of life: an equality between two empty sets would pass having compared "
                    + "nothing"));
    }
}
