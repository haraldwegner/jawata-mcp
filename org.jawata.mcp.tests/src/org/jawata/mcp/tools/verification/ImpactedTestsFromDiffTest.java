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
 *
 * <p><b>And the D21 half, which lives here because it needs the SAME fixture.</b>
 * The three tests above pin what the derivation ANSWERS; the three below pin
 * what the run path DOES with it — that a broad scope narrows itself by
 * default, that {@code fullRun} pins the whole scope, and that an answer the
 * evidence cannot support widens the run and says so instead of shrinking it.
 * They are one subject seen from both ends, and splitting them across two
 * classes would have copied ninety lines of git-and-attribution fixture to buy
 * a tidier class name.</p>
 */
class ImpactedTestsFromDiffTest {

    private static final String EDITED_METHOD_BODY = "        return x + 1;";
    private static final String COVERED_CLASS = "com.example.cov.Covered";
    private static final String COVERED_TEST = "com.example.cov.CoveredTest";
    private static final String WIDGET_TEST = "com.example.sel.WidgetTest";

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
                "CoveredTest exercises alwaysCalled — and deriving the symbol is only half "
                    + "of saying so: a rebuild makes the segment's own analysis refuse this "
                    + "class too, so without the fallback in CoverageAttribution.covers this "
                    + "arm names the right symbol and still selects nothing; got: " + impacted));
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

    // ------------------------------------------------- D21: the run USES it

    @Test
    @DisplayName("D21: a package run narrows ITSELF to the tests the change reaches — no flag")
    void aPackageRunNarrowsToTheTestsTheChangeReaches() throws Exception {
        RunTestsTool tool = selectionFixture();
        editWidget();

        Map<String, Object> run = packageRun(tool, Map.of());
        Map<String, Object> selection = cast2(run.get("selection"));
        Map<String, Object> summary = cast2(run.get("summary"));

        assertAll(
            () -> assertEquals(Boolean.TRUE, selection.get("narrowed"),
                "nothing was passed to ask for this — a selection offered as an opt-in is "
                    + "chosen by nobody, so being the DEFAULT is the deliverable; got: " + run),
            () -> assertEquals(List.of(WIDGET_TEST), selection.get("selectedClasses"),
                "the evidence names exactly the class covering the edit; got: " + selection),
            () -> assertEquals(1, summary.get("total"),
                "THE DISCRIMINATOR, and it is a measured pair rather than an assertion about "
                    + "one number: the package holds THREE test classes and its sibling test "
                    + "runs the same scope pinned and gets 3. Only a narrowing that reached "
                    + "the runner can produce 1 — a selection computed and then ignored would "
                    + "report narrowed=true here and still run three; got: " + run));
    }

    @Test
    @DisplayName("D21: fullRun PINS the whole scope — which is what a checkpoint gate asks for")
    void fullRunPinsTheWholeScope() throws Exception {
        RunTestsTool tool = selectionFixture();
        editWidget();

        Map<String, Object> run = packageRun(tool, Map.of("fullRun", true));
        Map<String, Object> summary = cast2(run.get("summary"));

        assertAll(
            () -> assertFalse(run.containsKey("selection"),
                "a pinned run carries NO selection note, because nothing was selected — "
                    + "reporting a narrowing that did not happen is worse than not narrowing; "
                    + "got: " + run),
            () -> assertEquals(3, summary.get("total"),
                "and all three classes ran. This is the OTHER half of the pair above: without "
                    + "it, a 1 there could mean the package selector finds one class rather "
                    + "than that the narrowing works; got: " + run));
    }

    @Test
    @DisplayName("D21: where the proof cannot hold, the answer SAYS SO and everything runs")
    void whereTheProofCannotHoldTheWholeScopeRuns() throws Exception {
        RunTestsTool tool = selectionFixture();
        Path pom = selRoot().resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom) + "\n<!-- changed -->\n");

        Map<String, Object> run = packageRun(tool, Map.of());
        Map<String, Object> selection = cast2(run.get("selection"));
        Map<String, Object> summary = cast2(run.get("summary"));

        assertAll(
            () -> assertEquals(Boolean.FALSE, selection.get("narrowed"), "got: " + run),
            () -> assertEquals("the whole scope", selection.get("ran"), "got: " + run),
            () -> assertTrue(String.valueOf(selection.get("reason")).contains("pom.xml"),
                "and it NAMES the file it could not speak for, because 'we could not look' and "
                    + "'there is nothing to run' are different facts and only one of them is a "
                    + "reason to run less; got: " + selection),
            () -> assertEquals(3, summary.get("total"),
                "and SAYING so is not the deliverable — RUNNING everything is. A response that "
                    + "explained itself and still ran one class would pass every assertion "
                    + "above it; got: " + run));
    }

    @Test
    @DisplayName("D21: evidence narrower than the run may not narrow it, however fresh it is")
    void evidenceFromOneClassMayNotNarrowAPackage() throws Exception {
        // The coverage fixture's attribution is recorded from ONE test class,
        // which is the shape that must be refused: a production class covered
        // both by the class that run saw and by one it never started comes back
        // claimed only by the first, and the second is then dropped from a run
        // that used to include it. Every other rule passes here — the evidence
        // is finalized, its revision is an ancestor, the edit resolves — so
        // this case reaches the scope rule and nothing else.
        RunTestsTool tool = attributedFixture();
        editAlwaysCalled();

        Map<String, Object> impacted = action(tool, "coverage_impacted_tests",
            Map.of("diff", "worktree"));
        Map<String, Object> verdict = cast2(impacted.get("selectionVerdict"));

        assertAll(
            () -> assertEquals(Boolean.TRUE, verdict.get("mayNarrow"),
                "the scope-independent rules all pass — so a refusal here would be some other "
                    + "rule answering and this test would prove nothing; got: " + verdict),
            () -> assertEquals(Boolean.FALSE, verdict.get("evidenceCoversWholeSuite"),
                "but the evidence saw one class, and the suite runner narrows EVERYTHING; "
                    + "got: " + verdict),
            () -> assertEquals(List.of(COVERED_TEST), verdict.get("evidenceScope"),
                "and it says which, so a caller can compare it against its own scope rather "
                    + "than take a yes or no computed for somebody else's; got: " + verdict));
    }

    @Test
    @DisplayName("D21: and the RUN refuses the same narrowness, on the scope it was asked for")
    void aPackageRunRefusesEvidenceNarrowerThanItself() throws Exception {
        // The sibling above pins the FACT the verdict states; this pins the
        // RULE the run path applies to it, which is a different half and was
        // reachable by nothing. Same fixture, same edit — only the width of the
        // recorded evidence differs, so the width is the only thing that can
        // explain the different answer.
        JdtServiceImpl service = helper.loadProjectCopy("selection-target");
        commitEverything(selRoot());
        RunTestsTool tool = new RunTestsTool(() -> service);
        runAttribution(tool, "class", WIDGET_TEST);
        editWidget();

        Map<String, Object> run = packageRun(tool, Map.of());
        Map<String, Object> selection = cast2(run.get("selection"));
        Map<String, Object> summary = cast2(run.get("summary"));

        assertAll(
            () -> assertEquals(Boolean.FALSE, selection.get("narrowed"),
                "WidgetTest's own evidence would have selected WidgetTest and been RIGHT by "
                    + "luck — the two unrelated classes really do not touch Widget. It must "
                    + "still refuse: being right about this fixture is not knowing, and the "
                    + "next package has a second coverer; got: " + run),
            () -> assertTrue(String.valueOf(selection.get("reason")).contains("narrower"),
                "and by the SCOPE rule rather than by one of the four that precede it — "
                    + "every other rule passes on this input, which is what makes the "
                    + "refusal attributable; got: " + selection),
            () -> assertEquals(3, summary.get("total"), "so all three ran; got: " + run));
    }

    @Test
    @DisplayName("D21: a run that RECORDS evidence is never shaped by evidence")
    void anAttributionRunIsNeverNarrowed() throws Exception {
        // Found by mutation M4 rather than by review. Without this, the second
        // attribution run over a package narrows itself to what the FIRST one
        // covered, records a manifest saying it only selected those classes,
        // and the next run reads that and narrows further — a loop that
        // converges on one test class and calls it a suite.
        RunTestsTool tool = selectionFixture();          // evidence now exists
        editWidget();

        Map<String, Object> rerun = packageRun(tool, Map.of("attribution", true));
        Map<String, Object> summary = cast2(rerun.get("summary"));

        assertAll(
            () -> assertFalse(rerun.containsKey("selection"),
                "a recording run carries no selection note at all — it was never a candidate "
                    + "for narrowing; got: " + rerun),
            () -> assertEquals(3, summary.get("total"),
                "and it ran the whole package, which is the only width at which the evidence "
                    + "it is about to record means what its manifest will say it means; "
                    + "got: " + rerun));
    }

    // ---------------------------------------------------------------- fixture

    /**
     * The selection fixture, with attribution recorded over the WHOLE package —
     * which is what makes its evidence wide enough to narrow a package run.
     */
    private RunTestsTool selectionFixture() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("selection-target");
        commitEverything(selRoot());
        RunTestsTool tool = new RunTestsTool(() -> service);
        runAttribution(tool, "package", "com.example.sel");
        return tool;
    }

    private Path selRoot() {
        return helper.getTempDirectory().resolve("selection-target");
    }

    /** An edit inside Widget's only method — the ordinary shape of a change. */
    private void editWidget() throws Exception {
        Path widget = selRoot().resolve("src/main/java/com/example/sel/Widget.java");
        String before = Files.readString(widget);
        String after = before.replace("        return x + 1;",
            "        int bumped = x + 1;\n        return bumped;");
        assertNotEquals(before, after, "Widget's bump body is what this edits; it moved");
        Files.writeString(widget, after);
    }

    /** A run over the whole fixture package — the broad scope D21 is about. */
    private Map<String, Object> packageRun(RunTestsTool tool, Map<String, Object> extra) {
        ObjectNode args = om.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "package");
        scope.put("packageName", "com.example.sel");
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        extra.forEach((k, v) -> {
            if (v instanceof Boolean b) args.put(k, b);
            else args.put(k, String.valueOf(v));
        });
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "package run failed: " + r.getError());
        return cast2(r.getData());
    }

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
        runAttribution(tool, "class", testClass);
    }

    /**
     * Record attribution over a scope. The KIND is a parameter because the two
     * fixtures need different widths deliberately: coverage-target can only be
     * attributed one class at a time (its package holds a ten-minute sleeper),
     * and that narrowness is itself the subject of
     * {@link #evidenceFromOneClassMayNotNarrowAPackage()}.
     */
    private void runAttribution(RunTestsTool tool, String scopeKind, String target) {
        ObjectNode args = om.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", scopeKind);
        scope.put("package".equals(scopeKind) ? "packageName" : "typeName", target);
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
