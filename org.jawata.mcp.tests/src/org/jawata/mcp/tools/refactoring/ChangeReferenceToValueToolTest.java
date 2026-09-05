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
 * Stage 5, row 2 — Change Reference to Value, as {@code data kind=reference_to_value}.
 *
 * <p>A value is exactly two properties, and the row installs both: it cannot change after it
 * is made, and two carrying the same data are the same. This file asserts BOTH on the same
 * run, because either alone is worse than neither — an immutable class still compared by
 * identity can be neither changed nor compared.</p>
 *
 * <p>The refusal that matters is a class that already declares its own equality. That was
 * somebody's decision about what the class means, and the generator would merely skip it with
 * a warning, leaving the row reporting success while its headline claim was half-done.</p>
 */
class ChangeReferenceToValueToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new DataTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        targets = service.getProjectRoot()
            .resolve("src/main/java/com/example/ReferenceToValueTargets.java");
    }

    private ToolResponse at(String declaration, String className) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(declaration)) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "reference_to_value");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(className));
                return tool.execute(args);
            }
        }
        throw new AssertionError("the fixture no longer declares: " + declaration);
    }

    @Test
    @DisplayName("both halves land on one run: every setter goes AND the class gains its "
        + "value equality")
    void makesAClassAValue() throws Exception {
        ToolResponse r = at("public static class Money", "Money");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        // COUNTED, because the decoy sibling declares the same setters: the file holds two of
        // each before the run and must hold the decoy's alone after it.
        // These say ONE of the two Moneys lost each setter, not which — the decoy test next
        // door is what pins that, and saying so here keeps the message inside what the
        // assertion can see.
        assertEquals(1, occurrences(after, "public void setCurrency(String currency)"),
            "one of the two setters of this name must be gone:\n" + after);
        assertEquals(1, occurrences(after, "public void setAmount(long amount)"),
            "and the SECOND — a row that removed only one would still report success:\n"
                + after);
        assertTrue(after.contains("this.currency = currency;")
                && after.contains("this.amount = amount;"),
            "each constructor call becomes a direct assignment, or the fields are never"
                + " set:\n" + after);
        // COUNTED, NOT CONTAINED. The fixture's Ticket already declares equals and hashCode —
        // it is the refusal case — so `contains(...)` is true of the pristine file and passes
        // with the generation step deleted. An audit proved exactly that by stubbing the
        // generate step out: five of five green with the row's advertised second half gone.
        assertEquals(2, occurrences(after, "public boolean equals(Object"),
            "the file must now declare equality TWICE: Ticket's own, and the one this row"
                + " generated. One means nothing was generated:\n" + after);
        assertEquals(2, occurrences(after, "public int hashCode()"),
            "with the hash that has to travel with it, or it breaks every hash-based"
                + " collection:\n" + after);
    }

    /** How many times a needle occurs — a containment check cannot see a SECOND one. */
    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("REFUSES a class that already declares its own identity")
    void refusesAClassThatDecidedItsIdentity() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("public static class Ticket", "Ticket");
        assertFalse(r.isSuccess(),
            "a hand-written equality is a decision about what the class MEANS, and the"
                + " generator would only skip it with a warning");
        assertTrue(String.valueOf(r.getError()).contains("already declares"),
            "the refusal must name that reason: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES auto_apply=false, because a recipe has no single change to preview")
    void refusesToStageBecauseItIsARecipe() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String[] lines = before.split("\n", -1);
        ToolResponse r = null;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("public static class Money")) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "reference_to_value");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf("Money"));
                args.put("auto_apply", false);
                r = tool.execute(args);
                break;
            }
        }
        assertFalse(r == null, "the fixture no longer declares Money");
        assertFalse(r.isSuccess(),
            "equals/hashCode is generated against the file the removals left, so there is no"
                + " staged change to show before they have run");
        assertTrue(String.valueOf(r.getError()).contains("COMPOSED"),
            "the refusal must say WHY, and point at the halves a caller can stage: "
                + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("the ONE undo handle reverts the removals AND the generated equality")
    void undoRevertsEveryStep() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("public static class Money", "Money");
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object data = r.getData();
        assertTrue(data instanceof java.util.Map, "expected a data map, got: " + data);
        Object handle = ((java.util.Map<?, ?>) data).get("undoChangeId");
        assertTrue(handle != null, "the recipe must hand back ONE undo handle: " + data);

        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        ToolResponse undone = lifecycle.execute(undo);
        assertTrue(undone.isSuccess(), "got: " + undone.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "one handle must revert BOTH halves — a recipe that undid the generation and"
                + " left the setters removed would leave a class in neither state");
    }

    /**
     * THE DECOY IS A PLAUSIBLE TARGET, and the first version of it was not.
     *
     * <p>{@code Legacy.Money} is declared FIRST, shares the target's simple name, and now
     * declares the SAME MEMBERS. That last part is what makes this a control: with a decoy that
     * lacked them, the by-name defect threw ("could not find setter") and the row reported
     * failure — loud, and the lucky case. With a plausible decoy the defect rewrites the wrong
     * class and reports SUCCESS, which is the shape that actually ships.</p>
     *
     * <p>The two classes now share every MEMBER a string assertion would reach for, so a
     * containment check cannot say which one was acted on. The assertion is positional instead:
     * after a correct run the surviving {@code setCurrency} is the DECOY's, declared above the
     * real class; after a wrong one it is the real class's, below it.</p>
     *
     * <p><b>THE ANCHOR IS THE DECOY'S VISIBILITY, and this sentence has now been wrong twice
     * about it.</b> The landmark is {@code public static class Money}: the decoy's class is
     * declared package-private, so that needle resolves to the real class and nothing else.
     * Drop {@code public} from it and it resolves to the decoy instead — measured. The first
     * version of this paragraph said the two classes were text-identical, which was false; the
     * second said they differ only in the constructor's visibility and the indentation, "not
     * something an assertion should lean on" — also false, because it named the two differences
     * that carry nothing and omitted the one every assertion here depends on.</p>
     */
    @Test
    @DisplayName("acts on the class it was pointed at, not on a SIBLING of the same simple name")
    void leavesTheDecoySiblingUntouched() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        assertEquals(2, occurrences(before, "public void setCurrency(String currency)"),
            "the fixture must declare the setter TWICE — once per Money — or this control has"
                + " stopped discriminating:\n" + before);

        ToolResponse r = at("public static class Money", "Money");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertEquals(1, occurrences(after, "public void setCurrency(String currency)"),
            "exactly one Money must have lost its setter:\n" + after);
        int survivor = after.indexOf("public void setCurrency(String currency)");
        int realClassAt = after.indexOf("public static class Money");
        assertTrue(survivor < realClassAt,
            "the surviving setter must be the DECOY's, which is declared above the class this"
                + " test pointed at. Below it means the row rewrote the sibling and reported"
                + " success:\n" + after);
        // COUNTING says one equality was generated, and NOT which class got it: a decoy that
        // gained one also yields 2 (Ticket's plus the decoy's). The positional assertion below
        // is the one that discriminates; an auditor proved this one passes while the equality
        // sits in the decoy.
        assertEquals(2, occurrences(after, "public boolean equals(Object"),
            "exactly one equality was generated, beside Ticket's own:\n" + after);
        // WHERE the generated equality landed, and this assertion is the one that catches the
        // generate step alone. The setter steps address their setter by its own handle, so they
        // reach the right method even when the TYPE key is wrong; only the generate step reads
        // the type. Measured: with the type key reverted to a simple name, every other
        // assertion here still passed while equals/hashCode were written into the decoy.
        assertTrue(after.indexOf("public boolean equals(Object") > realClassAt,
            "the FIRST equality in the file must be the real Money's, which is declared after"
                + " the decoy. Before it means the generation landed on the sibling:\n" + after);
    }

    @Test
    @DisplayName("removes BOTH 1-arg setters that share one name, each on its own field")
    void removesBothOverloadsOfOneName() throws Exception {
        ToolResponse r = at("public static class Seat", "Seat");
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertFalse(after.contains("public void setPlace(String label)"),
            "the String overload must be gone:\n" + after);
        // A CASE, NOT A CONTROL, and it is labelled because a mutation said so: restoring the
        // name + arity lookup leaves this green, because each step removes its setter and by
        // the second step only the other overload is left. The handle key is right by identity
        // rather than by luck, but this test does not prove it — the sibling-class test does.
        assertFalse(after.contains("public void setPlace(int number)"),
            "and so must the int one — a row that removed one overload and reported success"
                + " would leave half a value object:\n" + after);
        assertTrue(after.contains("this.label = label;") && after.contains("this.number = number;"),
            "each constructor call becomes the assignment of ITS OWN field:\n" + after);
    }

    @Test
    @DisplayName("runs from the class's TYPE NAME, with no file position given")
    void runsFromItsTypeName() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "reference_to_value");
        args.put("typeName", "com.example.ReferenceToValueTargets.Money");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        String after = Files.readString(targets, StandardCharsets.UTF_8);
        // NOT `contains("public boolean equals(Object")` — Ticket declares that in the pristine
        // fixture, so the assertion was true before the run and proved nothing about where the
        // name form landed. These two are true only of Money, and only after this row.
        assertEquals(1, occurrences(after, "public void setCurrency(String currency)"),
            "the name form must reach the same class the caret does — one of the two Moneys"
                + " loses its setter only if it did:\n" + after);
        assertEquals(2, occurrences(after, "public boolean equals(Object"),
            "and Money gained its own equality beside Ticket's:\n" + after);
    }
}
