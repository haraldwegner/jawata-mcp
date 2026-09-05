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
 * body shape, <b>14</b> are the plain one-assignment kind these two rows accept; the rest
 * validate, log, switch or delegate, and row 37 refuses those as not setting methods.</p>
 *
 * <p><b>An earlier version of this note gave a FALSE reason for the absence, and it is
 * corrected rather than replaced.</b> It said every plain setter is a WIRING setter and that
 * "a teaching repository for design patterns is largely assembly". Counted: at least six of
 * the fourteen are ordinary value or state setters — {@code SimpleMessage.setBody},
 * {@code AbstractInstance.setAlive}, {@code FileLoader.setFileName},
 * {@code Queen.setFlirtiness}, {@code Worker.setReceivedData},
 * {@code BookViewModel.setSelectedBook}. The CONCLUSION survives and the explanation does
 * not: all fourteen are refused because each has at least one call site outside its declaring
 * class's constructors — that is, because the value is genuinely still being changed after
 * construction, which is this refactoring's own precondition. A narrative that fits the
 * conclusion is not evidence for it.</p>
 *
 * <p>So there is no success-path demonstration on foreign code, and the absence is stated
 * rather than left looking like an oversight. What IS demonstrated is a refusal on real
 * upstream code, which is evidence rather than a demonstration.</p>
 *
 * <h2>WHICH refusal this pins CHANGED on 2026-09-05, and the change is the finding</h2>
 *
 * <p>It used to assert the CALLER refusal on {@code AbstractFilter.setNext}. It now asserts
 * the interface-contract refusal, because {@code AbstractFilter implements Filter} and
 * {@code Filter} declares {@code setNext} — a nearer precondition, which fires first. That
 * branch could not fire at all until the same day: the supertype lookup it reads answered
 * with superclasses ONLY, so a setter implementing an interface method looked to it like a
 * setter implementing nothing. The published refusal existed, was documented, and was
 * unreachable for the commonest way a Java method is part of a contract.</p>
 *
 * <p>Upstream's caller fact is still true — {@code setNext} is called by {@code FilterChain}
 * from outside — but this assertion no longer measures it, and saying so is the point: a
 * reader who took the old sentence at face value would believe a caller census is pinned
 * here when what is pinned is the class's interface.</p>
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
    @DisplayName("upstream's setter is refused because it implements an INTERFACE the class "
        + "declares, and the file is untouched")
    void refusesUpstreamsInterfaceSetter() throws Exception {
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
            "setNext satisfies Filter, so removing it would stop AbstractFilter's subclasses"
                + " being usable as the interface they are declared to be");
        String error = String.valueOf(r.getError());
        // THE INTERFACE BRANCH SPECIFICALLY, not merely some refusal. setNext is a plain
        // one-assignment setter, so the shape check passes, and it IS also called from
        // outside — so naming the supertype is what tells the interface branch apart from
        // the caller branch that would otherwise fire next.
        assertTrue(error.contains("overrides") && error.contains("Filter"),
            "it must be the CONTRACT refusal, naming the supertype it belongs to: " + error);
        assertFalse(error.contains("outside"),
            "and it must be that one rather than the caller refusal — both are true of this"
                + " method, and only the nearer one is what this test claims: " + error);
        assertEquals(before, Files.readString(filter, StandardCharsets.UTF_8),
            "a refusal must leave upstream's file byte-for-byte untouched");
    }
}
