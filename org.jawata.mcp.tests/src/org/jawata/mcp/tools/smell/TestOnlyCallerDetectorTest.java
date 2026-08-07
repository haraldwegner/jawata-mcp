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
 * <p>The fixture's test source imports nothing from JUnit and carries no
 * annotation, so its test-ness is available ONLY from the source root the
 * importer recorded. That is deliberate: it is what makes these assertions
 * fail if the detector ever stops consulting
 * {@link org.jawata.core.project.SourceRootClassifier} and grows a heuristic
 * of its own — the second-place-to-know-test-ness mistake that produced
 * mcp#9.</p>
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
