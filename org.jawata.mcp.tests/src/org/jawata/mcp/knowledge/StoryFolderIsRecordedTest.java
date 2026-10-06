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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S13 / D40c — the store's story folder is RECORDED by the loader,
 * not reconstructed from the paths of what it loaded.
 *
 * <p>What stood before walked every {@code memory:} source and narrowed to the
 * deepest directory containing all of them. That is correct for a store loaded
 * from ONE folder and wrong for every other case: with several roots the
 * narrowing climbs past all of them, and {@code promote} then exported an
 * accepted story into the ancestor — a file no load reads back, written with no
 * error and reported as a success.</p>
 *
 * <p><b>That ancestor was already measured in this codebase, by a different
 * sprint, and left as a comment.</b> {@code SubstrateRootTest} says of the same
 * root: <em>"the root is the user's home directory on any real machine"</em>,
 * recording that reaching the filesystem from there pinned twenty cores on
 * 2026-08-29. That scan was removed; the guess that produced the path was not.
 * D40c removes the guess.</p>
 *
 * <p><b>Why {@code SubstrateRootTest} could not catch this.</b> Both of its cases
 * load exactly one directory, and with one root the recorded folder and the
 * derived ancestor are the same path. It stays green across this change, which
 * is what makes it the control rather than a gap.</p>
 */
class StoryFolderIsRecordedTest {

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

    private void story(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: \"a story about " + name
            + " that a reader could act on\"\ntype: domain_fact\n"
            + "situation: I am checking which folder the store says is its own\n"
            + "reviewed: 2026-08-27\n---\nthe body of " + name + "\n");
    }

    private void load(Path dir) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        assertTrue(tool.execute(a).isSuccess(), "the load itself must succeed");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> substrate() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "stats");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "stats failed: " + r.getError());
        return (Map<String, Object>) ((Map<String, Object>) r.getData()).get("substrate");
    }

    @Test
    @DisplayName("D40c: ONE root is recorded as ITSELF, and the store says so")
    void oneRootIsRecordedAsItself(@TempDir Path tmp) throws Exception {
        Path only = tmp.resolve("stories");
        story(only, "the-only-one");
        load(only);

        Map<String, Object> s = substrate();
        assertAll(
            // NOT new coverage and it is labelled so: the old derivation answered
            // this same path, because with one folder the ancestor IS the folder.
            // It is here as the control that recording did not change the answer
            // in the case that was already right.
            () -> assertEquals(only.toAbsolutePath().normalize().toString(), s.get("root"),
                () -> "the folder loaded, exactly, and not a parent of it: " + s),
            () -> assertTrue(String.valueOf(s.get("rootFrom")).contains("recorded"),
                () -> "and the answer says it was recorded rather than worked out, which is "
                    + "what a reader needs to know to trust it: " + s),
            () -> assertFalse(store.storyFolderAmbiguous(),
                "one folder is not several"));
    }

    @Test
    @DisplayName("D40c: TWO roots leave NO single folder — never their shared ancestor")
    void twoRootsLeaveNoSingleFolder(@TempDir Path tmp) throws Exception {
        // THE DISCRIMINATOR. These two sit under one parent, so the derivation
        // this deliverable removes had a non-null answer to give: `tmp` itself.
        // An accepted story would have been written there.
        Path stories = tmp.resolve("knowledge/stories");
        Path memory = tmp.resolve("claude/memory");
        story(stories, "a-story");
        story(memory, "a-memory-note");
        load(stories);
        load(memory);

        Map<String, Object> s = substrate();
        assertAll(
            () -> assertNull(s.get("root"),
                () -> "two folders leave no single story folder. A NON-NULL root here is "
                    + "the defect itself: it can only be an ancestor of both, which is a "
                    + "directory nobody loaded and no load reads back: " + s),
            () -> assertTrue(store.storyFolderAmbiguous(),
                "and the store knows WHY it has none"),
            () -> assertTrue(String.valueOf(s.get("why")).contains("more than one folder"),
                () -> "the response distinguishes 'several were recorded' from 'none was' — "
                    + "rendering both as a blank root says the export ran and found nothing "
                    + "to do: " + s),
            // Proof of life: both loads really ingested, so the absence above is
            // about the FOLDER and not about an empty store.
            () -> assertTrue(
                Integer.parseInt(String.valueOf(s.get("entriesWithASourcePath")).split(" ")[0])
                    >= 2,
                () -> "both stories were ingested, so the null root is not an empty store "
                    + "answering: " + s));
    }

    @Test
    @DisplayName("D40c: reloading the SAME root is not a second folder")
    void reloadingTheSameRootIsNotASecondFolder(@TempDir Path tmp) throws Exception {
        Path only = tmp.resolve("stories");
        story(only, "the-only-one");
        load(only);
        load(only);

        Map<String, Object> s = substrate();
        assertAll(
            () -> assertEquals(only.toAbsolutePath().normalize().toString(), s.get("root"),
                () -> "a reload is the ordinary case — every boot does it — and treating it "
                    + "as a second folder would lose the answer on every restart: " + s),
            () -> assertFalse(store.storyFolderAmbiguous(), "and it is not ambiguous"));
    }

    @Test
    @DisplayName("D40c: the recording is normalised, so two spellings of one folder agree")
    void twoSpellingsOfOneFolderAreOneFolder(@TempDir Path tmp) {
        Path dir = tmp.resolve("stories");
        store.recordStoryFolder(dir);
        // The same directory reached through a redundant segment. Without
        // normalisation this is a second, different path and the store would call
        // itself ambiguous over one folder — which would turn the ordinary layered
        // configuration into a permanent refusal to export anything.
        store.recordStoryFolder(tmp.resolve("other").resolve("..").resolve("stories"));

        assertAll(
            () -> assertFalse(store.storyFolderAmbiguous(),
                "one folder spelled two ways is one folder"),
            () -> assertEquals(dir.toAbsolutePath().normalize(),
                store.storyFolder().orElseThrow(), "and it is the normalised form"));
    }
}
