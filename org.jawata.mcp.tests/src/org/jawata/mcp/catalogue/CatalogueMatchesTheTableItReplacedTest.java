package org.jawata.mcp.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TRANSCRIPTION CONTROL, and it is deliberately temporary.
 *
 * <p>{@code Refactorings} was written by copying 66 names out of
 * {@code PerformedRefactoringCountTest}. Copying 66 strings by hand is exactly the operation
 * that produces one wrong character in a name nobody re-reads, and a renamed refactoring would
 * then ship silently — every other test here asserts counts and keys, which a typo passes
 * straight through.</p>
 *
 * <p>So this reads the OLD table's source text and compares the names, in order, against the
 * new one. It is the only test here that reads a file rather than calling code, and that is on
 * purpose: the old table is about to be deleted, and until it is, its text is the authority the
 * new class was transcribed from.</p>
 *
 * <p><b>DELETE THIS CLASS in the step that deletes {@code PerformedRefactoringCountTest}'s
 * table.</b> It is a scaffold with a stated expiry, not a standing invariant — once the old
 * table is gone there is nothing to compare against and the file-reading would become a test of
 * its own fixture. {@link CatalogueIntegrityTest} is the standing guard.</p>
 */
class CatalogueMatchesTheTableItReplacedTest {

    /** {@code r.add(new Row(23, "Move Field", "move kind=field"));} — number and name. */
    private static final Pattern OLD_ROW = Pattern.compile("new Row\\((\\d+),\\s*\"([^\"]+)\"");

    private static final String OLD_TABLE =
            "org.jawata.mcp.tests/src/org/jawata/mcp/tools/PerformedRefactoringCountTest.java";

    /** {@code m.put("Long Function", "long_method");} — the smell's name, which is all we take. */
    private static final Pattern OLD_SMELL = Pattern.compile("m\\.put\\(\"([^\"]+)\",");

    private static final String OLD_SMELL_TABLE =
            "org.jawata.mcp.tests/src/org/jawata/mcp/tools/smell/"
                    + "EverySmellIsDetectedOrDeclinedTest.java";

    @Test
    @DisplayName("all 66 names match the table this replaced, in order, character for character")
    void theNamesMatchTheOldTable() throws IOException {
        Path old = oldTableSource();
        assertTrue(
                old != null && Files.exists(old),
                "the control's subject is gone — if PerformedRefactoringCountTest's table has been "
                        + "deleted, DELETE THIS CLASS TOO rather than leaving a test that cannot "
                        + "fail. Looked for " + OLD_TABLE + " above " + Path.of("").toAbsolutePath());

        List<String> theirs = new ArrayList<>();
        Matcher m = OLD_ROW.matcher(Files.readString(old));
        while (m.find()) {
            theirs.add(m.group(1) + " " + m.group(2));
        }
        // PROOF OF LIFE: without it, a pattern that matched nothing would make the comparison
        // below a comparison of two empty lists, and this control would certify anything.
        assertEquals(66, theirs.size(), "the old table did not yield 66 rows — the reader is wrong, not the data");

        List<String> ours = new ArrayList<>();
        for (CatalogueRow r : Refactorings.all()) {
            ours.add(r.id() + " " + r.name());
        }
        assertEquals(theirs, ours, "a name was mistyped or reordered in the move to production");
    }

