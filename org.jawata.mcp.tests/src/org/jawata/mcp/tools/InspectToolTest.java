package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 16b/A (v1.1.1) — routing tests for the parametric {@code inspect} front door (all read-only). */
class InspectToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private InspectTool tool;
    private ObjectMapper mapper;
    private String calculatorPath;
    private Map<String, AbstractTool> narrowByKind;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new InspectTool(() -> service);
        mapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        narrowByKind = new LinkedHashMap<>();
        narrowByKind.put("type_hierarchy", new GetTypeHierarchyTool(() -> service));
        narrowByKind.put("document_symbols", new GetDocumentSymbolsTool(() -> service));
        narrowByKind.put("type_members", new GetTypeMembersTool(() -> service));
        narrowByKind.put("classpath", new GetClasspathInfoTool(() -> service));
        narrowByKind.put("project_structure", new GetProjectStructureTool(() -> service));
        narrowByKind.put("type_usage", new GetTypeUsageSummaryTool(() -> service));
        narrowByKind.put("complexity", new GetComplexityMetricsTool(() -> service));
        narrowByKind.put("dependency_graph", new GetDependencyGraphTool(() -> service));
        narrowByKind.put("di_registrations", new GetDiRegistrationsTool(() -> service));
    }

    private ObjectNode args(String kind) {
        ObjectNode n = mapper.createObjectNode();
        if (kind != null) n.put("kind", kind);
        n.put("typeName", "com.example.Calculator");
        n.put("filePath", calculatorPath);
        return n;
    }

    @Test
    @DisplayName("schema lists all thirteen kinds (Sprint 23 'source', Sprint 24 'landmarks', Sprint 28g 'suite_policy' and 'impacted_tests'); requires kind")
    @SuppressWarnings("unchecked")
    void schema_lists_kinds() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> kinds = (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");
        assertEquals(13, kinds.size());
        assertTrue(kinds.contains("impacted_tests"),
            "Sprint 28g S10-9: the impacted-test selection answered on the read-only door: " + kinds);
        assertTrue(kinds.contains("source"), "Sprint 23 D8 lib-source kind: " + kinds);
        assertTrue(kinds.contains("landmarks"), "Sprint 24 D4 landmarks kind: " + kinds);
        assertTrue(kinds.contains("suite_policy"),
            "Sprint 28g D41: the accepted-abort policy answered on the read-only door: " + kinds);
        assertTrue(((List<String>) schema.get("required")).contains("kind"));
    }

    @Test
    @DisplayName("every kind routes to its narrow delegate (read-only parity)")
    void every_kind_routes() {
        for (Map.Entry<String, AbstractTool> e : narrowByKind.entrySet()) {
            String kind = e.getKey();
            ToolResponse viaParam = tool.execute(args(kind));
            ObjectNode narrowArgs = args(kind);
            narrowArgs.remove("kind");
            ToolResponse viaNarrow = e.getValue().execute(narrowArgs);
            assertEquals(viaNarrow.isSuccess(), viaParam.isSuccess(), "kind=" + kind + " success parity");
            JsonNode n = mapper.valueToTree(viaNarrow.isSuccess() ? viaNarrow.getData() : viaNarrow.getError());
            JsonNode p = mapper.valueToTree(viaParam.isSuccess() ? viaParam.getData() : viaParam.getError());
            assertEquals(n, p, "kind=" + kind + " payload parity");
        }
    }

    @Test
    @DisplayName("kind=suite_policy answers from the project's own build/ lists, found=false when it has none")
    void suite_policy_reads_the_project_lists() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "suite_policy");
        ToolResponse response = tool.execute(a);

        assertTrue(response.isSuccess(), String.valueOf(response));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals(Boolean.FALSE, data.get("found"),
            "the simple-maven fixture declares no expected-aborts list: an absence, said as one");
        assertEquals(0, data.get("acceptedAbortCount"));
        assertTrue(data.containsKey("projectKey"));
    }

    // ---- Sprint 28g S10-9: D41 clause 1 says the reach is computed "by the same compiler proof D21
    // computes", and a hired Claude is refused run_tests in plan mode. The selection is therefore
    // answered on this READ-ONLY door — as the SAME implementation, not a second one.

    @Test
    @DisplayName("kind=impacted_tests on a door nobody wired says so; it never answers an empty list")
    void impacted_tests_unwired_is_refused_not_empty() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "impacted_tests");
        ToolResponse response = tool.execute(a);

        assertFalse(response.isSuccess(),
            "an unwired door must not succeed: an empty selection reads as 'no test is impacted'");
        assertTrue(String.valueOf(response.getError().getMessage()).contains("not available"),
            "and it says why: " + response.getError().getMessage());
    }

    @Test
    @DisplayName("kind=impacted_tests hands its arguments to the wired selection and returns its answer unchanged")
    void impacted_tests_delegates_to_the_wired_selection() {
        java.util.concurrent.atomic.AtomicReference<JsonNode> seen =
            new java.util.concurrent.atomic.AtomicReference<>();
        ToolResponse canned = ToolResponse.success(Map.of("marker", "the selection's own answer"));
        tool.setImpactedTests(args -> {
            seen.set(args);
            return canned;
        });

        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "impacted_tests");
        a.put("forScope", "package:com.example");
        a.putArray("symbols").add("com.example.Calculator#add");
        ToolResponse response = tool.execute(a);

        assertTrue(response.isSuccess(), String.valueOf(response));
        assertEquals(canned.getData(), response.getData(), "the answer is the selection's, untouched");
        assertEquals("package:com.example", seen.get().path("forScope").asText(),
            "the selection received the caller's scope");
        assertEquals("com.example.Calculator#add", seen.get().path("symbols").get(0).asText(),
            "and the caller's symbols");
    }

    @Test
    @DisplayName("kind=impacted_tests is the SAME answer as run_tests(action=coverage_impacted_tests)")
    void impacted_tests_is_the_same_answer_as_run_tests() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        RunTestsTool runTests = new RunTestsTool(() -> service);
        InspectTool wired = new InspectTool(() -> service);
        wired.setImpactedTests(runTests::impactedTests);

        ObjectNode viaInspect = mapper.createObjectNode();
        viaInspect.put("kind", "impacted_tests");
        viaInspect.putArray("symbols").add("com.example.Calculator#add");
        ObjectNode viaRunTests = mapper.createObjectNode();
        viaRunTests.put("action", "coverage_impacted_tests");
        viaRunTests.putArray("symbols").add("com.example.Calculator#add");

        ToolResponse a = wired.execute(viaInspect);
        ToolResponse b = runTests.execute(viaRunTests);

        assertEquals(b.isSuccess(), a.isSuccess(), "success parity");
        assertEquals(mapper.valueToTree(b.isSuccess() ? b.getData() : b.getError()),
            mapper.valueToTree(a.isSuccess() ? a.getData() : a.getError()),
            "payload parity: two doors onto one implementation cannot disagree");
    }

    @Test
    @DisplayName("kind=type_members lists Calculator's members")
    void type_members_resolves() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "type_members");
        a.put("typeName", "com.example.Calculator");
        assertTrue(tool.execute(a).isSuccess());
    }

    @Test
    @DisplayName("missing/unknown kind invalid")
    void missing_unknown_kind_invalid() {
        assertFalse(tool.execute(args(null)).isSuccess());
        assertFalse(tool.execute(args("typo")).isSuccess());
    }

    // ---- mcp#23: kind=source is PAGED, and always says how much there is ----
    //
    // `inspect(kind=source, typeName=java.util.stream.Collectors)` came back at
    // 98,064 characters and the CLIENT REFUSED it ("exceeds maximum allowed
    // tokens") — a correct answer nobody can receive. The engine's own 120K
    // bound was neither caller-controllable nor transport-realistic, and it
    // reported `truncated` with no way to fetch the rest, which this project's
    // own history treats as half a fix.

    @SuppressWarnings("unchecked")
    private Map<String, Object> sourceOf(int maxChars, int offset) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "source");
        a.put("typeName", "com.example.Calculator");
        if (maxChars > 0) a.put("maxChars", maxChars);
        if (offset > 0) a.put("offset", offset);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "kind=source must resolve a workspace type");
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("mcp#23: a page carries the FULL length, so it never reads as the whole type")
    void source_page_declares_the_total_and_the_next_offset() {
        Map<String, Object> full = sourceOf(0, 0);
        int total = (Integer) full.get("sourceLength");
        assertTrue(total > 0, "the length is always reported: " + full.keySet());

        Map<String, Object> firstPage = sourceOf(40, 0);
        assertEquals(total, firstPage.get("sourceLength"),
            "a clipped page still declares the whole type's size");
        assertEquals(40, firstPage.get("returnedChars"));
        assertEquals(Boolean.TRUE, firstPage.get("truncated"));
        assertTrue(String.valueOf(firstPage.get("hint")).contains("offset=40"),
            "and it names the next call rather than leaving the caller stuck: "
                + firstPage.get("hint"));
        assertEquals(40, ((String) firstPage.get("source")).length());
    }

    @Test
    @DisplayName("mcp#23: the hinted offset returns the continuation, not the start again")
    void source_offset_continues_where_the_page_ended() {
        String whole = (String) sourceOf(0, 0).get("source");
        Map<String, Object> second = sourceOf(40, 40);

        assertEquals(40, second.get("offset"));
        assertEquals(whole.substring(40, 80), second.get("source"));
    }

    @Test
    @DisplayName("mcp#23: the default page is transport-realistic, well under the engine ceiling")
    void the_default_page_is_sized_for_a_client_not_for_the_engine() {
        assertTrue(org.jawata.mcp.tools.shared.LibrarySource.DEFAULT_MAX_CHARS <= 30_000,
            "98K characters was refused by the client; a default near the engine's 120K "
                + "ceiling would ship that failure to every caller");
    }
}
