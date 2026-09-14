package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WITH NO FOLDER CONFIGURED, AN ACCEPTED STORY IS WRITTEN INTO THE STORE'S OWN FOLDER.
 *
 * <p><b>The case (2026-09-14).</b> {@code /memorize} tells the agent that accepting an entry
 * writes its story file. The export was off unless the engine was started with
 * {@code -Djawata.stories.dir}, and no engine studio deploys sets it — so two lessons
 * accepted that day produced no file, silently. The store already knows its folder (the one
 * its stories were loaded from); that is now where an acceptance writes, and {@code off} is
 * the explicit way to write nothing.</p>
 */
class StoryExportDefaultTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        System.clearProperty(StoryWriter.DIRECTORY_PROPERTY);
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(StoryWriter.DIRECTORY_PROPERTY);
        store.close();
    }

    /** Give the store a story folder the way a real one gets it: by loading a story from it. */
    private void loadAStoryFrom(Path dir) throws Exception {
        Files.writeString(dir.resolve("the-loaded-one.md"),
            "---\nname: the-loaded-one\ndescription: \"a story about the loaded one that a"
            + " reader could act on\"\ntype: domain_fact\n"
            + "situation: I am checking where an accepted story is exported to\n"
            + "reviewed: 2026-09-14\n---\nthe body of the loaded one\n");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        assertTrue(tool.execute(a).isSuccess());
    }

    private String recordLesson() {
        String summary = "an accepted lesson is mirrored into a story file [" + UUID.randomUUID() + "]";
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", summary);
        a.put("situation", "when a reader accepts a row and expects its story file");
        a.put("verdict", "worked");
        a.putArray("symptoms").add("the accepted row had no file beside the other stories");
        assertTrue(tool.execute(a).isSuccess());
        return store.all().stream().filter(e -> summary.equals(e.summary()))
            .findFirst().orElseThrow().id();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> accept(String id) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "promote");
        a.put("id", id);
        a.put("status", ExperienceEntry.ACCEPTED);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "promote failed: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    @Test
    void anAcceptanceWritesIntoTheStoresOwnFolderWhenNoneIsConfigured(@TempDir Path dir)
            throws Exception {
        loadAStoryFrom(dir);
        Map<String, Object> data = accept(recordLesson());

        assertTrue(data.containsKey("story"),
            () -> "no folder is configured, and the store has one — the acceptance must still"
                + " write the story: " + data);
        Path written = Path.of(String.valueOf(data.get("story")));
        assertEquals(dir.toRealPath(), written.getParent().toRealPath(),
            "the story must land beside the stories the store was loaded from");
        assertTrue(Files.exists(written), "and the file must be on disk: " + written);
    }

    /**
     * THE EXPORTED FILE IS READ BACK ON THE NEXT LOAD, AND MUST NOT BECOME A SECOND ROW.
     *
     * <p>Found the first time this default ran: {@code NoDeletionInNormalOperationTest}
     * went from 190 rows to 191 on a reload, because the loader took the exported story for
     * new knowledge. A studio-deployed engine reloads its story folder on every start, so
     * every accepted entry would have doubled.</p>
     */
    @Test
    void reloadingTheFolderAfterAnAcceptanceAddsNoRow(@TempDir Path dir) throws Exception {
        loadAStoryFrom(dir);
        String id = recordLesson();
        assertTrue(accept(id).containsKey("story"), "PRECONDITION: the story was written");
        long before = store.count();

        ObjectNode reload = mapper.createObjectNode();
        reload.put("kind", "load");
        reload.put("path", dir.toString());
        assertTrue(tool.execute(reload).isSuccess());

        assertEquals(before, store.count(),
            "the exported story is the accepted row, not new knowledge");
    }

    /**
     * A STORY LOADED FROM THE FOLDER, THEN ACCEPTED, WRITES NO SECOND FILE AND LOADS ONCE.
     *
     * <p>Found by the architect watch over v4.3.3 before release: every acceptance exported,
     * so accepting a loaded story wrote a second file and moved its row onto it. The original
     * file then had no row, and the next load inserted it again.</p>
     */
    @Test
    void acceptingALoadedStoryWritesNoSecondFileAndReloadsOnce(@TempDir Path dir)
            throws Exception {
        loadAStoryFrom(dir);
        String id = store.all().stream()
            .filter(e -> e.sourceRef() != null && e.sourceRef().endsWith("the-loaded-one.md"))
            .findFirst().orElseThrow().id();
        long before = store.count();

        assertFalse(accept(id).containsKey("story"),
            "the loaded story already has its file — no second one is written");
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "the folder still holds only the loaded story");
        }
        assertTrue(store.all().stream().anyMatch(e -> id.equals(e.id())
                && e.sourceRef().endsWith("the-loaded-one.md")),
            "the row still belongs to its own file");

        ObjectNode reload = mapper.createObjectNode();
        reload.put("kind", "load");
        reload.put("path", dir.toString());
        assertTrue(tool.execute(reload).isSuccess());
        assertEquals(before, store.count(), "reloading the folder adds no row");
    }

    @Test
    void offWritesNothing(@TempDir Path dir) throws Exception {
        loadAStoryFrom(dir);
        System.setProperty(StoryWriter.DIRECTORY_PROPERTY, StoryWriter.OFF);
        Map<String, Object> data = accept(recordLesson());

        assertFalse(data.containsKey("story"), () -> "off means no file: " + data);
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "only the story that was loaded may be in the folder");
        }
    }
}
