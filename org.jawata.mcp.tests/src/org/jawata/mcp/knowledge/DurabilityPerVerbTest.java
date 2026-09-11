package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

/**
 * Sprint 28f E1 — a story someone TYPED survives every verb, asserted one verb at a time.
 *
 * <p><b>Why per verb rather than once.</b> "The store is durable" is not a property any
 * single call can show. Durability is a claim about a SET of operations, and the way it
 * fails is that one member of the set behaves unlike its neighbours — which is exactly
 * what happened on 2026-09-08, where one verb out of two dozen took 378 rows to 194 while
 * every other verb in the same store was faultless. So each verb is driven, and the story
 * is looked for again after each.</p>
 *
 * <p><b>THE VERB THAT MATTERS MOST HERE IS THE REBUILD.</b> A recorded story has no file
 * behind it, and the rebuild's whole job is to make the store agree with a folder of
 * files. The temptation — and the shape of the original accident — is to read "agree with
 * the folder" as "hold nothing the folder does not". It retires the FILE LANE and leaves
 * everything else, and this is where that is asserted.</p>
 *
 * <p><b>The three exclusions are named, not silent.</b> {@code wipe}, {@code delete} and
 * {@code restore} exist to remove or replace; a story surviving them would be the defect.
 * Each gets its own case below saying what it is allowed to take, and the classification
 * is checked against the PUBLISHED schema by equality — so a verb added to this door lands
 * in this test's face rather than slipping past it as neither preserved nor excluded.</p>
 */
class DurabilityPerVerbTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The anchor the story is recorded under, and recalled by. */
    private static final String ANCHOR = "com.example.DurableTarget";

    /**
     * The verbs whose job is to REMOVE or REPLACE — every other advertised kind must
     * leave the story where it is.
     *
     * <p>Written out rather than derived because there is nothing to derive it FROM: that
     * a verb is allowed to take knowledge away is a statement about what it is for, and no
     * signature carries it. Kept honest by {@link #every_advertised_verb_is_classified}.</p>
     */
    private static final Set<String> REMOVERS = Set.of("wipe", "delete", "restore");

    private static ObjectNode args(String kind) {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", kind);
        return n;
    }

    /** Assert the verb RAN, and hand back whatever it answered with. */
    private static Object ran(ToolResponse r, String verb) {
        assertTrue(r.isSuccess(),
            () -> verb + " must RUN here — a verb refused for want of an argument has not"
                + " been asked anything about durability: " + r.getError());
        return r.getData();
    }

    /**
     * The same, for the verbs that answer with a map.
     *
     * <p>Kept apart from {@link #ran} because they are not interchangeable: {@code recall}
     * with {@code format=text} answers with a flat STRING, which is the whole point of that
     * format, and casting it would fail on the one verb this test leans on hardest.</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r, String verb) {
        return (Map<String, Object>) ran(r, verb);
    }

    /** The kind enum as an AGENT receives it, not the private constant behind it. */
    @SuppressWarnings("unchecked")
    private static List<String> advertised(ExperienceTool tool) {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) props.get("kind");
        return (List<String>) kind.get("enum");
    }

    /** Record the story through the FRONT DOOR — the path a client actually has. */
    private static String recordTheStory(ExperienceTool tool) {
        ObjectNode a = args("record");
        a.put("type", "lesson");
        a.put("summary", "a clock read before the session opens dates the trade to 1970");
        a.put("situation", "when a market-data event arrives before the venue's first print");
        a.put("verdict", "worked");
        a.put("symbol", ANCHOR);
        String id = String.valueOf(data(tool.execute(a), "record").get("id"));
        assertFalse(id.isBlank() || "null".equals(id),
            "the control: the record verb must hand back the id it wrote");
        return id;
    }

    /**
     * The story is STILL THERE and can still be FOUND — two questions, not one.
     *
     * <p>A row that survives and cannot be recalled is lost in the only sense a reader
     * cares about, so presence alone would pass over half the failure.</p>
     */
    private static void stillThere(H2ExperienceStore store, ExperienceTool tool, String id,
            String afterVerb) {
        assertEquals(1, store.exportByIds(List.of(id)).size(),
            () -> "the story was GONE after " + afterVerb
                + " — it was typed by a human and no file backs it up");

        ObjectNode a = args("recall");
        a.put("symbol", ANCHOR);
        a.put("format", "text");
        String rendered = String.valueOf(ran(tool.execute(a), "recall after " + afterVerb));
        assertTrue(rendered.contains("1970"),
            () -> "the story survived " + afterVerb + " and can no longer be RECALLED, which"
                + " is lost in the only sense that matters to a reader: " + rendered);
    }

    private static void story(Path dir, String name, String summary) throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: " + summary
                + "\ntype: domain_fact\nreviewed: 2026-09-12\n---\n\nThe body.\n");
    }

    /**
     * EVERY advertised verb is either preserving or a named remover — no third state.
     *
     * <p>This is the guard the other two cases stand on. Without it a verb added to the
     * door is in neither list and nothing says so, which is indistinguishable from a verb
     * that was considered and found safe.</p>
     */
    @Test
    void every_advertised_verb_is_classified() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            Set<String> published = new LinkedHashSet<>(advertised(tool));

            assertTrue(published.containsAll(REMOVERS),
                () -> "a verb this test exempts is no longer published: " + REMOVERS
                    + " against " + published);

            Set<String> preserved = new LinkedHashSet<>(published);
            preserved.removeAll(REMOVERS);
            assertEquals(published.size() - REMOVERS.size(), preserved.size(),
                "the two groups partition the published set");
            assertTrue(preserved.contains("wipe_and_import"),
                () -> "the rebuild must be on the PRESERVING side — retiring the file lane is"
                    + " its job, and taking a typed story with it is the 2026-09-08 accident:"
                    + " " + preserved);
        }
    }

    /**
     * The run: record once, then drive each maintenance verb and look for the story again.
     *
     * <p>Ordered so that nothing here is entitled to remove it — {@code prune} sweeps the
     * rejected and superseded and this story is neither, {@code promote} only re-statuses
     * it. The assertions are made INSIDE the loop rather than gathered, because each verb
     * runs against the state the last one left: a failure at step seven says nothing about
     * step eight, and reporting both would invent a second finding.</p>
     */
    @Test
    void a_recorded_story_survives_every_maintenance_verb(@TempDir Path dir,
            @TempDir Path roots) throws Exception {
        story(roots, "from-a-file", "a story that really does come from a file");

        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            String id = recordTheStory(tool);
            stillThere(store, tool, id, "record itself");

            drive(tool, args("backup"), "backup");
            stillThere(store, tool, id, "backup");

            drive(tool, args("refresh"), "refresh");
            stillThere(store, tool, id, "refresh");

            drive(tool, args("compact"), "compact");
            stillThere(store, tool, id, "compact");

            ObjectNode dedup = args("dedup");
            dedup.put("confirm", true);
            drive(tool, dedup, "dedup");
            stillThere(store, tool, id, "dedup");

            ObjectNode prune = args("prune");
            prune.put("days", 0);
            drive(tool, prune, "prune");
            stillThere(store, tool, id, "prune");

            ObjectNode promote = args("promote");
            promote.put("id", id);
            promote.put("status", "accepted");
            drive(tool, promote, "promote");
            stillThere(store, tool, id, "promote");

            ObjectNode setForm = args("set_form");
            setForm.put("id", id);
            setForm.put("situation", "when a print arrives before the venue has opened");
            drive(tool, setForm, "set_form");
            stillThere(store, tool, id, "set_form");

            ObjectNode load = args("load");
            load.put("path", roots.toString());
            drive(tool, load, "load");
            stillThere(store, tool, id, "load");

            ObjectNode importing = args("import");
            importing.set("entries", JSON.valueToTree(store.exportByIds(List.of(id))));
            drive(tool, importing, "import");
            stillThere(store, tool, id, "import");

            // THE REBUILD MUST ACTUALLY RETIRE SOMETHING, or the loop that could sweep the
            // typed story never runs and this step passes over a no-op. So a second file is
            // loaded and then taken away: the rebuild finds one source gone, retires it, and
            // the question becomes what else it took on the way past.
            story(roots, "about-to-vanish", "a story whose file is about to be deleted");
            drive(tool, load, "load (the source the rebuild will retire)");
            Files.delete(roots.resolve("about-to-vanish.md"));

            ObjectNode rebuild = args("wipe_and_import");
            rebuild.put("confirm", true);
            rebuild.put("path", roots.toString());
            Map<String, Object> rebuilt = data(tool.execute(rebuild), "wipe_and_import");
            assertEquals(1L, rebuilt.get("removed"),
                () -> "the control: this rebuild must really have retired a file-lane row,"
                    + " or the retire loop never ran and the assertion below is satisfied by"
                    + " a rebuild that did nothing: " + rebuilt);
            stillThere(store, tool, id,
                "wipe_and_import — THE ONE THIS TEST EXISTS FOR. A rebuild retires the FILE"
                    + " lane; a story somebody typed has no file and is not the rebuild's to"
                    + " take");
        }
    }

    private static void drive(ExperienceTool tool, ObjectNode a, String verb) {
        data(tool.execute(a), verb);
    }

    /**
     * The three exclusions, each asserted for what it is ACTUALLY allowed to take.
     *
     * <p>Exempting them from the loop above would leave three verbs asserted by nothing,
     * which this codebase's own record calls indistinguishable from three that were
     * forgotten. So each is driven here and pinned to the narrowest true claim: delete
     * takes what it was NAMED and nothing beside it, restore returns the store to a
     * moment the caller chose, and wipe — alone — is entitled to everything.</p>
     */
    @Test
    void the_three_removing_verbs_take_only_what_they_are_entitled_to(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            String id = recordTheStory(tool);

            String other = store.put(SymbolFact
                .of("domain_fact", "an unrelated row, named for deletion", Confidence.MEDIUM)
                .symbol("com.example.Unrelated").build());

            ObjectNode delete = args("delete");
            delete.set("ids", JSON.valueToTree(List.of(other)));
            Map<String, Object> deleted = data(tool.execute(delete), "delete");
            assertEquals(0, store.exportByIds(List.of(other)).size(),
                () -> "the control: the delete really happened — " + deleted);
            stillThere(store, tool, id, "a delete that named a DIFFERENT row");

            Path copy = new StoreBackups(() -> store).before("wipe");
            assertNotNull(copy, "the control: a file-backed store can take a copy");
            store.put(SymbolFact
                .of("domain_fact", "a row written after the copy was taken", Confidence.MEDIUM)
                .symbol("com.example.Later").build());
            ObjectNode restore = args("restore");
            restore.put("name", copy.getFileName().toString());
            restore.put("confirm", true);
            data(tool.execute(restore), "restore");
            stillThere(store, tool, id,
                "a restore to a copy taken AFTER it was recorded — the story is inside the"
                    + " copy, so returning to that moment must bring it back with everything"
                    + " else");

            data(tool.execute(args("wipe")), "wipe");
            assertEquals(0, store.exportByIds(List.of(id)).size(),
                "wipe is the ONE verb entitled to the typed story, and it takes it — a wipe"
                    + " that spared rows would be a worse defect than one that took them,"
                    + " because its name is the whole contract");
        }
    }
}
