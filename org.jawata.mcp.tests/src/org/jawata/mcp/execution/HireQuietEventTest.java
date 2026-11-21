package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, Stage 27, row (z28): a hire that goes quiet is told to whoever watches, as an event of its own, and not only when something happens
 * to ask. The registry tells {@code stalled} once for a stretch of quiet, tells it again after the hire has acted and gone quiet again, and tells
 * nothing for a hire that is acting or is not quiet for long enough.
 */
class HireQuietEventTest {

    private static List<HireRegistry.Event> stalledEventsOf(String plan, List<HireRegistry.Event> all) {
        return all.stream().filter(event -> "stalled".equals(event.kind()) && plan.equals(event.planId())).toList();
    }

    @Test
    @DisplayName("28h z28: a quiet hire is announced once as stalled; again after it has acted and gone quiet; never while it acts, nor before the ceiling")
    void aHireThatGoesQuietIsAnnouncedOncePerStretchOfQuiet() throws Exception {
        List<HireRegistry.Event> heard = new ArrayList<>();
        Runnable unsubscribe = HireRegistry.shared().subscribe(heard::add);
        try (HireRegistry.Registration ignored = HireRegistry.shared().begin("chief-engineer", "a quiet task z28 event", "lead-z28-event")) {
            HireRegistry.shared().planOpened("plan-z28-quiet-event");
            HireRegistry.shared().noteQuiet(3_600);
            int beforeTheCeiling = stalledEventsOf("plan-z28-quiet-event", heard).size();
            Thread.sleep(1_300);
            HireRegistry.shared().noteQuiet(1);
            HireRegistry.shared().noteQuiet(1);
            List<HireRegistry.Event> first = List.copyOf(stalledEventsOf("plan-z28-quiet-event", heard));
            HireRegistry.shared().noteToolCall("a call the hire made");
            HireRegistry.shared().noteQuiet(1);
            int whileActing = stalledEventsOf("plan-z28-quiet-event", heard).size();
            Thread.sleep(1_300);
            HireRegistry.shared().noteQuiet(1);
            List<HireRegistry.Event> second = stalledEventsOf("plan-z28-quiet-event", heard);

            assertAll(
                () -> assertEquals(0, beforeTheCeiling, "a hire quiet for less than the ceiling was announced as stalled"),
                () -> assertEquals(1, first.size(), "a stretch of quiet was announced " + first.size() + " times, not once"),
                () -> assertTrue(!first.isEmpty() && "lead-z28-event".equals(first.get(0).leadId()) && "chief-engineer".equals(first.get(0).seat()), "the event does not name the hire's lead and seat"),
                () -> assertEquals(1, whileActing, "a hire that had just acted was announced as stalled"),
                () -> assertEquals(2, second.size(), "a second stretch of quiet was not announced"));
        } finally {
            unsubscribe.run();
        }
    }
}
