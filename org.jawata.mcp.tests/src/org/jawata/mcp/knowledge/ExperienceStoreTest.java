package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21 Stage 0 — H2 experience store: schema + open/close + entry round-trip. */
class ExperienceStoreTest {

    @Test
    void inMemory_put_get_count_roundtrip() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            assertEquals(0L, store.count(), "empty on open");

            SymbolFact fact = SymbolFact.of("domain_fact",
                    "Billing DTOs keep no-arg constructors", Confidence.MEDIUM)
                .symbol("com.example.billing.InvoiceDto")
                .details("legacy XML/Jackson tests depend on them")
                .build();

            String id = store.put(fact);
            assertNotNull(id);
            assertEquals(1L, store.count());

            Optional<Map<String, Object>> got = store.get(id);
            assertTrue(got.isPresent(), "round-trips the stored entry");
            assertEquals("domain_fact", got.get().get("type"));
            assertEquals("com.example.billing.InvoiceDto", got.get().get("symbol"));
            assertEquals("Billing DTOs keep no-arg constructors", got.get().get("summary"));
            assertEquals(Confidence.MEDIUM.wire(), got.get().get("confidence"));

            assertTrue(store.get("no-such-id").isEmpty(), "absent id → empty");
        }
    }

    @Test
    void fileStore_persists_across_reopen(@TempDir Path dir) {
        String id;
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            id = store.put(SymbolFact.of("lesson", "guard the workbench lifecycle", Confidence.HIGH)
                    .symbol("com.example.WorkflowCoordinator").build());
            assertEquals(1L, store.count());
        }
        // Reopen the same file DB — the entry must survive.
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count(), "entry persisted across reopen");
            assertTrue(store.get(id).isPresent());
        }
    }

    /**
     * #37 TRIPWIRE — pins what the DRIVER does, not what we wish it did.
     *
     * <p>The first attempt at #37 called {@code setNetworkTimeout} and reported the
     * connection as bounded. H2 2.2.224 ACCEPTS that call and discards it — the method
     * body is literally {@code return} — so nothing was bounded, and because no exception
     * is thrown, nothing said so either. Verifying that the method EXISTS was mistaken for
     * verifying that it WORKS.
     *
     * <p>When this test FAILS, the driver has started honouring the bound: at that moment
     * the store really is bounded, the UNBOUNDED warning is obsolete, and #37 may finally
     * be described as fixed at the connection. Until then it may not be.
     */
    @Test
    void the_shipped_h2_driver_discards_our_network_timeout(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            java.sql.Connection conn = store.sharedConnection();
            conn.setNetworkTimeout(Runnable::run, H2ExperienceStore.NETWORK_TIMEOUT_MILLIS);
            assertEquals(0, conn.getNetworkTimeout(),
                "H2 2.2.224 discards setNetworkTimeout. A non-zero answer means the driver now"
                + " honours it — update H2ExperienceStore's UNBOUNDED warning and #37's status.");
        }
    }

    /**
     * #37 — the inline-LOB bound must reach the REAL connection, not merely exist in the
     * source. Streaming a value as a remote LOB is what parked a reader in a socket read
     * for 58 minutes while every other store caller queued behind it.
     *
     * <p>Asked of the DATABASE, not of the URL: {@code getMetaData().getURL()} answers the
     * base URL with the settings stripped, so it can neither confirm nor deny the bound.
     * The first version of this test asked the URL and failed for that reason alone.
     */
    @Test
    void the_file_store_keeps_small_values_inline(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir);
             java.sql.Statement s = store.sharedConnection().createStatement();
             java.sql.ResultSet rs = s.executeQuery(
                 "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS"
                     + " WHERE SETTING_NAME = 'MAX_LENGTH_INPLACE_LOB'")) {
            assertTrue(rs.next(), "H2 must report the setting at all");
            assertEquals(String.valueOf(H2ExperienceStore.MAX_INPLACE_LOB_BYTES),
                rs.getString(1),
                "the DATABASE must carry the inline-LOB bound, not just the source");
        }
    }

    /**
     * #37 — the DISCRIMINATOR for the test above: without it, that test would pass whether
     * or not our setting was ever applied.
     *
     * <p>H2 lists {@code MAX_LENGTH_INPLACE_LOB} in {@code INFORMATION_SCHEMA.SETTINGS} only
     * when someone SETS it — a store opened without it reports no row at all. So the row's
     * PRESENCE on the file store is itself the evidence that the URL setting took effect,
     * and this test pins the absence that gives that evidence its meaning.
     */
    @Test
    void h2_does_not_list_the_inline_lob_bound_unless_we_set_it() throws Exception {
        try (H2ExperienceStore plain = H2ExperienceStore.openMemory();
             java.sql.Statement s = plain.sharedConnection().createStatement();
             java.sql.ResultSet rs = s.executeQuery(
                 "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS"
                     + " WHERE SETTING_NAME = 'MAX_LENGTH_INPLACE_LOB'")) {
            assertFalse(rs.next(),
                "a store opened WITHOUT the setting must not report it — if H2 starts listing"
                    + " a default here, the file-store test stops discriminating and needs a"
                    + " value comparison instead of a presence check");
        }
    }
}
