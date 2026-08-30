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
 * Sprint 28d S10.1 — THE PATTERN'S OWN CLASSIFICATION MUST SURVIVE EXTRACTION.
 *
 * <p>Harald, 2026-08-30: <i>"we have different patterns for different situations: create
 * an object -&gt; creational pattern like builder or factory. So you can distinguish"</i>.
 * He is describing something the source data already carries and this extractor throws
 * away.</p>
 *
 * <h2>What was measured, 2026-08-30</h2>
 *
 * <p><b>Upstream:</b> 185 of 188 pattern READMEs declare {@code category:} in their
 * frontmatter, across 16 values — Behavioral 40, Structural 34, Architectural 27,
 * Concurrency 22, Creational 14, then Data access, Functional, Resilience, Integration,
 * Testing, Messaging and the rest. They carry {@code tag:} as well.</p>
 *
 * <p><b>Downstream:</b> the 187 stored rows carry {@code slug, type, situation,
 * principle, details, source_ref, reference_type, cause}. No category. No tags. And two
 * fields that look like they might serve and do not: {@code type} is the literal string
 * {@code reference} on all 187, so it discriminates nothing, and {@code reference_type}
 * reads like a classification but holds the Java entry-point class
 * ({@code com.iluwatar.builder.App}).</p>
 *
 * <p><b>And it cannot be recovered from the prose:</b> 170 of 187 rows never name a
 * family anywhere in their principle or situation.</p>
 *
 * <h2>Why that matters, in the user's terms</h2>
 *
 * <p>Without a family, every question is answered by text similarity across all 187
 * generic pattern blurbs, which sit topically near anything about software — so a
 * question about a stop-hook race surfaces Thread-Pool Executor and Fan-Out/Fan-In
 * because all three are "about concurrency and coordination". <i>"I need to create an
 * object"</i> should reach 14 rows and instead ranks against 187.</p>
 */
class CatalogExtractorCarriesTheCategoryTest {

    /** A README in the fork's real shape: YAML frontmatter, then the template sections. */
    private static void writeReadme(Path dir, String slug, String frontmatter) throws IOException {
        Path d = Files.createDirectories(dir.resolve(slug));
        Files.writeString(d.resolve("README.md"),
            "---\n" + frontmatter + "\n---\n\n"
                + "## Intent of " + slug + " Design Pattern\n\n"
                + "The " + slug + " pattern does a thing worth describing in one line.\n\n"
                + "## When to Use the " + slug + " Pattern in Java\n\n"
                + "* Use it when the situation calls for it.\n\n"
                + "## Trade-offs\n\nBenefits and drawbacks.\n",
            StandardCharsets.UTF_8);
    }

    private List<CatalogExtractor.Record> extract(Path root) throws IOException {
        List<CatalogExtractor.Record> out = new ArrayList<>();
        new CatalogExtractor(root, "java-design-patterns", "22a34127", Map.of())
            .extract(0, out);
        return out;
    }

    @Test
    @DisplayName("S10.1: a pattern's declared category reaches the extracted record")
    void the_category_survives_extraction(@TempDir Path root) throws IOException {
        writeReadme(root, "builder",
            "title: \"Builder Pattern in Java\"\ncategory: Creational\nlanguage: en");
        writeReadme(root, "observer",
            "title: \"Observer Pattern in Java\"\ncategory: Behavioral\nlanguage: en");

        List<CatalogExtractor.Record> records = extract(root);
        assertEquals(2, records.size(), "PROOF OF LIFE: both READMEs extracted");

        CatalogExtractor.Record builder = records.stream()
            .filter(r -> "builder".equals(r.slug())).findFirst().orElseThrow();
        CatalogExtractor.Record observer = records.stream()
            .filter(r -> "observer".equals(r.slug())).findFirst().orElseThrow();

        assertEquals("Creational", builder.category(),
            "the family is IN the source frontmatter; dropping it is what makes"
                + " 'I need to create an object' rank against all 187 rows");
        assertEquals("Behavioral", observer.category(),
            "and the two must DIFFER — a constant would satisfy a one-row test while"
                + " discriminating nothing, which is exactly what `type` already does");
    }

    /**
     * THE TAGS TOO, because they are the finer cut inside a family.
     *
     * <p>Builder's own frontmatter reads {@code tag: [Gang of Four, Instantiation,
     * Object composition]}. "Instantiation" separates it from the other creational
     * patterns in a way the category alone cannot.</p>
     */
    @Test
    @DisplayName("S10.1: declared tags reach the record, in the block form the fork uses")
    void the_tags_survive_extraction(@TempDir Path root) throws IOException {
        writeReadme(root, "builder",
            "title: \"Builder Pattern in Java\"\n"
                + "category: Creational\n"
                + "tag:\n  - Gang of Four\n  - Instantiation\n  - Object composition");

        CatalogExtractor.Record builder = extract(root).get(0);

        assertEquals(List.of("Gang of Four", "Instantiation", "Object composition"),
            builder.tags(),
            "all three, in order, from the YAML block list the fork actually writes —"
                + " a parser taking only the first would pass a one-tag test");
    }

