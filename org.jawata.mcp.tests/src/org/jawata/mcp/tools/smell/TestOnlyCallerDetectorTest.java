package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.project.SourceRootClassifier;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 Stage 4 (D-UNWIRED) — {@code called_only_by_tests} against the
 * {@code test-only-caller} fixture, which seeds the v3.4.0 shape next to every
 * neighbouring case that must stay silent.
 *
 * <p>The fixture's test source imports nothing from JUnit, carries no
 * annotation, and is not named {@code *Test}, so an IMPORT- or NAME-based
 * heuristic finds nothing to go on. It is Maven-conventional, however, so it
 * does NOT defeat a PATH heuristic — a detector matching
 * {@code contains("src/test/")} passes every assertion here (C4 audit,
 * finding 4, and it was right). {@link #findsTheHollowMemberWhereNoPathConventionApplies()}
 * is the one that closes that family: the flat-{@code src} PDE pair, where the
 * convention and the model disagree.</p>
 */
class TestOnlyCallerDetectorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("test-only-caller");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "refused: " + (r.getError() != null
            ? r.getError().getCode() + " / " + r.getError().getMessage() : "?"));
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findings(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("findings");
    }

    private ObjectNode kindArgs() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "called_only_by_tests");
        return args;
    }

    private Set<String> symbols() {
        return findings(run(kindArgs())).stream()
            .map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("the hollow members are found — and they are the ONLY findings")
    void theHollowMembersAreTheExactFindingSet() {
        assertEquals(
            Set.of("com.example.hollow.Capability#enable()",
                   "com.example.hollow.Capability#hollowField",
                   "com.example.hollow.Capability#render(int)"),
            symbols(),
            "the exact set: a method, a field, and ONE of two overloads — each"
                + " kept alive entirely by tests");
    }

    @Test
    @DisplayName("control — a finding names the OVERLOAD, never just the method name")
    void aFindingNamesTheOverload() {
        // Found live on jawata's own PurityCheck#check: the bare name made a
        // test-only convenience overload read as "the parity gate is hollow",
        // when the arity the plan pipeline calls is wired. render() is
        // production's, render(int) is not — and the report must say which.
        Set<String> found = symbols();
        assertTrue(found.contains("com.example.hollow.Capability#render(int)"),
            () -> "the test-only overload is reported, by its parameter list: " + found);
        assertFalse(found.contains("com.example.hollow.Capability#render()"),
            () -> "the production-wired sibling overload is not: " + found);
    }

    @Test
    @DisplayName("control — a production caller suppresses it, however many tests also call it")
    void aProductionCallerSuppresses() {
        // usedInProduction() has BOTH a production caller (Production#run) and
        // a test caller. This is the difference the detector exists to draw:
        // "covered" is not "wired", and wired-plus-covered is not a finding.
        assertFalse(symbols().contains("com.example.hollow.Capability#usedInProduction()"),
            "a member production actually calls must never be reported");
    }

    @Test
    @DisplayName("control — ZERO callers is the ordinary unused check, not this one")
    void zeroCallersIsNotThisFinding() {
        assertFalse(symbols().contains("com.example.hollow.Capability#neverCalled()"),
            "nobody calls neverCalled() — that is find_quality_issue(kind=unused), a"
                + " different finding; reporting it here would make this a second"
                + " unused-member detector instead of a hollow-wiring one");
    }

    @Test
    @DisplayName("control — @Override, main(String[]) and interface members are skipped, and the skips are discriminating")
    void polymorphicAndEntryPointMembersAreSkipped() {
        // Each of these IS called from the fixture's test source and from
        // nowhere else, so each would be reported without its skip: toString()
        // is dispatched through the supertype, main() is called by the JVM,
        // and an interface method is implemented rather than called.
        Set<String> found = symbols();
        assertFalse(found.contains("com.example.hollow.Capability#toString()"),
            "@Override members are dispatched polymorphically — the caller that"
                + " matters calls the supertype");
        assertFalse(found.contains("com.example.hollow.Capability#main(String[])"),
            "an entry point has no source caller in production by construction");
        assertFalse(found.contains("com.example.hollow.Plugin#go()"),
            "an interface member is implemented, not called into existence");
        assertFalse(found.contains("com.example.hollow.Capability#go()"),
            "the @Override implementation of an interface member is skipped too");
    }

    @Test
    @DisplayName("the scan says what it examined, and says the answer is COMPLETE")
    void theScanDeclaresItsOwnReach() {
        Map<String, Object> data = run(kindArgs());
        assertEquals(4, ((Number) data.get("filesExamined")).intValue(),
            () -> "all four fixture sources examined: " + data);
        // Exactly the eight harvestable public members of the two MAIN types:
        // Capability{hollowField, LABEL, enable, usedInProduction, neverCalled,
        // render(int), render()} and Production{run}. toString/go/main are
        // skipped by rule, and ExerciseHarness's four public methods must NOT
        // appear — if they do, the test root was classified MAIN and every
        // reference in it counted as production, which silently empties the
        // finding set.
        assertEquals(8, ((Number) data.get("publicMainMembersTracked")).intValue(),
            () -> "public PRODUCTION members tracked, test-root members excluded: " + data);
        assertFalse(data.containsKey("scanIncomplete"),
            () -> "a complete scan must not claim partiality: " + data);
        assertNotNull(data.get("elapsedMs"), "the scan reports its own cost");
    }

    @Test
    @DisplayName("THE PATH-HEURISTIC DISCRIMINATOR: it finds the hollow member in a flat-src PDE pair")
    void findsTheHollowMemberWhereNoPathConventionApplies() throws Exception {
        // C4 audit, finding 4. The test-only-caller fixture is
        // Maven-conventional, so a detector carrying its own
        // contains("src/test/") heuristic produces byte-identical results on it
        // and every assertion in this class stays green. This pair is where the
        // heuristic and the model DISAGREE: both bundles keep sources flat
        // under src/, and the test bundle's directory name has no dot, so no
        // path convention places it. Under a heuristic the test bundle reads as
        // PRODUCTION, ExtLib#magic() gains a production caller, and this
        // finding disappears.
        JdtServiceImpl service = helper.loadProject("pde-external");
        service.addProject(helper.getFixturePath("pde-external-tests"));
        FindQualityIssueTool pde = new FindQualityIssueTool(() -> service);

        ToolResponse r = pde.execute(kindArgs());
        assertTrue(r.isSuccess(), () -> "refused: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fs = (List<Map<String, Object>>) data.get("findings");
        Set<String> found = fs.stream().map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());

        assertTrue(found.contains("com.example.ext.ExtLib#magic()"),
            () -> "magic() is public, in a flat-src MAIN bundle, and called only from the"
                + " flat-src test bundle — the mcp#9 layout, where a path convention"
                + " cannot see the test side: " + found + " / scan " + data);
    }

    @Test
    @DisplayName("THE THREE-WAY MAPPING: 'could not tell' is its own answer, never 'production'")
    void unplaceableIsNeverReadAsProduction() {
        // C4 audit, finding 3. markReferences read "verdict != TEST" as
        // production, so a compilation unit the classifier could not place
        // turned every reference it made into a production reference — the
        // finding it should have supported vanished, and the scan still called
        // itself COMPLETE. That is this sprint's own defect class (a failed
        // lookup returned as an answer) inside the detector written to catch it.
        //
        // HONEST LIMIT: this is asserted at the mapping, not end to end. A
        // .java file outside every source root is never LISTED (proven by the
        // sibling test below), so the only route to CROSS_CUTTING on a listed
        // file is a JavaModelException inside the classifier, which no fixture
        // can seed. Revert the mapping to the two-way form and this goes red.
        assertEquals(TestOnlyCallerDetector.Attribution.PRODUCTION,
            TestOnlyCallerDetector.attribute(SourceRootClassifier.Verdict.MAIN));
        assertEquals(TestOnlyCallerDetector.Attribution.TEST,
            TestOnlyCallerDetector.attribute(SourceRootClassifier.Verdict.TEST));
        assertEquals(TestOnlyCallerDetector.Attribution.UNKNOWN,
            TestOnlyCallerDetector.attribute(SourceRootClassifier.Verdict.CROSS_CUTTING),
            "an unplaceable file is UNKNOWN — reading it as PRODUCTION deletes findings"
                + " and reading it as TEST invents them");

        // And one unplaceable caller forfeits the claim entirely.
        assertTrue(TestOnlyCallerDetector.reportable(2, false, false),
            "test callers, no production caller, nothing unknown -> the finding");
        assertFalse(TestOnlyCallerDetector.reportable(2, false, true),
            "one unplaceable caller and 'every caller is a test' is no longer provable");
        assertFalse(TestOnlyCallerDetector.reportable(2, true, false),
            "a production caller suppresses");
        assertFalse(TestOnlyCallerDetector.reportable(0, false, false),
            "zero callers is the ordinary unused check");
    }

    @Test
    @DisplayName("why the mapping above cannot be driven end to end: an off-root file is never listed")
    void aFileOutsideEverySourceRootIsNotEvenListed() throws Exception {
        // The listing walks the model's source roots, so an off-root .java file
        // is invisible to the scan rather than unplaceable within it. Worth
        // pinning: it is what bounds the fix above to the mapping, and if the
        // listing ever changes to walk disk, this test tells you — because then
        // the CROSS_CUTTING path becomes reachable for real and needs its own
        // end-to-end control.
        java.nio.file.Path copy = helper.copyFixture("test-only-caller");
        java.nio.file.Path stray = copy.resolve("tools/StrayCaller.java");
        java.nio.file.Files.createDirectories(stray.getParent());
        java.nio.file.Files.writeString(stray, """
            public class StrayCaller {
                public void poke() {
                    new com.example.hollow.Capability().enable();
                }
            }
            """);
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(copy);
        FindQualityIssueTool scoped = new FindQualityIssueTool(() -> service);

        ToolResponse r = scoped.execute(kindArgs());
        assertTrue(r.isSuccess(), () -> "refused: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();

        assertEquals(4, ((Number) data.get("filesListed")).intValue(),
            () -> "the four source-root files, and NOT tools/StrayCaller.java: " + data);
        assertEquals(0, ((Number) data.getOrDefault("filesUnclassified", 0)).intValue(),
            () -> "an off-root file is not listed, so it is never an unplaceable one: " + data);
        assertFalse(data.containsKey("scanIncomplete"),
            () -> "and the scan is complete over what it is responsible for: " + data);

        // The stray file calls enable(). Since it is never listed, that call is
        // invisible — enable() is still reported. Were the listing to start
        // walking disk, this expectation is the first thing that breaks.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fs = (List<Map<String, Object>>) data.get("findings");
        assertTrue(fs.stream().anyMatch(
                f -> "com.example.hollow.Capability#enable()".equals(f.get("symbol"))),
            () -> "an off-root caller neither suppresses nor withholds: " + fs);

        service.dispose();
    }

    @Test
    @DisplayName("THE WIRING GATE: it fires in the standard quality sweep UNPROMPTED")
    @SuppressWarnings("unchecked")
    void firesInTheFamilySweepWithoutBeingNamed() {
        // The rule this detector exists to enforce, applied to the detector
        // itself: a capability whose activation depends on someone remembering
        // to name it is not shipped. Nothing here says
        // "called_only_by_tests" — the sweep must reach it on its own.
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "quality");
        args.put("summary", true);

        Map<String, Object> data = run(args);
        Map<String, Object> byKind = (Map<String, Object>) data.get("byKind");
        assertNotNull(byKind, () -> "the sweep reports counts by kind: " + data);
        assertEquals(3, ((Number) byKind.getOrDefault("called_only_by_tests", -1)).intValue(),
            () -> "the family sweep must reach this detector unprompted, with the"
                + " fixture's three findings: " + byKind);
    }
}
