package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One defect class, four instruments: <strong>a declared shape that lies about
 * the real one.</strong>
 *
 * <p>Every case here was measured, not suspected. {@code run_tests} declared
 * four actions and accepted eighteen. Its refusal message named ten of the
 * fourteen coverage suffixes and omitted {@code impacted_tests} — so
 * impacted-test selection, built and verified in Sprint 23, sat unused for five
 * weeks with nothing a caller could read saying it existed.
 * {@code find_references} answered a 28-reference symbol with
 * {@code totalReferences: 2} when asked for two. A whole-family sweep
 * advertised a synchronous path that times out on any real project. And a
 * workspace sweep merged findings from 29 projects into one list that never
 * said which project a row came from.</p>
 */
class DeclaredShapeHonestyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<String> actionEnum(AbstractTool tool) {
        Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
        return (List<String>) ((Map<String, Object>) props.get("action")).get("enum");
    }

    // ------------------------------------------------------------------
    // run_tests: the declared action set IS the accepted action set
    // ------------------------------------------------------------------

    /**
     * THE ANTI-DIVERGENCE GATE, and it is a gate rather than a reminder: the
     * dispatcher now refuses anything not in {@code ACTIONS} before its switch
     * ever sees it, so a routed-but-undeclared action is unreachable by
     * construction. This test covers the other direction — declared and not
     * routed — which is what the switch's {@code default} arm now means.
     */
    @Test
    @DisplayName("run_tests routes every action it declares")
    void everyDeclaredRunTestsActionIsRouted() {
        RunTestsTool tool = new RunTestsTool(() -> service);
        List<String> declared = actionEnum(tool);
        assertEquals(RunTestsTool.ACTIONS, declared,
            "the schema publishes the one list, never a hand-kept copy of it");
        assertTrue(declared.contains("coverage_impacted_tests"),
            "the capability that sat unused for five weeks must be visible in the schema");

        for (String action : declared) {
            ObjectNode args = mapper.createObjectNode();
            args.put("action", action);
            ToolResponse r = tool.execute(args);
            // Most of these fail for their OWN reasons — no scope, no
            // sessionId, no coverage artifact. What none of them may do is come
            // back as an unknown action, or as the not-routed defect code.
            if (!r.isSuccess()) {
                String code = String.valueOf(r.getError());
                assertFalse(code.contains("ACTION_NOT_ROUTED"),
                    "'" + action + "' is declared and reaches no handler: " + code);
                assertFalse(code.contains("Must be one of"),
                    "'" + action + "' is declared and refused as unknown: " + code);
            }
        }
    }

    /**
     * The refusal message is the ONE place a caller learns what exists, and it
     * was a hand-written subset. Naming the four it omitted individually rather
     * than asserting a substring of the whole list: those four are the finding.
     */
    @Test
    @DisplayName("run_tests names every action when it refuses one")
    void theRefusalNamesTheActionsItOnceHid() {
        RunTestsTool tool = new RunTestsTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("action", "coverage_nonsense");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        String message = String.valueOf(r.getError());
        for (String hidden : List.of("coverage_tests_covering", "coverage_of_test",
                "coverage_impacted_tests", "coverage_mutation")) {
            assertTrue(message.contains(hidden),
                "the refusal must name '" + hidden + "' — it named ten of fourteen and this "
                    + "was one of the four it hid: " + message);
        }
    }

    // ------------------------------------------------------------------
    // find_quality_issue: a family sweep refuses synchronously (#10)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a whole-family sweep refuses at once and names the async path")
    void aSynchronousFamilySweepRefusesAndSaysWhatToDoInstead() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "fowler");

        long started = System.currentTimeMillis();
        ToolResponse r = tool.execute(args);
        long elapsed = System.currentTimeMillis() - started;

        assertFalse(r.isSuccess(), "a synchronous family sweep is not available");
        String message = String.valueOf(r.getError());
        assertTrue(message.contains("SWEEP_REQUIRES_ASYNC"), message);
        assertTrue(message.contains("start"),
            "the refusal must name the path that works, or it is the timeout with better "
                + "manners: " + message);
        // The point of refusing rather than auto-starting: instant, not after
        // the client has already given up.
        assertTrue(elapsed < 5_000,
            "the refusal must be immediate; it took " + elapsed + " ms");
    }

    /** A single kind is unaffected — those complete well inside the timeout. */
    @Test
    @DisplayName("a single kind still answers synchronously")
    void oneKindIsStillSynchronous() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "long_method");
        assertTrue(tool.execute(args).isSuccess(),
            "refusing the family must not take the single-kind path with it");
    }

    /** And the async path is not merely advertised — it produces the sweep. */
    @Test
    @DisplayName("the path the refusal names actually works")
    void theAsyncPathTheRefusalNamesDelivers() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "quality");
        Map<String, Object> finished = data(org.jawata.mcp.fixtures.Sweeps.run(tool, args));
        assertEquals("finished", finished.get("state"));
        assertNotNull(finished.get("findings"), "the full result rides the status response");
    }

    // ------------------------------------------------------------------
    // find_references: the page cap is not the population
    // ------------------------------------------------------------------

    /**
     * Measured before the fix on a 28-reference symbol asked with
     * {@code maxResults=2}: {@code totalReferences: 2} AND
     * {@code meta.totalCount: 2}. Both fields, the page size, with
     * {@code truncated: true} beside them.
     *
     * <p>The fixture picks whatever symbol in the loaded project has the most
     * references rather than naming one, so this keeps meaning if the fixture
     * changes. It asserts the RELATIONSHIP — total &gt; page — which is the
     * property that was false.</p>
     */
    @Test
    @DisplayName("find_references reports the true total in both fields, never the cap")
    void theCapIsNotReportedAsTheTotal() {
        FindReferencesTool tool = new FindReferencesTool(() -> service);

        ObjectNode uncapped = mapper.createObjectNode();
        uncapped.put("kind", "references");
        uncapped.put("symbol", "com.example.Calculator");
        Map<String, Object> all = data(tool.execute(uncapped));
        int trueTotal = ((Number) all.get("totalReferences")).intValue();
        // The fixture must actually have something to truncate, or this test
        // would pass on an empty search and prove nothing.
        org.junit.jupiter.api.Assumptions.assumeTrue(trueTotal >= 2,
            "fixture needs at least 2 references to exercise a cap; got " + trueTotal);

        ObjectNode capped = mapper.createObjectNode();
        capped.put("kind", "references");
        capped.put("symbol", "com.example.Calculator");
        capped.put("maxResults", 1);
        ToolResponse r = tool.execute(capped);
        Map<String, Object> page = data(r);

        assertEquals(trueTotal, ((Number) page.get("totalReferences")).intValue(),
            "totalReferences is the POPULATION — it must not change because the caller "
                + "asked for fewer rows");
        assertEquals(1, ((List<?>) page.get("references")).size(), "the page obeys the cap");
        assertEquals(1, ((Number) page.get("returnedReferences")).intValue(),
            "and the page size has its own field rather than borrowing the total's");
        assertEquals(trueTotal, r.getMeta().getTotalCount(),
            "meta.totalCount is the same population — it lied in BOTH fields before");
        assertEquals(1, r.getMeta().getReturnedCount());
        assertEquals(Boolean.TRUE, r.getMeta().getTruncated());
    }

    // ------------------------------------------------------------------
    // the sweep rows name their project
    // ------------------------------------------------------------------

    /**
     * {@code compile_workspace} has stamped {@code sourceProject} on every
     * diagnostic all along; a family sweep merged findings from every loaded
     * project and stamped nothing. On a 29-project workspace that is thousands
     * of rows with ownership left for the reader to reconstruct from path
     * prefixes — work the loaded model has already done.
     */
    @Test
    @DisplayName("sweep findings name the project they came from")
    void sweepRowsCarryTheirProject() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "quality");
        Map<String, Object> finished = data(org.jawata.mcp.fixtures.Sweeps.run(tool, args));

        List<?> findings = (List<?>) finished.get("findings");
        assertNotNull(findings, "the sweep must produce findings to attribute");
        org.junit.jupiter.api.Assumptions.assumeFalse(findings.isEmpty(),
            "fixture produced no findings; nothing to attribute");

        String key = service.allProjects().iterator().next().projectKey();
        long attributed = findings.stream()
            .filter(f -> f instanceof Map<?, ?>)
            .map(f -> (Map<?, ?>) f)
            .filter(f -> f.get("filePath") instanceof String)
            .filter(f -> key.equals(f.get("sourceProject")))
            .count();
        long withPath = findings.stream()
            .filter(f -> f instanceof Map<?, ?>)
            .filter(f -> ((Map<?, ?>) f).get("filePath") instanceof String)
            .count();
        assertEquals(withPath, attributed,
            "every finding that names a file must name the project that file belongs to");
    }
}
