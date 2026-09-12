package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 7, E9 — the STORY verbs do not touch a job row.
 *
 * <p>A job is not a story. It is regenerated from the code it describes, it carries no
 * situation and no outcome, and no memory file produces one. The verbs that maintain the
 * story corpus — the reseed that rebuilds from files, and the prune that sweeps curated
 * rows — walk the whole store, so a job row is in their path without being their subject.
 * This class is what says they leave it alone.</p>
 *
 * <h2>Why this is a real risk and not bookkeeping</h2>
 *
 * <p>{@code wipe_and_import} deletes rows and reloads from files. A job has NO file, so a
 * total wipe would destroy it permanently — which is the defect the store already paid for
 * once with rows written by {@code record}, and which its own help text used to warn about
 * instead of preventing.</p>
 *
 * <p><b>The protection that exists covers jobs INCIDENTALLY, and that is the point of
 * pinning it.</b> The wipe spares a row whose {@code sourceRef} is null — "nothing on disk
 * can put this back" — and a job written through the verb has none. It is NOT scoped to the
 * CODE lane. So the day a job acquires a source ref for any reason, the protection stops
 * covering it silently, with no line of code having changed its mind. That is the shape this
 * sprint has found five times, and a test is the only thing that notices it.</p>
 */
class JobRowUntouchedByStoryVerbsTest {

    private static final String ANCHOR = "com.example.Reports#renderMonthly";
    private static final String JOB =
        "The one place a month's rows become the figure the invoice shows, so a rounding"
            + " rule lives here rather than at each caller.";

    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private ObjectNode args(String kind) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", kind);
        return a;
    }

    /** A job row through the front door, so it is written exactly as an agent writes one. */
    private void recordTheJob() {
        ObjectNode a = args("record");
        a.put("type", "job");
        a.put("symbol", ANCHOR);
        a.put("summary", JOB);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "PROOF OF LIFE: the job must be written before a verb can"
            + " fail to spare it; got " + r.getError());
    }

    private long jobRows() {
        return store.all().stream().filter(e -> "job".equals(e.type())).count();
    }

    /**
     * THE ONE THAT COULD DESTROY IT. A reseed wipes and reloads from files, and no file
     * produces a job.
     */
    @Test
    @DisplayName("wipe_and_import rebuilds from files and leaves the job row standing")
    void wipe_and_import_leaves_the_job_row_standing(@TempDir Path stories) throws Exception {
        recordTheJob();
        assertEquals(1, jobRows(), "proof of life");

        // A real story folder, so the reload has something to do and the wipe is not
        // spared by having nothing to replace the rows with.
        Files.writeString(stories.resolve("a-real-story.md"), """
            ---
            name: a-real-story
            description: A gate that cannot see zero reports compliance when it saw nothing.
            type: lesson
            situation: when a detector needs at least one caller to report anything
            verdict: worked
            reviewed: 2026-09-12
            ---

            Counting only what has callers means a member with none presents nothing to
            classify, so the scan is silent for exactly the case being asked about.
            """);

        ObjectNode a = args("wipe_and_import");
        a.put("confirm", true);
        a.put("path", stories.toString());
        ToolResponse r = tool.execute(a);

        assertTrue(r.isSuccess(), "the reseed itself must run; got " + r.getError());
        assertEquals(1, jobRows(),
            "the job survives a verb whose whole job is to replace the store from disk —"
                + " and nothing on disk could have put it back");
        assertEquals(JOB, store.all().stream()
                .filter(e -> "job".equals(e.type())).findFirst().orElseThrow().summary(),
            "and it is the SAME row rather than a rewritten one");
    }

    /**
     * THE SWEEP. {@code prune} removes rejected and superseded rows wholesale — it has no
     * id list at all — so a job in the wrong status bucket would go with them.
     */
    @Test
    @DisplayName("prune sweeps the curated statuses and leaves the job row standing")
    void prune_leaves_the_job_row_standing() {
        recordTheJob();
        assertEquals(1, jobRows(), "proof of life");

        ObjectNode a = args("prune");
        a.put("days", 0);
        ToolResponse r = tool.execute(a);

        assertTrue(r.isSuccess(), "the prune itself must run; got " + r.getError());
        assertEquals(1, jobRows(),
            "a job is neither rejected nor superseded, so the store-wide sweep passes over"
                + " it — the verb that once removed 101 rows when seven were asked for");
    }

    /**
     * THE OTHER DIRECTION: a job cannot arrive as a story.
     *
     * <p>The CODE lane is REGENERATED from code. A job typed into a memory file would be a
     * row nothing regenerates and nothing can correct — the one shape the lane's contract
     * forbids — so the ingest must not create one, however well-formed the file is.</p>
     */
    @Test
    @DisplayName("a memory file typed 'job' does not become a job row")
    void a_memory_file_typed_job_does_not_become_a_job_row(@TempDir Path stories)
            throws Exception {
        Files.writeString(stories.resolve("pretending-to-be-a-job.md"), """
            ---
            name: pretending-to-be-a-job
            description: Turns a month's rows into the figure the invoice shows.
            type: job
            symbol: com.example.Reports#renderMonthly
            reviewed: 2026-09-12
            ---

            A job authored by hand rather than regenerated from the code it describes.
            """);

        ObjectNode a = args("load");
        a.put("path", stories.toString());
        ToolResponse r = tool.execute(a);

        assertTrue(r.isSuccess(), "the load itself must run; got " + r.getError());
        // This asserts the absence of a JOB ROW, not an empty store: the file may
        // legitimately land as some other type, and that is not this case's business.
        assertEquals(0, jobRows(),
            "the CODE lane is regenerated from code, so a job that arrived from a file is a"
                + " row nothing can regenerate and nothing can correct: " + r.getData());
    }
}
