package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f E6 — a story that names no language is not a Java row.
 *
 * <p><b>The two statements this separates.</b> "The author named no language" and "the
 * author named Java" are different facts, and the store used to write the same value for
 * both: every insert site defaulted an absent language to the literal {@code "java"}. So
 * a markdown story — which states a claim in prose and names no language at all — was
 * recorded as Java, counted as Java, and handed to a JAVA SYMBOL RESOLVER by the
 * staleness sweep.</p>
 *
 * <p><b>Why that is not cosmetic.</b> The sweep's job is to notice when the code an entry
 * points at has gone, and to say so. Pointed at prose it cannot resolve anything, and the
 * store ends up telling its reader that correct knowledge has rotted — the one thing a
 * knowledge store must not get wrong, because a reader who stops trusting the staleness
 * signal has lost the signal.</p>
 */
class LanguageAbsenceTest {

    /** A story as a human writes one: a claim, and no mention of any language. */
    private static void prose(Path dir, String name, String summary) throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: " + summary
                + "\ntype: domain_fact\n---\n\nThe body, in prose.\n");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> byLanguage(H2ExperienceStore store) {
        Object counts = store.stats().get("by_language");
        assertTrue(counts instanceof Map,
            () -> "the control: stats must group by language at all, got " + counts);
        return (Map<String, Object>) counts;
    }

    /**
     * IT SITS OUTSIDE `java`, which is the half a count can see.
     *
     * <p>Asserted from both directions. That the row is absent from {@code java} is the
     * clause; that the store HOLDS it is the control, without which an empty store would
     * satisfy the first assertion perfectly.</p>
     */
    @Test
    void a_story_that_names_no_language_is_not_counted_as_java(@TempDir Path dir,
            @TempDir Path roots) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = new ExperienceMaintenance(store, fqn -> null);
            prose(roots, "a-claim", "a story stated in prose and in no language at all");
            Map<String, Object> report = maintenance.load(roots, true);
            assertEquals(1, report.get("loaded"), () -> "the control: it loaded — " + report);

            Map<String, Object> languages = byLanguage(store);
            assertEquals(1L, store.count(), "the control: the row is really in the store");
            assertNotEquals(1L, languages.get("java"),
                () -> "A PROSE STORY IS NOT A JAVA ROW. Every insert site used to default"
                    + " an absent language to \"java\", so a note written in English was"
                    + " recorded, counted and swept as Java: " + languages);
        }
    }

    /**
     * AND THE STALENESS SWEEP LEAVES IT.
     *
     * <p>The resolver here answers FALSE for everything — the state of a machine whose
     * project has moved or was never loaded. Under it, any row the sweep judges is
     * superseded or marked evidence-dead, so "still accepted afterwards" is a real claim
     * rather than a quiet one.</p>
     *
     * <p><b>The story carries NO symbol, and getting that wrong is what this javadoc is
     * for.</b> The first version of this case gave it {@code symbol: com.example.Gone#method}
     * "to reach the language branch" — and that manufactured a case where sweeping is
     * CORRECT: a story that deliberately anchors itself to a Java symbol is making a claim
     * about code, and when the symbol goes the story really is stale. Satisfying that
     * assertion meant making the resolver skip explicit Java anchors, which turned
     * SEVENTEEN existing tests red. They were right; the test was wrong. E6's sentence is
     * about a story that names no language — not one that names a symbol — and a story of
     * prose is exactly that.</p>
     */
    @Test
    void the_staleness_sweep_leaves_a_story_that_names_no_language(@TempDir Path dir,
            @TempDir Path roots) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            // A resolver that can resolve NOTHING: every anchor it is asked about is gone.
            ExperienceMaintenance maintenance =
                new ExperienceMaintenance(store, fqn -> Boolean.FALSE);
            prose(roots, "a-claim", "a story stated in prose, pointing at no code at all");
            Map<String, Object> report = maintenance.load(roots, true);
            assertEquals(1, report.get("loaded"), () -> "the control: it loaded — " + report);

            String id = store.all().get(0).id();
            assertEquals(ExperienceEntry.ACCEPTED, store.all().get(0).status(),
                "the control: it starts accepted, so a change below is the sweep's doing");

            Map<String, Object> swept = maintenance.refresh();

            assertEquals(ExperienceEntry.ACCEPTED,
                store.byIds(java.util.List.of(id)).get(0).status(),
                () -> "THE SWEEP LEFT IT. Judging prose against a Java symbol resolver"
                    + " tells the reader that correct knowledge has rotted, which is how a"
                    + " staleness signal stops being believed: " + swept);
            assertEquals(0, swept.get("checked"),
                () -> "and it was not even ASKED — a row with nothing to resolve must not"
                    + " reach the resolver at all: " + swept);
        }
    }
}
