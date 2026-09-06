package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.DataTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 2, Change Reference to Value, on CODE WE DID NOT AUTHOR — and what it proves is the
 * ROLLBACK.
 *
 * <p>The census in {@link RemoveSettingMethodForkSliceTest} covers this row too: the fork's
 * plain setters are wiring setters, so a class whose every setter this row could remove does
 * not occur, and there is no success-path demonstration on foreign code. That absence is
 * stated there and not repeated here.</p>
 *
 * <p><b>What foreign code CAN show for this row, and a fixture cannot show as convincingly,
 * is that a composed operation leaves nothing behind when a later step declines.</b> Row 2 is
 * a recipe: it removes each setter, then generates the equality. Upstream's
 * {@code AbstractFilter} has one setter and it is called from outside, so the first step
 * refuses — and the assertion that matters is that the file comes back byte-for-byte. A
 * half-applied refactoring on somebody else's code is the worst outcome this stage can
 * produce, worse than declining and worse than performing, because it is the one the caller
 * does not find out about.</p>
 */
class ChangeReferenceToValueForkSliceTest {

    private static final String PKG = "src/main/java/com/iluwatar/intercepting/filter";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path filter;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl jdt = helper.loadProjectCopy("fork-intercepting-filter");
        tool = new DataTool(() -> jdt, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        filter = jdt.getProjectRoot().resolve(PKG).resolve("AbstractFilter.java");
    }

    @Test
    @DisplayName("a step declining on upstream's class rolls the whole recipe back, "
        + "byte-for-byte")
    void rollsBackOnUpstreamsClass() throws Exception {
        String before = Files.readString(filter, StandardCharsets.UTF_8);
        String[] lines = before.split("\n", -1);
        int line = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("class AbstractFilter")) {
                line = i;
                break;
            }
        }
        assertTrue(line >= 0, "the vendored slice no longer declares AbstractFilter");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "reference_to_value");
        args.put("filePath", filter.toString());
        args.put("line", line);
        args.put("column", lines[line].indexOf("AbstractFilter"));

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "its one setter satisfies the Filter interface, so the"
            + " first step declines and the recipe cannot continue");
        String error = String.valueOf(r.getError());
        // WHICH REFUSAL, not merely that one fired. `setNext` satisfies two of this row's
        // preconditions at once — it implements an interface method AND is called from
        // outside — so a test that only checked "it declined" would pass whichever branch
        // answered, and would keep passing if the branches were reordered or one deleted.
        // The sibling RemoveSettingMethodForkSliceTest pins the same method the same way,
        // and this row reaches that refusal THROUGH its recipe's first step.
        assertTrue(error.contains("Filter"),
            "the refusal must be the CONTRACT one, which names the supertype — the caller"
                + " refusal would name a call site instead: " + error);
        assertTrue(error.contains("reference_to_value"),
            "and it must be reported as this row's failure rather than the delegate's,"
                + " because the caller asked this row: " + error);
        assertEquals(before, Files.readString(filter, StandardCharsets.UTF_8),
            "upstream's file is byte-for-byte untouched");
    }
}
