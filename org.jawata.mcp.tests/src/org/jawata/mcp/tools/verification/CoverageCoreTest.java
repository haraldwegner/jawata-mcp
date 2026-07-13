package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.coverage.CoverageManifest;
import org.jawata.mcp.coverage.CoverageModel;
import org.jawata.mcp.coverage.CoverageStore;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (D3, C7) — the coverage core against the half-covered fixture:
 * hand-computed truth, honest states, stale refusal, provenance, links,
 * bundle identity, explicit delete.
 */
class CoverageCoreTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RunTestsTool tool;
    private ObjectMapper om;
    private String covDirBefore;

    @BeforeEach
    void setUp() throws Exception {
        covDirBefore = System.getProperty("jawata.coverage.dir");
        Path covDir = Files.createTempDirectory("jawata-cov-store-");
        System.setProperty("jawata.coverage.dir", covDir.toString());
        service = helper.loadProjectCopy("coverage-target");
        tool = new RunTestsTool(() -> service);
        om = new ObjectMapper();
    }

    @AfterEach
    void restore() {
        if (covDirBefore == null) {
            System.clearProperty("jawata.coverage.dir");
        } else {
            System.setProperty("jawata.coverage.dir", covDirBefore);
        }
    }

    @Test
    @DisplayName("C7 core: hand-computed truth, per-symbol index, states, provenance, links, delete")
    void coverageCore_truthStatesProvenanceLinksDelete() throws Exception {
        String artifactId = runWithCoverage("com.example.cov.CoveredTest", 120);

        // ---- hand-computed truth on the Covered class -------------------
        Map<String, Object> covered = uncovered(artifactId, "com.example.cov.Covered");
        List<Map<String, Object>> facts = cast(covered.get("facts"));
        assertEquals(1, facts.size(), "one class root expected; got: " + covered);
        List<Map<String, Object>> methods = cast(facts.get(0).get("methods"));

        Map<String, Object> always = method(methods, "alwaysCalled");
        assertEquals("covered", always.get("state"), "alwaysCalled: " + always);
        assertEquals(0, always.get("linesMissed"), "alwaysCalled: " + always);

        Map<String, Object> never = method(methods, "neverCalled");
        assertEquals("missed", never.get("state"), "neverCalled: " + never);
        assertEquals(0, never.get("linesCovered"), "neverCalled: " + never);

        Map<String, Object> branchy = method(methods, "branchy");
        assertEquals(1, branchy.get("branchesCovered"), "branchy: " + branchy);
        assertEquals(1, branchy.get("branchesMissed"), "branchy: " + branchy);
        assertNotNull(branchy.get("partlyCoveredLines"), "branchy: " + branchy);

        // Lambda body: a real lambda$ method with REAL source lines, missed
        // (obtained but never run) — honest state, no invented precision.
        Map<String, Object> lambda = methods.stream()
            .filter(m -> String.valueOf(m.get("method")).startsWith("lambda$"))
            .findFirst().orElse(null);
        assertNotNull(lambda, "the lambda body must appear as a lambda$ method; got: " + methods);
        assertEquals("missed", lambda.get("state"), "lambda: " + lambda);
        assertFalse(String.valueOf(lambda.get("lines")).contains("-1"),
            "lambda must map to real source lines; got: " + lambda);

        // ---- per-symbol lookup + links + next action --------------------
        Map<String, Object> perSymbol = uncovered(artifactId, "com.example.cov.Covered#neverCalled");
        List<Map<String, Object>> symbolFacts = cast(perSymbol.get("facts"));
        assertEquals("missed", symbolFacts.get(0).get("state"), "got: " + perSymbol);
        assertNotNull(symbolFacts.get(0).get("uncoveredLines"), "got: " + perSymbol);
        Map<String, Object> links = cast2(perSymbol.get("links"));
        assertEquals("get_call_hierarchy", cast2(links.get("callers")).get("tool"));
        assertEquals("find_references", cast2(links.get("implementations")).get("tool"));
        assertNotNull(links.get("existingTests"));
        assertTrue(String.valueOf(perSymbol.get("nextAction")).contains("neverCalled"),
            "next-action hint must name the symbol; got: " + perSymbol.get("nextAction"));

        // ---- honest states: generated / not-instrumented / non-executable
        Map<String, Object> generated = uncovered(artifactId, "com.example.cov.CovEnum#values");
        assertEquals("generated-or-excluded", cast(generated.get("facts")).get(0).get("state"),
            "enum values(): " + generated);

        Map<String, Object> ghost = uncovered(artifactId, "com.example.cov.NeverLoaded");
        assertEquals("not-instrumented", cast(ghost.get("facts")).get(0).get("state"),
            "NeverLoaded: " + ghost);

        Map<String, Object> marker = uncovered(artifactId, "com.example.cov.CovMarker");
        assertEquals("non-executable", cast(marker.get("facts")).get(0).get("state"),
            "CovMarker: " + marker);

        // ---- report: totals, histogram, provenance, boundary ------------
        Map<String, Object> report = coverageAction("coverage_report", artifactId, null);
        Map<String, Object> totals = cast2(report.get("totals"));
        assertTrue(((Number) totals.get("linesCovered")).intValue() > 0, "report: " + report);
        assertTrue(((Number) totals.get("linesMissed")).intValue() > 0, "report: " + report);
        Map<String, Object> histogram = cast2(report.get("stateHistogram"));
        assertNotNull(histogram.get("covered"), "histogram: " + histogram);
        assertNotNull(histogram.get("not-instrumented"), "histogram: " + histogram);
        assertNotNull(histogram.get("non-executable"), "histogram: " + histogram);
        Map<String, Object> provenance = cast2(report.get("provenance"));
        for (String field : List.of("createdAt", "gitRevision", "dirtyFingerprint",
                "evidenceKind", "environment", "jdkVersion", "jacocoVersion",
                "completionStatus", "selection")) {
            assertNotNull(provenance.get(field), "provenance missing " + field + ": " + provenance);
        }
        assertEquals("unit", provenance.get("evidenceKind"));
        assertTrue(String.valueOf(report.get("measurementBoundary")).contains("child"),
            "boundary marker: " + report);

        // ---- explicit delete --------------------------------------------
        Map<String, Object> artifacts = coverageAction("coverage_artifacts", null, null);
        assertTrue(String.valueOf(artifacts.get("artifacts")).contains(artifactId));
        Map<String, Object> deleted = coverageAction("coverage_delete", artifactId, null);
        assertEquals(Boolean.TRUE, deleted.get("deleted"));
        ObjectNode gone = om.createObjectNode();
        gone.put("action", "coverage_report");
        gone.put("artifactId", artifactId);
        assertFalse(tool.execute(gone).isSuccess(), "deleted artifact must be unknown");
    }

    @Test
    @DisplayName("C7 stale: modified bytes are REFUSED, never reported as truth")
    void staleBytes_refused() throws Exception {
        String artifactId = runWithCoverage("com.example.cov.CoveredTest", 120);

        // Change Covered's bytecode and rebuild — the artifact's exec data
        // now describes bytes that no longer exist.
        Path src = service.getProjectRoot()
            .resolve("src/main/java/com/example/cov/Covered.java");
        String code = Files.readString(src).replace(
            "return x + 1;", "int y = x; return y + 1;");
        Files.writeString(src, code);
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());
        org.jawata.mcp.execution.RunnerClasspath.buildAndCheck(
            service.getJavaProject(), new org.eclipse.core.runtime.NullProgressMonitor());

        Map<String, Object> report = coverageAction("coverage_report", artifactId, null);
        assertTrue(String.valueOf(report.get("staleClasses")).contains("com.example.cov.Covered"),
            "report must REFUSE the stale class; got: " + report);
        assertNotNull(report.get("staleNote"));

        Map<String, Object> perClass = uncovered(artifactId, "com.example.cov.Covered");
        assertEquals("stale-bytes", cast(perClass.get("facts")).get(0).get("state"),
            "got: " + perClass);
    }

    @Test
    @DisplayName("C7 child JVM: outside-the-measurement is DECLARED, never silently zero")
    void childJvm_boundaryDeclared() throws Exception {
        String artifactId = runWithCoverage("com.example.cov.ChildSpawnTest", 180);
        Map<String, Object> child = uncovered(artifactId, "com.example.cov.ChildWork");
        // The child RAN ChildWork (the test asserted its output), but the
        // agent does not follow children: state not-instrumented + the
        // explicit boundary declaration.
        assertEquals("not-instrumented", cast(child.get("facts")).get(0).get("state"),
            "got: " + child);
        assertTrue(String.valueOf(child.get("measurementBoundary")).contains("child"),
            "the boundary must be declared; got: " + child);
    }

    @Test
    @DisplayName("C7 unknown-run-failed: an unfinalized run makes no coverage claims")
    void unknownRunFailed() throws Exception {
        ObjectNode args = runArgs("com.example.cov.CovHangTest", 6);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> data = cast2(r.getData());
        String artifactId = (String) data.get("coverageArtifactId");
        assertNotNull(artifactId);
        assertEquals(Boolean.TRUE, cast2(data.get("summary")).get("timedOut"));

        Map<String, Object> report = coverageAction("coverage_report", artifactId, null);
        assertEquals("unknown-run-failed", report.get("state"), "got: " + report);
        assertTrue(String.valueOf(report.get("note")).contains("TIMED_OUT"), "got: " + report);
    }

    @Test
    @DisplayName("C7 instrumentation-failure: no exec data means no invented claims")
    void instrumentationFailure() throws Exception {
        CoverageStore store = new CoverageStore();
        String id = store.newArtifactId();
        Path dir = store.createArtifactDir(id);
        Files.createFile(dir.resolve(CoverageStore.EXEC_FILE)); // empty = agent collected nothing
        CoverageManifest m = new CoverageManifest();
        m.artifactId = id;
        m.createdAt = java.time.Instant.now().toString();
        m.runFinalized = true;
        m.completionStatus = "FINALIZED";
        m.classRoots.add(service.getProjectRoot().resolve("bin").toString());
        store.writeManifest(id, m);

        Map<String, Object> report = coverageAction("coverage_report", id, null);
        assertEquals("instrumentation-failure", report.get("state"), "got: " + report);
    }

    @Test
    @DisplayName("C7 bundle identity: the same FQN under two class roots stays two facts")
    void sameFqn_twoRoots_twoFacts() throws Exception {
        // Compile one tiny class into TWO roots (javax.tools).
        Path work = Files.createTempDirectory("jawata-dup-");
        Path srcDir = Files.createDirectories(work.resolve("src/com/example/same"));
        Path source = srcDir.resolve("SameFqn.java");
        Files.writeString(source,
            "package com.example.same; public class SameFqn { public int v() { return 1; } }");
        Path rootA = Files.createDirectories(work.resolve("rootA"));
        Path rootB = Files.createDirectories(work.resolve("rootB"));
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        for (Path root : List.of(rootA, rootB)) {
            int rc = javac.run(null, null, null,
                "-d", root.toString(), source.toString());
            assertEquals(0, rc, "javac must succeed");
        }
        CoverageManifest m = new CoverageManifest();
        m.artifactId = "unit";
        m.runFinalized = true;
        m.classRoots.add(rootA.toString());
        m.classRoots.add(rootB.toString());

        CoverageModel model = CoverageModel.analyze(work.resolve("absent.exec"), m);
        List<CoverageModel.ClassCov> facts = model.allOf("com.example.same.SameFqn");
        assertEquals(2, facts.size(), "two roots must yield two facts");
        assertFalse(facts.get(0).classRoot.equals(facts.get(1).classRoot),
            "facts must be keyed by DIFFERENT roots");
    }

    // ------------------------------------------------------------- helpers

    private ObjectNode runArgs(String testClass, int timeout) {
        ObjectNode args = om.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", testClass);
        args.put("framework", "junit5");
        args.put("timeoutSeconds", timeout);
        args.put("coverage", true);
        return args;
    }

    private String runWithCoverage(String testClass, int timeout) {
        ToolResponse r = tool.execute(runArgs(testClass, timeout));
        assertTrue(r.isSuccess(), "run failed: " + r.getError());
        Map<String, Object> data = cast2(r.getData());
        assertEquals(Boolean.TRUE, cast2(data.get("summary")).get("evidenceFinalized"),
            "run must finalize; got: " + data.get("summary")
                + " stderr: " + data.get("stderrTail"));
        String artifactId = (String) data.get("coverageArtifactId");
        assertNotNull(artifactId, "coverage run must yield an artifact id");
        return artifactId;
    }

    private Map<String, Object> coverageAction(String action, String artifactId, String target) {
        ObjectNode args = om.createObjectNode();
        args.put("action", action);
        if (artifactId != null) args.put("artifactId", artifactId);
        if (target != null) args.put("target", target);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), action + " failed: " + r.getError());
        return cast2(r.getData());
    }

    private Map<String, Object> uncovered(String artifactId, String target) {
        return coverageAction("coverage_uncovered", artifactId, target);
    }

    private static Map<String, Object> method(List<Map<String, Object>> methods, String name) {
        return methods.stream().filter(m -> name.equals(m.get("method"))).findFirst()
            .orElseThrow(() -> new AssertionError("no method " + name + " in " + methods));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast2(Object o) {
        return (Map<String, Object>) o;
    }
}
