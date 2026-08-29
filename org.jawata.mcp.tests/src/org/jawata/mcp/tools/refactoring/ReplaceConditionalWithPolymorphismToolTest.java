package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.RefactorToPatternTool;
import org.jawata.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d Stage 8 — Replace Conditional with Polymorphism through the FRONT DOOR.
 *
 * <p>Every test drives {@code refactor_to_pattern}, never the delegate. A unit test
 * that constructs the operation itself supplies the very wiring production might be
 * missing — v3.4.0 shipped a central feature 1591/1591 green and inert for exactly
 * that reason.</p>
 *
 * <p><b>The fixture is authored, and that is a stated limit.</b> C8 rules an authored
 * before-case insufficient for the "on code we did not author" clause; that case is
 * the vendored fork slice and lives beside this. What an authored fixture buys is a
 * CONTROLLED shape — here, one built deliberately to defeat a bound the sibling State
 * tool takes.</p>
 */
class ReplaceConditionalWithPolymorphismToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool tool;
    private RefactoringChangeCache cache;
    private ObjectMapper mapper;
    private Path routerFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("factory-target");
        cache = new RefactoringChangeCache();
        tool = new RefactorToPatternTool(() -> service, cache);
        mapper = new ObjectMapper();
        routerFile = helper.getTempDirectory().resolve(
            "factory-target/src/main/java/com/example/factory/Router.java");
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

    private ObjectNode args() throws Exception {
        String source = Files.readString(routerFile);
        int line = lineOf(source, "switch (signal)");
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "replace_conditional_with_polymorphism");
        n.put("filePath", routerFile.toString());
        n.put("line", line);
        n.put("column", source.split("\n", -1)[line].indexOf("switch"));
        return n;
    }

    private long compileErrors(Path file) throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(file);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return java.util.Arrays.stream(ast.getProblems()).filter(IProblem::isError).count();
    }

    /**
     * THE DONE-DEFINITION: the dispatch site collapses to one virtual call, and the
     * arms are GONE from the method.
     *
     * <p>Both halves matter. An operation that generated the hierarchy and left the
     * switch in place would be a code generator, not a refactoring — and every
     * assertion about the generated classes would still pass.</p>
     */
    @Test
    @DisplayName("S8.9: the switch collapses to one call and the arms move out")
    void theDispatchCollapses() throws Exception {
        String before = Files.readString(routerFile);
        assertTrue(before.contains("switch (signal)"),
            "PROOF OF LIFE: the fixture must start with the switch, or a green below"
                + " would mean the operation rewrote nothing");

        ToolResponse r = tool.execute(args());
        assertTrue(r.isSuccess(), () -> "the operation refused: " + r.getError());

        String after = Files.readString(routerFile);
        assertFalse(after.contains("switch (signal)"),
            () -> "the switch survived — the hierarchy was generated BESIDE the"
                + " conditional rather than replacing it:\n" + after);
        assertTrue(after.contains("interface HandleBehaviour"),
            () -> "the behaviour interface must exist:\n" + after);
        assertTrue(after.contains("StartHandleBehaviour")
                && after.contains("StopHandleBehaviour")
                && after.contains("DefaultHandleBehaviour"),
            () -> "one implementation per arm, including the default:\n" + after);
    }

    /**
     * THE BINDING-DRIVEN REWRITE, which is the part a textual one gets wrong.
     *
     * <p>The fixture's arms assign through the qualified form AND read a BARE field,
     * while a PARAMETER sits alongside them. All three must land differently: the two
     * fields redirected at the context parameter, the method parameter untouched. A
     * search-and-replace would either miss the bare field — leaving it pointing at
     * nothing once the body moves into another class — or corrupt the parameter.</p>
     *
     * <p>Asserted by COMPILING, because that is the only check that cannot be fooled:
     * a bare field left behind does not resolve in the generated class, and a
     * corrupted parameter does not resolve either.</p>
     */
    @Test
    @DisplayName("S8.9: fields move to the context parameter, the method parameter does not")
    void theRewriteFollowsBindingsRatherThanText() throws Exception {
        ToolResponse r = tool.execute(args());
        assertTrue(r.isSuccess(), () -> "the operation refused: " + r.getError());

        String after = Files.readString(routerFile);
        assertEquals(0, compileErrors(routerFile),
            () -> "the rewritten file must COMPILE. This is the assertion that catches a"
                + " bare field reference left behind — it resolves inside the original"
                + " method and resolves nowhere once the body is a separate class:\n"
                + after);
        assertTrue(after.contains("ctx.count") && after.contains("ctx.multiplier"),
            () -> "both fields — the qualified one and the BARE one — must be redirected"
                + " at the context parameter:\n" + after);
        assertFalse(after.contains("ctx.amount"),
            () -> "'amount' is a method PARAMETER, not a field. Redirecting it at the"
                + " context is what a textual rewrite does and what a binding-driven one"
                + " must not:\n" + after);
    }

    /** Undo, and both halves: the source returns byte-identical. */
    @Test
    @DisplayName("S8.9: undo restores the original byte-identically")
    void undoRestores() throws Exception {
        String before = Files.readString(routerFile);
        ToolResponse r = tool.execute(args());
        assertTrue(r.isSuccess(), () -> "the operation refused: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String undoId = (String) data.get("undoChangeId");
        assertNotNull(undoId, "an applied refactoring owes an undo handle");
        assertTrue(!before.equals(Files.readString(routerFile)),
            "precondition: something must have changed, or the restore passes for free");

        ToolResponse undone = new UndoRefactoringTool(() -> service, cache)
            .execute(mapper.createObjectNode().put("undoChangeId", undoId));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(before, Files.readString(routerFile),
            "undo must restore the original BYTE-IDENTICALLY — equivalent-but-reformatted"
                + " means the engine rewrote code nobody asked it to touch");
    }

    /**
     * REFUSAL, and it is named for what it actually is: an ARGUMENT refusal — the
     * caret has no switch under it — not a shape refusal.
     *
     * <p>The distinction is kept because the two prove different things. Argument
     * validation proves the front door checks its inputs; a SHAPE refusal proves the
     * operation declines code it cannot transform soundly. This tool's shape refusals
     * (a non-enum discriminator, fall-through, fewer than two arms) are real and
     * carry real reasons — they are simply not what this test exercises, and calling
     * it one would be a claim the assertions do not support.</p>
     */
    @Test
    @DisplayName("S8.9 refusal: a caret with no switch under it is refused, nothing written")
    void aCaretAwayFromAnySwitchIsRefused() throws Exception {
        String before = Files.readString(routerFile);

        ObjectNode a = args();
        // The accessor — a real member, and nowhere near a switch.
        a.put("line", lineOf(before, "public int count()"));
        a.put("column", 4);

        ToolResponse r = tool.execute(a);
        assertFalse(r.isSuccess(),
            "a caret with no switch under it must be refused rather than walking to the"
                + " nearest one — silently retargeting rewrites code the caller never"
                + " pointed at");
        assertEquals(before, Files.readString(routerFile),
            "a refused operation must leave the source byte-identical");
    }

    /**
     * C8's PURITY half, which no other assertion in this class supplies.
     *
     * <p>Compiling is not purity. An operation that also reformatted a third file,
     * rewrote an unrelated import, or deleted something it had no business touching
     * would still return success, still leave everything compiling, and still pass
     * every other test here. Purity is a question about the SET of files that
     * changed, and only a before/after comparison of that set answers it.</p>
     *
     * <p>The boundary is TIGHTER than Extract Class's: this operation generates its
     * hierarchy as nested types inside the context, so it has no reason to create a
     * file or to touch a second one. Exactly one pre-existing source may differ and
     * nothing may appear.</p>
     *
     * <p>Scope: Java sources only, deliberately. The project is compiled during the
     * test, so build output legitimately changes; including it would fail this for
     * reasons unrelated to the refactoring.</p>
     */
    @Test
    @DisplayName("S8.9: the operation touches no Java source but the one it was pointed at")
    void theOperationTouchesNothingElse() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("factory-target");
        Map<Path, String> before = javaSourceSnapshot(projectRoot);
        assertTrue(before.size() > 1,
            "PROOF OF LIFE: the snapshot must see more than the file being changed, or"
                + " 'nothing else was touched' is a claim about an empty set");
        assertTrue(before.containsKey(routerFile), "the snapshot must include the target");

        ToolResponse r = tool.execute(args());
        assertTrue(r.isSuccess(), () -> "the operation refused: " + r.getError());

        Map<Path, String> after = javaSourceSnapshot(projectRoot);

        Set<Path> added = new TreeSet<>(after.keySet());
        added.removeAll(before.keySet());
        assertEquals(Set.of(), added,
            "this operation nests its hierarchy inside the context, so it has no reason"
                + " to create a file. One appearing means it took a different shape than"
                + " the one documented");

        Set<Path> removed = new TreeSet<>(before.keySet());
        removed.removeAll(after.keySet());
        assertEquals(Set.of(), removed, "the operation must delete no Java source");

        Set<Path> changed = new TreeSet<>();
        for (Map.Entry<Path, String> e : before.entrySet()) {
            String now = after.get(e.getKey());
            if (now != null && !now.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        assertEquals(Set.of(routerFile), changed,
            "exactly one pre-existing Java source may differ, and it is the file the"
                + " caret named. Anything else is the operation reaching outside its"
                + " scope");
    }

    // ------------------------------------------------------------------
    // S8.13 — THE TWO SHAPE REFUSALS, which were advertised and untested
    // ------------------------------------------------------------------

    /**
     * The caret for a switch in the refusal fixture, found by the enclosing method's
     * name so the two cases cannot be confused with each other.
     */
    private ObjectNode refusalArgs(String methodSignature) throws Exception {
        Path file = helper.getTempDirectory().resolve(
            "factory-target/src/main/java/com/example/factory/RefusalCases.java");
        String source = Files.readString(file);
        String[] lines = source.split("\n", -1);
        int from = lineOf(source, methodSignature);
        for (int i = from; i < lines.length; i++) {
            int col = lines[i].indexOf("switch (mode)");
            if (col >= 0) {
                ObjectNode n = mapper.createObjectNode();
                n.put("kind", "replace_conditional_with_polymorphism");
                n.put("filePath", file.toString());
                n.put("line", i);
                n.put("column", col);
                return n;
            }
        }
        throw new AssertionError("no switch after: " + methodSignature);
    }

    /**
     * REFUSAL: an arm assigns a variable the enclosing method owns.
     *
     * <p>It could travel as a parameter — it is read in the same arm. But Java passes
     * by value, so the write would land on a copy and be lost, and the refactoring
     * would change behaviour while every file still compiled. A transformation that
     * is wrong and green is the one this operation must never perform.</p>
     *
     * <p>The fixture method is otherwise a perfectly good candidate — enum
     * discriminator, arrow switch, two non-default arms plus a default — so a refusal
     * here is about the SHAPE and not about some other precondition failing.</p>
     */
    @Test
    @DisplayName("S8.13 refusal: an arm that ASSIGNS a method-scope variable, nothing written")
    void anArmAssigningAMethodScopeVariableIsRefused() throws Exception {
        ObjectNode args = refusalArgs("void accumulate(Mode mode, int step)");
        Path file = Path.of(args.get("filePath").asText());
        String before = Files.readString(file);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            () -> "an arm that assigns `step` must be REFUSED. Moving it would pass the"
                + " parameter by value and silently drop the write: " + r.getData());
        assertTrue(String.valueOf(r.getError()).contains("step"),
            () -> "the refusal must NAME the variable, or the caller cannot tell which"
                + " of the arms' identifiers caused it: " + r.getError());
        assertEquals(before, Files.readString(file),
            "a refused operation must leave the source byte-identical");
    }

    /**
     * REFUSAL: an arm uses {@code this} for something other than reaching a field.
     *
     * <p>Once the body is a class of its own, {@code this} IS that class, so the
     * reference silently comes to mean the behaviour object rather than the context.
     * In this fixture the receiving type would in fact make the compiler catch it;
     * the refusal exists for the cases where it would not — a parameter typed
     * {@code Object}, a varargs sink, a logger.</p>
     */
    @Test
    @DisplayName("S8.13 refusal: an arm that passes `this` to somebody, nothing written")
    void anArmPassingThisIsRefused() throws Exception {
        ObjectNode args = refusalArgs("void announce(Mode mode, Consumer<RefusalCases> sink)");
        Path file = Path.of(args.get("filePath").asText());
        String before = Files.readString(file);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            () -> "an arm passing `this` to a collaborator must be REFUSED: " + r.getData());
        assertTrue(String.valueOf(r.getError()).contains("this"),
            () -> "the refusal must say it is about `this`: " + r.getError());
        assertEquals(before, Files.readString(file),
            "a refused operation must leave the source byte-identical");
    }

    /**
     * THE CONTROL, and without it the two refusals above prove much less.
     *
     * <p>A tool that refused this fixture for any reason at all would pass both. The
     * fixture's own SLOW and default arms are ordinary — they touch only fields — so
     * a switch built from those alone must SUCCEED. That is what makes the refusals
     * attributable to the two shapes rather than to the file.</p>
     */
    @Test
    @DisplayName("S8.13 control: the same fixture's ordinary switch is NOT refused")
    void theOrdinaryCaseInTheSameFixtureStillSucceeds() throws Exception {
        // Router's switch is the ordinary one and lives in the same project; if the
        // operation were refusing everything in this fixture project, this goes red.
        ToolResponse r = tool.execute(args());
        assertTrue(r.isSuccess(),
            () -> "CONTROL: an ordinary switch in the same project must still be"
                + " accepted, or the two refusals above are about the project rather"
                + " than about the shapes they name: " + r.getError());
    }

    /** Every .java under the project, mapped to its exact bytes. */
    private static Map<Path, String> javaSourceSnapshot(Path root) throws Exception {
        Map<Path, String> snapshot = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile)
                              .filter(p -> p.toString().endsWith(".java"))
                              .toList()) {
                // ISO-8859-1 is a lossless byte-to-char mapping, so string equality here
                // IS byte equality — a reformat cannot slip through as "equal".
                snapshot.put(p, new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));
            }
        }
        return snapshot;
    }
}
