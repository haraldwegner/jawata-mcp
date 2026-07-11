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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.8.1 dogfood fix (found live 2026-07-11): a Class search for "*Tool" on
 * the jawata-mcp workspace ranked JRE-internal binary types (JavacTool,
 * JShellTool, …) ABOVE the project's own classes, and reported them with an
 * opaque workspace-handle path. Contract now: project-source results rank
 * before binary-classpath results (stable within each partition), and binary
 * results carry {@code binary: true} plus a readable archive path.
 */
class SearchSymbolsRankingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;
    private SearchSymbolsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        mapper = new ObjectMapper();
        tool = new SearchSymbolsTool(() -> service);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> run(String query, String kind, int maxResults) {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", query);
        if (kind != null) args.put("kind", kind);
        args.put("maxResults", maxResults);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "search must succeed; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return (List<Map<String, Object>>) data.get("results");
    }

    @Test
    @DisplayName("project-source results rank before binary-classpath results")
    void projectSourceRanksFirst() {
        // "H*" matches the fixture's HelloWorld (source) AND a pile of JRE
        // types (HashMap, Hashtable, …) via the project classpath.
        List<Map<String, Object>> results = run("H*", "Class", 30);
        assertTrue(results.stream().anyMatch(r -> "HelloWorld".equals(r.get("name"))),
            "fixture class HelloWorld must be found; got: " + results);

        // Every source hit (binary != true) must come before every binary hit.
        boolean seenBinary = false;
        for (Map<String, Object> r : results) {
            boolean binary = Boolean.TRUE.equals(r.get("binary"));
            if (binary) {
                seenBinary = true;
            } else {
                assertTrue(!seenBinary,
                    "source result '" + r.get("name") + "' ranked after a binary result: " + results);
            }
        }
        // And the fixture's own class is the very first result.
        assertEquals("HelloWorld", results.get(0).get("name"),
            "the project's own class must rank first; got: " + results);
    }

    @Test
    @DisplayName("binary results carry binary:true and a readable archive path")
    void binaryResultsCarryReadablePath() {
        List<Map<String, Object>> results = run("HashMap", "Class", 10);
        assertTrue(results.stream().anyMatch(r -> "HashMap".equals(r.get("name"))),
            "JRE HashMap must be found via the classpath; got: " + results);

        Map<String, Object> hashMap = results.stream()
            .filter(r -> "HashMap".equals(r.get("name"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, hashMap.get("binary"), "JRE type must be flagged binary");
        String fp = String.valueOf(hashMap.get("filePath"));
        assertTrue(fp.endsWith(".jar") || fp.endsWith(".jmod") || fp.contains("jrt"),
            "binary filePath must be a readable archive path, not a workspace handle; got: " + fp);
    }
}
