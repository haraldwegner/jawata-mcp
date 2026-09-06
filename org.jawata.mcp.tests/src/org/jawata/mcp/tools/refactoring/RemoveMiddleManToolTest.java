package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.InlineTool;
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
 * Sprint 28d-rescue, row 36 — Remove Middle Man, through {@code inline}.
 *
 * <p>The fixture's third method is the control. {@code shoutedManager()} calls the same
 * delegate and then transforms the result, so it looks like a forwarder to anything that
 * only checks what it calls. It must survive: deleting it would change what its callers
 * get, and no compile error would say so — the class would simply be missing a method
 * nobody in the fixture calls.</p>
 */
class RemoveMiddleManToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private InlineTool tool;
    private ObjectMapper mapper;
    private Path pkg;
    private JdtServiceImpl service;
    private RefactoringChangeCache cache;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        // The SAME cache the tool writes its undo handle into.
        cache = new RefactoringChangeCache();
        tool = new InlineTool(() -> service, cache);
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private int lineOf(Path file, String marker) throws Exception {
        String[] lines = read(file).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: " + file.getFileName() + " no longer has "
            + marker);
    }

    private ToolResponse removeMiddleMan(Path file, String marker) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "middle_man");
        args.put("filePath", file.toString());
        args.put("line", lineOf(file, marker));
        args.put("column", 13);
        return tool.execute(args);
    }

    @Test
    @DisplayName("a `this.`-qualified forwarder to ONE of two delegate fields is removed by name")
    void theThisQualifiedForwarderToANamedFieldIsRemoved() throws Exception {
        // TWO FIXES THIS ROW IS CREDITED WITH, AND NOTHING EXERCISED EITHER — a C6 audit
        // checked and was right: reverting them both left the suite green. Both came from
        // the fork, and both are here now as a fixture the corpus cannot supply on demand.
        Path middleMan = pkg.resolve("TwoDelegateMiddleMan.java");
        Path user = pkg.resolve("TwoDelegateUser.java");
        assertTrue(read(middleMan).contains("return this.department.manager();"),
            "PROOF OF LIFE: the forwarder must be written with an explicit `this.`, which is"
                + " a FieldAccess over a ThisExpression and not the SimpleName receiver the"
                + " other fixture has:\n" + read(middleMan));

        // THE REFUSAL THAT SITS UNDER THIS CASE, and it had no test of its own until S8b
        // step 7. Omitting `delegateField` on a class that forwards to TWO fields must
        // decline rather than pick one — and D3a's answer here is deliberately NO next step.
        ObjectNode unnamed = mapper.createObjectNode();
        unnamed.put("kind", "middle_man");
        unnamed.put("filePath", middleMan.toString());
        unnamed.put("line", lineOf(middleMan, "public class TwoDelegateMiddleMan"));
        unnamed.put("column", 13);
        ToolResponse undecided = tool.execute(unnamed);
        assertFalse(undecided.isSuccess(),
            "two delegates and no name is an ambiguous request, not a narrower one");
        assertTrue(String.valueOf(undecided.getError()).contains("delegateField"),
            "and the refusal must name the value it needs: " + undecided.getError());
        // The step this refusal leaves is THIS operation again with one more value, and that
        // value is a decision only the caller can make — so a pointer would be an instruction
        // to repeat the call that just failed. The written reason is at the refusal site.
        org.junit.jupiter.api.Assertions.assertNull(undecided.getError().getNextStep(),
            "no next step: the missing input is a choice, not a smaller operation: "
                + undecided.getError());

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "middle_man");
        args.put("filePath", middleMan.toString());
        args.put("line", lineOf(middleMan, "public class TwoDelegateMiddleMan"));
        args.put("column", 13);
        // NAMING ONE OF TWO. Refusing a two-delegate class outright was the first version's
        // rule, and it was wrong: removing one middle man at a time is what Fowler does.
        args.put("delegateField", "department");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "a two-delegate class with the field named must run; got: "
            + r.getError());

        String after = read(middleMan);
        assertFalse(after.contains("public String manager()"),
            "the named field's forwarder is gone — so the `this.` receiver WAS recognised;"
                + " a version that only matched a bare name would have found no forwarder"
                + " here and removed nothing while reporting success:\n" + after);
        assertTrue(after.contains("public int balance()"),
            "and the OTHER field's forwarder stays, because it was not the one named:\n"
                + after);
        assertTrue(read(user).contains(".manager()"),
            "the caller still reaches the delegate's method:\n" + read(user));
    }

    @Test
    @DisplayName("the forwarders go, callers reach the delegate, and the transformer survives")
    void theForwardingStops() throws Exception {
        Path middleMan = pkg.resolve("MiddleManPerson.java");
        Path caller = pkg.resolve("MiddleManCaller.java");
        assertTrue(read(caller).contains("person.manager()"),
            "PROOF OF LIFE: the caller must go through the middle man before this runs");
        String before = read(middleMan);
        String beforeCaller = read(caller);

        ToolResponse r = removeMiddleMan(middleMan, "public class MiddleManPerson");
        assertTrue(r.isSuccess(), "the removal must run; got: " + r.getError());

        String after = read(middleMan);
        assertFalse(after.contains("public String manager()"),
            "the pure forwarders are gone:\n" + after);
        assertFalse(after.contains("public int headcount()"),
            "both of them:\n" + after);
        // THE CONTROL. It calls the same delegate and transforms the answer, so it is
        // behaviour rather than forwarding, and deleting it would silently change what its
        // callers receive.
        assertTrue(after.contains("public String shoutedManager()"),
            "and the method that TRANSFORMS the result survives:\n" + after);
        assertTrue(after.contains("public Department department()"),
            "an accessor was generated, which is the exposure this refactoring is:\n" + after);

        String afterCaller = read(caller);
        assertTrue(afterCaller.contains("person.department().manager()")
                && afterCaller.contains("person.department().headcount()"),
            "and every call site now reaches the delegate directly:\n" + afterCaller);

        // AND IT REVERTS, ACROSS BOTH FILES. This row moved out of Stage 2 into Stage 6,
        // and Stage 2's exit says "each recipe reverts through its single undo handle" —
        // a clause Stage 6's gate does not carry, so a C6 audit found the row judged
        // against the wrong bar. One handle must take back the middle man's deletions AND
        // the caller's rewrites; taking back half would leave callers reaching for methods
        // that no longer exist.
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) r.getData();
        ObjectNode undo = mapper.createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(data.get("undoChangeId")));
        ToolResponse reverted = new org.jawata.mcp.tools.RefactoringTool(
            () -> service, cache, new org.jawata.mcp.domain.NoOpAdvisor()).execute(undo);
        assertTrue(reverted.isSuccess(), "the undo must run; got: " + reverted.getError());
        assertEquals(before, read(middleMan), "the middle man is back:\n" + read(middleMan));
        assertEquals(beforeCaller, read(caller), "and so is its caller:\n" + read(caller));
    }

    @Test
    @DisplayName("a class with no forwarder at all is refused")
    void aClassThatForwardsNothingIsRefused() throws Exception {
        Path notAMiddleMan = pkg.resolve("Department.java");
        String before = read(notAMiddleMan);

        ToolResponse r = removeMiddleMan(notAMiddleMan, "public class Department");

        assertFalse(r.isSuccess(), "there is no middle man here");
        assertTrue(String.valueOf(r.getError()).contains("no middle man"),
            "and the refusal says so rather than reporting a no-op success: " + r.getError());
        assertEquals(before, read(notAMiddleMan), "nothing may change on a refusal");
    }
}
