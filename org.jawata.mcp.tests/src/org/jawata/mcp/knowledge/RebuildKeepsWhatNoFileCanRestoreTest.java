package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * <p><b>This class declares its own hang backstop, and it is the slowest in the
 * suite (2026-09-12).</b> Measured at a shard's fair share of the cores: 173 s for
 * the class. That is legitimate work, not a defect — but it was long enough to
 * out-sit the runner's process-level stall watchdog under shard contention, and
 * that watchdog HALTS THE WHOLE SHARD: the run then reported {@code failed=0} over
 * half the tests, which reads green. So the budget is declared HERE, where it can
 * name the test that blew it and fail only that test.</p>
 *
 * <p><b>The 173 s is ONE test, and the average would have mispriced this.</b> The
 * first cut read 173 s over three tests as ~58 s each and set 600 s, calling it ten
 * times the measurement. The control disproved it: with the budget cut to 1 s,
 * exactly ONE test failed and two passed, so the other two finish inside a second
 * and {@code a_reseed_rebuilds_the_file_lane_and_touches_nothing_else} is
 * essentially the whole 173 s. A backstop is priced off the WORST run; an average
 * over an uneven distribution is not that number.</p>
 *
 * <p>Hence 1800 s. The multiplier that matters is the slowest machine this runs on,
 * not this one: {@code Sweeps.DEADLINE_MILLIS} records a two-core CI runner
 * measuring about five times slower than this twenty-core box, which puts the worst
 * legitimate run near 865 s. 1800 s clears that with room, and it costs nothing when
 * things are healthy — a passing test returns when its work is done and never waits
 * for the deadline.</p>
 *
 * <p><b>What this does NOT do, measured rather than assumed: it does not interrupt.</b>
 * JUnit's default thread mode is {@code SAME_THREAD}, so the deadline is checked
 * when the method RETURNS. The 1 s control run above still took 176 s — the test
 * ran its full length and the timeout was reported afterwards. So this catches a
 * test that is slow and FINISHES, naming it and failing only it; a test that is
 * genuinely WEDGED never returns, this never fires, and the runner's process-level
 * backstop is the only thing left. That is the partition on purpose, and it is why
 * that backstop still exists — see {@code SpikeTestMain.startWatchdog}.</p>
 */
@Timeout(value = 1800, unit = TimeUnit.SECONDS)
class RebuildKeepsWhatNoFileCanRestoreTest {

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

    /** The call, built once — so the refusal case drives the SAME request the others do. */
    private ObjectNode reseedArgs(Path dir) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "wipe_and_import");
        a.put("path", dir.toString());
        a.put("recursive", true);
        a.put("confirm", true);
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reseed(Path dir) {
        ToolResponse r = tool.execute(reseedArgs(dir));
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
        // S6 DISPOSITION: identifier swap ONLY. The loader became a record plus one
        // seeder, so the same seeding is spelled differently; not one assertion,
        // message or expected value below is touched. This test's contract — a
        // reseed rebuilds the file lane and touches nothing else — is unchanged,
        // which is why it may be edited at all: a refactor that needed its meaning
        // changed would be changing the contract, not the spelling.
        CatalogueOrigin fork = CatalogueSources.all().stream()
            .filter(o -> "java-design-patterns".equals(o.namespace()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the fork origin is not registered"));
        CatalogueSeeder.seed(store, fork);
        long catalogue = withPrefix(fork.prefix());
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

        assertEquals(catalogue, withPrefix(fork.prefix()),
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
     *
     * <p><b>Sprint 28f D3 changed how this is expressed, and the new form is
     * stronger.</b> It used to load ONE story, delete its file, and rebuild from the
     * now-empty root. D3 refuses an empty root — that is the 2026-09-08 shape, where a
     * rebuild from nothing emptied the lane and reported success — so the case is
     * written with a SURVIVOR instead. That distinguishes "the rebuild deleted the one
     * whose file is gone" from "the rebuild deleted everything", which the single-story
     * version could not tell apart at all.</p>
     */
    @Test
    void a_story_whose_file_is_gone_does_not_survive_the_rebuild(@TempDir Path dir)
            throws Exception {
        story(dir, "here-today");
        story(dir, "here-tomorrow");
        reseed(dir);
        assertEquals(2L, withPrefix("memory:"));

        Files.delete(dir.resolve("here-today.md"));
        Map<String, Object> report = reseed(dir);

        assertEquals(1L, withPrefix("memory:"),
            "a scoped delete must still DELETE — a rebuild that only ever adds would"
                + " make the store a place things can never leave. And exactly ONE"
                + " row goes: the survivor is what tells this apart from a rebuild"
                + " that emptied the lane");
        assertEquals(1, report.get("tombstoned"),
            () -> "and the removal is remembered, so the next crawl does not"
                + " re-import it: " + report);
    }

    /**
     * Sprint 28f D3 — and the form this test used to take is now REFUSED.
     *
     * <p>Deleting every file and rebuilding from the empty root used to be how a
     * caller emptied the file lane. It is also, exactly, the accident of 2026-09-08:
     * the verb reported {@code loaded=0} and a completed rebuild while the lane it had
     * just emptied was gone. The capability is not lost — {@code wipe} removes
     * everything and says so in its name — but it can no longer happen by accident to
     * someone who pointed a rebuild at the wrong directory.</p>
     *
     * <p>Kept HERE, beside the test whose shape it replaced, so the retired capability
     * is documented where a reader would look for it rather than only in a commit.</p>
     */
    @Test
    void a_rebuild_from_a_root_that_lost_every_file_is_refused(@TempDir Path dir)
            throws Exception {
        story(dir, "the-only-one");
        reseed(dir);
        assertEquals(1L, withPrefix("memory:"));

        Files.delete(dir.resolve("the-only-one.md"));
        ToolResponse refused = tool.execute(reseedArgs(dir));

        assertFalse(refused.isSuccess(),
            "a rebuild from a root with nothing left in it must be refused, not"
                + " reported as a completed rebuild that happened to find nothing");
        assertEquals(1L, withPrefix("memory:"),
            "AND THE ROW IS STILL THERE. That is the whole difference: the old shape"
                + " deleted first and asked afterwards");
    }
}
