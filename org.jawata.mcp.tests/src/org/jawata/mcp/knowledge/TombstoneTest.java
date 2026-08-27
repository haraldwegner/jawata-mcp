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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c (v14) — a curation must STICK: what a reseed deliberately removed
 * stays removed, however often a crawl runs afterwards.
 *
 * <p>The measured failure this pins (2026-08-27): the studio's deploy-time
 * auto-seed crawls the default memory roots with {@code sourceUnchanged} as its
 * only brake, and that check asks the STORE — so after a deliberate wipe/reseed
 * every removed source read as "new" and the whole legacy corpus re-entered on
 * the next deploy (413 rows on 2026-08-26). The tombstone is the store-side
 * memory of the removal; these tests drive it through the production tool
 * verbs, never through hand-set state.</p>
 */
class TombstoneTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void writeNote(Path file, String name, String description) throws Exception {
        Files.writeString(file,
            "---\nname: " + name + "\ndescription: \"" + description + "\"\n"
            + "type: domain_fact\nreviewed: 2026-08-27\n---\nthe body of " + name + "\n");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String kind, String path, boolean confirm) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        if (path != null) {
            a.put("path", path);
        }
        if (confirm) {
            a.put("confirm", true);
        }
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> kind + " failed: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    /**
     * THE SCENARIO, end to end: legacy corpus loaded -> reseed from the
     * substrate excludes it -> the next crawl (the deploy's auto-seed) does
     * NOT bring it back, and says so out loud.
     */
    @Test
    void a_reseed_excluded_source_stays_out_of_every_later_crawl(@TempDir Path dir) throws Exception {
        Path legacy = Files.createDirectory(dir.resolve("legacy"));
        Path substrate = Files.createDirectory(dir.resolve("substrate"));
        writeNote(legacy.resolve("old-world.md"), "old-world",
            "a note from the pre-rebuild corpus that the crawl keeps finding");
        writeNote(substrate.resolve("story.md"), "the-story",
            "a story the substrate deliberately carries");

        call("load", legacy.toString(), false);
        assertEquals(1, store.fileSourceRefs().size(), "the legacy note is in");

        Map<String, Object> reseed = call("reseed", substrate.toString(), true);
        assertEquals(1, reseed.get("tombstoned"),
            "the reseed must SAY it excluded one source — a silent exclusion is"
                + " indistinguishable from a lost one");

        // The "next deploy": the same crawl over the same legacy root.
        Map<String, Object> crawl = call("load", legacy.toString(), false);
        assertEquals(0, crawl.get("loaded"),
            "the removed source came back — the curation did not stick, which is"
                + " the measured 2026-08-26 re-pollution");
        assertEquals(1, crawl.get("tombstoned"),
            "and the skip is LOUD, never silent");
        assertEquals(1, store.fileSourceRefs().size(),
            "the store still holds exactly the substrate's story");
    }

    /**
     * REVIVAL IS A DELIBERATE ACT WITH THE SAME WEIGHT AS REMOVAL: a reseed of
     * a root that CONTAINS the file ingests it and clears its tombstone.
     */
    @Test
    void a_reseed_of_a_root_containing_the_file_revives_it(@TempDir Path dir) throws Exception {
        Path legacy = Files.createDirectory(dir.resolve("legacy"));
        Path substrate = Files.createDirectory(dir.resolve("substrate"));
        writeNote(legacy.resolve("note.md"), "the-note", "a note that comes back on purpose");
        writeNote(substrate.resolve("story.md"), "the-story", "the substrate story");

        call("load", legacy.toString(), false);
        call("reseed", substrate.toString(), true);
        assertEquals(1, store.tombstonedRefs().size(), "precondition: the note is dead");

        call("reseed", legacy.toString(), true);
        assertTrue(store.tombstonedRefs().stream().noneMatch(r -> r.contains("note.md")),
            "reseeding a root that contains the file is the revival — the"
                + " tombstone must not outlive the user's own decision to reload it");
        assertEquals(1, store.fileSourceRefs().size(), "and the note is back in");
    }

    /**
     * THE SECOND RESEED MUST NOT AMNESTY THE FIRST ONE'S REMOVALS. Reseed #2
     * wipes the store — including the tombstone table — so without carrying the
     * existing tombstones through its before-set, it would erase reseed #1's
     * curation and the next deploy would re-pollute. This is the control for
     * exactly that line; revert it and this goes red.
     */
    @Test
    void a_second_reseed_carries_the_first_ones_tombstones_forward(@TempDir Path dir) throws Exception {
        Path legacy = Files.createDirectory(dir.resolve("legacy"));
        Path substrate = Files.createDirectory(dir.resolve("substrate"));
        writeNote(legacy.resolve("old.md"), "old", "the removed legacy note");
        writeNote(substrate.resolve("story.md"), "story", "the substrate story");

        call("load", legacy.toString(), false);
        call("reseed", substrate.toString(), true);   // removes + tombstones old.md
        call("reseed", substrate.toString(), true);   // the routine repair, again

        assertTrue(store.tombstonedRefs().stream().anyMatch(r -> r.contains("old.md")),
            "reseed #2 erased reseed #1's curation — the next deploy re-imports"
                + " the legacy corpus");
        Map<String, Object> crawl = call("load", legacy.toString(), false);
        assertEquals(0, crawl.get("loaded"), "and the crawl proves it end to end");
    }

    /** A bare wipe means "empty store, fresh world" — tombstones go with it. */
    @Test
    void a_bare_wipe_clears_tombstones_too(@TempDir Path dir) throws Exception {
        Path legacy = Files.createDirectory(dir.resolve("legacy"));
        Path substrate = Files.createDirectory(dir.resolve("substrate"));
        writeNote(legacy.resolve("old.md"), "old", "the removed legacy note");
        writeNote(substrate.resolve("story.md"), "story", "the substrate story");
        call("load", legacy.toString(), false);
        call("reseed", substrate.toString(), true);
        assertEquals(1, store.tombstonedRefs().size());

        store.wipe();
        assertTrue(store.tombstonedRefs().isEmpty(),
            "wipe is not a reseed: it clears everything, curation included");
    }

    /** The count is visible in stats — zero and absent must not read alike. */
    @Test
    void stats_carries_the_tombstone_count() {
        assertEquals(0, store.stats().get("tombstones"),
            "present at zero — an absent key would make 'nothing curated' and"
                + " 'mechanism missing' the same reading");
        store.tombstone("memory:/somewhere/gone.md", "test removal");
        assertEquals(1, store.stats().get("tombstones"));
        assertFalse(store.sourceUnchanged("memory:/somewhere/gone.md", "anyhash"),
            "a tombstone is not a row — sourceUnchanged answers about live"
                + " entries only, and the crawl's skip comes from the tombstone"
                + " check, not from here");
    }
}
