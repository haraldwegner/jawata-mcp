package org.jawata.mcp.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d Stage 6 / S5 — ONE EXTRACTOR, ANY ORIGIN.
 *
 * <p>The extractor was written against one tree and hardcoded it twice: the
 * {@code source_ref} namespace, and the reference type's package. Both are now
 * supplied or resolved, so the same code reads our specimens and the fork.</p>
 *
 * <p><b>Why the fixture's package deliberately does not match its slug.</b> The
 * old derivation COMPOSED the package by stripping dashes from the slug —
 * {@code com.iluwatar.} plus {@code <slug-without-dashes>}. That happens to be
 * right for the fork, whose layout follows the convention, and it is a guess
 * everywhere else. A fixture whose package agreed with its slug could not tell
 * a composed answer from a resolved one: both would produce the same string and
 * the test would pass either way. So the slug here is {@code order-report} while
 * the package is {@code org.example.deliberately.other} — a composer says
 * {@code ...orderreport} and is wrong, a resolver walks the tree and is right.</p>
 */
class CatalogExtractorOriginTest {

    /** The two sections {@code extract} refuses to build a record without. */
    private static final String README = """
        # Order Report

        ## Intent of Order Report

        Reshape a long method into a short sequence of intention-revealing calls.

        ## When to Use Order Report in Java

        * One method interleaves several jobs at different levels of abstraction.

        ## Benefits and Trade-offs of Order Report

        Benefits:

        * The entry point states intent.

        Trade-offs:

        * More members than before.
        """;

    /**
     * A slug directory in the upstream layout, whose Java package is deliberately
     * unrelated to the slug.
     */
    private static Path fixture(Path root) throws Exception {
        Path slug = root.resolve("order-report");
        Files.createDirectories(slug);
        Files.writeString(slug.resolve("README.md"), README, StandardCharsets.UTF_8);

        Path pkg = slug.resolve("src/main/java/org/example/deliberately/other");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("OrderReport.java"),
            "package org.example.deliberately.other;\n\npublic final class OrderReport { }\n",
            StandardCharsets.UTF_8);
        return slug;
    }

    private static CatalogExtractor.Record onlyRecord(Path root, String namespace)
            throws Exception {
        CatalogExtractor extractor =
            new CatalogExtractor(root, namespace, "test-authority", Map.of());
        List<CatalogExtractor.Record> records = new ArrayList<>();
        CatalogExtractor.Report report = extractor.extract(0, records);
        assertEquals(1, report.recordsBuilt(),
            () -> "the fixture must yield exactly one record before anything about it can be"
                + " asserted — a missing template section skips it silently. Missing: "
                + report.missingSection());
        return records.get(0);
    }

    @Test
    void theNamespaceComesFromTheOriginRatherThanTheFork(@TempDir Path root) throws Exception {
        fixture(root);

        CatalogExtractor.Record r = onlyRecord(root, "jawata-samples");

        assertEquals("catalogue:jawata-samples/order-report/README.md", r.sourceRef(),
            "the ref must carry the ORIGIN's namespace. A hardcoded"
                + " catalogue:java-design-patterns/ here would give our own specimens the"
                + " fork's identity, and every ownership question in the seeder is keyed on"
                + " exactly this prefix");
    }

    @Test
    void theReferenceTypeIsRESOLVEDFromTheTreeAndNotComposedFromTheSlug(@TempDir Path root)
            throws Exception {
        fixture(root);

        CatalogExtractor.Record r = onlyRecord(root, "jawata-samples");

        assertEquals("org.example.deliberately.other.OrderReport", r.referenceType(),
            "the reference type must be READ off the tree. The slug is `order-report`, so a"
                + " composer built from it would answer with an `...orderreport` package that"
                + " does not exist — an address that does not open, which is the whole defect"
                + " this stage exists to end");
    }

    @Test
    void theProvenanceNamesTheOriginsOwnAuthority(@TempDir Path root) throws Exception {
        fixture(root);

        CatalogExtractor.Record r = onlyRecord(root, "jawata-samples");

        assertTrue(r.details().contains("test-authority"),
            () -> "the entry's own details must carry the authority it was derived at —"
                + " otherwise a row cannot say which version of its source it came from,"
                + " and re-resolution has nothing to compare against. details:\n"
                + r.details());
    }
}
