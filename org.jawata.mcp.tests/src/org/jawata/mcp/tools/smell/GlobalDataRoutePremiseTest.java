package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.DataTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PREMISE THE {@code global_data} ROUTE RESTS ON, pinned so it cannot rot.
 *
 * <p>{@code global_data} reports static mutable state, and its cure is
 * {@code data kind=encapsulate_field} — the operation the detector's own message asks for
 * (<i>"Consider Encapsulate Variable — put it behind a function so the writers can be
 * counted"</i>). That route is only honest if the operation actually accepts a STATIC field,
 * and nothing established that: {@code EncapsulateFieldTool} imposes no static refusal of its
 * own, but the JDT engine underneath might, and an architect watch named this explicitly as
 * the one thing its proposal rested on and could not settle by reading.</p>
 *
 * <p><b>Why this is a test rather than a note recording a probe.</b> A C3 audit refused this
 * sprint once for exactly that: a sound measurement whose fixture and probe were scratch and
 * never committed, leaving <i>"the measurement is sound; the evidence is not re-checkable,
 * which makes it a claim"</i>. If the engine ever starts refusing static fields, the
 * {@code global_data} route becomes an instruction that cannot run — and this goes red the
 * same day instead of a reader finding out.</p>
 *
 * <p><b>The sibling cure refuses this case BY NAME, which is what makes the question live.</b>
 * {@code data kind=encapsulate_collection} (row 9) declines a static field, saying so: <i>"Static
 * mutable state is the global_data kind rather than this one… curing it here would fix it under
 * one name and leave it reported under the other."</i> So one of the door's two encapsulation
 * kinds definitely will not take this input, and if the other will not either, the smell has no
 * runnable cure at all and its written reason would have to say so.</p>
 */
class GlobalDataRoutePremiseTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new DataTool(() -> service, new RefactoringChangeCache());
        targets = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/GlobalDataTargets.java");
    }

    @Test
    @DisplayName("encapsulate_field accepts a public static field — the global_data cure runs")
    void encapsulateFieldAcceptsAStaticField() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String[] lines = before.split("\n", -1);
        int line = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("public static int mutableCounter")) {
                line = i;
                break;
            }
        }
        assertTrue(line >= 0,
            "PROOF OF LIFE: the fixture no longer declares the public static field this"
                + " premise is about, so nothing below would be measuring it");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "encapsulate_field");
        args.put("filePath", targets.toString());
        args.put("line", line);
        args.put("column", lines[line].indexOf("mutableCounter"));

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(),
            "THE ROUTE'S PREMISE. `global_data` reports static mutable state and instructs a"
                + " reader to run this operation on it. If the engine declines a static field,"
                + " that instruction cannot run and the cure table is telling people to do"
                + " something the product refuses — so this failing means the ROUTE is wrong,"
                + " not this test. Refused with: "
                + (r.getError() != null
                    ? r.getError().getCode() + " / " + r.getError().getMessage()
                    : "(no error info)"));

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("mutableCounter") && !after.equals(before),
            "and it must actually rewrite the file — a success that changed nothing would"
                + " satisfy the assertion above while leaving the field exactly as exposed"
                + " as the finding said it was");
    }
}
