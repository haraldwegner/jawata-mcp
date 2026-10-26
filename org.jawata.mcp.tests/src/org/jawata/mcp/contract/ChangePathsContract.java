package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.jawata.spi.lang.ChangePaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every language promises about naming the files a change touched (Sprint 28k, Stage 9; contract §4.5 and §18 ring 1): the
 * path it shows for a file is the same each time and names the file, and a modified element that is not the language's own has no
 * file behind it. The lifecycle keys its compile check on these strings, so a path that changes between the ask before a change and
 * the ask after it makes a file look created, and its problems look new.
 */
public abstract class ChangePathsContract {

    /**
     * @return the language's paths
     * @throws Exception when the implementation cannot be built
     */
    protected abstract ChangePaths paths() throws Exception;

    @Test
    @DisplayName("the path shown for a file is stable and names the file")
    void thePathShownIsStableAndNamesTheFile() throws Exception {
        ChangePaths paths = paths();
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "a-folder", "Some.file").toAbsolutePath();

        String first = paths.display(file);

        assertNotNull(first);
        assertEquals(first, paths.display(file), "the same file reads the same");
        assertTrue(first.endsWith("Some.file"), "the shown path names the file: " + first);
    }

    @Test
    @DisplayName("two different files are shown differently")
    void twoFilesAreShownDifferently() throws Exception {
        ChangePaths paths = paths();
        Path one = Path.of(System.getProperty("java.io.tmpdir"), "one.file").toAbsolutePath();
        Path other = Path.of(System.getProperty("java.io.tmpdir"), "other.file").toAbsolutePath();

        assertFalse(paths.display(one).equals(paths.display(other)));
    }

    @Test
    @DisplayName("an element that is not the language's own has no file behind it, and asking about nothing is empty rather than a failure")
    void aForeignElementHasNoFile() throws Exception {
        ChangePaths paths = paths();

        assertTrue(paths.fileOf(new Object()).isEmpty());
        assertTrue(paths.fileOf(null).isEmpty());
    }
}
