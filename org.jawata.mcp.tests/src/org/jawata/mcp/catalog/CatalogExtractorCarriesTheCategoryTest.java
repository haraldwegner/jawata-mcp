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
 * <p><b>Upstream, CORRECTED 2026-08-30 by measuring it.</b> This paragraph first said
 * "185 of 188 pattern READMEs declare {@code category:}, across 16 values". There are
 * <b>187</b> READMEs and <b>every one of them names a family</b>, across 15 values —
 * Behavioral 41, Structural 35, Architectural 27, Concurrency 22, Creational 14, Data
 * access 12, Functional 8, Integration 6, Resilience 6, Messaging 4, Testing 4,
 * Performance optimization 3, Resource management 3, Idiom 1, Service Discovery 1. 185
 * spell the key {@code category:}; two spell it {@code categories:}. They carry a tag
 * list as well, under {@code tag:} (182) or {@code tags:} (5).</p>
 *
 * <p>The original numbers are quoted rather than overwritten because the correction IS
 * the finding: an exact key match reported "the author declared nothing" about authors
 * who had declared it.</p>
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
     * THE FORK SPELLS THE KEY TWO WAYS, and reading one is a wrong answer.
     *
     * <p>Measured over all 187 READMEs at pin {@code 22a34127d}: 185 write
     * {@code category:}, two write {@code categories:} — component (Structural) and
     * serialized-entity (Data access) — and none writes neither. An exact key match
     * on the singular returns {@code null} for those two, which is indistinguishable
     * from "upstream declared no family" while the family sits in the file.</p>
     *
     * <p>The fixture uses the PLURAL only, so the test fails if the synonym is
     * dropped; a fixture carrying both would pass on the singular alone.</p>
     */
    @Test
    @DisplayName("S10.1a: the plural spelling the fork also uses is read, not missed")
    void the_plural_key_spelling_is_read_too(@TempDir Path root) throws IOException {
        writeReadme(root, "component", "title: \"Component\"\ncategories: Structural");

        List<CatalogExtractor.Record> records = extract(root);
        assertEquals("Structural", records.get(0).category(),
            () -> "component declares `categories: Structural` upstream. Reading only the"
                + " singular reports no family for a pattern that named one — a missed"
                + " category, not an absent one, and the two mean opposite things");
    }

    /**
     * The SAME split on the tag key, and the majority runs the other way.
     *
     * <p>Measured at the pin: 182 READMEs write {@code tag:}, 5 write {@code tags:}
     * (client-session, context-object, model-view-intent, notification,
     * page-controller), none writes neither. So the plural is the minority here and
     * the majority for the family — neither can be assumed from the other, which is
     * why both keys get their own test rather than one shared one.</p>
     */
    @Test
    @DisplayName("S10.1a: the plural tag key is read too")
    void the_plural_tag_key_is_read_too(@TempDir Path root) throws IOException {
        writeReadme(root, "client-session",
            "title: \"Client Session\"\ncategory: Behavioral\ntags:\n  - Client-server\n  - State tracking");

        List<CatalogExtractor.Record> records = extract(root);
        assertEquals(List.of("Client-server", "State tracking"), records.get(0).tags(),
            () -> "an empty list is how this record says 'the author declared no tags'."
                + " Five READMEs declared tags under the plural key and got that answer");
    }

    /**
     * A BLANK LINE BEFORE THE FIRST ITEM ENDED THE LIST, and two patterns lost five
     * tags each to it.
     *
     * <p>thread-pool-executor and thread-specific-storage write {@code tag:}, then a
     * blank line, then their items. The block reader broke on the first non-item line
     * — the blank — and returned empty. Both declared five tags and the snapshot said
     * they had none.</p>
     *
     * <p>The fixture reproduces the exact upstream shape. The control is the closing
     * assertion: the list must still STOP at a following key, or blank-skipping would
     * swallow the next field's items.</p>
     */
    @Test
    @DisplayName("S10.1a: a blank line before the first item does not end the list")
    void a_blank_line_before_the_items_does_not_end_the_list(@TempDir Path root)
            throws IOException {
        writeReadme(root, "thread-pool-executor",
            "title: \"Thread-Pool Executor\"\ncategory: Concurrency\ntag:\n\n- Performance\n- Scalability");

        List<CatalogExtractor.Record> records = extract(root);
        assertEquals(List.of("Performance", "Scalability"), records.get(0).tags(),
            () -> "the items follow a blank line, exactly as two upstream READMEs write"
                + " them; breaking on the blank reported five declared tags as none");
        assertEquals("Concurrency", records.get(0).category(),
            () -> "and the control: the list must still stop at the next key rather than"
                + " running on through the rest of the frontmatter");
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
     * <p>No upstream README actually lacks a category — the "three" this comment once
     * claimed were two spelling the key {@code categories:} plus one miscount. The case is
     * kept anyway, because it is the CONTRACT and not a census: the row must carry NO
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
        // A REAL SOURCE FILE, because the second assertion needs the field to be
        // PRESENT. This test first shipped against a README-only fixture and went red
        // the moment `77e2277` made the snapshot omit the field for a slug with no Java
        // source — which was the correct behaviour meeting an assertion that assumed the
        // old one. Asserting "the new name is there" over a fixture that cannot produce
        // it was proving nothing even while it passed.
        Path pkg = root.resolve("builder/src/main/java/com/iluwatar/builder");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("App.java"),
            "package com.iluwatar.builder;\n\npublic final class App { }\n",
            StandardCharsets.UTF_8);

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
