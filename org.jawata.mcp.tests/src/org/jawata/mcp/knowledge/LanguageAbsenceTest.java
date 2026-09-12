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
     * A RE-INGEST DOES NOT QUIETLY RE-INTRODUCE THE GUESS.
     *
     * <p>The first load writes through {@code insert}; a second load of a CHANGED file
     * writes through {@code updateSourcedRow}, a different statement with its own copy of
     * every column binding — and the site this sprint added, which is where the defaulting
     * came back in new code the first time. A test that loads once exercises one of the
     * two and says nothing about the path a story takes for the rest of its life.</p>
     */
    @Test
    void a_reloaded_story_still_names_no_language(@TempDir Path dir, @TempDir Path roots)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceMaintenance maintenance = new ExperienceMaintenance(store, fqn -> null);
            prose(roots, "a-claim", "a story whose wording is settled");
            assertEquals(1, maintenance.load(roots, true).get("loaded"), "the control");

            // Same description, changed body — so the row is REWRITTEN where it lies,
            // which is the path with its own column bindings.
            Files.writeString(roots.resolve("a-claim.md"),
                "---\nname: a-claim\ndescription: a story whose wording is settled"
                    + "\ntype: domain_fact\n---\n\nThe body, corrected.\n");
            Map<String, Object> second = maintenance.load(roots, true);
            assertEquals(1, second.get("loaded"),
                () -> "the control: it really re-ingested — " + second);

            Map<String, Object> languages = byLanguage(store);
            assertEquals(1L, store.count(), "the control: still one row");
            assertNotEquals(1L, languages.get("java"),
                () -> "A REWRITE MUST NOT RE-GUESS. The update path carries its own copy of"
                    + " every binding, and it is where a default creeps back in: " + languages);
        }
    }

    /**
     * AND THE STALENESS SWEEP LEAVES IT — a REGRESSION LOCK, and not a test of this change.
     *
     * <p><b>Stated plainly because an auditor had to point it out: this case is invariant
     * under E6's own mutation.</b> Restore the {@code "java"} literal at the insert and it
     * stays green. {@code refresh} drops a row at its FQN guard — <i>no anchor, nothing to
     * resolve</i> — several lines before the language is consulted at all, so a story of
     * prose was already left alone and E6 did not make it so. What the clause describes was
     * true before this stage and is true after it.</p>
     *
     * <p>It is kept anyway, as the lock on the composed behaviour: a story can be loaded
     * from a file, carry no language, and survive a sweep run by a resolver that resolves
     * NOTHING — the state of a machine whose project has moved or was never loaded. The
     * mechanism is named here so nobody later reads the green as evidence for the change
     * above it.</p>
     *
     * <p><b>And the first version of this case asserted something E6 never claimed.</b> It
     * gave the story {@code symbol: com.example.Gone#method} "to reach the language branch",
     * which manufactures a case where sweeping is CORRECT — a story that deliberately
     * anchors itself to a Java symbol is making a claim about code, and when the symbol
     * goes the story really is stale. Satisfying it meant making the resolver skip explicit
     * Java anchors, which turned SEVENTEEN existing tests red. They were right; the test
     * was wrong.</p>
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
