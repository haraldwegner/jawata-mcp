package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.jawata.mcp.tools.FindModernizationTool;
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
 * <p>This is that instrument. It builds the aggregate from the fork checkout and asserts
 * BOTH halves of what was claimed: the rewriter census — which kinds fire, and on how many
 * files — and the FINDER count for row 50, which is the other side of the
 * finder-versus-rewriter gap. A first version checked only the rewriter while its own
 * javadoc called itself the instrument behind both numbers. A second audit caught that,
 * and it was right.</p>
 *
 * <p>It is also a regression detector: if the fork pin moves and a shape appears, the row
 * that was excused now has a candidate and this test says so.</p>
 *
 * <h2>Why it ABORTS rather than fails without the fork</h2>
 *
 * <p>The corpus is a checkout, not a vendored fixture — nearly two thousand files, which do not belong in
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
            // NOT "the whole fork", and that difference was an over-claim an audit caught.
            // Flattening every module into ONE source root collides paths — several
            // modules own a `com/iluwatar/App.java` — so the aggregate holds the DISTINCT
            // paths, fewer than the fork's file count. Asserting the exact number keeps
            // the claim and the corpus from drifting apart again.
            assertEquals(1884, files,
                "expected 1884 distinct source paths aggregated from the fork; got " + files
                    + ". The fork holds MORE files than this — the remainder collide on"
                    + " path when the modules are flattened, and each collision is"
                    + " represented by one of its copies. If this number moved, the pin"
                    + " moved, and every count below is about a different corpus.");

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

            // THE OTHER HALF. "The finder names 33 candidates in 21 files and the rewriter
            // changes 2" is a claim about TWO tools, and checking one of them re-checks
            // nothing about the gap between them.
            ObjectNode finderArgs = mapper.createObjectNode();
            finderArgs.put("kind", "loop_to_stream");
            finderArgs.put("maxResults", 500);
            ToolResponse found = new FindModernizationTool(() -> service).execute(finderArgs);
            assertTrue(found.isSuccess(), "the finder must run; got: " + found.getError());
            @SuppressWarnings("unchecked")
            Map<String, Object> finderData = (Map<String, Object>) found.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) finderData.get("candidates");
            long candidateFiles = candidates.stream()
                .map(c -> String.valueOf(c.get("filePath"))).distinct().count();

            assertEquals(29, ((Number) finderData.get("candidateCount")).intValue(),
                "the finder's candidate count over THIS corpus (the aggregate). An earlier"
                    + " claim said 33, which was measured over the fork IN PLACE — a larger"
                    + " file set. Comparing it against a rewriter count taken on the"
                    + " aggregate mixed two corpora, which is what this assertion prevents.");
            assertEquals(18L, candidateFiles, "in this many distinct files");
            assertEquals(2, actual.get("loop_to_pipeline").intValue(),
                "and the rewriter accepts this many of those files — the gap for which the"
                    + " `loops` cure tier is demoted from PERFORM to ADVISE");
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
