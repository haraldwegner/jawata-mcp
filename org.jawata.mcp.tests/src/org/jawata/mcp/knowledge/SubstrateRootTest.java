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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code stats} REPORTS WHAT THE STORE KNOWS ABOUT ITSELF, AND TOUCHES NO DISK.
 *
 * <p><b>The incident this guards (2026-08-29).</b> A drift check was added to the
 * substrate block: it walked the substrate root and counted markdown files no row
 * cited. The root is DERIVED as the common ancestor of every ingested entry's source
 * path, which on a real machine collapses to the user's home directory — so every
 * {@code stats} call scanned the whole home directory. The Studio calls {@code stats}
 * on a five-second timeout purely to learn whether the resident answers at all; the
 * client abandoned each call at five seconds while the resident kept scanning, and the
 * retry started another. Sixty-four concurrent home-directory scans per resident, all
 * twenty cores at 100%, load average 114 — for as long as that view stayed open.</p>
 *
 * <p><b>Why the old test could not catch it.</b> It drove the check against a
 * {@code @TempDir} holding two files, where the walk is instant. The fixture made the
 * defect invisible by construction: the feature was only ever exercised on a directory
 * small enough to hide what it did on a real one.</p>
 *
 * <p>So the rule, and it is Harald's: <i>if the store is up, answering stats should be
 * done in milliseconds.</i> A filesystem scan is not a store statistic. The test below
 * asserts the ABSENCE of that scan behaviourally — it puts an unloaded file where the
 * old check would have found one, and requires that {@code stats} does not report it.
 * It goes red the moment a walk returns to this path, under any name.</p>
 */
class SubstrateRootTest {

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

    private void story(Path file, String name) throws Exception {
        Files.writeString(file,
            "---\nname: " + name + "\ndescription: \"a story about " + name
            + " that a reader could act on\"\ntype: domain_fact\n"
            + "situation: I am checking whether the substrate and the store agree\n"
            + "reviewed: 2026-08-27\n---\nthe body of " + name + "\n");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> substrate() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "stats");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "stats failed: " + r.getError());
        return (Map<String, Object>) ((Map<String, Object>) r.getData()).get("substrate");
    }

    private void load(Path dir) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        assertTrue(tool.execute(a).isSuccess());
    }

    /**
     * THE REGRESSION GUARD: an unloaded file sits beside a loaded one, and
     * {@code stats} must not go looking for it.
     *
     * <p>The precondition matters as much as the assertion. Loading one file is what
     * gives the store a substrate root at all — without it the block short-circuits on
     * "no ingested entries" and every absence below would pass for the wrong reason.
     * With a root present, a scan is possible, and the point is that none happens.</p>
     */
    @Test
    void statsDoesNotScanTheSubstrateRoot(@TempDir Path dir) throws Exception {
        story(dir.resolve("loaded.md"), "the-loaded-one");
        load(dir);

        Map<String, Object> withRoot = substrate();
        assertTrue(withRoot.get("root") != null,
            () -> "PRECONDITION: one ingested entry must give the block a root, or the"
                + " absences below prove nothing — they would hold for a store that"
                + " simply had nowhere to look: " + withRoot);

        // Exactly the case the deleted check existed to report.
        story(dir.resolve("written-never-reseeded.md"), "the-forgotten-one");

        Map<String, Object> s = substrate();
        assertFalse(s.containsKey("unloadedFiles"),
            () -> "stats must not count files on disk. Reaching the filesystem here is"
                + " what pinned twenty cores on 2026-08-29, because the root is the"
                + " user's home directory on any real machine: " + s);
        assertFalse(s.containsKey("driftWarning"),
            () -> "and it must not warn about them either — the warning is the scan's"
                + " output, so its presence means the scan ran: " + s);
        assertFalse(s.containsKey("unloaded"),
            () -> "nor list them: " + s);
    }

    /**
     * A store with no ingested entries has no substrate root, and says so rather than
     * offering a plausible directory — an invented path is the same failure as a value
     * invented to satisfy a rule.
     */
    @Test
    void noIngestedEntriesMeansNoRoot() {
        Map<String, Object> s = substrate();
        assertTrue(s.get("root") == null,
            () -> "with nothing ingested there is no root to derive: " + s);
        assertTrue(String.valueOf(s.get("note")).contains("no substrate"),
            () -> "and the absence must be stated, not left to be inferred: " + s);
    }
}
