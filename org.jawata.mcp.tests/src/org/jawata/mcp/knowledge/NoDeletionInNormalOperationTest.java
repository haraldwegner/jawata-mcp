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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NOTHING IN NORMAL OPERATION MAKES THE ROW COUNT FALL (Sprint 28f Stage 6).
 *
 * <p><b>The defect this exists against, stated plainly.</b> Routine work used to destroy
 * knowledge: a reseed deleted every row and reloaded from files, so the three origins with
 * no file behind them went with nothing able to restore them, and the load path deleted a
 * file's rows before re-inserting them. Both were repairs somebody ran ON PURPOSE, which
 * is what made the loss ordinary rather than exceptional.</p>
 *
 * <p>So this walks the ordinary verbs — record, load, seed, promote, re-load — and asserts
 * after EVERY ONE that the count never fell. Forgetting is not forbidden in this product;
 * it is confined to {@code wipe_and_import}, which takes a backup and a confirmation and
 * is deliberately not exercised here.</p>
 *
 * <p><b>A monotone count is a weak claim on its own</b>, so the walk is asserted too: each
 * step that should ADD is asserted to have added, which is what stops a store that quietly
 * stopped writing from satisfying every assertion below by never changing at all.</p>
 */
class NoDeletionInNormalOperationTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    /** Every count seen so far, so a fall names the step that caused it. */
    private final List<String> trail = new ArrayList<>();
    private long low;

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

    /** Assert the count has not fallen since the last step, and remember it. */
    private long after(String step) {
        long now = store.count();
        trail.add(step + "=" + now);
        assertTrue(now >= low,
            () -> "THE COUNT FELL at '" + step + "': " + now + " < " + low
                + ". Normal operation must not destroy rows. Trail: " + trail);
        low = now;
        return now;
    }

    private void record(String summary) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "domain_fact");
        a.put("summary", summary);
        assertTrue(tool.execute(a).isSuccess());
    }

    private void story(Path dir, String name) throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: " + name + " states something a reader can use\n"
                + "type: domain_fact\nreviewed: 2026-09-12\n---\nthe body of " + name + "\n");
    }

    private void load(Path dir) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        a.put("recursive", true);
        assertTrue(tool.execute(a).isSuccess());
    }

    @Test
    void record_load_seed_promote_and_reload_never_lose_a_row(@TempDir Path dir) throws Exception {
        long start = after("start");
        assertEquals(0, start, "precondition: a fresh store is empty");

        record("a fact written straight into the store");
        long recorded = after("record");
        assertTrue(recorded > start, "the control: recording ADDS, so the walk is live");

        story(dir, "first-story");
        story(dir, "second-story");
        load(dir);
        long loaded = after("load");
        assertTrue(loaded > recorded, "the control: loading two files ADDS");

        CatalogueOrigin fork = CatalogueSources.all().stream()
            .filter(o -> "java-design-patterns".equals(o.namespace()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the fork origin is not registered"));
        CatalogueSeeder.seed(store, fork);
        long seeded = after("seed");
        assertTrue(seeded > loaded, "the control: seeding the catalogue ADDS");

        // A PROMOTE CHANGES A ROW AND MUST ADD NONE AND LOSE NONE.
        String id = store.all().stream()
            .filter(e -> "a fact written straight into the store".equals(e.summary()))
            .findFirst().orElseThrow().id();
        ObjectNode promote = mapper.createObjectNode();
        promote.put("kind", "promote");
        promote.put("id", id);
        promote.put("status", ExperienceEntry.ACCEPTED);
        assertTrue(tool.execute(promote).isSuccess());
        assertEquals(seeded, after("promote"),
            "an acceptance changes a row's status; it neither adds nor removes one");

        // RE-LOADING THE SAME FOLDER IS THE CASE THAT USED TO DELETE. The load path
        // deleted a source's rows before re-inserting them, so a load that died in
        // between took that file's knowledge with it. It upserts now.
        load(dir);
        assertEquals(seeded, after("reload"),
            "re-loading unchanged files rewrites them where they stand: " + trail);

        // AND A CHANGED FILE UPDATES IN PLACE rather than deleting and re-adding.
        Files.writeString(dir.resolve("first-story.md"),
            "---\nname: first-story\ndescription: first-story states something a reader can use\n"
                + "type: domain_fact\nreviewed: 2026-09-12\n---\nthe body of first-story, corrected\n");
        load(dir);
        assertEquals(seeded, after("reload-after-edit"),
            "an edited file updates its own rows: " + trail);
    }
}
