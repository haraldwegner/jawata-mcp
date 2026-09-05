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
 * Stage 5, row 45 — Replace Derived Variable with Query, as {@code data
 * kind=replace_derived_variable}.
 *
 * <p>A field always recomputed from other fields is an answer being cached, and its cost is
 * that it can go stale. Deleting it and computing on demand removes the class of bug rather
 * than an instance.</p>
 *
 * <p><b>The safety argument is the writers' AGREEMENT, and this file asserts it from both
 * sides.</b> Two writers assigning the same expression means the field's value is that
 * expression at all times, so a query is indistinguishable from reading it; two that disagree
 * mean it is not derived from one rule at all. There is a case for each, and the disagreeing
 * one checks that the refusal QUOTES what it found rather than merely declining.</p>
 */
class ReplaceDerivedVariableWithQueryToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path targets;
    private Path user;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("DerivedVariableTargets.java");
        user = pkg.resolve("DerivedVariableUser.java");
    }

    private ToolResponse at(String declaration, String fieldName) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "replace_derived_variable");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(fieldName));
                return tool.execute(args);
            }
        }
        throw new AssertionError("the fixture no longer declares: " + declaration);
    }

    @Test
    @DisplayName("the canonical case: the field and both writers go, a query takes their place")
    void replacesADerivedField() throws Exception {
        ToolResponse r = at("private int total;", "total");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("private int total() {"),
            "the query replaces the field and keeps its visibility:\n" + after);
        assertTrue(after.contains("return itemCount * unitPrice;"),
            "returning the rule every writer agreed on:\n" + after);
        assertFalse(after.contains("private int total;"),
            "the FIELD must be gone — leaving it is the stale value this row removes:\n"
                + after);
        assertFalse(after.contains("this.total = itemCount * unitPrice;"),
            "and so must every assignment that kept it up to date:\n" + after);
        assertTrue(after.contains("return \"due \" + total();"),
            "while the reader now asks the question:\n" + after);
    }

    @Test
    @DisplayName("a public derived field is rewritten in the OTHER file too, keeping visibility")
    void rewritesReadersInOtherFiles() throws Exception {
        ToolResponse r = at("public int grossAmount;", "grossAmount");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("public int grossAmount() {"),
            "a PUBLIC field becomes a PUBLIC query, or a reader outside loses access:\n"
                + after);

        String other = Files.readString(user, StandardCharsets.UTF_8);
        assertTrue(other.contains("return \"gross \" + invoice.grossAmount();"),
            "and the read in a file this call was never pointed at becomes a call, or the"
                + " deletion leaves it reading something gone:\n" + other);
    }

    @Test
    @DisplayName("REFUSES writers that disagree, and QUOTES what it found")
    void refusesDisagreeingWriters() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("private int summary;", "summary");
        assertFalse(r.isSuccess(), "two rules mean it is not derived from one");
        assertTrue(String.valueOf(r.getError()).contains("do not agree"),
            "the refusal must name that reason: " + r.getError());
        // QUOTING is the part that matters: a caller told only "they disagree" has to go
        // and find the writers themselves, which is the work the tool just did.
        assertTrue(String.valueOf(r.getError()).contains("rows * 2")
                && String.valueOf(r.getError()).contains("rows * 3"),
            "and it must quote BOTH rules, so the disagreement can be seen rather than"
                + " taken on trust: " + r.getError());
        assertTrue(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "a refusal must leave the file byte-for-byte untouched");
    }

    @Test
    @DisplayName("REFUSES a value derived from a PARAMETER, naming the row that does apply")
    void refusesAParameterDerivedField() throws Exception {
        ToolResponse r = at("private int amount;", "amount");
        assertFalse(r.isSuccess(), "a no-argument query cannot see a parameter");
        assertTrue(String.valueOf(r.getError()).contains("local or a parameter"),
            "the refusal must name that reason: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("Replace Query with Parameter"),
            "and point at the refactoring that DOES apply, rather than leaving the caller"
                + " with a decline: " + r.getError());
    }

    @Test
    @DisplayName("EVERY writer is checked for parameters, not just the first")
    void checksEveryWriterForOutsideNames() throws Exception {
        // THE SHADOWING PAIR, and the reason the check runs over all writers. `Ledger` has a
        // method whose expression reads FIELDS and a constructor whose textually IDENTICAL
        // expression reads its own PARAMETERS. Text-equal, meaning-different — so the
        // agreement check above passes and only a per-writer scan catches it. An earlier
        // version scanned the first writer alone, and which one that was depended on the
        // order the search returned.
        ToolResponse r = at("private int balance;", "balance");
        assertFalse(r.isSuccess(), "one of the two writers computes from its parameters,"
            + " so a no-argument query cannot stand in for the field");
        assertTrue(String.valueOf(r.getError()).contains("local or a parameter"),
            "and the refusal must name that, not the agreement — the two expressions DO"
                + " agree textually, which is exactly the trap: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a field nothing assigns — that is ordinary state")
    void refusesAnUnwrittenField() throws Exception {
        ToolResponse r = at("private int limit = 10;", "limit");
        assertFalse(r.isSuccess(), "nothing derives it");
        assertTrue(String.valueOf(r.getError()).contains("not derived from anything"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("the kind is routed and published by the door")
    void theDoorRoutesIt() {
        assertTrue(tool.delegates().containsKey("replace_derived_variable"),
            "row 45 must be reachable as data kind=replace_derived_variable");
        assertEquals("replace_derived_variable",
            tool.delegates().get("replace_derived_variable").kindName(),
            "the routing key and the delegate's own name are two spellings of one fact");
        assertTrue(tool.publishedKinds().contains("replace_derived_variable"),
            "and a client reading tools/list must see it — the enum is the routing table");
    }
}
