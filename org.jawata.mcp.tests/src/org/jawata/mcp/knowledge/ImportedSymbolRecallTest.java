package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * C4 investigation, minimal reproduction: an entry brought in through
 * {@code importEntries} (the export/import round-trip the E2 gate uses) must be
 * recallable by its EXACT symbol — that is the live behaviour the C0 baseline
 * recorded (`kw=hit` on the FQN cue), and the E2 harness shows it failing.
 * Whichever side this test convicts, the fix is aimed at the right place.
 */
class ImportedSymbolRecallTest {

    private static final String FQN =
        "com.jats2.gateways.alpol.alpaca.orders.OrderProcessor#getAllPositions";

    @Test
    void an_imported_entry_is_recallable_by_its_exact_symbol() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            Map<String, Object> report = store.importEntries(List.of(Map.of(
                "id", "d20ce2c6-test-0000-0000-000000000000",
                "type", "failure_mode",
                "status", "accepted",
                "confidence", "high",
                "summary", "Drift watchdog inverts SHORT positions",
                "symbol_fqn", FQN,
                "body", Map.of("type", "failure_mode",
                               "symbol", FQN,
                               "summary", "Drift watchdog inverts SHORT positions"))));
            assertEquals(1, report.get("imported"), "the row must land: " + report);

            List<StoredEntry> gathered =
                store.query(new RecallQuery(FQN, null, null, null, null));
            assertEquals(1, gathered.size(),
                "phase-1 must gather the entry by exact symbol; got " + gathered);
            assertEquals(FQN, gathered.get(0).symbolFqn(),
                "and the anchor must have survived the import");

            ExperienceRetrieval recall = new ExperienceRetrieval(store, () -> null);
            Map<String, Object> answer =
                recall.recall(new RecallQuery(FQN, null, null, null, null));
            assertEquals(ExperienceRetrieval.RESULT_MATCH, answer.get("result"),
                "the fit gate must confirm an exact-symbol match; got " + answer);
        } finally {
            store.close();
        }
    }
}
