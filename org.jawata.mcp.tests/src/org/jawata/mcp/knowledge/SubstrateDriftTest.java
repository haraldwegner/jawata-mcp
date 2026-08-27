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
 * THE DRIFT CHECK — a story written to the substrate and never reseeded in must
 * be VISIBLE, in the response, without anyone remembering to look.
 *
 * <p><b>The failure this exists to end (2026-08-27).</b> Writing the file and
 * reseeding it in are one job. The second half lived only in instruction text,
 * and it was skipped: four stories were authored, cold-read, stamped, committed
 * and reported as remembered while the store held none of them. Every surface
 * agreed that everything was fine, because every surface asked the store what it
 * had rather than asking whether the files had arrived.</p>
 *
 * <p>Instruction text cannot fix that — this project has recorded twice that an
 * agent routes around friction without narrating it, and that the only channels
 * which hold are the response, a hook, or a non-agent watcher. So the number
 * rides {@code stats}, where nobody has to ask for it.</p>
 */
class SubstrateDriftTest {

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

    /** THE CASE, reproduced: a file written beside loaded ones and never ingested. */
    @Test
    void a_written_but_unloaded_story_is_reported_by_stats(@TempDir Path dir) throws Exception {
        story(dir.resolve("loaded.md"), "the-loaded-one");
        load(dir);
        assertEquals(0, substrate().get("unloadedFiles"), "precondition: no drift yet");

        // The failure: the file is authored and committed, and nothing ingests it.
        story(dir.resolve("written-never-reseeded.md"), "the-forgotten-one");

        Map<String, Object> s = substrate();
        assertEquals(1, s.get("unloadedFiles"),
            "a story on disk that no row cites must be COUNTED — this is the"
                + " number whose absence let four stories be reported as remembered"
                + " while the store held none of them");
        assertTrue(String.valueOf(s.get("driftWarning")).contains("reseed"),
            () -> "the warning must name the cure, not merely the fault: " + s);
        assertTrue(String.valueOf(s.get("unloaded")).contains("written-never-reseeded.md"),
            () -> "and it must NAME the file, so the reader can act: " + s);
    }

    /** Loading it clears the drift — the number tracks reality, not a flag. */
    @Test
    void loading_the_file_clears_the_drift(@TempDir Path dir) throws Exception {
        story(dir.resolve("one.md"), "the-first");
        load(dir);
        story(dir.resolve("two.md"), "the-second");
        assertEquals(1, substrate().get("unloadedFiles"));

        load(dir);
        Map<String, Object> s = substrate();
        assertEquals(0, s.get("unloadedFiles"), "the drift is gone once it is in");
        assertFalse(s.containsKey("driftWarning"),
            "and no warning survives a store that agrees with its own substrate");
    }

    /**
     * A store with no ingested entries has no substrate root, so there is
     * nothing to compare against — and it says so rather than reporting a
     * comforting zero.
     */
    @Test
    void no_substrate_means_no_drift_claim() {
        Map<String, Object> s = substrate();
        assertFalse(s.containsKey("unloadedFiles"),
            "with no root there is no drift number to give, and inventing 0 would"
                + " be a clean verdict about files nobody looked for");
        assertTrue(String.valueOf(s.get("note")).contains("no substrate"), () -> s.toString());
    }
}
