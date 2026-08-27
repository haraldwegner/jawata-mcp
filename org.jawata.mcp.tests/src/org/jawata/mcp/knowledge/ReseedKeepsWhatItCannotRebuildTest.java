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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A WIPE MAY ONLY DELETE WHAT SOMETHING CAN PUT BACK.
 *
 * <p><b>The concept that was missing (2026-08-27).</b> A reseed deleted every
 * row and reloaded from files. Only ONE of the store's several origins has a
 * file behind it, so the other three were destroyed with no way back and no
 * word said:</p>
 *
 * <ul>
 *   <li><b>the pattern catalogue</b> — built from a snapshot compiled into the
 *       product; it returned only at the next resident start;</li>
 *   <li><b>a direct record</b> — {@code experience(kind=record)} writes no file,
 *       so nothing anywhere could restore it. The tool's own help text WARNED
 *       about this rather than preventing it.</li>
 * </ul>
 *
 * <p>The store has carried a {@code provenance_kind} column since v10 and no
 * verb ever read it to decide anything — a field that decides nothing. This
 * class pins the rule that replaces it: a reseed owns the FILE-DERIVED lane and
 * touches nothing else, and it reports what it kept.</p>
 *
 * <p><b>Within its lane the reseed stays TOTAL, deliberately.</b> A first cut
 * of this fix scoped the delete to the reseed's path, which read as safer and
 * broke the curation instrument: excluding a source by reseeding a narrower
 * root is HOW the legacy corpus was cleaned out, and the four
 * {@link TombstoneTest} cases went red because kept-out pollution was suddenly
 * kept in. An excluded file source is not a silent loss — it is reported, it is
 * tombstoned, the file still exists, and a reseed of its root revives it.</p>
 */
class ReseedKeepsWhatItCannotRebuildTest {

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

    private long withPrefix(String prefix) {
        return store.all().stream()
            .filter(e -> e.sourceRef() != null && e.sourceRef().startsWith(prefix))
            .count();
    }

    private long recorded() {
        return store.all().stream().filter(e -> e.sourceRef() == null).count();
    }

    private void story(Path dir, String name) throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: \"a story a reader could act on\"\n"
            + "type: domain_fact\n"
            + "situation: I am checking what a reseed is allowed to delete\n"
            + "reviewed: 2026-08-27\n---\nthe body of " + name + "\n");
    }

    /** Write one row with no file behind it, the way an agent or hook does. */
    private void recordDirectly() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "domain_fact");
        a.put("summary", "a fact written straight into the store, with no file behind it");
        assertTrue(tool.execute(a).isSuccess());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reseed(Path dir) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "reseed");
        a.put("path", dir.toString());
        a.put("recursive", true);
        a.put("confirm", true);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "reseed failed: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    /**
     * THE WHOLE RULE, in one run: four origins go in; the file lane is rebuilt
     * — in-scope reloaded, out-of-scope removed AND tombstoned — and the two
     * lanes no crawl can restore are untouched.
     *
     * <p>Asserted as ONE test on purpose. Each origin alone would pass against
     * an implementation that got the others wrong, and it is the combination
     * that was broken — the old code treated every row as if it were the first
     * kind.</p>
     */
    @SuppressWarnings("unchecked")
    @Test
    void a_reseed_rebuilds_the_file_lane_and_touches_nothing_else(
            @TempDir Path mine, @TempDir Path elsewhere) throws Exception {
        new PatternCatalogueLoader().load(store);
        long catalogue = withPrefix(PatternCatalogueLoader.SOURCE_PREFIX);
        assertTrue(catalogue > 0, "precondition: the bundled snapshot must actually seed");

        story(mine, "in-scope");
        story(elsewhere, "out-of-scope");
        ObjectNode load = mapper.createObjectNode();
        load.put("kind", "load");
        load.put("path", elsewhere.toString());
        assertTrue(tool.execute(load).isSuccess());

        recordDirectly();
        assertEquals(1L, recorded(), "precondition: one row with no file behind it");

        Map<String, Object> report = reseed(mine);

        assertEquals(catalogue, withPrefix(PatternCatalogueLoader.SOURCE_PREFIX),
            "THE CATALOGUE: a reseed cannot rebuild it, so it must not delete it");
        assertEquals(1L, recorded(),
            "THE DIRECT RECORD: nothing anywhere can put this back, and it was"
                + " being destroyed silently on the store's routine repair");
        assertEquals(0L, store.all().stream()
                .filter(e -> e.sourceRef() != null
                    && e.sourceRef().contains("out-of-scope"))
                .count(),
            "ANOTHER ROOT is EXCLUDED — that is the curation instrument, not a"
                + " loss: the file survives on disk and a reseed of its root"
                + " revives it");
        assertTrue(store.tombstonedRefs().stream().anyMatch(r -> r.contains("out-of-scope")),
            "and the exclusion is TOMBSTONED, so the deploy-time crawl cannot"
                + " re-import what this reseed deliberately kept out");
        assertEquals(1L, store.all().stream()
                .filter(e -> e.sourceRef() != null && e.sourceRef().contains("in-scope"))
                .count(),
            "AND THE ONE IN SCOPE is back, so this is a rebuild and not a refusal");

        assertEquals(1, report.get("tombstoned"), () -> "" + report);
        Map<String, Object> kept = (Map<String, Object>) report.get("kept");
        assertEquals((int) catalogue, kept.get("catalogue"), () -> "" + report);
        assertEquals(1, kept.get("recorded"), () -> "" + report);
    }

    /**
     * The in-scope row IS removed before being reloaded — otherwise this is not
     * a rebuild at all, and a story deleted from disk would live on in the store
     * forever. The rule is "only what the reload restores", not "nothing".
     */
    @Test
    void a_story_whose_file_is_gone_does_not_survive_the_rebuild(@TempDir Path dir)
            throws Exception {
        story(dir, "here-today");
        reseed(dir);
        assertEquals(1L, withPrefix("memory:"));

        Files.delete(dir.resolve("here-today.md"));
        Map<String, Object> report = reseed(dir);

        assertEquals(0L, withPrefix("memory:"),
            "a scoped delete must still DELETE — a reseed that only ever adds"
                + " would make the store a place things can never leave");
        assertEquals(1, report.get("tombstoned"),
            () -> "and the removal is remembered, so the next crawl does not"
                + " re-import it: " + report);
    }
}
