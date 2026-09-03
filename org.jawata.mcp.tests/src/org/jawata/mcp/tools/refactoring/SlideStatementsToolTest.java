package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 62 — Slide Statements, in the declaration-to-first-use form.
 *
 * <p>The assertions are about ORDER, so they are written as positions rather than as
 * "contains". A test that only checked the declaration was still present would pass
 * whether or not it moved, which is the assertion this rewrite most needs.</p>
 */
class SlideStatementsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path target;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        target = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/SlideTargets.java");
    }

    private String rewritten() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "slide_declaration");
        args.put("filePath", target.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a declaration used three lines later moves down to sit beside its use")
    void theDeclarationSlidesToItsUse() throws Exception {
        String body = methodBody(rewritten(), "slidesDown");
        int declaration = body.indexOf("int area =");
        int spacing = body.indexOf("int spacing =");
        assertTrue(declaration >= 0 && spacing >= 0,
            "both declarations must survive — this moves code and removes none:\n" + body);
        assertTrue(declaration > spacing,
            "`area` is first used in the return, so its declaration belongs after the two"
                + " that are used before it. It is still above them:\n" + body);
    }

    @Test
    @DisplayName("a local reassigned in between would make the initializer compute something else")
    void aReassignmentInBetweenIsRefused() throws Exception {
        String body = methodBody(rewritten(), "reassignedInBetween");
        int declaration = body.indexOf("int area =");
        int reassign = body.indexOf("width = width * 3;");
        assertTrue(declaration >= 0 && reassign >= 0, "the fixture must be intact:\n" + body);
        assertTrue(declaration < reassign,
            "width is tripled in between. Sliding the declaration past that would compute"
                + " an area three times too large — a different program, not a"
                + " refactoring:\n" + body);
    }

    @Test
    @DisplayName("an initializer that reads a field is refused, without arguing about the call")
    void aFieldReadIsRefused() throws Exception {
        String body = methodBody(rewritten(), "readsAField");
        int declaration = body.indexOf("int scaled =");
        int call = body.indexOf("bumpScale();");
        assertTrue(declaration >= 0 && call >= 0, "the fixture must be intact:\n" + body);
        assertTrue(declaration < call,
            "the initializer reads a field and a call in between changes it. The rule"
                + " refuses on the field alone rather than trying to prove the call"
                + " harmless:\n" + body);
    }

    @Test
    @DisplayName("an initializer that calls something cannot move, because WHEN it runs matters")
    void aCallingInitializerIsRefused() throws Exception {
        String body = methodBody(rewritten(), "callsSomething");
        int declaration = body.indexOf("int computed =");
        int other = body.indexOf("int other =");
        assertTrue(declaration >= 0 && other >= 0, "the fixture must be intact:\n" + body);
        assertTrue(declaration < other,
            "compute() has a side effect. Sliding the declaration moves when it happens,"
                + " which is visible to everything that reads what it changed:\n" + body);
    }

    /** One method's source, stopping before the next member's javadoc. */
    private static String methodBody(String source, String methodName) {
        int start = source.indexOf(" " + methodName + "(");
        assertTrue(start >= 0, "method '" + methodName + "' is gone from the fixture");
        int end = source.length();
        for (String boundary : List.of("\n    /**", "\n    public ", "\n    private ")) {
            int next = source.indexOf(boundary, start);
            if (next >= 0) {
                end = Math.min(end, next);
            }
        }
        return source.substring(start, end);
    }
}
