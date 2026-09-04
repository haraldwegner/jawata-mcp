package org.jawata.mcp.tools.refactoring;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractTool;
import org.jawata.mcp.tools.ExtractTool;
import org.jawata.mcp.tools.InlineTool;
import org.jawata.mcp.tools.MoveTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE COUNT, COMPUTED FROM THE SHIPPED SCHEMAS — the C3-to-C7 exit clause says
 * "recomputed from the shipped tool list, not asserted", and the difference is the whole
 * point: a number written into a test is a second copy of a fact, and the copy is what
 * goes stale.
 *
 * <p>What is asserted here is the plan's own per-tool contract for the three doors Stage 6
 * touched — {@code extract} 11, {@code inline} 5, {@code move} 6. The C9 clause spells out
 * why a ceiling alone is not enough: it "would pass a tool that finished two kinds short".
 * So these are equalities, and each side of them is read from the tool.</p>
 *
 * <p><b>What this does NOT compute, said rather than left to assumption:</b> the sprint's
 * headline figure — 62 of Fowler's 66 — cannot be derived from the shipped list, because
 * nothing in the code maps a Fowler row to the kind that performs it. That mapping lives in
 * the spec's 90-row inventory, which is a document. Deriving the headline needs the table
 * to exist IN CODE, and building it is Stage 9's clause, not this one's. Reporting 62 from
 * here would be asserting a number twice, which is exactly what the clause forbids.</p>
 */
class Stage6ShippedCountTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
    }

    /** A door's kind enum, read from the schema a client reads. */
    private static List<String> publishedKindsOf(AbstractTool tool) {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
            (Map<String, Object>) tool.getInputSchema().get("properties");
        List<String> kinds = new ArrayList<>();
        Object kind = properties.get("kind");
        if (kind instanceof Map<?, ?> map && map.get("enum") instanceof Collection<?> values) {
            values.forEach(v -> kinds.add(String.valueOf(v)));
        }
        return kinds;
    }

    @Test
    @DisplayName("the three Stage 6 doors publish exactly the kind counts the plan assigns")
    void theKindCountsAreWhatThePlanAssigns() {
        RefactoringChangeCache cache = new RefactoringChangeCache();
        Map<String, AbstractTool> doors = new LinkedHashMap<>();
        doors.put("extract", new ExtractTool(() -> service, cache));
        doors.put("inline", new InlineTool(() -> service, cache));
        doors.put("move", new MoveTool(() -> service, cache));

        Map<String, Integer> counted = new LinkedHashMap<>();
        doors.forEach((name, door) -> counted.put(name, publishedKindsOf(door).size()));

        assertEquals(Map.of("extract", 11, "inline", 5, "move", 6), counted,
            "the plan's C9 clause names a count per tool and says why a ceiling would not"
                + " do — it 'would pass a tool that finished two kinds short'. These are"
                + " read from the published schemas, so a kind that reaches the enum"
                + " without reaching a delegate still counts, and a kind that reaches a"
                + " delegate without reaching the enum does not. Counted: " + counted
                + ", kinds: " + doors.entrySet().stream()
                    .map(e -> e.getKey() + "=" + publishedKindsOf(e.getValue())).toList());
    }
}
