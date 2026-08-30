package org.jawata.mcp.catalog;

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
}