    /**
     * A README WITHOUT A CATEGORY IS NOT A FAILURE, and must not become one.
     *
     * <p>Three of the 188 declare none. Refusing them would turn a 185/188 improvement
     * into a broken extraction; inventing a default would put 3 patterns in a family
     * nobody assigned them to, which is worse than leaving them unclassified — an
     * absent category is answerable ("we do not know"), a wrong one is not.</p>
     */
    @Test
    @DisplayName("S10.1: a README with no category extracts, uncategorised")
    void a_readme_without_a_category_still_extracts(@TempDir Path root) throws IOException {
        writeReadme(root, "mystery", "title: \"Mystery Pattern\"\nlanguage: en");

        List<CatalogExtractor.Record> records = extract(root);
        assertEquals(1, records.size(), "it extracts rather than being refused");
        assertTrue(records.get(0).category() == null || records.get(0).category().isBlank(),
            "and it is UNCATEGORISED rather than defaulted into somebody's family: "
                + records.get(0).category());
    }

    /**
     * THE WIRING HALF, and it is the half that was missing.
     *
     * <p>The three tests above assert the category reaches the {@code Record}. All three
     * passed while {@code snapshot} emitted seven fields and neither new one — so the
     * classification was parsed, held in memory, and dropped on the floor one method
     * later. Nothing downstream could have seen it, and no test said so.</p>
     *
     * <p>That is the shape this project has shipped before: a capability built, tested at
     * the point it is produced, and never asserted at the point it is CONSUMED. The
     * snapshot is the only artifact that leaves this class, so it is the only place the
     * claim "the pattern keeps its family" can actually be checked.</p>
     */
    @Test
    @DisplayName("S10.1a: the category and tags reach the SNAPSHOT, not just the record")
    void the_classification_reaches_the_snapshot(@TempDir Path root) throws IOException {
        writeReadme(root, "builder",
            "title: \"Builder Pattern in Java\"\ncategory: Creational\n"
                + "tag: [Gang of Four, Instantiation]");

        List<CatalogExtractor.Record> records = extract(root);
        ObjectMapper json = new ObjectMapper();
        JsonNode row = new CatalogExtractor(root, "java-design-patterns", "22a34127", Map.of())
            .snapshot(records, json).path("patterns").path(0);

        assertEquals("Creational", row.path("category").asText(null),
            () -> "the declared family must be IN the snapshot — the record holding it is"
                + " not the deliverable, because nothing downstream reads a record. Row: "
                + row);
        assertEquals(List.of("Gang of Four", "Instantiation"),
            List.of(row.path("tags").get(0).asText(), row.path("tags").get(1).asText()),
            () -> "and the tags with it, in declaration order. Row: " + row);
    }

    /**
     * The absent case, at the snapshot rather than the record — for the same reason.
     *
     * <p>Three of the 188 upstream READMEs declare no category. The row must carry NO
     * category key rather than an empty string: a consumer filtering on presence and a
     * consumer filtering on non-emptiness then agree, and neither can read {@code ""} as
     * a family named "".</p>
     */
    @Test
    @DisplayName("S10.1a: an uncategorised pattern gets NO category key, not an empty one")
    void an_uncategorised_pattern_carries_no_key(@TempDir Path root) throws IOException {
        writeReadme(root, "mystery", "title: \"Mystery Pattern\"\nlanguage: en");

        List<CatalogExtractor.Record> records = extract(root);
        ObjectMapper json = new ObjectMapper();
        JsonNode row = new CatalogExtractor(root, "java-design-patterns", "22a34127", Map.of())
            .snapshot(records, json).path("patterns").path(0);

        assertTrue(row.path("category").isMissingNode(),
            () -> "an absent category is absent, never \"\": " + row);
        assertTrue(row.path("tags").isMissingNode(),
            () -> "and an empty tag list writes no array at all: " + row);
    }

    /**
     * S10.1c — the misnamed field, asserted at the wire.
     *
     * <p>{@code reference_type} holds {@code com.iluwatar.builder.App}: an entry-point
     * class, not a classification. It was read as the family by the one person who
     * looked, which is how S10.1 started. The rename is asserted on the snapshot because
     * the snapshot is what a later reader opens.</p>
     */
    @Test
    @DisplayName("S10.1c: the entry-point class is spelled as what it is")
    void the_entry_point_class_is_not_called_a_reference_type(@TempDir Path root)
            throws IOException {
        writeReadme(root, "builder", "title: \"Builder\"\ncategory: Creational");

        List<CatalogExtractor.Record> records = extract(root);
        ObjectMapper json = new ObjectMapper();
        JsonNode row = new CatalogExtractor(root, "java-design-patterns", "22a34127", Map.of())
            .snapshot(records, json).path("patterns").path(0);

        assertTrue(row.path("reference_type").isMissingNode(),
            () -> "the old spelling must be GONE, not carried alongside — two names for one"
                + " field is how the next reader picks the wrong one. Row: " + row);
        assertTrue(row.has("entry_point_class"),
            () -> "and the field itself must still be there under its real name: " + row);
    }
}
