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
 * Row 2, Change Reference to Value, on CODE WE DID NOT AUTHOR — and what it proves is that
 * the recipe DECLINES AT ITS FIRST STEP, for the reason it names, having written nothing.
 *
 * <p>The census in {@link RemoveSettingMethodForkSliceTest} covers this row too: the fork's
 * plain setters are wiring setters, so a class whose every setter this row could remove does
 * not occur, and there is no success-path demonstration on foreign code. That absence is
 * stated there and not repeated here.</p>
 *
 * <p><b>THIS FILE DOES NOT PROVE THE ROLLBACK, and said it did until a C2 re-audit read the
 * two together.</b> Step 1 throws before it returns a change, so {@code RecipeEngine.run}
 * rolls back an EMPTY undo list and nothing was ever written — which makes the
 * byte-for-byte comparison below true of the pristine fixture, and true whatever the engine
 * does afterwards. It is kept because an untouched file is still the outcome a caller needs,
 * and it is labelled for what it is rather than promoted to evidence it cannot carry. A
 * rollback that has something to undo needs a recipe whose SECOND step declines; no test in
 * this suite constructs one, and that is recorded at C2 rather than papered over here.</p>
 *
 * <p><b>Which refusal fires, and why the needle is what it is.</b> Upstream's
 * {@code setNext} satisfies two of this row's preconditions at once — it implements
 * {@code Filter.setNext} AND is called from {@code FilterChain} — so an assertion that only
 * checked "it declined" would pass whichever branch answered. The first needle here was
 * {@code "Filter"}, which BOTH refusals print: the caller refusal interpolates the declaring
 * type, and that type is {@code AbstractFilter}. So the branch under test was never pinned.
 * The needle is now wording only the contract branch emits.</p>
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
        // WHICH REFUSAL, not merely that one fired — and the needle has to come from words
        // only the contract branch emits. `setNext` satisfies two of this row's preconditions
        // at once, so a test that checked "it declined" would pass whichever branch answered.
        // `contains("Filter")` was that test wearing a discriminator's clothes: the CALLED-
        // FROM-OUTSIDE refusal prints the declaring type, which is `AbstractFilter`, and its
        // outsider list names `FilterChain` — so it printed the needle too, and deleting the
        // contract branch left this green. Row 2 cannot assert the reason CODE the sibling
        // RemoveSettingMethodForkSliceTest uses, because the recipe stringifies its step's
        // refusal (ChangeReferenceToValueTool) and the enum does not survive.
        assertTrue(error.contains("part of a contract this class declares"),
            "the refusal must be the CONTRACT one — this wording is emitted by that branch"
                + " alone: " + error);
        assertFalse(error.contains("is called from outside"),
            "and it must NOT be the caller refusal, which is equally true of this method and"
                + " which the previous needle could not tell apart: " + error);
        assertTrue(error.contains("reference_to_value"),
            "and it must be reported as this row's failure rather than the delegate's,"
                + " because the caller asked this row: " + error);
        assertEquals(before, Files.readString(filter, StandardCharsets.UTF_8),
            "upstream's file is byte-for-byte untouched");
    }
}
