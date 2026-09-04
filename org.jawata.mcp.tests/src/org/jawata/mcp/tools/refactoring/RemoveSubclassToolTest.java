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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, row 38 — Remove Subclass, through the {@code inline} front door.
 *
 * <p>The operation performs one half of Fowler's treatment and refuses the other, so the
 * refusals are where the behaviour actually lives. Two of them are exercised here against
 * fixtures built to carry exactly one distinction each: a subclass that overrides, and a
 * subclass whose constructor fixes an argument. Both look identical to the happy-path
 * fixture apart from that one difference, which is what makes them controls rather than
 * decoration — if the checks were absent, the same call would fold them away.</p>
 */
class RemoveSubclassToolTest {

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

    /** The source with its comment lines dropped, so a prose mention is not read as code. */
    private static String codeOf(String source) {
        StringBuilder code = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("//") && !trimmed.startsWith("/*")
                    && !trimmed.startsWith("*")) {
                code.append(line).append('\n');
            }
        }
        return code.toString();
    }

    private ToolResponse removeSubclass(Path file, String marker, int column) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "subclass");
        args.put("filePath", file.toString());
        args.put("line", lineOf(file, marker));
        args.put("column", column);
        return tool.execute(args);
    }

    @Test
    @DisplayName("a subclass carrying no distinction folds into its parent and its users repoint")
    void theSubclassIsFoldedIn() throws Exception {
        Path subclass = pkg.resolve("PlainCharge.java");
        Path parent = pkg.resolve("Charge.java");
        Path user = pkg.resolve("PlainChargeUser.java");
        assertTrue(read(user).contains("new PlainCharge("),
            "PROOF OF LIFE: the user must construct the subclass before this runs");
        // AND the parent must carry the dangling link before the run, or the assertion that
        // it is gone afterwards passes over a fixture that never had one. A C6 audit named
        // this: a negative with no proof of life is satisfied by absence.
        assertTrue(read(parent).contains("{@link PlainCharge}"),
            "PROOF OF LIFE: the parent's javadoc must link the subclass this row deletes:\n"
                + read(parent));

        ToolResponse r = removeSubclass(subclass, "public class PlainCharge", 13);
        assertTrue(r.isSuccess(), "the fold must run; got: " + r.getError());

        String afterParent = read(parent);
        assertTrue(afterParent.contains("doubled()"),
            "the subclass's own member moved up:\n" + afterParent);
        // THE JAVADOC LINK TO THE DELETED TYPE IS UNWRAPPED. A {@link PlainCharge} in the
        // parent's own doc comment points at nothing once the fold runs, and no compile
        // gate can see it — javadoc is a comment. This was fixed once in InlineClassTool
        // and stayed broken here until DeleteAtom's callers were ENUMERATED: there are two,
        // and one of them had the answer. Note the distinction the assertion below it
        // preserves — a @link is a machine-checkable reference and is repaired, while
        // PROSE naming the same word is left alone, because guessing which mentions meant
        // the type is exactly what a rewriter must not do.
        assertFalse(afterParent.contains("{@link PlainCharge}"),
            "the parent's dangling link to the deleted subclass is unwrapped:\n"
                + afterParent);
        // AN INDEPENDENT PROSE MENTION, not the unwrap's own output. A C6 audit found this
        // pair asserting a distinction the fixture could not make: `PlainCharge` appeared
        // exactly once, inside the link, so "its name survives as prose" was matching the
        // text the unwrap had just written. The fixture now says PlainCharge a second time
        // in an ordinary sentence, and THAT is what must be left alone.
        assertTrue(afterParent.contains("names\n * PlainCharge again as ORDINARY PROSE")
                || afterParent.contains("PlainCharge again as ORDINARY PROSE"),
            "prose naming the same type is NOT rewritten — a rewriter that edited comments"
                + " would be guessing which mentions meant the type, and guessing wrong"
                + " inside a comment is invisible to every later gate:\n" + afterParent);

        String afterUser = read(user);
        // The declared type AND the constructor call. A rewrite that repointed one and not
        // the other would leave the file uncompilable — the pipeline's compile gate would
        // catch that — but this says WHICH halves are expected rather than only that
        // something compiled.
        assertTrue(afterUser.contains("Charge charge = new Charge("),
            "the declaration and the construction both name the parent now:\n" + afterUser);
        // A WHOLE-WORD match, because the user class is itself called PlainChargeUser and
        // a plain substring check passes trivially on its own name — which is exactly how
        // it first "failed" here, reporting a file the rewrite had got completely right.
        assertFalse(java.util.regex.Pattern.compile("\\bPlainCharge\\b")
                .matcher(codeOf(afterUser)).find(),
            "and no CODE mentions the removed type:\n" + afterUser);
        // The fixture's javadoc still says "PlainCharge", deliberately, and this pins that
        // the rewrite left it alone. Prose is not a type reference: a rewriter that edited
        // comments would be guessing at which mentions meant the type and which meant the
        // concept, and guessing wrong inside a comment is invisible to every later gate.
        assertTrue(afterUser.contains("Uses PlainCharge by its type"),
            "the javadoc is prose and is not rewritten:\n" + afterUser);
        assertFalse(Files.exists(subclass), "and the subclass's file is deleted");
    }

    @Test
    @DisplayName("a subclass that OVERRIDES is refused — the override is the distinction")
    void anOverridingSubclassIsRefused() throws Exception {
        Path subclass = pkg.resolve("OverridingCharge.java");
        ToolResponse r = removeSubclass(subclass, "public class OverridingCharge", 13);

        assertFalse(r.isSuccess(), "folding it in would change what gets dispatched");
        assertTrue(String.valueOf(r.getError()).contains("overrides"),
            "and the refusal must name the override as the reason: " + r.getError());
        assertTrue(Files.exists(subclass), "nothing may be deleted on a refusal");
    }

    @Test
    @DisplayName("a subclass whose constructor FIXES an argument is refused")
    void aFixedArgumentConstructorIsRefused() throws Exception {
        Path subclass = pkg.resolve("FixedCharge.java");
        ToolResponse r = removeSubclass(subclass, "public class FixedCharge", 13);

        assertFalse(r.isSuccess(),
            "`new FixedCharge()` and `new Charge(...)` are not the same call");
        assertTrue(String.valueOf(r.getError()).contains("factory"),
            "and the refusal must point at what Fowler does instead: " + r.getError());
        assertTrue(Files.exists(subclass), "nothing may be deleted on a refusal");
    }
}
