package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * REL44-16 — the first watch of a file does not call its findings "new since your change".
 *
 * <p><b>The case.</b> On the running engine an extract on one file was answered with a naming
 * finding on its logger constant, and a signature change on another with an empty catch in a method
 * the change never touched — both standing in the committed files before the edit, both presented
 * under "new since your change — design fix or bandage?". The watch keys findings by member, not by
 * line, so a line shift was not the cause: a file the watch has never looked at has no stored
 * baseline, the empty baseline admits every finding, and the whole pre-existing backlog was
 * reported as introduced by the change.</p>
 *
 * <p>The first look still SHOWS what it found — knowing a file's smells before building on it is
 * the watch's job — but it says they may predate the change, because nothing recorded the file
 * before it. A stored baseline, even an empty one, is a look that happened, and from then on a
 * finding outside it IS new.</p>
 */
class WatchFirstLookIsNotCalledNewTest {

    private H2ExperienceStore store;
    private LearnerEventStore events;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static Map<String, Object> finding(String symbol, String message) {
        return Map.of("symbol", symbol, "line", 7, "message", message);
    }

    @Test
    void theFirstLookSaysItsFindingsMayPredateTheChange() {
        List<Map<String, Object>> current = new ArrayList<>();
        current.add(finding("com.acme.Billing#locate", "Empty catch block swallows exception"));
        WatchEngine engine = new WatchEngine((kind, path) -> "bugs".equals(kind)
            ? ToolResponse.success(Map.of("findings", List.copyOf(current)))
            : ToolResponse.success(Map.of("findings", List.of())), events);

        String first = engine.watch("s1", List.of("Billing.java")).orElseThrow();

        assertTrue(first.contains("Empty catch block"), "the first look still shows what it found");
        assertFalse(first.contains("new since your change"),
            "but does not call a finding nobody recorded before the change NEW: " + first);
        assertTrue(first.contains("may predate your change"),
            "it says what it can and cannot know: " + first);

        // From the second look on there IS a record, so a finding outside it is new.
        current.add(finding("com.acme.Billing#charge", "== on strings"));
        String second = engine.watch("s1", List.of("Billing.java")).orElseThrow();
        assertTrue(second.contains("new since your change"), second);
        assertTrue(second.contains("== on strings"), second);
        assertFalse(second.contains("Empty catch block"), "the recorded finding stays silent");
    }

    @Test
    void aStoredEmptyBaselineIsALookThatHappened() {
        List<Map<String, Object>> current = new ArrayList<>();
        WatchEngine engine = new WatchEngine((kind, path) -> "bugs".equals(kind)
            ? ToolResponse.success(Map.of("findings", List.copyOf(current)))
            : ToolResponse.success(Map.of("findings", List.of())), events);

        assertTrue(engine.watch("s1", List.of("Clean.java")).isEmpty(),
            "the control: a clean file's first look says nothing");
        current.add(finding("com.acme.Clean#run", "null deref"));
        String after = engine.watch("s1", List.of("Clean.java")).orElseThrow();

        assertTrue(after.contains("new since your change"),
            "a file looked at and found clean has a record, so a finding now is new: " + after);
    }
}
