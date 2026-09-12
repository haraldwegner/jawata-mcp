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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE ACCEPTANCE WRITES THE FILE, AND WHAT IT WRITES COMES BACK (Sprint 28f Stage 6).
 *
 * <p><b>This is the test that binds the writer to the parser, and nothing else does.</b>
 * {@code StoryWriter} spells the frontmatter keys that
 * {@code ExperienceMaintenance.parse} reads, and the two deliberately share no constant:
 * a shared constant would keep them spelled alike while saying nothing about whether the
 * parser still READS a key it spells. Here the file goes out through the product's own
 * acceptance and comes back through the product's own loader, so a key the parser stops
 * reading turns this red instead of silently dropping a field on export.</p>
 *
 * <p><b>Why the strong assertion is BYTE-STABILITY ON RE-EXPORT rather than an unchanged
 * row.</b> The loader indexes the prosified file name as a cue, so a re-imported row
 * carries one symptom the original did not. That is the loader's behaviour, it is
 * deterministic, and pretending otherwise would mean either weakening the comparison
 * until it proved nothing or changing the loader to suit its own test. So the knowledge
 * fields are compared exactly, and the FILE is compared byte-for-byte across a second
 * export — which is the property that actually matters: the folder does not churn.</p>
 */
class StoryRoundTripTest {

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

    /** Record one full-shaped lesson and answer its id. */
    private String recordLesson(String summary) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", summary);
        a.put("situation", "when a reader accepts a row that the store must mirror to a file");
        a.put("verdict", "worked");
        a.put("cause", "the export half had no column to read the review stamp from");
        a.put("details", "The mechanism, the evidence and the cost belong here.");
        a.putArray("symptoms").add("the folder disagreed with the database");
        assertTrue(tool.execute(a).isSuccess());
        return store.all().stream()
            .filter(e -> summary.equals(e.summary()))
            .findFirst().orElseThrow(() -> new AssertionError("the recorded row is missing"))
            .id();
    }

    private Map<String, Object> promote(String id) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "promote");
        a.put("id", id);
        a.put("status", ExperienceEntry.ACCEPTED);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "promote failed: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return data;
    }

    private List<Path> filesInFolder() throws Exception {
        try (var s = Files.list(stories)) {
            return s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    private StoredEntry byId(String id) {
        return store.byIds(List.of(id)).stream().findFirst()
            .orElseThrow(() -> new AssertionError("no row " + id));
    }

    /**
     * THE TRIGGER IS ASSERTED, NOT ASSUMED — which is the plan's own wording for this
     * row. The control is the recording itself: a recorded candidate writes nothing, so
     * an implementation that exported on every write would fail here rather than pass
     * the acceptance case by accident.
     */
    @Test
    void recording_writes_nothing_and_accepting_is_what_writes_the_file() throws Exception {
        String id = recordLesson("a reader's acceptance is what puts the story on disk");
        assertTrue(filesInFolder().isEmpty(),
            "a recorded candidate has been reviewed by nobody, so it exports nothing");

        Map<String, Object> data = promote(id);

        List<Path> after = filesInFolder();
        assertEquals(1, after.size(), "the acceptance writes exactly one file: " + after);
        assertNotNull(data.get("story"), "and the response names the file it wrote");
        assertEquals(after.get(0).toString(), data.get("story"));
    }

    /**
     * THE ROUND TRIP: what the acceptance wrote, the product's own loader reads back —
     * every knowledge field intact, including the review stamp without which the
     * re-import would demote the row to candidate.
     */
    @Test
    void what_the_acceptance_wrote_the_loader_reads_back() throws Exception {
        String summary = "the store writes the file and the folder is a mirror of it";
        String id = recordLesson(summary);
        promote(id);
        StoredEntry exported = byId(id);
        assertNotNull(exported.facets().reviewedAt(), "precondition: the acceptance stamped it");

        // A SECOND, EMPTY store reads the folder — so nothing below can be satisfied by
        // the row that is already here.
        try (H2ExperienceStore fresh = H2ExperienceStore.open(null)) {
            ExperienceTool reader = new ExperienceTool(() -> null, fresh);
            ObjectNode load = mapper.createObjectNode();
            load.put("kind", "load");
            load.put("path", stories.toString());
            assertTrue(reader.execute(load).isSuccess());

            StoredEntry back = fresh.all().stream()
                .filter(e -> summary.equals(e.summary()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "the exported story did not come back; the store holds "
                        + fresh.all().stream().map(StoredEntry::summary).toList()));

            assertEquals(exported.type(), back.type(), "the type survives");
            assertEquals(exported.facets().situation(), back.facets().situation(),
                "the situation survives — it is what makes the row findable");
            assertEquals(exported.facets().cause(), back.facets().cause(),
                "the cause survives — the solution binds to it");
            assertEquals(exported.facets().verdict(), back.facets().verdict(),
                "the verdict survives");
            assertEquals(exported.body().get("details"), back.body().get("details"),
                "the details survive — the mechanism, the evidence and the cost");
            assertTrue(back.symptoms().contains("the folder disagreed with the database"),
                "the author's own cue survives: " + back.symptoms());
        }
    }

    /**
     * BYTE-STABLE ON RE-EXPORT. The second export of the same row must equal the first —
     * otherwise the folder churns on every acceptance and a human watching it in git
     * cannot tell a real change from the exporter breathing.
     */
    @Test
    void exporting_the_same_row_twice_writes_the_same_bytes() throws Exception {
        String id = recordLesson("a second export of one row must not churn the folder");
        promote(id);
        List<Path> first = filesInFolder();
        assertEquals(1, first.size());
        String before = Files.readString(first.get(0));

        // Demote and re-accept: a second genuine export of the same row.
        assertTrue(store.setStatus(id, "candidate"));
        promote(id);

        List<Path> second = filesInFolder();
        assertEquals(1, second.size(),
            "the re-export overwrites its own file rather than adding one: " + second);
        assertEquals(before, Files.readString(second.get(0)),
            "the same row must render the same bytes");
    }

    /**
     * A ROW NOBODY REVIEWED EXPORTS NO STAMP. This is the assertion that keeps the
     * writer from forging one: the export path can always reach a clock, and writing
     * today's date here would claim a review that did not happen.
     */
    @Test
    void a_row_with_no_review_renders_no_stamp() {
        String id = recordLesson("a row nobody accepted has no review date to write");
        String rendered = StoryWriter.render(byId(id));
        assertFalse(rendered.contains("reviewed:"),
            "nobody reviewed this row, so the story must carry no stamp:\n" + rendered);

        promote(id);
        assertTrue(StoryWriter.render(byId(id)).contains("reviewed:"),
            "and once a reader accepts it, the stamp is there — so the absence above"
                + " is the writer discriminating rather than the key never being written");
    }
}
