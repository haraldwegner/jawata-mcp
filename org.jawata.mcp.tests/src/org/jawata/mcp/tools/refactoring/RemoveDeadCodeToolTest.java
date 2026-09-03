package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.jawata.mcp.tools.FindUnusedCodeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 34 — Remove Dead Code.
 *
 * <p>Half of these cases assert that something is STILL THERE, and that half is the
 * reason the file changing is checked before anything else. If the rule silently found
 * no problems — which is exactly what happens when the unused warnings are not being
 * reported — the tool answers success, the file is untouched, and every survival case
 * passes while proving nothing. {@link #rewritten()} refuses that shape up front.</p>
 */
class RemoveDeadCodeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String RELATIVE = "src/main/java/com/example/DeadCodeTargets.java";

    private JdtServiceImpl service;
    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path target;
    private String before;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        target = service.allProjects().iterator().next().projectRoot().resolve(RELATIVE);
        before = Files.readString(target, StandardCharsets.UTF_8);
    }

    private String rewritten() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "remove_dead_code");
        args.put("filePath", target.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the cleanup must run; got: " + r.getError());
        String after = Files.readString(target, StandardCharsets.UTF_8);
        // PROOF OF LIFE. JDT reads the removals off the unit's compiler problems, so a
        // parse that does not REPORT unused members yields no fix, no edit, and an
        // untouched file — under which every "must survive" case below is vacuous.
        assertNotEquals(before, after,
            "the rule changed nothing, so it is not being asked the question it thinks it"
                + " is asking — check the forced compiler options and problem detection");
        return after;
    }

    @Test
    @DisplayName("private members nothing reaches are removed; the ones that are reached stay")
    void deadPrivateMembersGo() throws Exception {
        String after = rewritten();

        assertFalse(after.contains("obsolete"), "an unused private method goes:\n" + after);
        assertFalse(after.contains("neverRead"), "an unused private field goes:\n" + after);
        assertFalse(after.contains("NeverUsed"), "an unused private type goes:\n" + after);

        assertTrue(after.contains("private String kept"),
            "note() reads it through used(), so it is not dead:\n" + after);
        assertTrue(after.contains("public String note()"), "public API is never touched:\n" + after);
        assertTrue(after.contains("calls++"), "calls is read and written, so it stays:\n" + after);
    }

    @Test
    @DisplayName("an unused local goes, but a side effect in its initializer survives as a statement")
    void anUnusedLocalKeepsItsSideEffect() throws Exception {
        String body = methodBody(rewritten(), "locals");

        assertFalse(body.contains("int plain"),
            "nothing happens on the right-hand side, so the whole declaration goes:\n" + body);
        assertFalse(body.contains("int fromCall"),
            "the variable itself is dead either way:\n" + body);
        // THE PROPERTY. Removing the declaration and its initializer together would drop a
        // call the method was making — the code still compiles, the suite stays green, and
        // the work simply stops happening.
        assertTrue(body.contains("compute();"),
            "the call must survive as a statement; deleting it with the variable removes"
                + " work the method did:\n" + body);
    }

    @Test
    @DisplayName("the unused import survives — organize_imports owns imports, and this refusal is real work")
    void theUnusedImportIsRefused() throws Exception {
        // MEASURED: the parse DOES raise "The import java.util.ArrayList is never used".
        // JDT would remove it, and passing removeUnusedImports=false is the only thing
        // stopping it — so unlike the boundary case below, this assertion has a live
        // control. Flip that flag to true and this test goes red.
        assertTrue(rewritten().contains("import java.util.ArrayList;"),
            "an import vanishing from a sweep named for dead code is a change the caller"
                + " did not ask for, and organize_imports is where it belongs");
    }

    @Test
    @DisplayName("casts and signatures are outside this row, and stay untouched")
    void castsAndSignaturesAreOutOfScope() throws Exception {
        String after = rewritten();
        // These two are NOT refused by a flag doing work: the rule enables only the
        // unused-member and unused-local warnings, so no cast or parameter problem is
        // ever raised and nothing proposes either removal. The flags guarding them are a
        // standing guard for a project that turns those warnings on, not an active one
        // here. What this case pins is the row's BOUNDARY, whatever enforces it.
        assertTrue(after.contains("(String) s"),
            "an unnecessary cast is not unreachable code:\n" + after);
        assertTrue(after.contains("int ignored"),
            "the parameter is read nowhere, but dropping it is a signature change:\n" + after);
        assertTrue(after.contains("used(\"n\", 1)"),
            "and the method keeps its name — JDT renames on overload collision when it"
                + " drops a parameter, which is a surprise no cleanup should spring:\n" + after);
    }

    @Test
    @DisplayName("every finding the unused detector reports is answered by this cure")
    @SuppressWarnings("unchecked")
    void theDetectorsFindingsAreAnswered() throws Exception {
        // The detector and the cure compute "unused" independently — ours by walking
        // bindings, JDT's from the compiler's own problems. Routing from the finding to
        // this kind is part of the row's contract, so the two must agree on this file.
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", RELATIVE);
        ToolResponse found = new FindUnusedCodeTool(() -> service).execute(args);
        assertTrue(found.isSuccess(), "the detector must run; got: " + found.getError());

        Map<String, Object> data = (Map<String, Object>) found.getData();
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("unusedItems");
        assertTrue(items != null && !items.isEmpty(),
            "PROOF OF LIFE: the detector must name something on this fixture, or the loop"
                + " below asserts nothing at all");

        String after = rewritten();
        for (Map<String, Object> item : items) {
            String name = String.valueOf(item.get("name"));
            assertFalse(after.contains(name),
                "the detector reported '" + name + "' as unused, and the cure that routes"
                    + " from that finding left it in place:\n" + after);
        }
    }

    /** One method's source, stopping before the next member's javadoc or declaration. */
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
