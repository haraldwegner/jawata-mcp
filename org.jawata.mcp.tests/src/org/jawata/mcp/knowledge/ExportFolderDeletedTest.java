package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE FOLDER IS A MIRROR, SO LOSING IT LOSES NOTHING (Sprint 28f Stage 6).
 *
 * <p><b>This is the test that says which way round the sprint put things.</b> The store
 * used to be rebuilt FROM files, so deleting the folder destroyed knowledge nothing could
 * put back — that is the whole defect this sprint exists to close. Now the database is the
 * truth and the folder is an export, so the folder can be deleted, moved, regenerated or
 * kept in git without the store noticing.</p>
 *
 * <p>The second half matters as much as the first: after the loss the export must still
 * WORK. A mirror that silently stops being written after its directory goes is a mirror
 * that quietly diverges, which is worse than one that is plainly absent.</p>
 */
class ExportFolderDeletedTest {

    @TempDir
    Path stories;

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        System.setProperty(StoryWriter.DIRECTORY_PROPERTY, stories.toString());
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(StoryWriter.DIRECTORY_PROPERTY);
        store.close();
    }

    private String record(String summary) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "domain_fact");
        a.put("summary", summary);
        assertTrue(tool.execute(a).isSuccess());
        return store.all().stream()
            .filter(e -> summary.equals(e.summary()))
            .findFirst().orElseThrow(() -> new AssertionError("the recorded row is missing"))
            .id();
    }

    private void accept(String id) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "promote");
        a.put("id", id);
        a.put("status", ExperienceEntry.ACCEPTED);
        assertTrue(tool.execute(a).isSuccess());
    }

    private List<Path> stories() throws Exception {
        if (!Files.isDirectory(stories)) {
            return List.of();
        }
        try (var s = Files.list(stories)) {
            return s.filter(p -> p.toString().endsWith(".md")).toList();
        }
    }

    @Test
    void deleting_the_folder_costs_the_store_nothing_and_the_next_acceptance_rebuilds_it()
            throws Exception {
        String first = record("the folder is an export and the database is the truth");
        accept(first);
        assertEquals(1, stories().size(), "precondition: the acceptance wrote a file");
        long rowsBefore = store.count();
        assertTrue(rowsBefore > 0);

        // The folder goes, entirely — the case that used to destroy knowledge.
        try (var walk = Files.walk(stories)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception e) {
                    throw new AssertionError("could not delete " + p, e);
                }
            });
        }
        assertFalse(Files.exists(stories), "precondition: the folder really is gone");

        assertEquals(rowsBefore, store.count(),
            "THE STORE IS UNTOUCHED. The folder is a mirror; losing it loses no knowledge");
        assertTrue(store.all().stream().anyMatch(e -> first.equals(e.id())),
            "and the row itself is still there, not merely the count");

        // AND THE EXPORT STILL WORKS. A mirror that stops being written after its
        // directory goes diverges silently, which is worse than one plainly absent.
        String second = record("a later acceptance must recreate the folder it writes into");
        accept(second);
        assertEquals(1, stories().size(),
            "the next acceptance recreated the folder and wrote into it: " + stories());
    }
}
