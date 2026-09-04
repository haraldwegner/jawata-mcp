package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 5 — Combine Functions into Class, through {@code extract}.
 *
 * <p>Which functions belong together is the caller's decision and no detector can make it,
 * so the tool's own job is to check that the grouping it was handed is REAL: same first
 * parameter type, all static. The fixture carries a third function that shares nothing, and
 * naming it alongside the others is the refusal test — without that check the tool would
 * pick a field from a type mismatch and emit code that compiles and is wrong, which is the
 * only failure this operation can have.</p>
 */
class CombineFunctionsIntoClassToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractTool tool;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ExtractTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private ToolResponse combine(List<String> functions, String newTypeName) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "combine_functions");
        args.put("filePath", pkg.resolve("ReadingFunctions.java").toString());
        args.put("newTypeName", newTypeName);
        ArrayNode names = args.putArray("functions");
        functions.forEach(names::add);
        return tool.execute(args);
    }

    @Test
    @DisplayName("the functions become a class holding the data, and callers construct it")
    void theFunctionsAreCombined() throws Exception {
        Path owner = pkg.resolve("ReadingFunctions.java");
        Path caller = pkg.resolve("ReadingCaller.java");
        assertTrue(read(caller).contains("ReadingFunctions.baseCharge(reading)"),
            "PROOF OF LIFE: the caller must go through the static function first");

        ToolResponse r = combine(List.of("baseCharge", "taxThreshold"), "ReadingCharge");
        assertTrue(r.isSuccess(), "the combine must run; got: " + r.getError());

        Path created = pkg.resolve("ReadingCharge.java");
        assertTrue(Files.exists(created), "the new class must be written to its own file");
        String type = read(created);
        assertTrue(type.contains("private final Reading reading;"),
            "holding the shared data as a field:\n" + type);
        assertTrue(type.contains("public int baseCharge()"),
            "with the first function's shared parameter gone from its signature:\n" + type);
        assertTrue(type.contains("public int taxThreshold(int allowance)"),
            "and the rest of the second function's parameters kept:\n" + type);

        String afterOwner = read(owner);
        assertFalse(afterOwner.contains("baseCharge"),
            "the functions left their old home:\n" + afterOwner);
        assertTrue(afterOwner.contains("unrelated"),
            "and the one that was not named is untouched:\n" + afterOwner);

        String afterCaller = read(caller);
        assertTrue(afterCaller.contains("new ReadingCharge(reading).baseCharge()"),
            "the call sites construct the new class:\n" + afterCaller);
        assertTrue(afterCaller.contains("new ReadingCharge(reading).taxThreshold(5)"),
            "keeping the arguments that were not the data:\n" + afterCaller);
    }

    @Test
    @DisplayName("functions that do not share a first parameter are refused, both named")
    void aFalseGroupingIsRefused() throws Exception {
        Path owner = pkg.resolve("ReadingFunctions.java");
        String before = read(owner);

        ToolResponse r = combine(List.of("baseCharge", "unrelated"), "NotAGroup");

        assertFalse(r.isSuccess(), "these two share no data, so there is no class to make");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("unrelated") && error.contains("baseCharge"),
            "and the refusal must name BOTH, since the mismatch is between them and either"
                + " one alone tells the caller nothing: " + error);
        assertEquals(before, read(owner), "nothing may change on a refusal");
        assertFalse(Files.exists(pkg.resolve("NotAGroup.java")),
            "and no file may be created on a refusal");
    }

    @Test
    @DisplayName("one function is refused — combining one thing is not this refactoring")
    void oneFunctionIsRefused() {
        ToolResponse r = combine(List.of("baseCharge"), "Solo");

        assertFalse(r.isSuccess(), "a class around one function is not Combine Functions");
        assertTrue(String.valueOf(r.getError()).contains("TWO"),
            "and the refusal says so: " + r.getError());
    }
}
