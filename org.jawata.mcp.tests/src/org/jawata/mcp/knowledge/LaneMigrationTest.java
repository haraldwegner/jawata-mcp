package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f Stage 5 — every row lands in the lane its type was RULED into, and a type
 * nobody ruled on lands in none.
 *
 * <h2>Why this asserts the mapping per type instead of "every row has a lane"</h2>
 *
 * <p>The stage's clause originally ended "the rest &rarr; experience". Under a catch-all the
 * obvious assertion — <i>every existing row lands in exactly one lane</i> — is true whatever
 * the mapping does, including a mapping that files domain facts as experiences. It is an
 * assertion that cannot fail, which is the shape this sprint has refused at C3, C5 and C7.</p>
 *
 * <p>So the catch-all is gone and this test carries the ruling itself: one row per type, each
 * with the lane it was ruled into, checked under {@code assertAll} so a mapping that breaks
 * several types names all of them in one run rather than stopping at the first.
 * {@link #an_unclassified_type_gets_no_lane_at_all()} is the falsifiability: reintroduce any
 * default and it goes red.</p>
 *
 * <h2>The ruling, and where it came from</h2>
 *
 * <p>Measured on the live store 2026-09-12, 384 rows: {@code reference} 190,
 * {@code lesson} 133, {@code domain_fact} 49, {@code failure_mode} 8, {@code api_contract} 4.
 * Only {@code api_contract} reached the old catch-all, plus exactly one {@code reference} that
 * is not the catalogue's (190 rows against a 189-row catalogue).</p>
 *
 * <p>Both were ruled DOMAIN by reading them against the store's own form gate: an experience
 * owes a SITUATION and a VERDICT, because for one the outcome IS the lesson; a fact owes
 * neither. None of those five rows carries either. The {@code api_contract} that settles it on
 * content rather than shape states that {@code IType.getFullyQualifiedName()} separates a
 * nested type with {@code $} where {@code ITypeBinding.getQualifiedName()} uses {@code .} —
 * true whoever reads it, with no situation and no outcome.</p>
 */
class LaneMigrationTest {

    /**
     * The ruling, as data: one row per type, with the provenance that decides the one type
     * whose lane depends on it.
     *
     * <p>This table is the test. Both halves read it — the pure mapping and the migrated
     * database — so the unit answer and the stored answer cannot drift apart, and a type
     * added here without a ruling in {@link KnowledgeLane} turns both halves red.</p>
     */
    private record Ruled(String type, String provenance, KnowledgeLane lane, String why) {

        /** How the failure message names this row, since two entries share a type. */
        String describe() {
            return "type '" + type + "'"
                + (provenance == null ? "" : " (provenance " + provenance + ")");
        }
    }

    private static final List<Ruled> RULED = List.of(
        new Ruled("lesson", null, KnowledgeLane.EXPERIENCE,
            "the stage's own clause: a lesson is what happened in a situation"),
        new Ruled("failure_mode", null, KnowledgeLane.EXPERIENCE,
            "the stage's own clause"),
        new Ruled("reference", CatalogueManifest.PROVENANCE, KnowledgeLane.EXPERIENCE,
            "a borrowed pattern is somebody else's experience — the clause names it"),
        new Ruled("domain_fact", null, KnowledgeLane.DOMAIN,
            "the stage's own clause"),
        new Ruled("api_contract", null, KnowledgeLane.DOMAIN,
            "RULED: a contract states what a thing GUARANTEES. No situation, no verdict,"
                + " so it never turned out any way at all"),
        new Ruled("reference", "recorded", KnowledgeLane.DOMAIN,
            "RULED: a pointer to a resource of ours carries neither a situation nor an"
                + " outcome. The live store holds exactly one of these"),
        new Ruled("naming_convention", null, KnowledgeLane.DOMAIN,
            "a convention is a fact about how we spell things"),
        new Ruled(KnowledgeLane.RULE_TYPE, null, KnowledgeLane.RULES,
            "Stage 5's own type: a rule versions and retires rather than being superseded"));

    /** A type no ruling covers. Deliberately NOT in any of the sets {@link KnowledgeLane} reads. */
    private static final String UNRULED_TYPE = "hazard";

    @Test
    void every_ruled_type_maps_to_its_lane() {
        List<Executable> checks = new ArrayList<>();
        for (Ruled r : RULED) {
            checks.add(() -> assertEquals(r.lane(), KnowledgeLane.of(r.type(), r.provenance()),
                () -> r.describe() + " must be " + r.lane().wire() + " — " + r.why()));
        }
        assertAll("the ruled type -> lane mapping", checks);
    }

    /**
     * THE CLAUSE THAT CAN FAIL, and the reason this class exists in this shape.
     *
     * <p>With a catch-all every type has a lane and no assertion about lanes can go red.
     * Restore one — "the rest maps to experience" — and this test fails, which is the only
     * case a catch-all exists to hide: a type somebody introduced and nobody classified,
     * filed as an experience by a default rather than by a reading.</p>
     */
    @Test
    void an_unclassified_type_gets_no_lane_at_all() {
        assertNull(KnowledgeLane.of(UNRULED_TYPE, null),
            "A TYPE NOBODY HAS RULED ON IS NOT AN EXPERIENCE. It is an unanswered question,"
                + " and the column says so by staying NULL. If this went green with a lane,"
                + " a catch-all is back and every assertion about lanes is unfalsifiable.");
        assertNull(KnowledgeLane.of(null, null), "a null type classifies nothing");
        assertNull(KnowledgeLane.of("  ", null), "and neither does a blank one");
    }

    /** The lane the store actually holds for a row, or null. */
    private static String laneOf(H2ExperienceStore store, String id) throws Exception {
        try (var ps = store.sharedConnection()
                .prepareStatement("SELECT lane FROM experience_entry WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), () -> "the row vanished: " + id);
                return rs.getString(1);
            }
        }
    }

    private static String write(H2ExperienceStore store, String type, String provenance,
                                String summary) {
        ExperienceEntry e = ExperienceEntry.of(
                SymbolFact.of(type, summary, Confidence.MEDIUM).build())
            .status(ExperienceEntry.ACCEPTED)
            .provenanceKind(provenance)
            .build();
        return store.putWithSource(e, "lane-fixture/" + type + "-" + summary.hashCode() + ".md");
    }

    /**
     * The real dispatch, over a store made to look pre-v18.
     *
     * <p><b>The lane is CLEARED before the migration runs, and without that this test would
     * be vacuous.</b> The insert path now sets the lane itself, so a row written here already
     * carries one; winding the version back and re-running {@code migrate} would then assert
     * the WRITER's work while the rung's {@code WHERE lane IS NULL} matched nothing. Clearing
     * it — and asserting it is clear, which is the proof of life below — is what puts the
     * migration on the hook.</p>
     *
     * <p>It drives {@code migrate} rather than the rung, because a rung can be written and
     * left out of the {@code if (from < n)} chain, and no direct call would show that.</p>
     */
    @Test
    void an_upgrade_puts_every_ruled_type_in_its_lane_and_leaves_an_unruled_one_out(
            @TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            Map<Ruled, String> ids = new LinkedHashMap<>();
            for (Ruled r : RULED) {
                ids.put(r, write(store, r.type(), r.provenance(),
                    "a " + r.type() + " row for the lane migration"));
            }
            String unruled = write(store, UNRULED_TYPE, null,
                "a type nobody has classified, recorded before anyone ruled on it");

            Connection c = store.sharedConnection();
            // Make the store look pre-v18: the column exists but nothing has filled it.
            try (Statement s = c.createStatement()) {
                s.execute("UPDATE experience_entry SET lane = NULL");
                s.execute("UPDATE schema_version SET version = 17");
            }
            // PROOF OF LIFE. Without it every assertion after the migration is equally true
            // of a store whose rows the INSERT had already laned.
            List<Executable> cleared = new ArrayList<>();
            for (Map.Entry<Ruled, String> e : ids.entrySet()) {
                cleared.add(() -> assertNull(laneOf(store, e.getValue()),
                    e.getKey().describe() + " must start with NO lane, or the migration is"
                        + " not what is being measured"));
            }
            assertAll("the pre-migration state", cleared);
            assertEquals(17, SchemaMigrations.detectVersion(c),
                "the wind-back must take, or migrate() has nothing to upgrade from");

            SchemaMigrations.migrate(c, dir, false);

            assertEquals(SchemaMigrations.LATEST, SchemaMigrations.detectVersion(c),
                "the upgrade must complete, or the assertions below are about a half-run");

            List<Executable> checks = new ArrayList<>();
            for (Map.Entry<Ruled, String> e : ids.entrySet()) {
                Ruled r = e.getKey();
                checks.add(() -> assertEquals(r.lane().wire(), laneOf(store, e.getValue()),
                    r.describe() + " must migrate into " + r.lane().wire() + " — " + r.why()));
            }
            checks.add(() -> assertNull(laneOf(store, unruled),
                "THE DISCRIMINATOR: '" + UNRULED_TYPE + "' is a type nobody ruled on, and the"
                    + " migration must leave it WITHOUT a lane rather than absorbing it. A"
                    + " catch-all would put it in a lane here and every other assertion in"
                    + " this class would still pass."));
            assertAll("the migrated lanes", checks);
        }
    }

    /**
     * The other half of "set by every writer": a row recorded into a store that is ALREADY at
     * {@link SchemaMigrations#LATEST} is laned by the insert, with no migration in sight.
     *
     * <p>Migrating once and laning on write are two different claims and the migration test
     * cannot make this one — it clears the column precisely so the rung is what it measures.</p>
     */
    @Test
    void a_row_recorded_today_is_laned_by_the_writer(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(SchemaMigrations.LATEST,
                SchemaMigrations.detectVersion(store.sharedConnection()),
                "a freshly opened store is at LATEST, or this measures a migration instead");

            List<Executable> checks = new ArrayList<>();
            for (Ruled r : RULED) {
                String id = write(store, r.type(), r.provenance(),
                    "a " + r.type() + " row recorded after v18");
                checks.add(() -> assertEquals(r.lane().wire(), laneOf(store, id),
                    "the INSERT must lane " + r.describe() + ": a lane set only by the"
                        + " migration would be right once and wrong for every row recorded"
                        + " afterwards"));
            }
            String unruled = write(store, UNRULED_TYPE, null, "recorded, and still unruled");
            checks.add(() -> assertNull(laneOf(store, unruled),
                "and the writer defaults an unruled type no more than the migration does"));
            assertAll("the lanes the writer set", checks);
        }
    }

    /**
     * The mapping's experience half IS {@link EntryForm#EXPERIENCE_TYPES}, not a copy of it.
     *
     * <p>Two lists of "which types are experiences" drift the first time either moves, and the
     * drift is silent — both remain internally consistent. This asserts the derivation rather
     * than the values, so adding a type to the form gate carries it into the lane for free and
     * nobody has to remember a second place.</p>
     */
    @Test
    void the_experience_lane_is_derived_from_the_form_gate() {
        assertTrue(EntryForm.EXPERIENCE_TYPES.size() >= 2,
            "proof of life: an empty form gate would make the loop below assert nothing");
        List<Executable> checks = new ArrayList<>();
        for (String type : EntryForm.EXPERIENCE_TYPES) {
            checks.add(() -> assertEquals(KnowledgeLane.EXPERIENCE, KnowledgeLane.of(type, null),
                () -> "'" + type + "' is held to the experience form, so it is in the"
                    + " experience lane — the lane reads that set rather than copying it"));
        }
        assertAll("every form-gated type is an experience", checks);
    }

    /**
     * The primer's domain set and the lane's domain set are ONE set, moved rather than copied.
     *
     * <p>{@code ExperienceRetrieval} pushes these types unprompted BECAUSE they are
     * domain-layer knowledge, which is the same fact the lane records. This asserts they
     * cannot come apart; it is the control for the move.</p>
     */
    @Test
    void every_domain_type_is_in_the_domain_lane() {
        assertNotNull(KnowledgeLane.DOMAIN_TYPES);
        assertTrue(KnowledgeLane.DOMAIN_TYPES.contains("domain_fact"),
            "proof of life: the set the primer reads must still hold its headline type");
        List<Executable> checks = new ArrayList<>();
        for (String type : KnowledgeLane.DOMAIN_TYPES) {
            checks.add(() -> assertEquals(KnowledgeLane.DOMAIN, KnowledgeLane.of(type, null),
                () -> "'" + type + "' is a domain-layer type and must be in the domain lane"));
        }
        assertAll("every domain type maps to the domain lane", checks);
    }
}
