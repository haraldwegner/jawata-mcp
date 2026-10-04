package org.jawata.mcp.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jawata.catalogue.Catalogue;
import org.jawata.catalogue.CatalogueRow;
import org.jawata.catalogue.Patterns;
import org.jawata.catalogue.Refactorings;
import org.jawata.catalogue.Smells;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue table itself: its size, its keys, and that it names no language.
 *
 * <p>Sprint 28g D39's measure, and the architecture's P2 step 3 verify clause. Every assertion
 * here is about the TABLE, never about what an engine can do with it — that is each engine's
 * own coverage declaration and is checked on the engine's side.</p>
 *
 * <p>The 66-row claim is scoped to the {@code fowler:} block rather than to the table's size,
 * which is what lets a {@code cpp:} row join without breaking a count that was never about
 * it.</p>
 *
 * <h2>Why this lives in {@code org.jawata.mcp.catalogue} and not in {@code org.jawata.catalogue}</h2>
 *
 * <p>It was written in the latter and that was a defect. This bundle is an OSGi FRAGMENT of
 * {@code org.jawata.mcp}, and the host now imports {@code org.jawata.catalogue} from the
 * catalogue bundle — so a fragment class declared in that same package is a SPLIT PACKAGE: the
 * package resolves to the exporting bundle and the fragment's own classes in it may not be
 * found. The symptom was nondeterministic, which is what makes it worth a paragraph: the two
 * classes loaded and passed on one run and came back
 * {@code ClassNotFoundException … cannot be found by org.jawata.mcp_4.3.3} on the next, with
 * only a string literal changed between them. A test that loads in some runs and not others is
 * a bug, never a flake, and the bug was the package.</p>
 */
class CatalogueIntegrityTest {

    @Test
    @DisplayName("Fowler's refactoring block is 66 rows, numbered 1 to 66 with none missing or doubled")
    void theFowlerBlockIsComplete() {
        // THE LANE IS THE BLOCK, which is why this reads Refactorings and not
        // Catalogue.withOrigin(FOWLER): Fowler published the smells too, so that call now
        // answers 90 and a 66 asserted against it would have been asserting the wrong set.
        List<CatalogueRow> fowler = Refactorings.all();
        Set<String> ids = new LinkedHashSet<>();
        for (CatalogueRow r : fowler) {
            assertTrue(ids.add(r.id()), "two rows share the key " + r.key());
        }
        List<String> missing = new ArrayList<>();
        for (int n = 1; n <= 66; n++) {
            if (!ids.contains(String.valueOf(n))) {
                missing.add("fowler:" + n);
            }
        }
        assertAll(
                () -> assertEquals(66, fowler.size(), "the published catalogue is 66 refactorings"),
                () -> assertEquals(List.of(), missing, "a row of the published catalogue is absent"));
    }

    @Test
    @DisplayName("Fowler's chapter 3 is 24 rows, in his order, with none missing or doubled")
    void theSmellBlockIsComplete() {
        List<CatalogueRow> smells = Smells.all();
        Set<String> ids = new LinkedHashSet<>();
        for (CatalogueRow r : smells) {
            assertTrue(ids.add(r.id()), "two rows share the key " + r.key());
        }
        List<String> missing = new ArrayList<>();
        for (int n = 1; n <= 24; n++) {
            if (!ids.contains(Smells.SMELL + n)) {
                missing.add("fowler:" + Smells.SMELL + n);
            }
        }
        assertAll(
                () -> assertEquals(24, smells.size(), "Fowler's chapter 3 is twenty-four smells"),
                () -> assertEquals(List.of(), missing, "a smell of the published chapter is absent"),
                // The two DECLINED smells are rows like any other. A declination is a claim an
                // engine makes ABOUT a row, so the row has to exist for the claim to be about
                // anything — dropping them here would make the engine's note unanchored.
                () -> assertEquals(
                        "Mysterious Name",
                        smells.get(0).name(),
                        "a declined smell is still a row"),
                () -> assertEquals(
                        "Comments",
                        smells.get(23).name(),
                        "a declined smell is still a row"));
    }

