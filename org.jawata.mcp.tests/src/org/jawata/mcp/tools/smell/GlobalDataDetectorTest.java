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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — the Global Data detector, against com.example.GlobalDataTargets.
 *
 * <p>Pinned in BOTH directions. A detector is easy to write so that it speaks, and the
 * expensive mistake is the one that speaks about everything: a rule keyed on
 * {@code static} alone would report every constant in the codebase, and a rule keyed on
 * {@code final} would miss the disguised half — the final reference to a mutable
 * collection, which is the shape Fowler is actually pointing at.</p>
 */
class GlobalDataDetectorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Set<String> symbols(Integer threshold) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "global_data");
        if (threshold != null) {
            args.put("threshold", threshold);
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "global_data must dispatch — refused with: "
            + (r.getError() != null ? r.getError().getCode() + " / " + r.getError().getMessage()
                                    : "(no error info)"));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("the reassignable reference, the mutable collection and the array are all reported")
    void reportsEveryShapeOfStaticMutableState() {
        Set<String> hits = symbols(null);
        assertTrue(hits.contains("com.example.GlobalDataTargets#mutableCounter"),
            "a static non-final field is the plain case: " + hits);
        assertTrue(hits.contains("com.example.GlobalDataTargets#SHARED_NAMES"),
            "a static final List is the case a rule written on `final` would miss —"
                + " the reference is fixed and the contents are not: " + hits);
        assertTrue(hits.contains("com.example.GlobalDataTargets#SHARED_SLOTS"),
            "an array is mutable whatever it holds: " + hits);
        assertTrue(hits.contains("com.example.GlobalDataTargets#SHARED_INDEX"),
            "and a Map is the same case as the List: " + hits);
    }

    @Test
    @DisplayName("a constant is not global data, however static and final it is")
    void doesNotReportImmutableConstants() {
        Set<String> hits = symbols(null);
        assertFalse(hits.contains("com.example.GlobalDataTargets#LABEL"),
            "a static final String is a name for a value. Reporting it would bury every"
                + " real finding under every constant in the codebase: " + hits);
        assertFalse(hits.contains("com.example.GlobalDataTargets#LIMIT"),
            "and so is a primitive constant: " + hits);
        assertFalse(hits.contains("com.example.GlobalDataTargets#perInstance"),
            "an instance field is not global data at all: " + hits);
    }

    @Test
    @DisplayName("private static state is opt-in, because its reach is one file")
    void privateStaticStateIsBehindTheThreshold() {
        assertFalse(symbols(null).contains("com.example.GlobalDataTargets#hiddenCounter"),
            "at the default reach the check reports what something OUTSIDE the class can"
                + " see; private static state is a real class-variable finding but a"
                + " different conversation, and mixing them buries the actionable half");
        assertTrue(symbols(1).contains("com.example.GlobalDataTargets#hiddenCounter"),
            "and at threshold 1 it is reported — otherwise the switch does nothing and"
                + " the paragraph documenting it is false");
    }
}
