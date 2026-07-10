package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21 Stage 5 — the domain-layer primer + flat text rendering. */
class ExperiencePrimerTest {

    private H2ExperienceStore store;
    private ExperienceRetrieval retrieval;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        retrieval = new ExperienceRetrieval(store, () -> null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void putAccepted(String type, String summary, String scopeKind) {
        ExperienceEntry.Builder b = ExperienceEntry.of(
            SymbolFact.of(type, summary, Confidence.HIGH).build()).status(ExperienceEntry.ACCEPTED);
        if (scopeKind != null) {
            b.scopeKind(scopeKind);
        }
        store.put(b.build());
    }

    @Test
    void primer_returns_domain_nodes_only() {
        putAccepted("domain_fact", "the system is about orders and fulfilment", null);
        putAccepted("note", "a concept in a bounded context", "bounded_context"); // domain via scope
        putAccepted("lesson", "guard the lifecycle", null);                        // NOT domain
        // A domain node that is only a candidate must not appear.
        store.put(ExperienceEntry.of(SymbolFact.of("domain_fact", "unpromoted", Confidence.LOW).build())
            .build());

        Map<String, Object> primer = retrieval.primer(20);
        assertEquals(ExperienceRetrieval.RESULT_PRIMER, primer.get("result"));
        assertEquals(2, primer.get("count"), "two accepted domain nodes (type + scope), lesson excluded");
    }

    @Test
    void primer_absence_when_no_domain_knowledge() {
        putAccepted("lesson", "not a domain node", null);
        Map<String, Object> primer = retrieval.primer(20);
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, primer.get("result"));
    }

    @Test
    void renderText_renders_lines_for_a_match() {
        store.put(ExperienceEntry.of(
            SymbolFact.of("failure_mode", "compose_method rolled back here", Confidence.HIGH)
                .symbol("com.example.OrderService").details("recursive body").build()).build());
        Map<String, Object> r = retrieval.recall(new RecallQuery("com.example.OrderService", null, null, null, null));
        String text = ExperienceRetrieval.renderText(r);
        assertTrue(text.startsWith("[failure_mode] compose_method rolled back here"), text);
        assertTrue(text.contains("recursive body"), "details rendered");
    }

    @Test
    void renderText_renders_message_for_absence() {
        Map<String, Object> r = retrieval.recall(new RecallQuery("com.nothing.Here", null, null, null, null));
        assertEquals("No known knowledge for this cue.", ExperienceRetrieval.renderText(r));
    }
}