    @Test
    @DisplayName("the two lanes share one origin and no key collides across them")
    void theLanesShareAnOriginWithoutColliding() {
        // Fowler published both tables, so both carry origin `fowler` — and his chapter 3
        // numbers nothing, which is why the smell lane's id carries its own prefix. Without
        // that, `fowler:3` would mean Change Value to Reference AND Long Function.
        Set<String> keys = new LinkedHashSet<>();
        List<String> collisions = new ArrayList<>();
        for (CatalogueRow r : Catalogue.rows()) {
            if (!keys.add(r.key())) {
                collisions.add(r.key());
            }
        }
        assertAll(
                () -> assertEquals(List.of(), collisions, "one key names two rows"),
                () -> assertEquals(
                        90,
                        Catalogue.withOrigin(Refactorings.FOWLER).size(),
                        "everything Fowler published: 66 refactorings and 24 smells"),
                () -> assertEquals(
                        Catalogue.rows().size(),
                        Refactorings.all().size() + Smells.all().size() + Patterns.all().size(),
                        "the table is exactly its lanes — a lane missing from Catalogue.rows()"
                                + " would make every cross-lane question blind to it"));
    }

    @Test
    @DisplayName("the pattern lane is the pinned snapshot's own rows, counted against its own header")
    void thePatternLaneIsTheSnapshotsOwnRows() {
        List<CatalogueRow> patterns = Patterns.all();
        Set<String> keys = new LinkedHashSet<>();
        List<String> wrongOrigin = new ArrayList<>();
        for (CatalogueRow r : patterns) {
            assertTrue(keys.add(r.key()), "two pattern rows share the key " + r.key());
            if (!r.origin().equals(Patterns.FORK)) {
                wrongOrigin.add(r.key());
            }
        }
        assertAll(
                // THE COUNT IS HELD AGAINST THE RESOURCE'S OWN HEADER, not against a number
                // typed here. A hand-written 187 in this assertion would be a second copy of
                // the one fact the lane exists to stop copying, and it would agree with a
                // truncated resource as happily as with a whole one.
                () -> assertEquals(
                        Patterns.declaredCount(),
                        patterns.size(),
                        "the lane read fewer rows than its own header declares — the resource is"
                                + " truncated, or the reader is dropping lines"),
                () -> assertTrue(
                        patterns.size() > 0,
                        "PROOF OF LIFE: an empty lane would make the comparison above a"
                                + " comparison of two zeros"),
                () -> assertEquals(List.of(), wrongOrigin, "a pattern row carries a foreign origin"),
                // The name is the slug verbatim — nothing is title-cased, because the snapshot
                // carries no published title and a generated one would be mine.
                () -> assertEquals(
                        "visitor",
                        Catalogue.byKey("java-design-patterns:visitor").name(),
                        "a pattern row's name is its slug, as the fork publishes it"));
    }

    @Test
    @DisplayName("the language clause is about a row's NAME, never about who published it")
    void theLanguageClauseIsAboutTheNameNotThePublisher() {
        // `java-design-patterns` contains "java", and that is correct rather than a violation:
        // an origin names the PUBLISHER, and this publisher happens to be a Java repository. A
        // C++ corpus would arrive as `cpp-design-patterns` and would be just as right. What may
        // never name a language is the row's NAME, which is what noRowNamesALanguage reads —
        // stated here because a reader meeting that origin would otherwise suspect the clause
        // had been quietly narrowed to let it through.
        assertTrue(
                Patterns.FORK.contains("java"),
                "if the fork's origin stops naming its language this note is stale, not wrong");
        assertFalse(
                Catalogue.withOrigin(Patterns.FORK).isEmpty(),
                "the exemption must be about rows that exist");
    }

    @Test
    @DisplayName("a key is looked up without the caller knowing which lane holds it")
    void byKeyAnswersAcrossLanes() {
        assertAll(
                () -> assertEquals("Move Field", Catalogue.byKey("fowler:23").name()),
                () -> assertEquals("Lazy Element", Catalogue.byKey("fowler:smell-14").name()),
                // A miss is null rather than an exception: asking whether the catalogue holds a
                // key is a legitimate question, and a second language will ask it about keys we
                // do not have yet.
                () -> assertEquals(null, Catalogue.byKey("cpp:rule-of-five")),
                () -> assertEquals(null, Catalogue.byKey("fowler:67")));
    }

