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
 * Stage 5, row 65 — Split Variable, reached as {@code data kind=split_variable}.
 *
 * <p>A variable assigned twice is two variables wearing one name. This gives the second its
 * own name and its own declaration, so each is assigned once — and from the same
 * implementation it performs Fowler's Remove Assignment to Parameter, which is the identical
 * cure applied to a parameter.</p>
 *
 * <p><b>The accumulator rule is asserted from BOTH sides, and that is the point of this
 * file.</b> An earlier version tested "the value reads the variable" and would have refused
 * {@code prefix = prefix.trim()} — Fowler's own parameter example. The condition is the LOOP;
 * self-reference merely correlates with it. So there is a case that reads itself and SPLITS,
 * beside a case in a loop that is REFUSED.</p>
 */
class SplitVariableToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        targets = service.getProjectRoot()
            .resolve("src/main/java/com/example/SplitVariableTargets.java");
    }

    /** The caret on the variable's name at the line that declares or assigns it. */
    private ToolResponse at(String marker, String variable, String newName) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "split_variable");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(variable));
                if (newName != null) {
                    args.put("newName", newName);
                }
                return tool.execute(args);
            }
        }
        throw new AssertionError("the fixture no longer contains: " + marker);
    }

    @Test
    @DisplayName("the canonical case: the second value gets its own name and declaration")
    void splitsALocalWithTwoMeanings() throws Exception {
        ToolResponse r = at("double temp = 2 * (height + width);", "temp", "area");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        // Exactly the fixture's own line, unchanged: this operation names the SECOND value
        // and leaves the first alone. Written as one form rather than a disjunction — an
        // `||` over "changed or unchanged" is a check that cannot fail.
        assertTrue(after.contains("double temp = 2 * (height + width);"),
            "the FIRST value keeps its declaration untouched — this operation names the"
                + " second, and renaming the first is `rename_symbol`:\n" + after);
        assertTrue(after.contains("System.out.println(temp);"),
            "and the use BEFORE the assignment still reads the first value — which is the"
                + " whole claim that these were two variables:\n" + after);
        assertTrue(after.contains("double area = height * width;"),
            "the assignment becomes a DECLARATION of the second value, typed and named:\n"
                + after);
        assertTrue(after.contains("return area;"),
            "and every use after it reads the new name:\n" + after);
    }

    @Test
    @DisplayName("the parameter case — and its value READS the parameter, which is not a loop")
    void removesAnAssignmentToAParameter() throws Exception {
        ToolResponse r = at("prefix = prefix.trim();", "prefix", "trimmed");
        assertTrue(r.isSuccess(), "`prefix = prefix.trim()` reads itself and still splits:"
            + " nothing loops, so there is one derivation and two meanings. An earlier rule"
            + " refused exactly this: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("String trimmed = prefix.trim();"),
            "the assignment to the parameter becomes a local declaration:\n" + after);
        assertTrue(after.contains("return trimmed + \"-\" + id;"),
            "and the later use reads the local rather than the parameter:\n" + after);
        assertTrue(after.contains("public String label(String prefix, int id) {"),
            "while the signature is untouched — this changes the body, not the contract:\n"
                + after);
    }

    @Test
    @DisplayName("a LOOP's OWN variable splits — the loop alone is not the accumulator test")
    void splitsALoopsOwnVariable() throws Exception {
        // THE PAIR TO refusesAnAccumulator, and the reason the rule is about WHERE the
        // variable is declared rather than about the loop. `word` is the for-each's own: it
        // is re-bound each iteration and carries nothing across, so splitting is safe.
        // Upstream's Mapper.map is exactly this, and a loop-only rule refused it.
        ToolResponse r = at("word = word.trim();", "word", "cleaned");
        assertTrue(r.isSuccess(), "a loop variable is re-bound each pass and accumulates"
            + " nothing, so the loop alone cannot be the test: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("String cleaned = word.trim();"),
            "the assignment becomes a declaration inside the loop body:\n" + after);
        assertTrue(after.contains("found = found + cleaned.length();"),
            "and the later use in the same iteration reads it:\n" + after);
    }

    @Test
    @DisplayName("REFUSES an assignment inside a LOOP — the accumulator Fowler excludes")
    void refusesAnAccumulator() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("sum = sum + value;", "sum", "running");
        assertFalse(r.isSuccess(), "each pass reads what the last left");
        assertTrue(String.valueOf(r.getError()).contains("inside a LOOP"),
            "the refusal must name the loop, which is the actual condition — not"
                + " self-reference, which merely correlates with it: " + r.getError());
        assertTrue(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "and a refusal must leave the file byte-for-byte untouched");
    }

    @Test
    @DisplayName("REFUSES an increment, which is accumulation by construction")
    void refusesAnIncrement() throws Exception {
        ToolResponse r = at("seen++;", "seen", "counted");
        assertFalse(r.isSuccess(), "`seen++` computes its new value from its old one");
        assertTrue(String.valueOf(r.getError()).contains("incremented"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES an assignment in a different block from the declaration")
    void refusesAConditionalAssignment() throws Exception {
        ToolResponse r = at("inputVal = inputVal - 2;", "inputVal", "reduced");
        assertFalse(r.isSuccess(), "a use reached without running the assignment would be"
            + " rewritten to a variable that was never declared");
        assertTrue(String.valueOf(r.getError()).contains("different block"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a variable assigned once — there is nothing to split")
    void refusesASingleAssignment() throws Exception {
        ToolResponse r = at("String greeting = \"hi \" + name;", "greeting", "other");
        assertFalse(r.isSuccess(), "one value, one meaning");
        assertTrue(String.valueOf(r.getError()).contains("never reassigned"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES three values, which need two names to a call that carries one")
    void refusesMultipleReassignments() throws Exception {
        ToolResponse r = at("int step = start;", "step", "second");
        assertFalse(r.isSuccess(), "two reassignments need two names");
        assertTrue(String.valueOf(r.getError()).contains("reassigned 2 times"),
            "the refusal must say HOW MANY it found, so a caller can see what it saw: "
                + r.getError());
    }

    @Test
    @DisplayName("REFUSES a missing newName — naming the second value IS the refactoring")
    void refusesAMissingName() throws Exception {
        ToolResponse r = at("double temp = 2 * (height + width);", "temp", null);
        assertFalse(r.isSuccess(), "there is no default, deliberately");
        assertTrue(String.valueOf(r.getError()).contains("newName is required"),
            "the refusal must name the parameter: " + r.getError());
    }

    @Test
    @DisplayName("the kind is routed and published by the door")
    void theDoorRoutesIt() {
        assertTrue(tool.delegates().containsKey("split_variable"),
            "row 65 must be reachable as data kind=split_variable");
        assertEquals("split_variable", tool.delegates().get("split_variable").kindName(),
            "the routing key and the delegate's own name are two spellings of one fact");
        assertTrue(tool.publishedKinds().contains("split_variable"),
            "and a client reading tools/list must see it — the enum is the routing table");
    }
}
