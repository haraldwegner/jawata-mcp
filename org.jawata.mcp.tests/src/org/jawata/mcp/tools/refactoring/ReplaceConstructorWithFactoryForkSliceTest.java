package org.jawata.mcp.tools.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.RefactorToPatternTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28d Stage 8 / S8.4 — Replace Constructor with Factory Method ON CODE WE
 * DID NOT AUTHOR.
 *
 * <h2>Why a separate test, when the operation is already covered</h2>
 *
 * <p>C8 inherits C7's clause and its reason: "each op's before-case is pre-existing
 * code (a fork slice or equivalent), named per op — <b>a fixture written for the
 * test does not satisfy the clause</b>". A fixture written to exercise a
 * refactoring is written, consciously or not, in the shape that refactoring
 * handles. It cannot fail the way real code fails.</p>
 *
 * <p>Stage 7's slice earned that clause immediately: it surfaced a JDT limitation
 * — accessor conversion refusing on real code — that no hand-written fixture had
 * produced.</p>
 *
 * <h2>The before-case</h2>
 *
 * <p>A byte-identical copy of {@code abstract-factory} from the pinned fork at
 * {@code 22a34127}, one of the four patterns D1 measured as blocked without a
 * constructor call-site rewrite. {@code ElfKingdomFactory} constructs its products
 * internally, so the call sites are real and were written by someone who had never
 * heard of this operation. See {@code PROVENANCE.md} beside the fixture.</p>
 *
 * <h2>THE SHAPE THIS SLICE BRINGS, and it is the interesting one</h2>
 *
 * <p>{@code ElfArmy} and its siblings declare <b>no explicit constructor</b> — only
 * the implicit default. So the caret cannot sit on a declaration; it must sit on a
 * constructor CALL, which JDT's engine supports through its own
 * {@code getCtorCallAt}. Whether the engine will introduce a factory for an
 * IMPLICIT constructor is not predicted here. It is measured, and whatever it
 * answers is pinned — the same method that turned Stage 7's fork slice from a
 * box-ticking exercise into a real finding.</p>
 */
class ReplaceConstructorWithFactoryForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool tool;
    private ObjectMapper mapper;
    private Path factoryFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("fork-abstract-factory");
        tool = new RefactorToPatternTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        factoryFile = helper.getTempDirectory().resolve(
            "fork-abstract-factory/src/main/java/com/iluwatar/abstractfactory/"
                + "ElfKingdomFactory.java");
    }

    private static int lineOf(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("fixture no longer contains: " + needle);
    }

    /**
     * The operation on unfamiliar code, with the caret on a real constructor CALL.
     *
     * <p><b>It asserts ONE outcome, and that is the second draft.</b> The first
     * accepted success OR refusal and asserted the right things about each — and so
     * pinned NEITHER: a regression from one to the other would have passed in
     * silence. That is the same defect shape this sprint keeps finding in its own
     * instruments, so it is not left standing in a test written to catch it.</p>
     *
     * <p>What is asserted is what was MEASURED. If a future JDT changes the answer,
     * the failure message says so and carries the engine's own words, and the cure
     * is to pin the refusal here instead — exactly as Stage 7 pinned the accessor
     * refusal rather than working around it.</p>
     */
    @Test
    @DisplayName("S8.4: the operation meets a verbatim fork slice, and the outcome is pinned")
    void theOperationMeetsCodeWeDidNotAuthor() throws Exception {
        String before = Files.readString(factoryFile);

        // PROVENANCE, asserted rather than trusted. If someone edits this fixture into
        // a shape that suits the operation, the clause it exists to satisfy is void —
        // and the licence header is the marker that it is still upstream's file.
        assertTrue(before.contains("THE SOFTWARE IS PROVIDED"),
            "the fixture must still carry upstream's MIT licence header. Without it this"
                + " is no longer evidence about anybody's code but our own, and the MIT"
                + " terms require the attribution retained besides");
        assertTrue(before.contains("ElfKingdomFactory concrete factory"),
            "the fixture must still be the pattern's own class, unedited");
        assertTrue(before.contains("new ElfArmy()"),
            "PROOF OF LIFE: the call site this test acts on must exist before the move");

        int line = lineOf(before, "return new ElfArmy();");
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "replace_constructor_with_factory");
        args.put("filePath", factoryFile.toString());
        args.put("line", line);
        args.put("column", before.split("\n", -1)[line].indexOf("new ElfArmy()"));
        args.put("factoryMethodName", "newArmy");
        // The products have no explicit constructor to make private, so the
        // done-definition flag is deliberately off here: this test is about whether
        // the engine can act on an IMPLICIT constructor at all.
        args.put("protectConstructor", false);

        ToolResponse r = tool.execute(args);

        // ONE BRANCH, DELIBERATELY. An earlier draft accepted success OR refusal and
        // asserted the right things about each — and pinned NEITHER: a regression from
        // one to the other would have passed silently, which is precisely the shape of
        // gate this sprint keeps finding. So the measured outcome is asserted, and the
        // failure message carries JDT's own words if it ever changes.
        assertTrue(r.isSuccess(),
            () -> "the operation refused on the fork slice. That may be CORRECT behaviour —"
                + " these products declare no explicit constructor, only the implicit"
                + " default, and an engine that cannot act on one should decline and say"
                + " so. If this is now the truth, pin the refusal here instead of this"
                + " assertion, exactly as Stage 7 pinned the accessor refusal. JDT said: "
                + r.getError());

        String after = Files.readString(factoryFile);
        assertTrue(!after.equals(before),
            "the operation reported SUCCESS and changed nothing. A green result over an"
                + " untouched file is the worst of the outcomes: it reads exactly like a"
                + " real one");
        assertTrue(after.contains("newArmy"),
            () -> "the factory call must be present at the rewritten site:\n" + after);
    }

    /**
     * PURITY — nothing OUTSIDE the operation's stated scope changed.
     *
     * <p>Compiling afterwards proves the result is valid; it does not prove the
     * operation was surgical. A refactoring that also reformatted a third file, or
     * rewrote an unrelated import, leaves everything compiling and every other
     * assertion green — and diverges from what the caller asked for in a way only a
     * whole-tree comparison can see.</p>
     *
     * <p><b>Measured here rather than on the authored fixture</b>, deliberately:
     * {@code factory-target} holds two files, so "nothing else was touched" would be
     * a claim about at most one other file. This slice holds twelve, so the same
     * assertion is checked against ten that must come through untouched.</p>
     *
     * <p>Byte equality is real rather than asserted: ISO-8859-1 is a lossless
     * byte-to-char mapping, so {@code String.equals} here is a byte comparison and a
     * whitespace-only reformat cannot pass as equal.</p>
     */
    @Test
    @DisplayName("S8.5: the operation touches nothing outside its scope")
    void theOperationTouchesNothingElse() throws Exception {
        Path root = helper.getTempDirectory().resolve("fork-abstract-factory");
        Map<Path, String> before = snapshot(root);
        assertTrue(before.size() > 5,
            "ANTI-VACUITY: the snapshot must cover a real tree, or 'nothing else changed'"
                + " is a claim about almost nothing. Found " + before.size() + " files");

        String source = Files.readString(factoryFile);
        int line = lineOf(source, "return new ElfArmy();");
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "replace_constructor_with_factory");
        args.put("filePath", factoryFile.toString());
        args.put("line", line);
        args.put("column", source.split("\n", -1)[line].indexOf("new ElfArmy()"));
        args.put("factoryMethodName", "newArmy");
        args.put("protectConstructor", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "the operation refused: " + r.getError());

        Map<Path, String> after = snapshot(root);
        assertEquals(before.keySet(), after.keySet(),
            "the operation must neither add nor delete a file in this slice");

        Set<Path> changed = new LinkedHashSet<>();
        for (Map.Entry<Path, String> e : before.entrySet()) {
            if (!e.getValue().equals(after.get(e.getKey()))) {
                changed.add(e.getKey());
            }
        }
        assertTrue(changed.contains(factoryFile),
            "PROOF OF LIFE: the file holding the call site must be among the changed set,"
                + " or this test is asserting purity about an operation that did nothing");
        // The factory lands on the product type, so ElfArmy may legitimately change too.
        // What must NOT change is anything else — the Orc half of the slice above all,
        // which shares interfaces with the Elf half and is where an over-broad
        // search-and-rewrite would show first.
        for (Path p : changed) {
            String name = p.getFileName().toString();
            assertTrue(name.equals("ElfKingdomFactory.java") || name.equals("ElfArmy.java"),
                () -> "unexpected file changed: " + name + ". The operation was asked to"
                    + " rewrite one construction of ElfArmy; everything else in the slice"
                    + " must come through untouched. Changed: " + changed);
        }
    }

    /** Every Java source under the tree, read as bytes. */
    private static Map<Path, String> snapshot(Path root) throws Exception {
        Map<Path, String> out = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                out.put(p, Files.readString(p, java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }
        return out;
    }
}
