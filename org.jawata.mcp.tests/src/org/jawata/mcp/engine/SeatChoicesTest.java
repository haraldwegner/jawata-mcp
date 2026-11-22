package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jawata.mcp.execution.SeatBrains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The host opens the seat store through the exported engine package, and a hire reads that same store. */
class SeatChoicesTest {

    @AfterEach
    void dropTheProcessStore() {
        SeatBrains.uninstall();
    }

    @Test
    @DisplayName("the exported door opens the store a hire reads")
    void theExportedDoorOpensTheStoreAHireReads() throws Exception {
        Path dir = Files.createTempDirectory("seat-choices-");
        Path file = dir.resolve("seats-brains");
        String previous = System.getProperty(SeatBrains.FILE_PROPERTY);
        System.clearProperty(SeatBrains.FILE_PROPERTY);
        try {
            assertEquals(file, SeatChoices.storeFile(dir));
        } finally {
            if (previous == null) {
                System.clearProperty(SeatBrains.FILE_PROPERTY);
            } else {
                System.setProperty(SeatBrains.FILE_PROPERTY, previous);
            }
        }
        SeatChoices.open(file);
        try {
            SeatBrains.Store store = SeatBrains.installed().orElseThrow();
            store.save("pm", "claude-code", "Haiku 5.5",
                "{\"claude-code\":{\"vendor\":\"anthropic\",\"model\":\"Haiku 5.5\"}}");
            SeatBrains.Choice choice = store.choose(
                "{\"claude-code\":{\"vendor\":\"anthropic\",\"model\":\"Haiku 5.5\"}}", "pm");
            assertEquals("claude-code", choice.brain());
            assertEquals("Haiku 5.5", choice.model());
            assertTrue(Files.exists(file));
            assertSame(store, SeatBrains.installed().orElseThrow());
        } finally {
            SeatBrains.uninstall();
        }
    }
}
