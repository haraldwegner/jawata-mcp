package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
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

    @SuppressWarnings("unchecked")
    private Set<String> runScoped(DivergentChangeDetector det, String filePath) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "divergent_change");
        if (filePath != null) {
            args.put("filePath", filePath);
        }
        ToolResponse r = det.detect(service, args);
        assertTrue(r.isSuccess(), "divergent_change must succeed");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Sprint 22a 2.6.1 (#2): filePath scopes the churn detector — other high-churn files excluded")
    void filePath_scopes_the_churn_detector() {
        String hw = "src/main/java/com/example/HelloWorld.java";
        String cm = "src/main/java/com/example/ComposeMethodTargets.java";
        List<List<String>> commits = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            commits.add(List.of(hw, cm, "src/main/java/com/example/area" + i + "/Other.java"));
        }
        DivergentChangeDetector det = new DivergentChangeDetector(root -> GitHistory.of(commits));

        // Unscoped: both high-churn files are flagged.
        Set<String> all = runScoped(det, null);
        assertTrue(all.contains("HelloWorld") && all.contains("ComposeMethodTargets"),
            "both high-churn files are flagged when unscoped: " + all);

        // Scoped to one file: ONLY that file — the churn detector must respect filePath.
        Set<String> scoped = runScoped(det, hw);
        assertTrue(scoped.contains("HelloWorld"), "the scoped file is still flagged: " + scoped);
        assertFalse(scoped.contains("ComposeMethodTargets"),
            "filePath must scope the churn detector — other files are excluded: " + scoped);
    }
}
