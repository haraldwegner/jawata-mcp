package org.jawata.mcp.models;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage 27 (z4): access to a folder is not reach of the Java tools. A Java tool named a file or folder that exists but lies in no loaded Java
 * project says that it is not a Java project, and a path that does not exist at all is still not found.
 */
class ToolResponseNotAJavaProjectTest {

    @Test
    @DisplayName("a file or a folder that exists but is no part of a loaded Java project is answered as not a Java project, and a path that is not there is still not found")
    void anExistingPlaceOutsideTheJavaProjectsIsNotAJavaProject(@TempDir Path folder) throws Exception {
        Path file = Files.writeString(folder.resolve("package.json"), "{}");

        ToolResponse theFile = ToolResponse.fileNotFound(file.toString());
        ToolResponse theFolder = ToolResponse.fileNotFound(folder.toString());
        ToolResponse missing = ToolResponse.fileNotFound(folder.resolve("Missing.java").toString());

        assertAll(
            () -> assertEquals("NOT_A_JAVA_PROJECT", theFile.getError().getCode(), "an existing file outside the Java projects"),
            () -> assertTrue(theFile.getError().getMessage().contains("not a Java project"), theFile.getError().getMessage()),
            () -> assertEquals("NOT_A_JAVA_PROJECT", theFolder.getError().getCode(), "an existing folder outside the Java projects"),
            () -> assertEquals("FILE_NOT_FOUND", missing.getError().getCode(), "CONTROL: a path that is not there was answered as a non-Java project"));
    }

    @Test
    @DisplayName("C27-27: a relative path has no place of its own, so it is not claimed to exist on disk because a file of that name lies in the engine's working folder")
    void aRelativePathIsJudgedByNoFolderOfTheEngine() throws Exception {
        Path here = Files.createTempFile(Path.of("").toAbsolutePath(), "relative-probe-", ".txt");
        try {
            ToolResponse relative = ToolResponse.fileNotFound(here.getFileName().toString());
            ToolResponse absolute = ToolResponse.fileNotFound(here.toString());

            assertAll(
                () -> assertEquals("FILE_NOT_FOUND", relative.getError().getCode(), "a relative name was resolved against the engine's folder: " + relative.getError().getMessage()),
                () -> assertEquals("NOT_A_JAVA_PROJECT", absolute.getError().getCode(), "CONTROL: the same file named in full was not found"));
        } finally {
            Files.deleteIfExists(here);
        }
    }
}
