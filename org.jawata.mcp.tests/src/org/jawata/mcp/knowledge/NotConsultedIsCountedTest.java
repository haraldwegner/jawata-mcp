package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * REL44-49 — HOW OFTEN THE LOOKUP ON EVERY RESPONSE SAYS "THE STORE WAS NOT CONSULTED".
 *
 * <p>Measured 2026-10-19: one such answer on the live resident could not be reproduced in 190 timed
 * calls, so the rate has to be counted where it happens, over days. The answer already exists on the
 * wire ({@code unavailable}); what did not exist was a number. A lookup past its deadline is NOT a
 * quiet surface — counting it as {@code silent} would read as "the store knew nothing" — so it has
 * its own counter, and the cases below hold the two apart.</p>
 */
class NotConsultedIsCountedTest {

    private static final String SURFACE = QualityLedger.SURFACE_CHOKE_PRIOR_WORK;

    private static Map<String, Object> recallOver(ExperienceStore searched, QualityLedger ledger,
            long budgetMillis) {
        ExperienceRetrieval retrieval = ExperienceRetrieval.keywordOnly(searched, () -> null);
        retrieval.setQualityLedger(ledger);
        return retrieval.recall(new RecallQuery("com.example.Anything", null, null, null, null,
            LaneScope.everyLane()), SURFACE, budgetMillis);
    }

    @Test
    void a_lookup_that_got_no_answer_in_time_is_counted_as_not_consulted() {
        try (H2ExperienceStore counting = H2ExperienceStore.openMemory()) {
            QualityLedger ledger = new QualityLedger(counting);

            Map<String, Object> answer = recallOver(new UnreadableStore(60_000), ledger, 200);

            assertEquals(ExperienceRetrieval.RESULT_UNAVAILABLE, answer.get("result"));
            Map<String, Long> counters = ledger.counters();
            assertEquals(1L, counters.get("not_consulted." + SURFACE),
                "the lookup went unanswered, and the count says so: " + counters);
            assertNull(counters.get("silent." + SURFACE),
                "a lookup nobody answered is not a surface that stayed quiet: " + counters);
        }
    }

    @Test
    void a_store_that_was_read_and_held_nothing_is_silent_and_not_unconsulted() {
        try (H2ExperienceStore counting = H2ExperienceStore.openMemory();
                H2ExperienceStore empty = H2ExperienceStore.openMemory()) {
            QualityLedger ledger = new QualityLedger(counting);

            Map<String, Object> answer = recallOver(empty, ledger, 5_000);

            assertEquals(ExperienceRetrieval.RESULT_ABSENCE, answer.get("result"),
                "the control: a healthy empty store answers an observed absence");
            Map<String, Long> counters = ledger.counters();
            assertEquals(1L, counters.get("silent." + SURFACE));
            assertNull(counters.get("not_consulted." + SURFACE),
                "reading the store and finding nothing is the opposite of not looking: " + counters);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_statistics_say_how_often_each_surface_was_not_consulted() {
        try (H2ExperienceStore counting = H2ExperienceStore.openMemory()) {
            QualityLedger ledger = new QualityLedger(counting);
            recallOver(new UnreadableStore(60_000), ledger, 200);

            Object group = ledger.statsBlock().get("recalls_not_consulted");

            assertTrue(group instanceof Map, "the quality block has its own group: " + group);
            assertEquals(1L, ((Map<String, Object>) group).get(SURFACE));
        }
    }
}
