package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

/**
 * Sprint 28f D2 — the two new verbs at the front door, in the forms a caller uses.
 *
 * <p><b>Why this class exists beside the other two.</b> {@code StoreBackupsTest}
 * drives the mechanism and {@code DestructiveVerbsBackUpFirstTest} drives the verbs
 * that DESTROY. Neither touches {@code backup}, and neither touches the listing form
 * of {@code restore} — the branch that answers when no name is given, which is the
 * half that makes "restore by version" possible at all. A C1 audit found both
 * uncovered: the keys {@code backups} and {@code depth} were pinned nowhere, so
 * renaming either in the engine would leave every test in both repositories green
 * while studio's picker silently rendered "No copies yet." A version list that cannot
 * be populated is a Restore button with nothing to click.</p>
 */
class BackupAndRestoreVerbsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode args(String kind) {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", kind);
        return n;
    }

    private static void put(H2ExperienceStore store, String summary) {
        store.put(SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
            .symbol("com.example.Target").build());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    /**
     * {@code backup} takes a copy on demand — the verb for the moment before
     * something this product does not know about.
     */
    @Test
    void the_backup_verb_takes_a_copy_on_demand(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "a row worth a copy");

            Map<String, Object> out = data(tool.execute(args("backup")));
            Path copy = Path.of(String.valueOf(out.get("backup")));
            assertTrue(Files.isRegularFile(copy), "the named copy is on disk: " + copy);
            assertTrue(copy.getFileName().toString().endsWith("-manual.zip"),
                "a copy nobody's destruction prompted is named for that, so a human"
                    + " choosing one can tell it from the five taken by a verb: "
                    + copy.getFileName());
            assertEquals(1, out.get("kept"), () -> "it says how many are kept now: " + out);
            assertEquals(StoreBackups.DEFAULT_DEPTH, out.get("depth"),
                () -> "and how many it keeps, which is the ceiling a caller plans against: " + out);
        }
    }

    /** An in-memory resident refuses rather than reporting a copy it did not take. */
    @Test
    void an_in_memory_resident_refuses_to_report_a_copy_it_could_not_take() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "a row with no file under it");
            ToolResponse r = tool.execute(args("backup"));
            assertFalse(r.isSuccess(),
                "there is no file to copy, so a success here would be a copy that does not"
                    + " exist — the shape this codebase records as its top defect class");
            assertNotNull(r.getError());
        }
    }

    /**
     * {@code restore} with NO name LISTS, and this pins the keys studio reads.
     *
     * <p>The no-argument form of a verb is deliberately never the destructive reading
     * of its own name. That also makes the listing reachable without a second verb —
     * and it is how a caller learns the names in the first place, since a name is the
     * only thing {@code restore} accepts.</p>
     */
    @Test
    void restore_with_no_name_lists_the_versions(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "a row");
            put(store, "and another, so the control below is not about an empty store");
            long rowsBefore = store.count();
            StoreBackups backups = new StoreBackups(() -> store);
            Path first = backups.before("wipe");
            Path second = backups.before("prune");
            assertNotNull(first);
            assertNotNull(second);

            Map<String, Object> out = data(tool.execute(args("restore")));

            // THE KEY NAMES ARE THE ASSERTION. studio's picker reads `backups` and
            // `depth` off this response; nothing else in either repository pins them,
            // so a rename here is invisible until a human opens the view.
            Object listed = out.get("backups");
            assertTrue(listed instanceof List, () -> "`backups` must be a list: " + out);
            assertEquals(
                List.of(second.getFileName().toString(), first.getFileName().toString()),
                listed,
                () -> "NEWEST FIRST, by name — the order the picker shows and the order"
                    + " eviction depends on: " + out);
            assertEquals(StoreBackups.DEFAULT_DEPTH, out.get("depth"),
                () -> "`depth` tells the reader how many of these will survive: " + out);
            assertNotNull(out.get("howToRestore"),
                () -> "and the listing says how to act on itself: " + out);

            // Captured rather than written as a literal: the first version of this
            // assertion hard-coded 2 against a store holding 1, so it failed on its own
            // arithmetic instead of on the property it is about.
            assertEquals(rowsBefore, store.count(),
                "the control: LISTING is not restoring — a verb whose no-argument form"
                    + " performed the destructive reading of its own name is a trap");
        }
    }

    /**
     * A NAMED restore without {@code confirm} is refused, and nothing changes.
     *
     * <p>{@code wipe_and_import} has always refused without it and this verb is no
     * less destructive — one call replaces every row. studio asks the user first, but
     * a confirmation the CALLER performs is one the caller can skip, which is the
     * argument this deliverable makes about the backup, applied one level up.</p>
     *
     * <p>The LISTING form takes no confirm, and the control below says so: a gate that
     * also blocked the listing would make the names unlearnable, and nobody can
     * restore a version they cannot name.</p>
     */
    @Test
    void a_named_restore_without_confirm_is_refused(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "the row a careless restore would replace");
            Path copy = new StoreBackups(() -> store).before("wipe");
            assertNotNull(copy);
            put(store, "the row written after the copy");
            assertEquals(2L, store.count());

            ObjectNode a = args("restore");
            a.put("name", copy.getFileName().toString());
            ToolResponse refused = tool.execute(a);

            assertFalse(refused.isSuccess(), "an ungated restore is what this gate is for");
            assertTrue(String.valueOf(refused.getError()).contains("confirm"),
                () -> "the refusal must say what to pass: " + refused.getError());
            assertEquals(2L, store.count(),
                "and NOTHING was replaced — a refusal that still restored would be the"
                    + " worst of both");

            // The control: the listing form is NOT gated, or the names are unlearnable.
            assertTrue(tool.execute(args("restore")).isSuccess(),
                "listing takes no confirm — it changes nothing and is how a caller"
                    + " learns the version names in the first place");
        }
    }

    /**
     * An unknown name is refused, and the refusal carries the names that exist.
     *
     * <p>Driven through the front door rather than the class, because this is the
     * error a caller who mistyped a version actually meets.</p>
     */
    @Test
    void an_unknown_version_is_refused_over_the_front_door(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "a row");
            Path real = new StoreBackups(() -> store).before("wipe");
            assertNotNull(real);

            ObjectNode a = args("restore");
            a.put("name", "no-such-version.zip");
            a.put("confirm", true);
            ToolResponse r = tool.execute(a);

            assertFalse(r.isSuccess());
            String rendered = String.valueOf(r.getError());
            assertTrue(rendered.contains(real.getFileName().toString()),
                () -> "a refusal that does not say what IS available leaves the caller"
                    + " guessing: " + rendered);
            assertEquals(1L, store.count(), "and a refused restore changed nothing");
        }
    }
}
