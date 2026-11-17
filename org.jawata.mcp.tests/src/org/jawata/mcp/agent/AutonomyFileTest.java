package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D57 (m): the file the engine hands the principal's brain, holding the chat's one autocontinue state, so the jawata hook inside that brain
 * obeys the button and the typed commands alike.
 */
class AutonomyFileTest {

    @Test
    @DisplayName("D57 (m): the state file starts off, holds on and off as told, and is gone once closed")
    void theStateFileHoldsWhatItIsToldAndIsRemovedWhenClosed() throws Exception {
        AutonomyFile file = AutonomyFile.create();
        Path path = file.path();
        String start = Files.readString(path).strip();
        file.write(true);
        String on = Files.readString(path).strip();
        file.write(false);
        String off = Files.readString(path).strip();
        file.close();

        assertAll(
            () -> assertEquals("off", start, "a new file must not grant anything"),
            () -> assertEquals("on", on),
            () -> assertEquals("off", off),
            () -> assertFalse(Files.exists(path), "the file outlived its brain: " + path),
            () -> assertEquals("JAWATA_AUTONOMY_FILE", AutonomyFile.VARIABLE, "the hook reads this exact variable"));
    }
}
