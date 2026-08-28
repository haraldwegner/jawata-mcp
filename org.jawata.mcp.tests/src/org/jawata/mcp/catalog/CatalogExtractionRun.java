package org.jawata.mcp.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sprint 28c M0/M1 — drives {@link CatalogExtractor} and writes the snapshot.
 *
 * <p>Not an assertion suite: it is the extraction RUN, kept in the test bundle
 * because that is where the extractor lives and where the fork checkout is
 * reachable. It is inert unless {@code -Djawata.catalog.fork=<path>} names the
 * pinned checkout, so a normal suite run neither needs the fork nor writes
 * anything — and says so rather than passing silently.</p>
 *
 * <p>{@code -Djawata.catalog.sample=N} extracts the first N patterns and prints
 * them for reading. That is D5's sample-before-bulk gate, and it exists because
 * this loader has produced heading-shaped entries before.</p>
 */
class CatalogExtractionRun {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void extract() throws Exception {
        String fork = System.getProperty("jawata.catalog.fork");
        if (fork == null || fork.isBlank()) {
            System.out.println("[CATALOG] NOT RUN — no fork at -Djawata.catalog.fork. "
                + "This is the extraction run, not a gate; it writes the snapshot only "
                + "when pointed at the pinned checkout.");
            return;
        }
        Path forkRoot = Paths.get(fork);
        String commit = System.getProperty("jawata.catalog.commit", "UNPINNED");
        int sample = Integer.getInteger("jawata.catalog.sample", 0);
        Path out = Paths.get(System.getProperty("jawata.catalog.out", "build/acceptance"));

        Map<String, String> reviewed =
            CatalogExtractor.readReviewed(out.resolve("situations.json"), JSON);
        // The fork's own identity, now supplied rather than hardcoded in the
        // extractor. Its licence line stays: attribution is a condition of
        // redistributing that prose, and S5 generalised the extractor without
        // dropping the obligation.
        String namespace = System.getProperty("jawata.catalog.namespace", "java-design-patterns");
        String licence = System.getProperty("jawata.catalog.licence",
            "MIT (fork of iluwatar/java-design-patterns), attribution retained.");
        CatalogExtractor extractor =
            new CatalogExtractor(forkRoot, namespace, commit, licence, reviewed);

        List<CatalogExtractor.Record> records = new ArrayList<>();
        CatalogExtractor.Report report = extractor.extract(sample, records);

        System.out.println("[CATALOG] readmes seen: " + report.readmesSeen()
            + " | records built: " + report.recordsBuilt()
            + " | reviewed situations available: " + reviewed.size()
            + " | still on a DRAFT: " + report.unreviewed().size()
            + " | missing a template section: " + report.missingSection().size());
        if (!report.missingSection().isEmpty()) {
            System.out.println("[CATALOG] MISSING SECTION (curate, never skip): "
                + report.missingSection());
        }

        if (sample > 0) {
            for (CatalogExtractor.Record r : records) {
                System.out.println("\n--- " + r.slug()
                    + (r.situationReviewed() ? "  [reviewed]" : "  [DRAFT]"));
                System.out.println("situation : " + r.situation());
                System.out.println("principle : " + r.principle());
                System.out.println("ref type  : " + r.referenceType());
                System.out.println("details   : "
                    + r.details().replace("\n", "\n            "));
            }
            return;
        }

        Files.createDirectories(out);
        Path snapshot = out.resolve("patterns-" + commit.substring(0, Math.min(12, commit.length()))
            + ".json");
        Files.writeString(snapshot,
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                extractor.snapshot(records, JSON)) + "\n",
            StandardCharsets.UTF_8);

        StringBuilder rep = new StringBuilder();
        rep.append("# Catalogue extraction report\n#\n");
        rep.append("# fork commit      ").append(commit).append('\n');
        rep.append("# READMEs seen     ").append(report.readmesSeen()).append('\n');
        rep.append("# entries built    ").append(report.recordsBuilt()).append('\n');
        rep.append("# skipped          ").append(report.readmesSeen() - report.recordsBuilt()).append('\n');
        rep.append("# situations on a reviewed line   ")
           .append(report.recordsBuilt() - report.unreviewed().size()).append('\n');
        rep.append("# situations still on a DRAFT     ")
           .append(report.unreviewed().size()).append('\n');
        if (!report.unreviewed().isEmpty()) {
            rep.append("#\n# UNREVIEWED — a draft situation is never shipped:\n");
            for (String s : report.unreviewed()) {
                rep.append("#   ").append(s).append('\n');
            }
        }
        if (!report.missingSection().isEmpty()) {
            rep.append("#\n# MISSING A TEMPLATE SECTION — curate in the snapshot, never skip:\n");
            for (String s : report.missingSection()) {
                rep.append("#   ").append(s).append('\n');
            }
        }
        Files.writeString(out.resolve("catalogue-extraction-report.txt"), rep.toString(),
            StandardCharsets.UTF_8);
        System.out.println("[CATALOG] snapshot -> " + snapshot);
    }
}
