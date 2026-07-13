package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.coverage.MechanicalChangeJournal;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.jawata.mcp.tools.RenameSymbolTool;
import org.jawata.mcp.tools.RunTestsTool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (D6 + GATE-1 addition, C11) — BOTH advisory directions and the
 * coverage-lack smell: an uncovered behavioral change triggers the done-time
 * advisory with the exact lines; the SAME kind of change made by an exempt
 * mechanical tool (a real rename_symbol through the ToolRegistry, exercising
 * the journal wiring end-to-end) stays silent; the smell kind lists untested
 * symbols WITH fresh evidence and answers honestly WITHOUT.
 */
class CoverageAdvisoryTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RunTestsTool tool;
    private ObjectMapper om;
    private String covDirBefore;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        covDirBefore = System.getProperty("jawata.coverage.dir");
        System.setProperty("jawata.coverage.dir",
            Files.createTempDirectory("jawata-cov-store-").toString());
        MechanicalChangeJournal.clear();
        service = helper.loadProjectCopy("coverage-target");
        tool = new RunTestsTool(() -> service);
        om = new ObjectMapper();
        root = service.getProjectRoot();
        git("init", "-q");
        git("config", "user.email", "fixture@test");
        git("config", "user.name", "fixture");
        git("add", "-A");
        git("commit", "-q", "-m", "fixture baseline");
    }

    @AfterEach
    void restore() {
        MechanicalChangeJournal.clear();
        if (covDirBefore == null) {
            System.clearProperty("jawata.coverage.dir");
        } else {
            System.setProperty("jawata.coverage.dir", covDirBefore);
        }
    }

    @Test
    @DisplayName("C11a: an uncovered BEHAVIORAL change triggers the advisory with exact lines")
    void behavioralChange_advisoryFires() throws Exception {
        Path covered = root.resolve("src/main/java/com/example/cov/Covered.java");
        Files.writeString(covered, Files.readString(covered).replace(
            "public int alwaysCalled(int x) {",
            """
            public int behavioralNew(int x) {
                    int b = x * 7;
                    return b - 1;
                }

                public int alwaysCalled(int x) {"""));
        rebuild();
        runWithCoverage("com.example.cov.CoveredTest");

        ToolResponse delta = delta();
        String steering = delta.getMeta() == null ? null : delta.getMeta().getSteering();
        assertNotNull(steering, "the advisory must fire on an uncovered behavioral change");
        assertTrue(steering.contains("COVERAGE ADVISORY"), "got: " + steering);
        assertTrue(steering.contains("com.example.cov.Covered"), "got: " + steering);
        // The exact lines: the two body lines of behavioralNew.
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>)
            ((Map<String, Object>) delta.getData()).get("totals");
        assertEquals(2, totals.get("uncoveredChangedLines"), "got: " + delta.getData());
    }

    @Test
    @DisplayName("C11b: the same fixture changed by an EXEMPT tool (real rename_symbol) stays silent")
    void mechanicalTransform_noAdvisory() throws Exception {
        // A REAL mechanical transform through the ToolRegistry — the journal
        // hook records the modified files exactly as production would. Target:
        // Flip#flip, whose CALL SITES live in FlipTest — UNCOVERED executable
        // lines under a CoveredTest-only coverage run, so WITHOUT the
        // exemption this rename WOULD trigger the advisory.
        Path flip = root.resolve("src/main/java/com/example/cov/Flip.java");
        String source = Files.readString(flip);
        int offset = source.indexOf("public int flip") + "public int ".length();
        int line = (int) source.substring(0, offset).chars().filter(c -> c == '\n').count();
        int column = offset - (source.lastIndexOf('\n', offset) + 1);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new RenameSymbolTool(() -> service, new RefactoringChangeCache()));
        ObjectNode rename = om.createObjectNode();
        rename.put("filePath", flip.toString());
        rename.put("line", line);
        rename.put("column", column);
        rename.put("newName", "flop");
        ToolResponse renamed = registry.callTool("rename_symbol", rename);
        assertTrue(renamed.isSuccess(), "rename failed: " + renamed.getError());

        rebuild();
        runWithCoverage("com.example.cov.CoveredTest");

        ToolResponse delta = delta();
        // The discrimination is REAL: the rename's call-site lines are
        // uncovered-and-changed…
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>)
            ((Map<String, Object>) delta.getData()).get("totals");
        assertTrue(((Number) totals.get("uncoveredChangedLines")).intValue() >= 1,
            "the rename must have touched uncovered executable lines "
                + "(else this test proves nothing); got: " + delta.getData());
        // …and STILL no advisory fires, because the journal knows the change
        // was mechanical.
        String steering = delta.getMeta() == null ? null : delta.getMeta().getSteering();
        assertFalse(steering != null && steering.contains("COVERAGE ADVISORY"),
            "a mechanical transform must NOT trigger the advisory; got: " + steering);
        assertTrue(String.valueOf(delta.getData()).contains("exemptMechanicalTransform"),
            "the exemption must be visible in the row data; got: " + delta.getData());
    }

    @Test
    @DisplayName("C11c: coverage_lack smell — findings WITH fresh evidence, honest absence WITHOUT")
    void smell_withAndWithoutEvidence() throws Exception {
        FindQualityIssueTool smell = new FindQualityIssueTool(() -> service);

        // WITHOUT any artifact: honest no-evidence answer, zero findings.
        ObjectNode args = om.createObjectNode();
        args.put("kind", "coverage_lack");
        ToolResponse without = smell.execute(args);
        assertTrue(without.isSuccess(), "got: " + without.getError());
        Map<String, Object> dataWithout = cast2(without.getData());
        assertEquals(0, dataWithout.get("count"), "got: " + dataWithout);
        assertEquals("none", dataWithout.get("evidence"), "got: " + dataWithout);
        assertNotNull(dataWithout.get("note"));

        // WITH fresh evidence: the untested symbol is a finding; the active
        // threshold policy is visible.
        ObjectNode policy = om.createObjectNode();
        policy.put("action", "coverage_threshold_set");
        policy.put("lineThresholdPercent", 80);
        assertTrue(tool.execute(policy).isSuccess());
        runWithCoverage("com.example.cov.CoveredTest");

        ToolResponse with = smell.execute(args);
        assertTrue(with.isSuccess(), "got: " + with.getError());
        Map<String, Object> dataWith = cast2(with.getData());
        String findings = String.valueOf(dataWith.get("findings"));
        assertTrue(findings.contains("com.example.cov.Covered#neverCalled"),
            "the untested method must be a finding; got: " + findings);
        assertNotNull(dataWith.get("evidence"), "got: " + dataWith);
        assertNotNull(dataWith.get("threshold"), "the active policy must be visible: " + dataWith);
    }

    // ------------------------------------------------------------- helpers

    private ToolResponse delta() {
        ObjectNode args = om.createObjectNode();
        args.put("action", "coverage_delta");
        args.put("diff", "worktree");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "delta failed: " + r.getError());
        return r;
    }

    private void runWithCoverage(String testClass) {
        ObjectNode args = om.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", testClass);
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        args.put("coverage", true);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "run failed: " + r.getError());
        assertEquals(Boolean.TRUE, cast2(cast2(r.getData()).get("summary")).get("evidenceFinalized"),
            "got: " + r.getData());
    }

    private void rebuild() throws Exception {
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());
        assertEquals(null, org.jawata.mcp.execution.RunnerClasspath.buildAndCheck(
            service.getJavaProject(), new org.eclipse.core.runtime.NullProgressMonitor()),
            "fixture must rebuild cleanly");
    }

    private void git(String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(
            List.of("git", "-C", root.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), "git failed: " + out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast2(Object o) {
        return (Map<String, Object>) o;
    }
}
