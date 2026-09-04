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

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new InlineTool(() -> service, new RefactoringChangeCache());
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
    @DisplayName("the forwarders go, callers reach the delegate, and the transformer survives")
    void theForwardingStops() throws Exception {
        Path middleMan = pkg.resolve("MiddleManPerson.java");
        Path caller = pkg.resolve("MiddleManCaller.java");
        assertTrue(read(caller).contains("person.manager()"),
            "PROOF OF LIFE: the caller must go through the middle man before this runs");

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
