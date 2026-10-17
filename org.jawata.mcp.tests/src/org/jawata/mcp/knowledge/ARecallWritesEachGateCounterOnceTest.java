package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * REL44-8 — a recall writes each fit-gate counter ONCE, however many entries it checks.
 *
 * <p><b>The case.</b> The hook's recall missed its 200 ms deadline on ordinary calls once the store
 * held about 11,000 entries, and the store's own counters said why: {@code gate.package.checked}
 * stood at 2,144,863 after a day. The fit gate checks every candidate entry against the cue and
 * counted each check as it went — and counting was a synchronous {@code MERGE} into
 * {@code quality_counter}, two of them per check (the visit and the verdict). A recall over 11,000
 * entries was therefore about 22,000 database writes before it could answer.</p>
 *
 * <p>The property is stated as STATEMENTS, read off H2's own query statistics, not as time: a
 * timing assertion measures the machine it runs on, while the number of writes a recall issues is
 * the cost itself and does not move with load. The counts are unchanged — every check is still
 * counted — only the writes are batched.</p>
 */
class ARecallWritesEachGateCounterOnceTest {

    private static final int ENTRIES = 300;

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void aRecallOverManyEntriesCountsEveryCheckInAHandfulOfWrites() throws SQLException {
        for (int i = 0; i < ENTRIES; i++) {
            store.put(ExperienceEntry.of(SymbolFact.of("lesson",
                    "billing lesson number " + i + " about invoices", Confidence.MEDIUM)
                .scope(List.of("com.acme.billing"), List.of()).build()).build());
        }
        QualityLedger quality = new QualityLedger(store);
        ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
        retrieval.setQualityLedger(quality);
        // The store's own shared connection — the store owns it and closes it in tearDown, so it
        // is borrowed here and deliberately not closed.
        Connection db = store.sharedConnection();
        try (Statement s = db.createStatement()) {
            s.execute("SET QUERY_STATISTICS TRUE");
        }

        retrieval.recall(new RecallQuery(null, "com.acme.billing", null, null, null, LaneScope.everyLane()));

        long counterWrites = counterWrites(db);
        long checked = quality.counters().getOrDefault("gate.package.checked", 0L);
        assertTrue(checked >= ENTRIES,
            "the control: every entry was checked against the package cue, and every check is"
                + " still counted: " + checked);
        assertTrue(counterWrites > 0,
            "the control: the statistics saw the counter writes at all: " + counterWrites);
        assertTrue(counterWrites <= 10,
            "a recall writes each counter once, not once per entry it checks: " + counterWrites
                + " writes for " + checked + " checks");
    }

    /** How many times the ledger's one write statement ran, per H2's own statistics. */
    private static long counterWrites(Connection db) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT SUM(EXECUTION_COUNT) FROM INFORMATION_SCHEMA.QUERY_STATISTICS"
                    + " WHERE SQL_STATEMENT LIKE 'MERGE INTO quality_counter%'");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
