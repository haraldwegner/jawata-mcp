package com.example.rel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Maven starts a module's tests in the module's own folder, and tests read files by paths relative
 * to it. Both tests fail when the runner starts them anywhere else.
 */
public class RelativeFileTest {

    @Test
    void readsAFileOfTheSiblingModuleByARelativePath() throws Exception {
        assertEquals("login page", Files.readString(Path.of("../sample-app/data/page.txt")).strip());
    }

    @Test
    void startsInTheFolderOfTheModuleThatOwnsTheTests() {
        assertEquals("test-module", Path.of("").toAbsolutePath().getFileName().toString());
    }
}