    @Test
    @DisplayName("all 24 smell names match the map this replaced, in Fowler's order")
    void theSmellNamesMatchTheOldMap() throws IOException {
        Path old = sourceOf(OLD_SMELL_TABLE);
        assertTrue(
                old != null && Files.exists(old),
                "the control's subject is gone — if EverySmellIsDetectedOrDeclinedTest's map has "
                        + "been deleted, DELETE THIS TEST TOO rather than leaving one that cannot "
                        + "fail. Looked for " + OLD_SMELL_TABLE + " above "
                        + Path.of("").toAbsolutePath());

        List<String> theirs = new ArrayList<>();
        Matcher m = OLD_SMELL.matcher(Files.readString(old));
        while (m.find()) {
            theirs.add(m.group(1));
        }
        // PROOF OF LIFE, and it is load-bearing for a reason this file has already paid once:
        // the pattern reads `m.put("…"` and that idiom could appear in a fixture or a second
        // map in the same file. Pinning the COUNT means a reader that drifted is caught here
        // rather than silently comparing the wrong twenty-four.
        assertEquals(
                24,
                theirs.size(),
                "the old map did not yield 24 names — the reader is wrong, not the data: " + theirs);

        List<String> ours = new ArrayList<>();
        for (CatalogueRow r : Smells.all()) {
            ours.add(r.name());
        }
        assertEquals(theirs, ours, "a smell was mistyped or reordered in the move to production");
    }

    /**
     * ONLY THE NAME IS TAKEN FROM THE OLD MAP, and the omission is the deliverable.
     *
     * <p>That map's value is the {@code find_quality_issue} kind — {@code long_method} for Long
     * Function. It is the Java engine's spelling and it is exactly the third column that was
     * removed from the refactoring rows, so a smell row that carried it would re-make the table
     * one engine's. This asserts the absence rather than leaving it to be read off the row's
     * shape, because an absence nothing checks is indistinguishable from an oversight.</p>
     */
    @Test
    @DisplayName("no smell row carries the kind that detects it — that is the engine's claim")
    void theSmellRowCarriesNoKind() {
        List<String> offenders = new ArrayList<>();
        // Every kind the old map named, including the five whose spelling differs from
        // Fowler's title and are therefore the ones a transcription would most plausibly drag
        // across: long_method, god_class, lazy_class, inappropriate_intimacy, switch_statements.
        List<String> kinds = List.of(
                "duplicated_code", "long_method", "long_parameter_list", "global_data",
                "mutable_data", "divergent_change", "shotgun_surgery", "feature_envy",
                "data_clumps", "primitive_obsession", "switch_statements", "loops",
                "lazy_class", "speculative_generality", "temporary_field", "message_chains",
                "middle_man", "inappropriate_intimacy", "god_class", "alternative_classes",
                "data_class", "refused_bequest");
        for (CatalogueRow r : Smells.all()) {
            for (String kind : kinds) {
                if (r.name().contains(kind) || r.key().contains(kind)) {
                    offenders.add(r.key() + " = " + r.name() + " carries '" + kind + "'");
                }
            }
        }
        assertEquals(List.of(), offenders, "a smell row names the engine kind that detects it");
    }

    /**
     * THE PATTERN LANE AGAINST THE SNAPSHOT IT WAS SEEDED FROM — and this control does not expire.
     *
     * <p>The other two controls here are scaffolds with a stated end: they compare a new
     * production table against the test table it replaced, and they are deleted when that table
     * is. This one is different in kind. {@code patterns.tsv} is GENERATED from the pinned
     * snapshot and the snapshot stays — so the two are a projection and its source, permanently,
     * and a regeneration that silently dropped rows is exactly what this catches.</p>
     *
     * <p>The snapshot is found by its pinned commit in the filename rather than by a fixed path,
     * because re-pinning the fork renames it. A miss states what it looked for.</p>
     */
    @Test
    @DisplayName("every pattern row is a slug of the pinned snapshot, in its order, none added or dropped")
    void thePatternRowsMatchTheSnapshotTheyWereSeededFrom() throws IOException {
        Path dir = sourceOf("build/acceptance");
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

    /**
     * The old table's source file, found by walking up from wherever the runner started.
     *
     * <p>The suite runs from the dist, whose working directory is not reliably the repository
     * root, so the path is searched for rather than assumed — and a miss returns {@code null}
     * so the assertion above can say what it looked for instead of failing on a bare
     * {@code NoSuchFileException}.</p>
     */
    private static Path oldTableSource() {
        return sourceOf(OLD_TABLE);
    }

    private static Path sourceOf(String repoRelative) {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve(repoRelative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
