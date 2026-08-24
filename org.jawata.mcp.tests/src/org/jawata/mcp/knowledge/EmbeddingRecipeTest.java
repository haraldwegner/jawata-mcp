package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.Map;

import org.jawata.mcp.embed.EmbedderIdentity;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28c C1 — the embedded DOCUMENT is part of the embedder identity.
 *
 * <p>The defect this class exists to make impossible is not a crash. Adding
 * {@code situation} to the text a row is reduced to changes every vector the
 * recipe produces; the backfill re-embeds a row only when its stored identity
 * differs from the current one. Change the text and leave the identity alone,
 * and the ~2,480 vectors already in a real store stay as they were while new
 * rows are computed from the new document — two incomparable populations,
 * scored against each other, ranking and passing floors exactly like real
 * numbers. Every test would stay green and nothing would report it.</p>
 *
 * <p>So the coupling is asserted rather than remembered. {@link EmbedderIdentity}
 * already refuses to compare vectors across identities; what it could not see
 * was a recipe change that never announced itself as one.</p>
 */
class EmbeddingRecipeTest {

    /**
     * A fixed row, so the digest below depends on the RECIPE and nothing else.
     * Every field is distinguishable in the output, which is what lets the
     * digest notice a dropped or reordered one.
     */
    private static final String SITUATION = "when a consumer reconnects mid-batch";
    private static final String SUMMARY = "re-read the queue head before re-arming";
    private static final String DETAILS = "the head moves while the consumer is away";

    /**
     * The recipe's fingerprint, per identity version. A row is added here ONLY
     * together with a deliberate recipe change and its version bump.
     *
     * <p>v1 (Sprint 27): summary, details. v2 (Sprint 28c): situation, summary,
     * details.</p>
     */
    private static final Map<Integer, String> RECIPE_DIGEST_BY_VERSION = Map.of(
        1, sha256(SUMMARY + " " + DETAILS),
        2, sha256(SITUATION + " " + SUMMARY + " " + DETAILS),
        // v3 (Sprint 28c D13) repeats v2's digest, and that is the honest entry
        // rather than a copy-paste: documentOf is UNCHANGED. What changed is that
        // the pipeline now also produces three PER-FIELD vectors per row, pinned
        // by their own digests below. The version had to move anyway — the
        // backfill selects on identity, so without a bump every existing row
        // would keep its current-identity composite, never be revisited, and
        // leave all three lanes empty forever.
        3, sha256(SITUATION + " " + SUMMARY + " " + DETAILS));

    /**
     * The LANE recipes, per identity version — the half {@code documentOf}'s
     * digest cannot see.
     *
     * <p>Absent before v3, because the lanes did not exist. A version that
     * produces lanes must pin what each lane embeds, for exactly the reason the
     * composite is pinned: change which field a lane reads without moving the
     * version, and rows embedded under the old assignment keep their identity,
     * are never re-embedded, and are scored against rows embedded under the new
     * one — with the situation lane holding summaries on half the corpus and
     * nothing anywhere reporting it.</p>
     */
    private static final Map<Integer, String> LANE_DIGEST_BY_VERSION = Map.of(
        3, sha256(SITUATION + "|" + SUMMARY + "|" + DETAILS));

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * <p>THE COUPLING. Changing {@code documentOf} without bumping
     * {@code CURRENT_VERSION} leaves the digest disagreeing with the table row
     * for the current version — red. Bumping the version without adding a row
     * is red too, because the table has no entry to compare against. Doing both
     * deliberately, with a new row, is green.</p>
     *
     * <p>It is one assertion rather than two independent ones on purpose: two
     * would let someone update the expected text and walk past the version
     * entirely, which is precisely the change that must not be quiet.</p>
     */
    @Test
    void the_document_recipe_and_the_embedder_identity_move_together() {
        String expected = RECIPE_DIGEST_BY_VERSION.get(EmbedderIdentity.CURRENT_VERSION);
        assertNotNull(expected,
            "EmbedderIdentity.CURRENT_VERSION is " + EmbedderIdentity.CURRENT_VERSION
                + " and RECIPE_DIGEST_BY_VERSION has no row for it. If you bumped the "
                + "version, add the digest of the recipe that version produces.");

        assertEquals(expected,
            sha256(EmbeddingService.documentOf(SITUATION, SUMMARY, DETAILS)),
            "EmbeddingService.documentOf produces a DIFFERENT document than identity "
                + "version " + EmbedderIdentity.CURRENT_VERSION + " describes. If you "
                + "changed the recipe you MUST bump EmbedderIdentity.CURRENT_VERSION and "
                + "add its digest here — otherwise vectors computed from the old text "
                + "keep their current identity, are never re-embedded, and get scored "
                + "against vectors computed from the new one.");
    }

    /**
     * THE SAME COUPLING, for the per-field lanes.
     *
     * <p>Swap two branches of {@code Lane.documentFor} — situation reading the
     * summary and back — and this goes red naming both. Without it that swap is
     * invisible: the composite digest above is unaffected, every row still gets
     * three vectors, every count still reconciles, and retrieval simply weighs
     * the wrong field at 0.6 forever.</p>
     */
    @Test
    void the_lane_recipes_and_the_embedder_identity_move_together() {
        String expected = LANE_DIGEST_BY_VERSION.get(EmbedderIdentity.CURRENT_VERSION);
        assertNotNull(expected,
            "EmbedderIdentity.CURRENT_VERSION is " + EmbedderIdentity.CURRENT_VERSION
                + " and LANE_DIGEST_BY_VERSION has no row for it. A version that "
                + "produces per-field vectors must pin what each lane embeds.");

        assertEquals(expected,
            sha256(EmbeddingService.Lane.SITUATION.documentFor(SITUATION, SUMMARY, DETAILS)
                + "|" + EmbeddingService.Lane.SUMMARY.documentFor(SITUATION, SUMMARY, DETAILS)
                + "|" + EmbeddingService.Lane.DETAILS.documentFor(SITUATION, SUMMARY, DETAILS)),
            "a lane embeds a DIFFERENT field than identity version "
                + EmbedderIdentity.CURRENT_VERSION + " describes — so rows written "
                + "before the change keep their identity, are never re-embedded, and "
                + "are ranked against rows whose lanes hold different fields");
    }

