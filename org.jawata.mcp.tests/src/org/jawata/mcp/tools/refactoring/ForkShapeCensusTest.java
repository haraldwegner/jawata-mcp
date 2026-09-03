package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE INSTRUMENT BEHIND A CLAIM, committed so the claim can be re-checked.
 *
 * <p>Stage 3 reports that rows 7, 44 and 63 have no fork demonstration because their
 * shapes occur nowhere in the corpus, and that row 50's finder and rewriter disagree by
 * about ten to one. Both numbers came from aggregating the fork into one project and
 * sweeping it. A C3 audit found that the thing which produced them was scratch and had
 * never been committed — so the measurement was sound and the evidence was not
 * reproducible, which makes it a claim rather than a result.</p>
 *
 * <p>This is that instrument. It builds the aggregate from the fork checkout, runs the
 * four kinds, and asserts the census. It is also a regression detector: if the fork pin
 * moves and a shape appears, the row that was excused now has a candidate and this test
 * says so.</p>
 *
 * <h2>Why it ABORTS rather than fails without the fork</h2>
 *
 * <p>The corpus is a checkout, not a vendored fixture — 1884 files, which do not belong in
 * this repository. A machine without it cannot run this, and the honest answer there is
 * "not measured", not "passed". That is the same shape as the suite's existing
 * corpus-absence aborts.</p>
 */
class ForkShapeCensusTest {

    /** Where the pinned fork checkout lives; override with -Djawata.test.fork=... */
    private static final String FORK_PROPERTY = "jawata.test.fork";
    private static final String DEFAULT_FORK = "/home/harald/CursorProjects/java-design-patterns";

    /**
     * The census, as measured on pin 22a34127d. Each entry is FILES CHANGED by that kind
     * across the whole corpus.
     */
    private static final Map<String, Integer> EXPECTED = new LinkedHashMap<>(Map.of(
        "consolidate_conditional", 0,
        "control_flag_to_break", 0,
        "split_loop", 0,
        "loop_to_pipeline", 2));

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("the fork census: three rows fire on nothing, and row 50 fires on two files")
    void theCensusHolds() throws Exception {
        Path fork = Path.of(System.getProperty(FORK_PROPERTY, DEFAULT_FORK));
        Assumptions.assumeTrue(Files.isDirectory(fork),
            "NOT RUN — no fork checkout at " + fork + " (set -D" + FORK_PROPERTY + "). "
                + "Stage 3's fork-exhaustiveness numbers are unverified in this run.");

        Path fixtures = helper.getFixturePath("simple-maven").getParent();
        Path aggregate = fixtures.resolve("probe-fork-census");
        try {
            int files = buildAggregate(fork, aggregate);
            // PROOF OF LIFE. A census over an empty aggregate reports zero for every kind
            // and would "confirm" three of the four expectations while measuring nothing.
            assertTrue(files > 1500,
                "expected the whole fork (~1884 source files), aggregated " + files
                    + " — the census would be about a different corpus");

            JdtServiceImpl service = helper.loadProjectCopy("probe-fork-census");
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Integer> actual = new LinkedHashMap<>();
            for (String kind : EXPECTED.keySet()) {
                ObjectNode args = mapper.createObjectNode();
                args.put("kind", kind);
                ToolResponse r = new ApplyCleanupTool(() -> service, new RefactoringChangeCache())
                    .execute(args);
                assertTrue(r.isSuccess(), kind + " must run over the corpus; got: " + r.getError());
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) r.getData();
                Object changed = data.get("filesChanged");
                actual.put(kind, changed instanceof Integer n ? n : 0);
            }
            assertEquals(EXPECTED, actual,
                "the fork census moved. If the pin changed this is news, not a defect: a row"
                    + " excused for having no candidate may now have one, and Stage 3's"
                    + " written reasons need re-reading before this expectation is updated.");
        } finally {
            deleteTree(aggregate);
        }
    }

    /** Copy every module's main and test sources into one project. Returns the file count. */
    private static int buildAggregate(Path fork, Path aggregate) throws IOException {
        deleteTree(aggregate);
        Path src = aggregate.resolve("src/main/java");
        Files.createDirectories(src);
        try (Stream<Path> modules = Files.list(fork)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                copyTree(module.resolve("src/main/java"), src);
                copyTree(module.resolve("src/test/java"), src);
            }
        }
        Files.writeString(aggregate.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        try (Stream<Path> all = Files.walk(src)) {
            return (int) all.filter(p -> p.toString().endsWith(".java")).count();
        }
    }

    private static void copyTree(Path from, Path into) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.filter(Files::isRegularFile).toList()) {
                Path destination = into.resolve(from.relativize(source).toString());
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Lombok, slf4j, junit and h2 so the corpus resolves as far as it can. */
    private static final String POM = String.join("\n", List.of(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">",
        "  <modelVersion>4.0.0</modelVersion>",
        "  <groupId>org.jawata.fixtures</groupId>",
        "  <artifactId>probe-fork-census</artifactId>",
        "  <version>1.0.0</version>",
        "  <properties><maven.compiler.release>21</maven.compiler.release></properties>",
        "  <dependencies>",
        "    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>"
            + "<version>1.18.36</version></dependency>",
        "    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>"
            + "<version>2.0.18</version></dependency>",
        "    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>"
            + "<version>5.11.4</version></dependency>",
        "    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId>"
            + "<version>2.2.224</version></dependency>",
        "  </dependencies>",
        "</project>", ""));
}