    @Test
    @DisplayName("every key is namespaced by its origin, so a second language needs no renumbering")
    void everyKeyCarriesItsOrigin() {
        for (CatalogueRow r : Catalogue.rows()) {
            assertTrue(r.key().contains(":"), "an un-namespaced key: " + r.key());
            assertFalse(r.origin().isBlank(), "a key with no origin: " + r.key());
            assertFalse(r.id().isBlank(), "a key with no id: " + r.key());
        }
        // The constructor refuses the bare form outright, so the property cannot be lost by
        // someone adding a row the easy way.
        assertThrows(IllegalArgumentException.class, () -> new CatalogueRow("23", "Move Field"));
        assertThrows(IllegalArgumentException.class, () -> new CatalogueRow(":23", "Move Field"));
    }

    @Test
    @DisplayName("no row names a language — the table is shared, not Java's")
    void noRowNamesALanguage() {
        // The point of promoting the table: it must read the same to a C++ or Rust engine. A
        // row whose NAME carried a language would be a row only one engine could mean.
        List<String> languages = List.of("java", "kotlin", "c++", "cpp", "rust", "python", "jdt");
        List<String> offenders = new ArrayList<>();
        for (CatalogueRow r : Catalogue.rows()) {
            String lower = r.name().toLowerCase();
            for (String lang : languages) {
                if (lower.contains(lang)) {
                    offenders.add(r.key() + " = " + r.name());
                }
            }
        }
        assertEquals(List.of(), offenders, "a catalogue row names a language");
    }

    @Test
    @DisplayName("the row carries no operation — what to do about it is an engine's claim, not the table's")
    void theRowCarriesNoOperation() {
        // A row is (key, name). If an operation ever reappears on it, the table has become one
        // engine's again, which is the state D39 exists to leave.
        assertEquals(
                2,
                CatalogueRow.class.getRecordComponents().length,
                "a catalogue row is its key and its name: "
                        + List.of(CatalogueRow.class.getRecordComponents()));
    }
    /**
     * THE PATTERN LANE AGAINST THE SNAPSHOT IT WAS SEEDED FROM — and this control does not expire.
     *
     * <p><b>It was written in the scaffold class and that was a defect a C8 audit caught.</b>
     * That class's own header orders its deletion when the test table it compares against goes,
     * and this is the ONLY assertion anywhere holding {@code patterns.tsv} against the pinned
     * snapshot. Obeying that header would have left the 187 self-certifying: the sibling count
     * check reads {@code Patterns.all()} against {@code Patterns.declaredCount()}, and both come
     * from the same resource. This repository has already shipped the shape where the proof
     * artifact was deleted and the claim became unprovable.</p>

     * <p>It belongs HERE because it does not expire: {@code patterns.tsv} is GENERATED from the
     * pinned snapshot and the snapshot stays, so the two are a projection and its source,
     * permanently, and a regeneration that silently dropped rows is what this catches.</p>
     *
     * <p>The snapshot is found by its pinned commit in the filename rather than by a fixed path,
     * because re-pinning the fork renames it. A miss states what it looked for.</p>
     */
    @Test
    @DisplayName("every pattern row is a slug of the pinned snapshot, in its order, none added or dropped")
    void thePatternRowsMatchTheSnapshotTheyWereSeededFrom() throws IOException {
        Path dir = RepoFiles.sourceOf("build/acceptance");
        assertTrue(dir != null && Files.isDirectory(dir),
                "looked for build/acceptance above " + Path.of("").toAbsolutePath());

        List<Path> snapshots;
        try (var listing = Files.list(dir)) {
            snapshots = listing
                    .filter(p -> p.getFileName().toString().startsWith("patterns-"))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        assertEquals(1, snapshots.size(),
                "exactly one pinned pattern snapshot must be present — two would make it"
                        + " ambiguous which one the lane was generated from: " + snapshots);

        JsonNode snapshot = new ObjectMapper().readTree(snapshots.get(0).toFile());
        List<String> theirs = new ArrayList<>();
        for (JsonNode p : snapshot.get("patterns")) {
            theirs.add(p.get("slug").asText());
        }
        // PROOF OF LIFE, and it reads the snapshot's OWN declared count rather than a literal:
        // a reader that matched nothing would otherwise compare two empty lists.
        assertEquals(snapshot.get("count").asInt(), theirs.size(),
                "the snapshot's patterns array disagrees with its own count field");

        List<String> ours = new ArrayList<>();
        for (CatalogueRow r : Patterns.all()) {
            ours.add(r.name());
        }
        assertEquals(theirs, ours,
                "the pattern lane and the snapshot it is generated from have diverged — the"
                        + " resource was hand-edited, or a regeneration dropped rows");
    }


}