    /**
     * An absent field yields NO lane text, so it yields no vector and
     * {@code RelevanceMerge} scores it zero.
     *
     * <p>The alternative — an empty string — would have to mean "absent" in
     * three places instead of one, and the one place that forgot would embed
     * whitespace into a real point in the space that some questions land near.</p>
     */
    @Test
    void a_field_the_entry_does_not_have_produces_no_lane_text() {
        assertNull(EmbeddingService.Lane.SITUATION.documentFor(null, SUMMARY, DETAILS),
            "a legacy row declares no situation; that lane must be absent, not blank");
        assertNull(EmbeddingService.Lane.DETAILS.documentFor(SITUATION, SUMMARY, "   "),
            "and whitespace is absence too — it embeds to a point, not to nothing");
        assertEquals(SUMMARY, EmbeddingService.Lane.SUMMARY.documentFor(null, SUMMARY, null),
            "while a field that IS there is embedded as itself, not as a composite");
    }

    /** The situation is in the document, and leads it: an anchorless question is a situation. */
    @Test
    void the_document_leads_with_the_situation() {
        assertTrue(EmbeddingService.documentOf(SITUATION, SUMMARY, DETAILS).startsWith(SITUATION),
            "a question asked with no code anchor IS a situation, so the field the store "
                + "now demands must be the one the vector leads with");
        assertEquals(SUMMARY + " " + DETAILS,
            EmbeddingService.documentOf(null, SUMMARY, DETAILS),
            "and a row that declares no situation — every pre-28c entry — reduces to "
                + "exactly what it did before, with no leading separator");
    }

    /**
     * The half that matters at runtime: a row carrying a PREVIOUS identity is
     * re-embedded, so the corpus converges on one document rule instead of
     * holding two.
     *
     * <p>Revert {@code CURRENT_VERSION} to 1 and this goes red — the row's
     * stored identity would equal the current one, the backfill's
     * {@code embedder_identity <> ?} would not select it, and the vector
     * computed under the old recipe would live on unnoticed.</p>
     */
    @Test
    void a_row_embedded_under_the_previous_recipe_is_found_stale_and_re_embedded()
            throws Exception {
        EmbeddingService svc = EmbeddingService.shared();
        if (!svc.available()) {
            // Not an abort dressed as a pass: with no embedder there is no vector to
            // make stale, and the degrade path is asserted by EmbeddingStoreTest.
            return;
        }
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String id = store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", SUMMARY, Confidence.MEDIUM).details(DETAILS).build())
                .situation(SITUATION)
                .verdict("worked")
                .form(1)
                .build());

            String current = svc.identityKey();
            byte[] fresh = vectorOf(store, id);
            assertNotNull(fresh, "the write path embedded it");

            // Age the row: the vector a PREVIOUS release computed, under the recipe
            // that release used. Written through SQL because no production path can
            // produce a stale row on purpose — which is the point.
            byte[] old = EmbeddingService.toBytes(
                svc.embed(SUMMARY + " " + DETAILS));
            try (PreparedStatement ps = store.sharedConnection().prepareStatement(
                    "UPDATE experience_entry SET embedding = ?, embedder_identity = ?"
                        + " WHERE id = ?")) {
                ps.setBytes(1, old);
                ps.setString(2, EmbedderIdentity.MINILM_L6_V2 + "/384/v1");
                ps.setString(3, id);
                ps.executeUpdate();
            }
            assertNotEquals(current, identityOf(store, id),
                "precondition: the row now carries a previous identity");

            int done = new EmbeddingIndex(store, svc).backfill(100);

            assertTrue(done >= 1, "the backfill found the stale row: re-embedded " + done);
            assertEquals(current, identityOf(store, id),
                "and brought it to the current identity, so it is comparable again");
            assertArrayEquals2(fresh, vectorOf(store, id),
                "the re-embedded vector is the one the CURRENT recipe produces — the same "
                    + "the write path computed. If these differ, the backfill and the write "
                    + "path disagree about the document, which is the one thing documentOf "
                    + "exists to prevent");
        }
    }

    private static byte[] vectorOf(H2ExperienceStore store, String id) throws Exception {
        try (PreparedStatement ps = store.sharedConnection().prepareStatement(
                "SELECT embedding FROM experience_entry WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    private static String identityOf(H2ExperienceStore store, String id) throws Exception {
        try (PreparedStatement ps = store.sharedConnection().prepareStatement(
                "SELECT embedder_identity FROM experience_entry WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void assertArrayEquals2(byte[] a, byte[] b, String message) {
        assertEquals(a == null ? -1 : a.length, b == null ? -1 : b.length, message);
        if (a != null && b != null) {
            for (int i = 0; i < a.length; i++) {
                assertEquals(a[i], b[i], message + " (byte " + i + ")");
            }
        }
    }
}
