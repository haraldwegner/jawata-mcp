package org.jawata.mcp.learn;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 26 Stage 2 (D1): the watch engine — delta-scoped, baseline-diffed,
 * budget-capped, decay-suppressed; every emission journaled. Fake detector
 * (the functional seam); the real binding is exercised by the registry
 * integration and the C7 wiring proof.
 */
class WatchEngineTest {

    private H2ExperienceStore store;
    private LearnerEventStore events;

    private static Map<String, Object> finding(String kind, String file, int line, String msg) {
        return Map.of("kind", kind, "filePath", file, "line", line, "message", msg);
    }

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    @Test
    void only_NEW_findings_surface_and_the_baseline_advances() {
        List<Map<String, Object>> current = new ArrayList<>();
        WatchEngine engine = new WatchEngine((kind, path) ->
            "bugs".equals(kind)
                ? ToolResponse.success(Map.of("findings", List.copyOf(current)))
                : ToolResponse.success(Map.of("findings", List.of())), events);

        current.add(finding("bugs", "A.java", 5, "== on strings"));
        Optional<String> first = engine.watch("s1", List.of("A.java"));
        assertTrue(first.isPresent(), "a fresh finding surfaces");
        assertTrue(first.get().contains("design fix or bandage?"));
        assertTrue(first.get().contains("== on strings"));

        // Same finding again: baselined — silent.
        assertTrue(engine.watch("s1", List.of("A.java")).isEmpty(),
            "an already-baselined finding never re-surfaces");

        // A NEW finding on the same file surfaces alone.
        current.add(finding("bugs", "A.java", 9, "null deref"));
        Optional<String> second = engine.watch("s1", List.of("A.java"));
        assertTrue(second.isPresent());
        assertTrue(second.get().contains("null deref"));
        assertFalse(second.get().contains("== on strings"), "old finding stays silent");
    }

    @Test
    void the_noise_budget_caps_emissions_per_answer() {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(finding("bugs", "B.java", i, "smell-" + i));
        }
        WatchEngine engine = new WatchEngine((kind, path) ->
            "bugs".equals(kind)
                ? ToolResponse.success(Map.of("findings", many))
                : ToolResponse.success(Map.of("findings", List.of())), events);
        String block = engine.watch("s1", List.of("B.java")).orElseThrow();
        long lines = block.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(WatchEngine.NOISE_BUDGET, lines, "at most the budget rides one answer");
    }

    @Test
    void emissions_are_journaled_as_watch_finding_events() {
        WatchEngine engine = new WatchEngine((kind, path) ->
            "unused".equals(kind)
                ? ToolResponse.success(Map.of("findings",
                    List.of(finding("unused", "C.java", 3, "never called"))))
                : ToolResponse.success(Map.of("findings", List.of())), events);
        engine.watch("s7", List.of("C.java"));
        assertEquals(1L, events.countByKind()
            .getOrDefault(LearnerEvent.KIND_WATCH_FINDING, 0L).longValue());
    }

    @Test
    void a_failing_detector_is_skipped_never_fatal() {
        AtomicInteger calls = new AtomicInteger();
        WatchEngine engine = new WatchEngine((kind, path) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("detector boom");
        }, events);
        assertDoesNotThrow(() -> engine.watch("s1", List.of("D.java")));
        assertTrue(calls.get() > 0);
    }

    @Test
    void non_java_and_beyond_cap_files_are_ignored() {
        AtomicInteger calls = new AtomicInteger();
        WatchEngine engine = new WatchEngine((kind, path) -> {
            calls.incrementAndGet();
            return ToolResponse.success(Map.of("findings", List.of()));
        }, events);
        List<String> delta = new ArrayList<>();
        delta.add("pom.xml");
        for (int i = 0; i < WatchEngine.MAX_FILES_PER_CALL + 5; i++) {
            delta.add("F" + i + ".java");
        }
        engine.watch("s1", delta);
        assertEquals(WatchEngine.MAX_FILES_PER_CALL * WatchEngine.WATCH_KINDS.size(),
            calls.get(), "pom.xml skipped; file cap enforced");
    }
}
