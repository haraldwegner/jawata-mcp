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
 * Rows 37 and 2 on CODE WE DID NOT AUTHOR — and this is a MEASURED ABSENCE, pinned.
 *
 * <h2>The census, and what it says</h2>
 *
 * <p>Over the fork's 1354 main sources, 29 classes declare 43 public setters. Classified by
 * body shape, roughly a dozen are the plain one-assignment kind these two rows accept; the
 * rest validate, log, switch or delegate, and row 37 refuses those as not setting methods.
 * Every plain one is a WIRING setter — {@code setPresenter}, {@code setLoader},
 * {@code setNext}, {@code setFilterManager}, {@code setFlirtiness} — called by an assembler
 * after construction.</p>
 *
 * <p><b>That is not a gap in the rows; it is what the corpus is.</b> Remove Setting Method
 * applies where a field should be settled at construction, and a wiring setter is the exact
 * opposite: it exists so a collaborator can be attached later. A teaching repository for
 * design patterns is largely assembly, so the shape these rows cure is rare in it and the
 * shape they refuse is everywhere. Row 45 recorded the mirror image of this for its own
 * subject.</p>
 *
 * <p>So there is no success-path demonstration on foreign code, and the absence is stated
 * rather than left looking like an oversight. What IS demonstrated is the refusal, on a real
 * upstream wiring setter, which is evidence rather than a demonstration — and it PINS the
 * census: if upstream ever grows a setter this row would perform on, or this one stops being
 * called from outside, the assertion below stops holding and someone re-reads this note.</p>
 */
class RemoveSettingMethodForkSliceTest {

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
    @DisplayName("upstream's wiring setter is refused, the outside caller is named, and the "
        + "file is untouched")
    void refusesUpstreamsWiringSetter() throws Exception {
        String before = Files.readString(filter, StandardCharsets.UTF_8);
        String[] lines = before.split("\n", -1);
        int line = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("public void setNext(")) {
                line = i;
                break;
            }
        }
        assertTrue(line >= 0, "the vendored slice no longer declares setNext");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "remove_setting_method");
        args.put("filePath", filter.toString());
        args.put("line", line);
        args.put("column", lines[line].indexOf("setNext"));

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "a setter called after construction is how the chain is built; removing it would"
                + " take away the wiring");
        String error = String.valueOf(r.getError());
        // The CALLER refusal specifically, not merely some refusal. setNext IS a plain
        // one-assignment setter, so the shape check passes and this is the branch that must
        // fire — which is what makes the assertion about this row rather than about any
        // decline.
        assertTrue(error.contains("outside"),
            "it must be the caller refusal, since the body shape is acceptable: " + error);
        assertEquals(before, Files.readString(filter, StandardCharsets.UTF_8),
            "a refusal must leave upstream's file byte-for-byte untouched");
    }
}
