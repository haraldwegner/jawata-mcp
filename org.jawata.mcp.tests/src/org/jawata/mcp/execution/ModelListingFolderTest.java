package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 28h-C26-7: listing a brain's models opened its session in a fresh scratch folder each time. Claude and Cursor each keep a record per folder a
 * session was opened in, under the person's home, which nothing removed, so every listing left one behind. A listing now opens its brain in
 * one named folder, kept between listings, so each brain keeps one record in all.
 */
class ModelListingFolderTest {

    @Test
    @DisplayName("28h-C26-7: every listing uses the same folder, it exists, and it is named for the user and for this engine's listings")
    void everyListingUsesTheSameFolder() throws Exception {
        Path first = AcpBrainSession.listingFolder();
        Path second = AcpBrainSession.listingFolder();

        assertAll(
            () -> assertEquals(first, second, "THE CLAIM: two listings were given two folders, which is one record under the home each"),
            () -> assertTrue(Files.isDirectory(first), "the folder was not made: " + first),
            () -> assertTrue(first.getFileName().toString().startsWith("jawata-models-"), String.valueOf(first)),
            () -> assertTrue(first.getFileName().toString().endsWith(System.getProperty("user.name")),
                "the folder is not named for the user, so another user's folder of that name would be used: " + first));
    }

    @Test
    @DisplayName("28h-C26-7: the folder is made again when something removed it, rather than a listing failing")
    void aRemovedFolderIsMadeAgain() throws Exception {
        Path folder = AcpBrainSession.listingFolder();
        Files.delete(folder);

        Path again = AcpBrainSession.listingFolder();

        assertAll(
            () -> assertEquals(folder, again),
            () -> assertTrue(Files.isDirectory(again), "the folder was not made again: " + again));
    }
}
