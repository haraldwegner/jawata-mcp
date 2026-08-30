package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d S10.0 — THE AUTHOR DECLARES THE CUES; THE LOADER STOPS GUESSING.
 *
 * <p>A recall cue is the phrase someone searches on to find an entry. Retrieval has two
 * lanes and they do different jobs: similarity NOMINATES (it measures topical overlap,
 * not answering — measured on this store, junk outscored a correct answer two to one),
 * and the exact cue index VOUCHES. So a cue carries the authority of the author's own
 * word about when an entry applies.</p>
 *
 * <p><b>The loader has been forging that authority.</b> It harvested cues out of the
 * text's FORMATTING and put them in the vouching lane, where they arrive indistinguishable
 * from something a person declared. Four rounds narrowed which formatting it read —
 * mcp#7 stopped it reading backticked tokens, and it took {@code **bold**} spans instead.
 * The story template opens every section with a bold heading, so each story became
 * findable by its own headings, which are identical across every story.</p>
 *
 * <p><b>Measured 2026-08-30, the discriminating pair:</b> {@code recall(symptom="The
 * case")} returned FIVE direct hits whose only tie to the phrase was {@code **The case.**}
 * standing as a heading inside them; {@code recall(symptom="Why this correction exists")},
 * a phrase appearing nowhere, returned zero. One resolves, one does not, and the
 * difference is typography.</p>
 *
 * <h2>Why this test pins the ABSENCE as hard as the presence</h2>
 *
 * <p>Harald, 2026-08-30: <i>"Find the cue by content and not by a format. This is
 * nonsense"</i>, and <i>"Why guessing at all."</i> The fix this plan proposed a day
 * earlier — treat a bold span that BEGINS a line as a heading, keep harvesting the rest —
 * is refused as the fifth narrowing of one guess. Exclude leading bold and the harvester
 * takes whatever is next.</p>
 *
 * <p>So {@link #a_story_that_declares_nothing_gets_no_cues} is the load-bearing half. A
 * test that only checked declared cues WORK would pass with the harvester still running
 * beside them, and the forged entries would keep arriving.</p>
 */
class DeclaredCuesReplaceHarvestingTest {

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ExperienceMaintenance maint() {
        return new ExperienceMaintenance(store, fqn -> null);
    }

    private void write(Path dir, String file, String frontmatter, String body) throws IOException {
        Files.writeString(dir.resolve(file), "---\n" + frontmatter + "\n---\n" + body);
    }

    /** Is anything findable by this phrase through the exact cue lane? */
    private boolean resolves(String cue) {
        List<StoredEntry> hits = store.query(new RecallQuery(null, null, null, cue, null));
        return !hits.isEmpty();
    }

    /**
     * THE BODY THAT PRODUCED THE DEFECT — the story template's own shape, with a bold
     * span opening each section. Written once and shared, so both tests are about the
     * same input and the only variable is what the frontmatter declares.
     */
    private static final String TEMPLATE_BODY = """
        **The case.** A desktop application blanked its screen on idle and would not wake.

        **The gap.** Restarting the display manager did not recover it either.

        **Why it survived being written down.** Nobody re-read the note after the fix.
        """;

    @Test
    @DisplayName("S10.0: an author's declared cues are the entry's cues")
    void declared_cues_are_honoured(@TempDir Path dir) throws IOException {
        write(dir, "declared.md",
            "name: idle-blank-recovery\n"
                + "description: the mouse cursor proves the GPU is still scanning out\n"
                + "type: domain_fact\n"
                + "symptoms: black screen on wake, cursor visible but desktop gone",
            TEMPLATE_BODY);

        maint().load(dir);

        assertTrue(resolves("black screen on wake"),
            "the author declared this cue and it must resolve — otherwise the field is"
                + " accepted and then dropped, which is worse than not having it");
        assertTrue(resolves("cursor visible but desktop gone"),
            "the SECOND declared cue too: a parser that takes only the first value would"
                + " pass a single-cue test while silently discarding the rest");
    }

    /**
     * THE HALF THAT PROVES THE HARVESTER IS GONE RATHER THAN OVERRIDDEN.
     *
     * <p>Same body, same bold headings, no declared cues. Every phrase below was harvested
     * as a cue by the version this replaces — they are quoted from a real story's
     * {@code symptoms} array, read back out of the live store on 2026-08-29.</p>
     */
    @Test
    @DisplayName("S10.0: a story that declares no cues gets none — its headings are not cues")
    void a_story_that_declares_nothing_gets_no_cues(@TempDir Path dir) throws IOException {
        write(dir, "undeclared.md",
            "name: idle-blank-recovery\n"
                + "description: the mouse cursor proves the GPU is still scanning out\n"
                + "type: domain_fact",
            TEMPLATE_BODY);

        maint().load(dir);

        // PROOF OF LIFE FIRST. Without this the three assertions below would also pass
        // against a load that stored nothing at all, which is the shape that has bitten
        // this sprint twice — an instrument reporting clean because it examined nothing.
        assertFalse(store.query(new RecallQuery(null, null, null,
                "the mouse cursor proves the GPU is still scanning out", null)).isEmpty(),
            "PROOF OF LIFE: the entry must actually be in the store, or the absences"
                + " asserted below are absences of everything");

        for (String heading : List.of("The case", "The gap", "Why it survived being written down")) {
            assertFalse(resolves(heading),
                () -> "'" + heading + "' is a SECTION HEADING in this story's body and"
                    + " nothing else. It resolving as a cue means the harvester is still"
                    + " running: every story sharing this template becomes findable by"
                    + " the same phrases, and the exact lane — whose whole job is to"
                    + " carry what a person vouched for — is filled with typography.");
        }
    }
}
