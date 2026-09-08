package org.jawata.mcp.knowledge;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#48 — <b>upgrading the store several residents share is a fleet-wide event,
 * not a side effect of starting one process.</b>
 *
 * <p>Three individually reasonable things line up into one bad outcome: migrate-on-open is
 * unconditional, {@code shared} is the DEFAULT store mode, and an unknown CLI flag passes
 * through silently by design — the argv is shared with the Eclipse launcher, so a mistyped
 * isolation flag is indistinguishable from a legitimate one. A single dev-build launch
 * therefore upgrades the fleet's store, and {@code migrate} already REFUSES a store newer
 * than the running resident — so every peer loses its knowledge layer at its next restart,
 * with one INFO line as the only warning, already scrolled past by the time it commits.</p>
 *
 * <p><b>The exemption is the clause that matters most, and the issue does not state it.</b> A
 * brand-new store is v0 and is not an upgrade of anything. Refusing on every shared migration
 * would stop a clean install from starting at all — so the guard turns on {@code from >= 1},
 * which is the same condition that already governs the pre-migration backup, because the two
 * are about the same event: an EXISTING store being moved forward.</p>
 */
class SharedStoreUpgradeIsAFleetEventTest {

    private static Connection connect(Path dir) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:"
            + dir.resolve("jawata-experience").resolve("experience").toAbsolutePath()
            + ";DB_CLOSE_ON_EXIT=FALSE");
        return ds.getConnection();
    }

    /** A store that exists and is BEHIND — the only state the guard is about. */
    private static void anExistingStoreOneVersionBehind(Connection c) throws Exception {
        SchemaMigrations.migrate(c, null, false);          // build it, isolated
        try (Statement s = c.createStatement()) {
            s.execute("UPDATE schema_version SET version = " + (SchemaMigrations.LATEST - 1));
        }
    }

    @Test
    @DisplayName("mcp#48: upgrading the SHARED store is refused, and the refusal says what it would do to the peers")
    void aSharedStoreIsNotUpgradedAsASideEffect(@TempDir Path dir) throws Exception {
        try (Connection c = connect(dir)) {
            anExistingStoreOneVersionBehind(c);

            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> SchemaMigrations.migrate(c, dir, true),
                "a shared store that is BEHIND must not be upgraded by one process starting");

            assertAll(
                () -> assertTrue(refusal.getMessage().contains("USER-SHARED"),
                    "the refusal must name which store it is about; got: " + refusal.getMessage()),
                // The consequence, not just the act: a reader who does not know that peers
                // REFUSE a newer store cannot judge whether to override. That is the whole
                // reason this is not a silent INFO line.
                () -> assertTrue(refusal.getMessage().contains("REFUSE"),
                    "it must say what happens to the other residents; got: "
                        + refusal.getMessage()),
                () -> assertTrue(refusal.getMessage().contains(SchemaMigrations.ALLOW_UPGRADE),
                    "and it must name the way through; got: " + refusal.getMessage()));
        }
    }

    @Test
    @DisplayName("mcp#48's most important control: a BRAND-NEW shared store still migrates, so a clean install starts")
    void aFreshSharedStoreIsNotAnUpgradeAndIsNotRefused(@TempDir Path dir) throws Exception {
        try (Connection c = connect(dir)) {
            // Nothing has ever been created here, so detectVersion answers 0. Refusing this
            // would make a first run of a shared-mode resident fail to start — the guard
            // would have replaced a fleet hazard with a worse one.
            Map<String, Object> report = SchemaMigrations.migrate(c, dir, true);

            assertAll(
                () -> assertEquals(0, report.get("from"),
                    "precondition: this really is a fresh store, or the case is not the one "
                        + "the exemption is for; got: " + report),
                () -> assertEquals(true, report.get("migrated"),
                    "a brand-new shared store must migrate — it is not an UPGRADE of anything"),
                () -> assertEquals(SchemaMigrations.LATEST, report.get("to")));
        }
    }

    @Test
    @DisplayName("mcp#48's control: an ISOLATED store still migrates silently — the old behaviour is kept where it belongs")
    void anIsolatedStoreIsUnaffected(@TempDir Path dir) throws Exception {
        try (Connection c = connect(dir)) {
            anExistingStoreOneVersionBehind(c);

            Map<String, Object> report = SchemaMigrations.migrate(c, dir, false);

            assertAll(
                () -> assertEquals(SchemaMigrations.LATEST - 1, report.get("from"),
                    "precondition: it really was behind; got: " + report),
                () -> assertEquals(true, report.get("migrated"),
                    "an isolated store keeps migrating on open — without this the guard is "
                        + "unconditional and has changed every store, not the fleet's"));
        }
    }

    @Test
    @DisplayName("mcp#48: the operator can say yes, and then it proceeds")
    void anExplicitAllowLetsTheUpgradeThrough(@TempDir Path dir) throws Exception {
        try (Connection c = connect(dir)) {
            anExistingStoreOneVersionBehind(c);

            String had = System.getProperty(SchemaMigrations.ALLOW_UPGRADE);
            System.setProperty(SchemaMigrations.ALLOW_UPGRADE, "true");
            try {
                Map<String, Object> report = SchemaMigrations.migrate(c, dir, true);
                assertEquals(true, report.get("migrated"),
                    "an explicit allow must let the fleet upgrade proceed — a guard with no "
                        + "way through is an outage, not a safeguard");
            } finally {
                if (had == null) {
                    System.clearProperty(SchemaMigrations.ALLOW_UPGRADE);
                } else {
                    System.setProperty(SchemaMigrations.ALLOW_UPGRADE, had);
                }
            }
        }
    }
}
