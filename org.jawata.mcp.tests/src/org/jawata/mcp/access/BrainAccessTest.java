package org.jawata.mcp.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Third audit 2026-11-20, finding A: a project can be loaded or unloaded with no access question after it (the engine loads at its start, a
 * brain calls load_project and then reads with its own tools), so the file a brain's guard reads must follow the projects on its own.
 */
class BrainAccessTest {

    @AfterEach
    void withdraw() {
        BrainAccess.publish(null, null);
    }

    @Test
    @DisplayName("third audit 2026-11-20 (finding A): the brains' file follows a project loaded and one unloaded with no access question in between")
    void theBrainsFileFollowsTheProjectsWithNoAccessQuestion(@TempDir Path root) throws Exception {
        Path core = root.resolve("core");
        Path later = root.resolve("later");
        AtomicReference<List<Path>> loaded = new AtomicReference<>(List.of(core));
        BrainAccess.publish(loaded::get, root.resolve("editor-folders"));
        Path file = Path.of(BrainAccess.environment().get(BrainAccess.ACCESS_FOLDERS_FILE));
        assertEquals(core.toString(), Files.readString(file), "CONTROL: the file does not hold the projects at the brain's start");

        loaded.set(List.of(core, later));
        String afterALoad = waitFor(file, core + "\n" + later);
        loaded.set(List.of(later));
        String afterAnUnload = waitFor(file, later.toString());

        assertEquals(core + "\n" + later, afterALoad, "a project loaded with no access question after it did not reach the brains' file");
        assertEquals(later.toString(), afterAnUnload, "a project unloaded with no access question after it stayed in the brains' file");
    }

    /** The file's text once it says {@code expected}, or what it says after three beats. */
    private static String waitFor(Path file, String expected) throws Exception {
        long until = System.currentTimeMillis() + 3 * BrainAccess.KEEP_SECONDS * 1000;
        String text = Files.readString(file);
        while (!text.equals(expected) && System.currentTimeMillis() < until) {
            Thread.sleep(100);
            text = Files.readString(file);
        }
        return text;
    }
}
