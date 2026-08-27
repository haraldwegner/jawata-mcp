package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * v15, found by DOGFOODING the released build (2026-08-27) — an analogy must
 * carry the cause, on both the structured and the text path.
 *
 * <p><b>The case.</b> A live recall against the rebuilt store returned five
 * comparable experiences and not one of them said what problem it solves. The
 * cause had been wired into the entry renderer and into the nomination
 * candidates, and the analogy carrier — a third path — was missed. That is the
 * shape this project has recorded before: two rendering paths, one keeps the
 * rule and the other quietly lapses, and {@link ExperienceAnalogies#toMaps}'s
 * own javadoc claims it cannot happen.</p>
 *
 * <p><b>Why the analogy needs it MOST.</b> Its framing is <i>"judge whether it
 * transfers"</i>. One symptom has many causes, so a list of analogies IS a
 * differential; without the diagnoses it asks the reader to discriminate on
 * nothing but a summary.</p>
 */
class AnalogyCarriesTheCauseTest {

    private static final String CAUSE =
        "websocket delivery is not guaranteed and the messages are not resent";

    private static StoredEntry entry(String cause) {
        return new StoredEntry("id-1", "lesson", null, null, null, "accepted",
            "high", "java", null, "read the order status with a REST GET", List.of(),
            null, null, null, java.time.Instant.EPOCH, Map.of(),
            new StoredEntry.Facets("when an ack has not arrived in time", cause,
                "worked", "recorded", 1, null, null));
    }

    private static Map<String, Object> mapOf(StoredEntry e) {
        List<Map<String, Object>> maps = ExperienceAnalogies.toMaps(
            List.of(new ExperienceAnalogies.Analogy(e, List.of("similar symptom"), null)));
        assertEquals(1, maps.size());
        return maps.get(0);
    }

    /** THE STRUCTURED PATH — the answer an agent parses. */
    @Test
    void the_structured_analogy_carries_the_cause() {
        assertEquals(CAUSE, mapOf(entry(CAUSE)).get("cause"),
            "an analogy without its diagnosis asks the reader to judge transfer"
                + " with the one field that decides it missing");
    }

    /**
     * THE TEXT PATH — the line a hook injects. Rendered from the SAME map, so
     * the two surfaces cannot disagree.
     */
    @Test
    void the_rendered_analogy_line_carries_the_cause() {
        String line = ExperienceRetrieval.renderAnalogyLine(mapOf(entry(CAUSE)));
        assertTrue(line.contains(CAUSE),
            () -> "the text line dropped the cause the structured answer carries: " + line);
        assertTrue(line.contains("In a similar situation:"), () -> line);
    }

    /**
     * An entry with NO cause emits no key and no empty parenthetical — absence
     * stays absence rather than becoming a blank claim.
     */
    @Test
    void an_analogy_without_a_cause_says_nothing_rather_than_something_empty() {
        Map<String, Object> m = mapOf(entry(null));
        assertFalse(m.containsKey("cause"), "no cause means no key");
        String line = ExperienceRetrieval.renderAnalogyLine(m);
        assertFalse(line.contains("because"),
            () -> "an absent diagnosis must not render as an empty because: " + line);
    }
}
