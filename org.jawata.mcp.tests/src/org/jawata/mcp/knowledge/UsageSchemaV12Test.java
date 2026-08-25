package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c D14 — the usage ledger's schema, and the one asymmetry in it.
 *
 * <p>The rung adds two tables that look alike and must not behave alike.
 * {@code usage_entry} counts what happened to a specific entry, so it dies with
 * that entry: a shown-count against a deleted row is not a number about
 * anything, and a reseed mints new ids for everything, which without the
 * cascade would leave a full set of orphaned counters behind on every rebuild,
 * permanently and invisibly.</p>
 *
 * <p>{@code usage_query} is the opposite. It records the question asked and
 * whether anything was chosen, and a question that got NO answer is the one
 * piece of evidence here that has to outlive the corpus it failed against —
 * that is the writing backlog. A rebuild that erased it would erase precisely
 * the instruction for what to write next.</p>
 *
 * <p>So the discriminating test is not "do the tables exist". It is: delete the
 * entries and check that one side went and the other stayed. That fails if the
 * cascade is missing, and it fails just as loudly if someone later gives
 * {@code usage_query} a foreign key because it looked inconsistent without
 * one.</p>
 */
class UsageSchemaV12Test {

    private static boolean hasTable(Connection c, String name) throws Exception {
        try (ResultSet rs = c.getMetaData().getTables(null, null, name.toUpperCase(), null)) {
            return rs.next();
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws Exception {
        try (ResultSet rs = c.getMetaData()
                .getColumns(null, null, table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        }
    }

    private static long rows(Connection c, String table) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * A fresh install is the majority case and the worst one to leave uncovered:
     * asserting only that the version reads 12 would pass with an entirely EMPTY
     * rung, because the version is written unconditionally.
     */
    @Test
    void a_fresh_store_carries_both_usage_tables_and_the_origin_column(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            Connection c = store.borrowRead();
            try {
                assertTrue(hasTable(c, "usage_entry"), "usage_entry missing on a fresh store");
                assertTrue(hasTable(c, "usage_query"), "usage_query missing on a fresh store");
                assertTrue(hasColumn(c, "experience_entry", "origin_client"),
                    "origin_client missing on a fresh store");
            } finally {
                store.releaseRead(c, true);
            }
        }
    }

    @Test
    void counters_die_with_their_entry_and_the_demand_record_outlives_the_corpus(
            @TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            // Built through the production write path, so the v12 rung really ran —
            // a hand-rolled DDL fixture can pass while the real writer is broken.
            String id = store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson",
                        "A count against a deleted entry is not a number about anything.",
                        Confidence.HIGH).symbol("com.example.Usage").build())
                .status(ExperienceEntry.ACCEPTED)
                .situation("when a rebuild replaces every row and mints new ids")
                .verdict("worked")
                .form(1)
                .build());

            Connection c = store.borrowRead();
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO usage_entry (entry_id, shown, chosen) VALUES ('"
                    + id + "', 7, 0)");
                s.execute("INSERT INTO usage_query "
                    + "(asked_at, cue_kind, question, shown_count, chosen) VALUES "
                    + "(CURRENT_TIMESTAMP, 'symptom', "
                    + "'how do I stop a rebuild erasing what nobody answered', 0, FALSE)");
            } finally {
                store.releaseRead(c, true);
            }

            assertEquals(1L, store.wipe(), "the fixture entry should be the only one wiped");

            Connection after = store.borrowRead();
            try {
                assertEquals(0L, rows(after, "usage_entry"),
                    "a counter survived its entry — the cascade is missing, and every "
                        + "reseed will leave orphaned counters behind");
                assertEquals(1L, rows(after, "usage_query"),
                    "the demand record went with the corpus — an unanswered question is "
                        + "the backlog, and it must outlive the entries it failed against");
                try (Statement s = after.createStatement();
                     ResultSet rs = s.executeQuery(
                         "SELECT chosen FROM usage_query")) {
                    rs.next();
                    assertFalse(rs.getBoolean(1), "the surviving row is the unanswered one");
                }
            } finally {
                store.releaseRead(after, true);
            }
        }
    }
}
