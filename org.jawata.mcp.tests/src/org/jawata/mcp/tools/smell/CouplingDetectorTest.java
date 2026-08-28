package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d — the {@code coupling} detector: coupling reported as connascence.
 *
 * <p>PROOF OF LIFE BEFORE ZERO, and BEFORE any number at all. Two failures
 * produce byte-identical output to a clean result: a detector that never fires,
 * and a scan that read nothing. So every assertion here goes through
 * {@link #couplingOver} which checks {@code filesExamined} FIRST — a count of
 * findings taken from a scan that examined nothing is not a measurement — and
 * the silent cases below are written to be read only after
 * {@link #firesOnTheLopsidedCorpus} has shown the same detector, on the same
 * service, reporting two.</p>
 *
 * <p>The fixture {@code connascence} exists for this: ten packages with a
 * deliberately lopsided distribution (see its pom for the full derivation), so
 * that a QUANTILE has a population to be a quantile OF.</p>
 */
class CouplingDetectorTest {

    /** The fixture's outlier: 9 connascence sites, every one crossing the boundary. */
    private static final String HUB = "com.example.hub";
    /** Moderate: 4 cross-boundary sites into the model package. */
    private static final String LEDGER = "com.example.ledger";
    /** The GOOD pole: dense connascence, all of it WITHIN the package. */
    private static final String MODEL = "com.example.model";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("connascence");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    /**
     * Run {@code coupling} and refuse to hand back anything until the scan is
     * shown to have LOOKED. {@code minimumExamined} is the caller's claim about
     * how much of the corpus this call should have covered.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> couplingOver(ObjectNode args, int expectedExamined) {
        args.put("kind", "coupling");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "coupling must dispatch; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(expectedExamined, ((Number) data.get("filesExamined")).intValue(),
            () -> "the scan must have EXAMINED the corpus before any count means anything; "
                + "response: " + data);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findings(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("findings");
    }

    private static List<String> packagesIn(Map<String, Object> data) {
        return findings(data).stream().map(f -> String.valueOf(f.get("symbol"))).toList();
    }

    private ObjectNode args() {
        return mapper.createObjectNode();
    }

    // ------------------------------------------------------------ proof of life

    @Test
    @DisplayName("PROOF OF LIFE: fires non-zero on the lopsided corpus — 2 findings, hub first")
    void firesOnTheLopsidedCorpus() {
        Map<String, Object> data = couplingOver(args(), 12);
        List<Map<String, Object>> found = findings(data);
        assertEquals(2, found.size(),
            () -> "the connascence fixture must produce exactly 2 findings (hub, ledger); got: "
                + found);
        assertEquals(List.of(HUB, LEDGER), packagesIn(data),
            "findings are ordered by cross-boundary weight, heaviest first");
        assertFalse(packagesIn(data).contains(MODEL),
            "com.example.model keeps ALL its connascence within the package — Page-Jones's "
                + "good pole — so it must NOT be flagged");
    }

    @Test
    @DisplayName("STRENGTH is a named connascence form, not a bare number")
    void reportsStrengthAsANamedForm() {
        Map<String, Object> data = couplingOver(args(), 12);
        String hub = String.valueOf(findings(data).get(0).get("message"));
        assertTrue(hub.contains("Connascence of Position"),
            () -> "hub's strongest form is Position (a 3-arg and a 2-arg call across the "
                + "boundary); message was: " + hub);
        assertTrue(hub.contains("Connascence of Type") && hub.contains("Connascence of Name"),
            () -> "the per-form breakdown must name every form present; message was: " + hub);
    }

    @Test
    @DisplayName("DEGREE and LOCALITY are both reported, and the cure is Page-Jones's rule")
    void reportsDegreeLocalityAndTheCure() {
        Map<String, Object> data = couplingOver(args(), 12);
        for (Map<String, Object> finding : findings(data)) {
            String message = String.valueOf(finding.get("message"));
            assertTrue(message.contains("DEGREE:"), () -> "no DEGREE axis in: " + message);
            assertTrue(message.contains("dependent package(s)"),
                () -> "DEGREE must be the count of dependents: " + message);
            assertTrue(message.contains("LOCALITY:"), () -> "no LOCALITY axis in: " + message);
            assertTrue(message.contains("cross the package boundary")
                    && message.contains("stay within"),
                () -> "LOCALITY must contrast across-boundary with within-package: " + message);
            assertTrue(message.contains("minimise connascence ACROSS encapsulation boundaries, "
                    + "maximise it WITHIN"),
                () -> "the cure sentence is Page-Jones's rule: " + message);
        }
        String ledger = String.valueOf(findings(data).get(1).get("message"));
        assertTrue(ledger.contains("1 dependent package(s)"),
            () -> "com.example.ledger is depended on by com.example.hub only: " + ledger);
    }

    // ------------------------------------------------------- the cut is a quantile

    @Test
    @DisplayName("the cut is an ORDER STATISTIC of the scanned corpus, read off the data")
    void theCutIsAnOrderStatisticOfTheCorpus() {
        Map<String, Object> data = couplingOver(args(), 12);
        assertEquals(10, ((Number) data.get("packagesScanned")).intValue(),
            () -> "the fixture is ten packages wide, which is what gives a decile meaning: "
                + data);
        assertEquals(90, ((Number) data.get("percentile")).intValue(),
            "the default flag is the top decile");
        assertEquals(0.0, ((Number) data.get("medianWeight")).doubleValue(), 0.0,
            () -> "eight of the ten packages have zero cross-boundary connascence, so the "
                + "corpus median is 0: " + data);
        assertEquals(6, ((Number) data.get("cutWeight")).intValue(),
            () -> "the 90th percentile of [0 x8, 6, 19] by nearest rank is the 9th value, 6 — "
                + "a value READ OFF this corpus, not a constant: " + data);
    }

    @Test
    @DisplayName("moving the percentile moves the cut — no absolute cutoff is hiding inside")
    void movingThePercentileMovesTheCut() {
        // Same corpus, same code, a different order statistic. If a fixed weight
        // were doing the work, this would not change.
        ObjectNode strictest = args();
        strictest.put("threshold", 100);
        Map<String, Object> data = couplingOver(strictest, 12);
        assertEquals(19, ((Number) data.get("cutWeight")).intValue(),
            () -> "the 100th percentile is the corpus maximum, 19: " + data);
        assertEquals(List.of(HUB), packagesIn(data),
            "at the top of the distribution only the single heaviest package survives");
    }

    // ---------------------------------------------- the instrument can go silent

    @Test
    @DisplayName("a one-package scope reports ZERO — and says the question was unanswerable")
    void aSinglePackageCorpusHasNoOutlier() {
        // Only meaningful because firesOnTheLopsidedCorpus() shows the same detector,
        // over the same fixture, reporting two. An outlier is defined against a
        // population; one package is not one, so nothing can be above the median.
        assertEquals(2, findings(couplingOver(args(), 12)).size(),
            "proof of life must hold before this zero counts as evidence");

        ObjectNode oneFile = args();
        oneFile.put("filePath", "src/main/java/com/example/hub/Coordinator.java");
        Map<String, Object> data = couplingOver(oneFile, 1);
        assertEquals(1, ((Number) data.get("packagesScanned")).intValue(),
            () -> "one file, one package: " + data);
        assertEquals(0, findings(data).size(),
            () -> "the heaviest package in the project must NOT be flagged when it is the "
                + "whole corpus — that would be an arithmetic artefact, not a finding: " + data);
    }

    // ------------------------------------------------------------- registration

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("registered: `coupling` is in find_quality_issue's kind enum and the fowler family")
    void registeredAsAKind() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        List<String> kinds = (List<String>) kind.get("enum");
        assertTrue(kinds.contains("coupling"), () -> "kind enum must carry coupling; got: " + kinds);
        // THE COUNT DOES NOT LIVE HERE — it lives in PrincipleDetectorKindsTest.
        // It was removed from the cqs test and left here, so the very next three
        // detectors turned THIS test red: a fact stated in two places, fixed in
        // one. That is the duplicate this sprint's own roster exists to end.
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a fowler family sweep reaches the new kind through the front door")
    void answersAFamilySweep() {
        ObjectNode args = args();
        args.put("family", "fowler");
        args.put("limit", 2000);
        ToolResponse r = org.jawata.mcp.fixtures.Sweeps.run(tool, args);
        assertTrue(r.isSuccess(), () -> "fowler sweep must succeed; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(((List<String>) data.get("kinds")).contains("coupling"),
            () -> "the fowler family must list coupling; got: " + data.get("kinds"));
        List<Map<String, Object>> merged = (List<Map<String, Object>>) data.get("findings");
        long fromCoupling = merged.stream().filter(f -> "coupling".equals(f.get("kind"))).count();
        assertEquals(2, fromCoupling,
            () -> "the sweep must carry the coupling findings themselves, not just the kind "
                + "name; got " + merged.size() + " findings total");
    }
}
