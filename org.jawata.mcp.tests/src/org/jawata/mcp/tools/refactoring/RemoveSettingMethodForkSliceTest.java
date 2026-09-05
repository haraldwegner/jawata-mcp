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
 * <p>Over the fork's 1354 main sources there are <b>48</b> {@code public void set…}
 * declarations. Classified by body shape, <b>13</b> are the plain one-assignment kind these
 * two rows accept — one parameter, one statement, {@code f = p;} — and the rest validate, log,
 * switch, delegate, or take a number of parameters that is not one. The arity distribution of
 * the 48, measured: <b>34 take one, 12 take two, one takes three</b>
 * ({@code RegisterWorkerDto.setupWorkerDto}) <b>and one takes none</b>
 * ({@code App.setUp}). Row 37 refuses everything but the 13 at its shape check.</p>
 *
 * <p><b>This paragraph has now been wrong TWICE, and both corrections are kept rather than
 * overwritten, because the second is the more instructive.</b> The first version said every
 * plain setter is a WIRING setter and that "a teaching repository for design patterns is
 * largely assembly" — a narrative that fitted the conclusion. The correction replaced it with
 * a count of <em>fourteen</em> and a list of six examples, and a fresh auditor re-derived both:
 * the count is <em>thirteen</em>, and one of the six named examples —
 * {@code Worker.setReceivedData} — takes TWO parameters, so it is refused by the shape check
 * and was never in the accepted set at all. Correcting a false explanation with a
 * hand-assembled count is the same defect wearing better clothes.</p>
 *
 * <p>What survives, stated only as far as it was measured: of the thirteen, the ones examined
 * are ordinary value or state setters rather than collaborator wiring —
 * {@code SimpleMessage.setBody}, {@code AbstractInstance.setAlive},
 * {@code FileLoader.setFileName}, {@code Queen.setFlirtiness},
 * {@code BookViewModel.setSelectedBook} — and each is refused because the value is genuinely
 * still being changed after construction, which is this refactoring's own precondition. That
 * is a claim about the ones read, NOT about all thirteen; the row has no success-path
 * demonstration on foreign code either way.</p>
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
