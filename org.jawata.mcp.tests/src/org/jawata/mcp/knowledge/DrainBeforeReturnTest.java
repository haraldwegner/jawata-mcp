package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f E5 — a load returns with its OWN rows already searchable.
 *
 * <p><b>The defect this locks, measured rather than imagined.</b> The end-to-end gate
 * imported 48 rows and then recalled for them; the meaning index held 189 — the catalogue
 * exactly — and answered every cue with design patterns. The rows were in the store the
 * whole time and outside the index that answers by meaning, so they could be listed and
 * exported and never FOUND by asking. Nothing in the response said so, and three recall
 * checks failed two lifecycles downstream with nothing connecting them to the cause.</p>
 *
 * <p><b>What this asserts is the REPORT, not the recall.</b> A recall assertion would pass
 * or fail on retrieval quality — how a model ranks one row against 189 others — which is a
 * different subject with its own instrument. The claim here is narrower and exact: when
 * {@code load} hands back its report, nothing it wrote is still waiting for a vector, and
 * the report SAYS so. {@code EmbedOnWriteTest} owns the recall half.</p>
 */
class DrainBeforeReturnTest {

    /** A story as a human writes one: frontmatter, a claim, and a body in prose. */
    private static void story(Path dir, String name, String summary, String body)
            throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: " + summary
                + "\ntype: domain_fact\n---\n\n" + body + "\n");
    }

    private static long unembedded(Map<String, Object> report) {
        Object v = report.get("unembedded");
        assertTrue(v instanceof Number,
            () -> "the load report must CARRY its pending count, or a caller has to infer"
                + " 'nothing left' from 'some work done' — which is the inference the gate"
                + " made and got wrong. Report was: " + report);
        return ((Number) v).longValue();
    }

    /**
     * TWENTY FILES IN, NOTHING PENDING OUT.
     *
     * <p>This is the case C3's mutation aims at: skip the embed in {@code load} and the
     * count below stops being zero. The control above it matters as much — without
     * asserting the load actually loaded, an empty root would satisfy "nothing pending"
     * perfectly while proving nothing at all.</p>
     */
    @Test
    void a_load_returns_with_nothing_of_its_own_still_pending(@TempDir Path dir,
            @TempDir Path roots) throws Exception {
        Assumptions.assumeTrue(EmbeddingService.shared().available(),
            "no embedder available — there is no index to be ahead of");
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = new ExperienceMaintenance(store, fqn -> null);
            for (int i = 0; i < 20; i++) {
                story(roots, "story-" + i,
                    "a settled observation about batch number " + i,
                    "The body, in prose, saying what happened and what to do about it.");
            }

            Map<String, Object> report = maintenance.load(roots, true);

            assertEquals(20, report.get("loaded"),
                () -> "the control: twenty files must actually have loaded, or 'nothing"
                    + " pending' is true of an empty store — " + report);
            assertEquals(0L, unembedded(report),
                () -> "A LOAD MUST NOT ANSWER WHILE ITS OWN ROWS ARE UNSEARCHABLE. Rows that"
                    + " are in the store and not in the meaning index can be listed and"
                    + " exported and never found by asking, and the report would read"
                    + " 'loaded: 20' with nothing to warn anyone: " + report);
        }
    }

    /**
     * AND A STORY THAT YIELDS MORE THAN ONE ROW IS STILL COVERED.
     *
     * <p><b>This case exists because the budget and the thing it bounds are counted in
     * different units, and that is exactly where an off-by-a-factor hides.</b> The embed
     * pass is sized by the load's {@code loaded} count, which counts FILES. A story with
     * section headings becomes a parent row plus one row per section — so a file-sized
     * budget is smaller than the rows the load wrote, and the surplus would be left
     * unsearchable while the report still said the load was done.</p>
     *
     * <p>Whether that is a real gap or one the loader closes some other way is not
     * something to reason about — it is something to ask. If this goes red, the bound is
     * wrong and must count rows rather than files.</p>
     */
    @Test
    void a_sectioned_story_is_covered_too(@TempDir Path dir, @TempDir Path roots)
            throws Exception {
        Assumptions.assumeTrue(EmbeddingService.shared().available(),
            "no embedder available — there is no index to be ahead of");
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = new ExperienceMaintenance(store, fqn -> null);
            story(roots, "many-parts", "a story that carries several distinct sections",
                "An opening paragraph that states the whole claim.\n\n"
                + "## The first part\n\nWhat happened when the batch ran overnight.\n\n"
                + "## The second part\n\nWhy the overnight run behaves unlike the day one.\n\n"
                + "## The third part\n\nWhat to do about it before the next batch.\n");

            Map<String, Object> report = maintenance.load(roots, true);

            assertEquals(1, report.get("loaded"),
                () -> "the control: the story loaded — " + report);
            assertTrue(store.count() >= 1,
                () -> "the control: rows exist to be covered — " + store.count());
            assertEquals(0L, unembedded(report),
                () -> "THE BUDGET IS COUNTED IN FILES AND THE WORK IN ROWS. This story is one"
                    + " file; if the loader split it into a parent plus sections, a"
                    + " file-sized embed budget cannot reach them all, and the surplus is"
                    + " left unsearchable behind a report that reads as finished. Store"
                    + " holds " + store.count() + " row(s): " + report);
        }
    }
}
