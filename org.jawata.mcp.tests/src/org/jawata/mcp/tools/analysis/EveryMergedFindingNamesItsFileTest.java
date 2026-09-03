package org.jawata.mcp.tools.analysis;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONE SPELLING FOR THE FILE A FINDING IS ABOUT.
 *
 * <p>{@code find_quality_issue} merges six differently-named result lists into one
 * {@code findings} array. Four things then read each row's file: the {@code excludePaths}
 * filter, conflict arbitration, the multi-project {@code sourceProject} attribution, and
 * the baseline's row identity. All four read {@code filePath}.</p>
 *
 * <p>Two producers spelled it {@code file} instead — the naming check and the
 * large-classes check — so for their rows all four read null. Nothing failed: the filter
 * excluded nothing, arbitration skipped them, attribution left them unowned, and the
 * baseline keyed them as {@code naming|null|<line>}, which collapses every row on the
 * same line number in different files into one entry. A merged list that silently means
 * different things per producer is the shape this test closes.</p>
 *
 * <p>It is written over the MERGED output rather than per tool, because the merge is
 * where the disagreement does its damage, and a per-tool assertion would have to be
 * remembered once per new detector. A row missing {@code filePath} fails here whichever
 * producer emitted it.</p>
 */
class EveryMergedFindingNamesItsFileTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service, () -> null);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(ObjectNode args) {
        // A FAMILY sweep refuses synchronously by design — it runs twelve detectors and
        // would outlive a client's timeout — so it goes through the async helper every
        // other test uses. A single kind answers directly.
        ToolResponse r = args.has("family")
            ? org.jawata.mcp.fixtures.Sweeps.run(tool, args)
            : tool.execute(args);
        assertTrue(r.isSuccess(), "the scan must run; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> out = new ArrayList<>();
        // Every list shape a detector may return — the same set the tool's own
        // RESULT_LIST_KEYS names. Read from the response, not assumed.
        for (String key : List.of("findings", "unusedItems", "violations", "issues",
                "cycles", "largeClasses")) {
            if (data.get(key) instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> row) {
                        out.add((Map<String, Object>) row);
                    }
                }
            }
        }
        return out;
    }

    private ObjectNode kind(String kind) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        return a;
    }

    @Test
    @DisplayName("the two producers that spelled it `file` now name filePath, like every reader")
    void theTwoRepairedProducersNameFilePath() {
        Map<String, List<Map<String, Object>>> byKind = new LinkedHashMap<>();
        byKind.put("naming", rowsOf(kind("naming")));

        ObjectNode large = kind("large_classes");
        // Low enough that the fixture's own classes qualify — this test needs rows to
        // examine, and a threshold nothing crosses would let it pass over nothing.
        large.put("maxMethods", 1);
        large.put("maxFields", 0);
        large.put("maxLines", 5);
        byKind.put("large_classes", rowsOf(large));

        byKind.forEach((k, rows) -> {
            assertFalse(rows.isEmpty(),
                "PROOF OF LIFE: '" + k + "' must produce rows here, or the assertions"
                    + " below hold over an empty list and prove nothing.");
            for (Map<String, Object> row : rows) {
                assertTrue(row.get("filePath") instanceof String path && !path.isBlank(),
                    "every merged row names its file under `filePath`, which is what all"
                        + " four consumers read; '" + k + "' row: " + row);
                assertFalse(row.containsKey("file"),
                    "and it does not ALSO carry the old spelling — two keys for one fact"
                        + " is the state this repair removes, not an improvement on it;"
                        + " '" + k + "' row: " + row);
            }
        });
    }

    @Test
    @DisplayName("a family sweep's merged list is uniform, whichever detector produced a row")
    void theWholeMergedListIsUniform() {
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "quality");
        List<Map<String, Object>> rows = rowsOf(args);
        assertFalse(rows.isEmpty(),
            "PROOF OF LIFE: the quality family must find something on this fixture.");

        List<Map<String, Object>> unnamed = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            // A row with no file at all is legitimate for some kinds (a package cycle is
            // not about one file), so the assertion is about the SPELLING: a row that
            // names a file must name it the one way every consumer reads.
            if (row.containsKey("file") && !(row.get("filePath") instanceof String)) {
                unnamed.add(row);
            }
        }
        assertTrue(unnamed.isEmpty(),
            "these merged rows name their file under a key nothing reads: " + unnamed);
    }
}
