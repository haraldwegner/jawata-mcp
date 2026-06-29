package org.goja.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 17 — Divergent Change detector. Git history is injected (deterministic):
 * a synthetic history with a high-churn, high-area-spread file is flagged; an
 * unavailable history produces no findings (the off-a-git-work-tree contract).
 */
class DivergentChangeDetectorTest {

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
    private Set<String> run(DivergentChangeDetector det) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "divergent_change");
        ToolResponse r = det.detect(service, args);
        assertTrue(r.isSuccess(), "divergent_change must succeed");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags a file with many commits across many areas")
    void flags_high_churn_file() {
        String hw = "src/main/java/com/example/HelloWorld.java";
        List<List<String>> commits = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            commits.add(List.of(hw, "src/main/java/com/example/area" + i + "/Other.java"));
        }
        GitHistory hist = GitHistory.of(commits);
        Set<String> hits = run(new DivergentChangeDetector(root -> hist));
        assertTrue(hits.contains("HelloWorld"),
            "file changed in 5 commits across 5 areas should be flagged: " + hits);
    }

    @Test
    @DisplayName("no findings when git history is unavailable (off a work-tree)")
    void no_findings_without_git() {
        Set<String> hits = run(new DivergentChangeDetector(root -> GitHistory.unavailable()));
        assertFalse(hits.contains("HelloWorld"), "no git history → no divergent_change findings: " + hits);
        assertTrue(hits.isEmpty(), "unavailable history must yield zero findings: " + hits);
    }
}
