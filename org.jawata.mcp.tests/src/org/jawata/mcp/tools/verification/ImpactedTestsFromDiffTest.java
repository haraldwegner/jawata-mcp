package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#40 — impacted-test selection driven by a GIT DIFF rather than by an
 * explicit symbol list.
 *
 * <p>{@code CoverageAttributionTest} drives {@code coverage_impacted_tests}
 * with {@code symbols=[…]}, which never enters the derivation in
 * {@code RunTestsTool#coverageImpactedTests}. That derivation is the half the
 * field found broken: a diff naming a MODIFIED production file came back with
 * an EMPTY symbol list, so a caller trusting it would skip every test covering
 * the change — and the mixed case (one file modified, one test added) came back
 * non-empty and plausible while naming only the added test's own symbols, which
 * is a false green with a clean face.</p>
 *
 * <p><b>What the two arms measure, and why both are needed.</b> They differ by
 * exactly one thing — whether the edited class was REBUILT — and that one thing
 * is what decides the answer, which is why neither arm alone would have found
 * it. Not rebuilt, the evidence still matches the bytes and the derivation is
 * exact, naming the METHOD. Rebuilt, JaCoCo matches the class name and refuses
 * its id, {@code CoverageModel.fillDetail} is skipped for {@code STALE_BYTES},
 * and the class reaches the derivation carrying no methods at all — which is
 * the state of any file you have just edited, and was the field's.</p>
 *
 * <p>Exercising either needs a git-backed project ON TOP of an attribution run,
 * so this makes the per-test fixture copy a real repository before recording
 * evidence against it.</p>
 */
class ImpactedTestsFromDiffTest {

    private static final String EDITED_METHOD_BODY = "        return x + 1;";
    private static final String COVERED_CLASS = "com.example.cov.Covered";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ObjectMapper om;
    private String covDirBefore;

    @BeforeEach
    void setUp() throws Exception {
        covDirBefore = System.getProperty("jawata.coverage.dir");
        System.setProperty("jawata.coverage.dir",
            Files.createTempDirectory("jawata-cov-store-").toString());
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
    @DisplayName("mcp#40: a MODIFIED file whose evidence still matches derives the METHOD it changed")
    void aModifiedFileDerivesTheMethodItChanged() throws Exception {
        RunTestsTool tool = attributedFixture();
        editAlwaysCalled();

        Map<String, Object> impacted = action(tool, "coverage_impacted_tests",
            Map.of("diff", "worktree"));
        List<String> symbols = strings(impacted.get("symbols"));

        assertAll(
            () -> assertTrue(symbols.contains(COVERED_CLASS + "#alwaysCalled"),
                "an edit inside a method body must derive THAT method — the type-level "
                    + "fallback is for the case where nothing narrower is known, and a "
                    + "derivation that always widened would make every selection the whole "
                    + "class; got: " + impacted),
            () -> assertEquals(List.of(), impacted.get("staleClasses"),
                "nothing was rebuilt here, so the evidence matches the bytes — this is the "
                    + "control that the sibling arm's staleness is the sibling arm's, and "
                    + "not a property of the fixture; got: " + impacted),
            () -> assertEquals(List.of(), impacted.get("filesWithoutEvidence"), "got: " + impacted),
            () -> assertEquals(1, impacted.get("filesChanged"), "got: " + impacted),
            () -> assertTrue(namesCoveredTest(impacted), "got: " + impacted));
    }

    @Test
    @DisplayName("mcp#40: a MODIFIED file REBUILT since the evidence still selects its tests")
    void aRebuiltFileStillSelectsItsTests() throws Exception {
        RunTestsTool tool = attributedFixture();
        editAlwaysCalled();
        rebuild("com/example/cov/Covered.class",
            "src/main/java/com/example/cov/Covered.java");

        Map<String, Object> impacted = action(tool, "coverage_impacted_tests",
            Map.of("diff", "worktree"));
        List<String> symbols = strings(impacted.get("symbols"));

        assertAll(
            () -> assertTrue(symbols.contains(COVERED_CLASS),
                "a rebuilt class carries NO method detail, so the type is what is known and "
                    + "the type is what must be said — deriving nothing makes a caller skip "
                    + "every test that covers the change; got: " + impacted),
            () -> assertEquals(List.of(COVERED_CLASS), impacted.get("staleClasses"),
                "and the response must NAME why it could say nothing narrower, or a caller "
                    + "cannot tell an exact answer from a widened one; got: " + impacted),
            () -> assertEquals(List.of(), impacted.get("filesWithoutEvidence"), "got: " + impacted),
            () -> assertTrue(namesCoveredTest(impacted),
                "CoveredTest exercises alwaysCalled, and attribution segments match by class "
                    + "NAME — stale bytes never stopped that; got: " + impacted));
    }

    @Test
    @DisplayName("mcp#40: a changed file the evidence has never seen is NAMED, not dropped")
    void aFileWithNoEvidenceIsNamed() throws Exception {
        RunTestsTool tool = attributedFixture();
        Path pom = root().resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom) + "\n<!-- changed -->\n");

        Map<String, Object> impacted = action(tool, "coverage_impacted_tests",
            Map.of("diff", "worktree"));

        assertAll(
            () -> assertEquals(List.of("pom.xml"), impacted.get("filesWithoutEvidence"),
                "no class in the evidence is this file's, so nothing here can speak for it — "
                    + "and 'we could not look' is a different fact from 'there is nothing', "
                    + "which is the one a narrowing caller must not confuse; got: " + impacted),
            () -> assertEquals(1, impacted.get("filesChanged"), "got: " + impacted),
            () -> assertEquals(List.of(), impacted.get("symbols"), "got: " + impacted));
    }

    // ---------------------------------------------------------------- fixture

    /** A git-backed copy of the coverage fixture with attribution recorded against it. */
    private RunTestsTool attributedFixture() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("coverage-target");
        commitEverything(root());
        RunTestsTool tool = new RunTestsTool(() -> service);
        runAttribution(tool, "com.example.cov.CoveredTest");
        return tool;
    }

    private Path root() {
        return helper.getTempDirectory().resolve("coverage-target");
    }

    /** An edit INSIDE a method body — the ordinary shape of a change. */
    private void editAlwaysCalled() throws Exception {
        Path covered = root().resolve("src/main/java/com/example/cov/Covered.java");
        String before = Files.readString(covered);
        String after = before.replace(EDITED_METHOD_BODY,
            "        int bumped = x + 1;\n        return bumped;");
        assertNotEquals(before, after,
            "the fixture's alwaysCalled body is what this test edits; it moved");
        Files.writeString(covered, after);
    }

    /**
     * Compile the edited source over the class the evidence was recorded
     * against — what any build does the moment you change a file, and what
     * turns matched evidence into stale evidence.
     */
    private void rebuild(String classPath, String sourcePath) throws Exception {
        Path classRoot = classRootHolding(classPath);
        Path classFile = classRoot.resolve(classPath);
        byte[] wasCompiled = Files.readAllBytes(classFile);
        run(root(), "javac", "-g", "-d", classRoot.toString(),
            root().resolve(sourcePath).toString());
        assertFalse(java.util.Arrays.equals(wasCompiled, Files.readAllBytes(classFile)),
            "the rebuild must actually change the bytes, or this test measures the sibling arm");
    }

    /**
     * The recorded class root that holds a compiled class, read from the
     * artifact's own manifest in the store this test pointed the tool at — the
     * roots are the JDT workspace's OUTPUT folders and are nowhere under the
     * project copy, so they cannot be derived from the source layout.
     */
    private Path classRootHolding(String classPath) throws Exception {
        Path store = Path.of(System.getProperty("jawata.coverage.dir"));
        try (var walk = Files.walk(store)) {
            for (Path manifest : walk
                    .filter(p -> p.getFileName().toString().equals("manifest.json")).toList()) {
                for (var root : om.readTree(manifest.toFile()).path("classRoots")) {
                    Path candidate = Path.of(root.asText());
                    if (Files.isRegularFile(candidate.resolve(classPath))) {
                        return candidate;
                    }
                }
            }
        }
        throw new AssertionError("no recorded class root under " + store + " holds " + classPath
            + " — the attribution run is expected to have compiled and recorded it");
    }

    private static void commitEverything(Path root) throws Exception {
        run(root, "git", "init", "-q");
        run(root, "git", "add", "-A");
        run(root, "git", "-c", "user.email=fixture@example.com", "-c", "user.name=fixture",
            "commit", "-q", "-m", "fixture baseline");
    }

    private static void run(Path cwd, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), String.join(" ", command) + " failed: " + output);
    }

    private void runAttribution(RunTestsTool tool, String testClass) {
        ObjectNode args = om.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", testClass);
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        args.put("attribution", true);
        args.put("evidenceKind", "unit");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "attribution run failed: " + r.getError());
        Map<String, Object> data = cast2(r.getData());
        assertEquals(Boolean.TRUE, cast2(data.get("summary")).get("evidenceFinalized"),
            "got: " + data.get("summary") + " stderr: " + data.get("stderrTail"));
    }

    private Map<String, Object> action(RunTestsTool tool, String action,
            Map<String, Object> extra) {
        ObjectNode args = om.createObjectNode();
        args.put("action", action);
        extra.forEach((k, v) -> args.put(k, String.valueOf(v)));
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), action + " failed: " + r.getError());
        return cast2(r.getData());
    }

    private static boolean namesCoveredTest(Map<String, Object> impacted) {
        return cast(impacted.get("impactedTests")).stream()
            .anyMatch(t -> String.valueOf(t.get("test")).contains("CoveredTest"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        return (List<String>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast2(Object o) {
        return (Map<String, Object>) o;
    }
}
