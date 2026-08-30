package org.jawata.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d S10.1 — THE EXTRACTOR MUST REPRODUCE ITS OWN COMMITTED ARTIFACT.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>All 187 committed rows carry a {@code cause} — the design FORCE the pattern
 * answers, which {@code CatalogueManifest.entryFor} reads onto every entry and the
 * recall differential ranks on when two patterns share a situation (Factory and
 * Builder both answer "constructing an object"; the cause is what separates them).</p>
 *
 * <p>Those causes were authored straight into {@code patterns.json} and existed in no
 * other file. The extractor emitted no cause at all — so re-running the extraction
 * DELETED all 187, and the loss was invisible: the run reports counts, not fields, and
 * the snapshot would still have looked like a complete snapshot. Nothing would have
 * failed until a recall quietly stopped discriminating.</p>
 *
 * <p>Measured 2026-08-30 against the real fork at pin
 * {@code 22a34127d0b08449c24cf7e230c04a097deca2f3}: with the causes curated into
 * {@code causes.json} and read back, the regenerated snapshot is byte-identical to the
 * committed one on {@code type}, {@code situation}, {@code cause}, {@code principle} and
 * {@code source_ref} across all 187 rows. The one remaining difference is a single prose
 * line in {@code details} — {@code iluwatar/java-design-patterns} became
 * {@code java-design-patterns} when S5 stopped hardcoding the fork's identity — and the
 * Licence line directly below it still names the owner, so the attribution obligation is
 * carried.</p>
 *
 * <h2>Why a test and not a note</h2>
 *
 * <p>The property "this extractor can reproduce what it produced" has no natural
 * failure: dropping a field makes the output smaller, not wrong-looking. So it is
 * asserted here, on a field the loader actually reads, with the absent case asserted
 * beside it so the gate cannot be satisfied by emitting a cause unconditionally.</p>
 */
class CatalogExtractorReproducesItsArtifactTest {

    private static void writeReadme(Path dir, String slug) throws IOException {
        Path d = Files.createDirectories(dir.resolve(slug));
        Files.writeString(d.resolve("README.md"),
            "---\ntitle: \"" + slug + "\"\ncategory: Creational\n---\n\n"
                + "## Intent of " + slug + " Design Pattern\n\n"
                + "The " + slug + " pattern does a thing worth describing in one line.\n\n"
                + "## When to Use the " + slug + " Pattern in Java\n\n"
                + "* Use it when the situation calls for it.\n\n"
                + "## Trade-offs\n\nBenefits and drawbacks.\n",
            StandardCharsets.UTF_8);
    }

    private static JsonNode snapshotRow(Path root, Map<String, String> causes)
            throws IOException {
        CatalogExtractor x = new CatalogExtractor(
            root, "java-design-patterns", "test-authority", null, Map.of(), causes);
        List<CatalogExtractor.Record> records = new ArrayList<>();
        x.extract(0, records);
        return x.snapshot(records, new ObjectMapper()).path("patterns").path(0);
    }

    @Test
    @DisplayName("S10.1: a curated cause survives extraction and reaches the snapshot")
    void the_curated_cause_reaches_the_snapshot(@TempDir Path root) throws IOException {
        writeReadme(root, "builder");

        JsonNode row = snapshotRow(root, Map.of("builder",
            "the object's optional parts combine combinatorially, so constructors per"
                + " combination cannot scale"));

        assertEquals(
            "the object's optional parts combine combinatorially, so constructors per"
                + " combination cannot scale",
            row.path("cause").asText(null),
            () -> "the cause is what the recall differential ranks on when two patterns"
                + " share a situation. Emitting no cause is how 187 of them were silently"
                + " deleted by a regeneration. Row: " + row);
    }

    /**
     * The other half, and without it the gate is satisfiable by a constant.
     *
     * <p>An absent cause must produce NO key rather than an empty string: a wrong cause
     * would be ranked on, so "we do not know" and "" must not be the same answer.</p>
     */
    @Test
    @DisplayName("S10.1: a slug with no curated cause carries no cause key")
    void an_uncurated_slug_carries_no_cause(@TempDir Path root) throws IOException {
        writeReadme(root, "mystery");

        JsonNode row = snapshotRow(root, Map.of());

        assertTrue(row.path("cause").isMissingNode(),
            () -> "an absent cause is absent, never \"\" — otherwise this gate passes on"
                + " an extractor that emits a constant. Row: " + row);
    }

    /**
     * The curated file is read under its own key, exactly as situations are.
     *
     * <p>Two curated inputs handled two different ways is how the next person picks the
     * wrong one, so this asserts the shared reader rather than a second bespoke one.</p>
     */
    @Test
    @DisplayName("S10.1: causes.json is read the same way situations.json is")
    void the_curated_file_is_read_under_its_own_key(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("causes.json");
        Files.writeString(f,
            "{\"_why\": \"a note the reader ignores\","
                + " \"causes\": {\"builder\": \"the parts combine combinatorially\"}}",
            StandardCharsets.UTF_8);

        Map<String, String> causes =
            CatalogExtractor.readCurated(f, new ObjectMapper(), "causes");

        assertEquals(Map.of("builder", "the parts combine combinatorially"), causes,
            "the named key is read and the sibling prose note is not");
        assertEquals(Map.of(),
            CatalogExtractor.readCurated(dir.resolve("absent.json"), new ObjectMapper(), "causes"),
            "a missing file is an empty map, not a failure — the run must work before the"
                + " curation exists");
    }
}
