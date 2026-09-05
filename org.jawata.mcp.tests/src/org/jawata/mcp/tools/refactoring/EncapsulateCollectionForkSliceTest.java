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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROW 9 (Encapsulate Collection) performed on code we did not author.
 *
 * <p>{@code WorkCenter.getWorkers()} is upstream's own accessor: hand-written, declared to
 * return {@code List<Worker>}, and handing the field straight back. It is exactly the shape
 * {@code mutable_data} reports and exactly the shape this row cures, and nobody wrote it for
 * either purpose — it is how the Leader/Followers demo shares its worker pool.</p>
 *
 * <p><b>ONE candidate across the whole vendored corpus, and that number is measured rather
 * than sampled.</b> All twelve slices were scanned for a non-private accessor whose body is a
 * bare {@code return field;} and whose declared return type is a collection interface; this is
 * the only one. That is a fact about a teaching repository — a pattern demo shares state
 * deliberately and briefly — rather than about the row, and it is written down so that a
 * reader can tell a thin demonstration from a missing one.</p>
 *
 * <p><b>Lombok is present in this module and does NOT disqualify it, which is the opposite of
 * the call made for row 54.</b> There, every field of upstream's {@code Character} sat under
 * class-level {@code @Getter}/{@code @Setter}, so the field's own readers were generated and
 * therefore invisible to both the reference search and the compile gate. Here the only Lombok
 * member is {@code @Getter} on the unrelated {@code leader} field; {@code workers} has no
 * generated accessor at all, and this operation reads and writes nothing else.</p>
 */
class EncapsulateCollectionForkSliceTest {

    private static final String PKG = "src/main/java/com/iluwatar/leaderfollowers";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path workCenter;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-leader-followers");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        workCenter = service.getProjectRoot().resolve(PKG).resolve("WorkCenter.java");
    }

    @Test
    @DisplayName("upstream's own shared worker pool stops being handed out")
    void performsOnUpstreamsWorkCenter() throws Exception {
        String[] lines = Files.readString(workCenter, StandardCharsets.UTF_8).split("\n", -1);
        int line = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("getWorkers(")) {
                line = i;
                break;
            }
        }
        assertTrue(line >= 0, "the vendored slice no longer declares getWorkers");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "encapsulate_collection");
        args.put("filePath", workCenter.toString());
        args.put("line", line);
        args.put("column", lines[line].indexOf("getWorkers"));

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "upstream's accessor is hand-written, returns the field bare"
            + " and is declared with the interface type — the shape this row performs on: "
            + r.getError());

        String after = Files.readString(workCenter, StandardCharsets.UTF_8);
        assertTrue(after.contains("return Collections.unmodifiableList(workers);"),
            "the view replaces the leak on UPSTREAM's own accessor — which is the whole"
                + " defect here, since the mutators were already written:\n" + after);

        // AND NOTHING ELSE IS ADDED, because upstream already took Fowler's second step.
        // Counting is the assertion: a `contains` check would pass on a file carrying two
        // addWorker declarations, which is exactly the outcome that does not compile.
        assertEquals(1, countOf(after, "public void addWorker("),
            "upstream's own addWorker must survive, alone — a second declaration of it is"
                + " the one outcome that would not compile:\n" + after);
        assertEquals(1, countOf(after, "public void removeWorker("),
            "and likewise its removeWorker:\n" + after);
        assertTrue(after.contains("public void addWorker(Worker worker) {"),
            "upstream's own signature and parameter name are untouched — this operation"
                + " added nothing to a class that had already done that half:\n" + after);
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
