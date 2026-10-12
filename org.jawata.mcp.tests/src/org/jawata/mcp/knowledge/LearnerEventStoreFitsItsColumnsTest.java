package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jawata.mcp.learn.LearnerEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A learner event whose bounded fields are longer than their columns is still journalled.
 *
 * <p>Found on the 4.4.0 macOS CI run, where {@code HiredRunProposesTest} lost its journal row:
 * a hired run writes {@code "script:" + the brain's path} into {@code tool}, which is
 * {@code VARCHAR(80)}, and the path under macOS's temporary directory made that value 121
 * characters. H2 refused it on all eight attempts and the event was dropped. Linux passed only
 * because its {@code /tmp} path is short, so the condition is forced here instead of waiting for
 * a platform to supply it: a value of that measured length, on every machine.</p>
 */
class LearnerEventStoreFitsItsColumnsTest {

    /** The measured shape and length of the value that was refused: 121 characters. */
    private static final String MEASURED_TOOL = "script:/var/folders/36/tjdph2t965j8snz9_vkdnw0r0000gn/T/"
        + "jawata-hired-run-2908031234567890123/brain-1234567890123456789.sh";

    private H2ExperienceStore store;
    private LearnerEventStore events;

    @BeforeEach
    void setUp() throws Exception {
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("an event whose tool is longer than its column is journalled, not dropped")
    void anOverWideToolIsStillJournalled() {
        assertEquals(121, MEASURED_TOOL.length(),
            "the fixture is the measured length, which exceeds the column; shorter would measure"
                + " nothing");

        events.append(new LearnerEvent("plan-1", LearnerEvent.KIND_HIRED_RUN, MEASURED_TOOL,
            "{\"artifactId\":\"proposal-1\"}"));

        assertAll(
            () -> assertEquals(1L,
                events.countByKind().getOrDefault(LearnerEvent.KIND_HIRED_RUN, 0L),
                "one event appended, one row journalled"),
            () -> assertEquals(0L, events.failedWrites(),
                "and no write was dropped on the way"));
    }

    @Test
    @DisplayName("a fitted value is unchanged when it fits, and cut to exactly the width with a marker when it does not")
    void fittingKeepsWhatFitsAndMarksWhatWasCut() {
        String fitted = LearnerEventStore.fitted(MEASURED_TOOL, LearnerEventStore.TOOL_WIDTH);
        assertAll(
            () -> assertEquals("claude-code",
                LearnerEventStore.fitted("claude-code", LearnerEventStore.TOOL_WIDTH),
                "a value that fits is written as it is"),
            () -> assertNull(LearnerEventStore.fitted(null, LearnerEventStore.TOOL_WIDTH),
                "and an absent value stays absent rather than becoming text"),
            () -> assertEquals(LearnerEventStore.TOOL_WIDTH, fitted.length(),
                "a cut value is exactly the column's width: " + fitted),
            () -> assertTrue(fitted.startsWith("script:/var/folders/"),
                "it keeps its leading part, which says what kind of brain it was: " + fitted),
            () -> assertTrue(fitted.endsWith("…"),
                "and it says it was cut, so a reader does not take it for the whole path: "
                    + fitted));
    }
}
