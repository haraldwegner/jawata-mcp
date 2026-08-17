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
    @DisplayName("schema lists all eleven kinds (Sprint 23 'source' + Sprint 24 'landmarks'); requires kind")
    @SuppressWarnings("unchecked")
    void schema_lists_kinds() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> kinds = (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");
        assertEquals(11, kinds.size());
        assertTrue(kinds.contains("source"), "Sprint 23 D8 lib-source kind: " + kinds);
        assertTrue(kinds.contains("landmarks"), "Sprint 24 D4 landmarks kind: " + kinds);
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
