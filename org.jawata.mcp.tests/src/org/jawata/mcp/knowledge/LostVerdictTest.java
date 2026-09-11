package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

/**
 * Sprint 28f D3 — a rebuild that holds LESS than the store did says so itself.
 *
 * <p><b>The shape being replayed.</b> On 2026-09-08 this verb ran twice. The 14:04 run
 * was ordinary. The 14:18 run rebuilt a store of 192 file-derived sources from a root
 * that delivered 2, and reported the same shape as the ordinary one — a completed
 * rebuild, with counts a reader had to compare for themselves to notice that 190
 * sources had gone. Nobody compares two numbers they were not told to compare.</p>
 *
 * <p><b>So the verdict is IN the response.</b> {@code success} is delivered ≥ retired,
 * and when it is false the reason names both figures and points at the copy. That copy
 * is D2's, and the two halves are one answer: the response says a loss happened AND
 * where to get the previous state back. Either alone leaves the reader worse off — a
 * verdict with no undo, or an undo nobody knows they need.</p>
 */
class LostVerdictTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void story(Path dir, String name, String summary) throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: " + summary
                + "\ntype: domain_fact\nreviewed: 2026-09-12\n---\n\nThe body.\n");
    }

    private static ObjectNode rebuild(Path root) {
        ObjectNode a = JSON.createObjectNode();
        a.put("kind", "wipe_and_import");
        a.put("confirm", true);
        a.put("path", root.toString());
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected a rebuild: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    /**
     * THE 14:18 SHAPE, in miniature: many sources in, few delivered, verdict false.
     *
     * <p>Six and two rather than 192 and 2 — the ratio is what reproduces, and the
     * arithmetic is the same one that turned 378 rows into 194.</p>
     */
    @Test
    void a_rebuild_that_delivers_less_than_it_retires_reports_a_loss(@TempDir Path dir,
            @TempDir Path wide, @TempDir Path narrow) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            for (int i = 0; i < 6; i++) {
                story(wide, "wide-" + i, "a story the wide root carries, number " + i);
            }
            data(tool.execute(rebuild(wide)));
            assertEquals(6, store.fileSourceRefs().size(), "the control: six sources in");

            story(narrow, "narrow-0", "the only story the narrow root carries");
            story(narrow, "narrow-1", "the second story the narrow root carries");
            Map<String, Object> report = data(tool.execute(rebuild(narrow)));

            assertEquals(Boolean.FALSE, report.get("success"),
                () -> "a rebuild delivering 2 where 6 were retired is a LOSS and must say"
                    + " so in its own response — the 14:18 run reported the same shape as"
                    + " the ordinary one: " + report);
            assertEquals(6L, report.get("removed"), () -> "" + report);
            String reason = String.valueOf(report.get("reason"));
            assertTrue(reason.contains("2") && reason.contains("6"),
                () -> "the reason names BOTH figures, so nobody has to derive the loss: "
                    + reason);
            assertNotNull(report.get("backup"),
                () -> "and it points at the copy of the store as it stood before, because"
                    + " a verdict with no undo leaves the reader no better off: " + report);
            assertTrue(Files.isRegularFile(Path.of(String.valueOf(report.get("backup")))),
                "the copy it names is really there");
        }
    }

    /**
     * THE 14:04 SHAPE: an ordinary rebuild is a success, and still carries the copy.
     *
     * <p>The control. Without it the assertions above are satisfied by a verb that
     * reports every rebuild as a loss, which would be its own kind of useless.</p>
     */
    @Test
    void an_ordinary_rebuild_is_a_success_and_still_names_its_copy(@TempDir Path dir,
            @TempDir Path roots) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            story(roots, "first", "a story the root has carried all along");
            data(tool.execute(rebuild(roots)));

            story(roots, "second", "a story the root gained since the last rebuild");
            Map<String, Object> report = data(tool.execute(rebuild(roots)));

            assertEquals(Boolean.TRUE, report.get("success"),
                () -> "a rebuild that lost nothing is a success: " + report);
            assertEquals(0L, report.get("removed"), () -> "and it retired nothing: " + report);
            assertTrue(report.get("reason") == null,
                () -> "a success carries no loss reason, or the word stops meaning"
                    + " anything: " + report);
            assertNotNull(report.get("backup"),
                () -> "EVERY run carries the copy, not only the bad ones — a copy taken"
                    + " only when something goes wrong is taken too late: " + report);
        }
    }
}
