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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d Stage 8 — Replace Conditional with Polymorphism ON CODE WE DID NOT
 * AUTHOR, and the proof that it is not the State tool wearing a new name.
 *
 * <h2>Finding this before-case was itself a measurement</h2>
 *
 * <p>The fork is a catalogue of patterns ALREADY APPLIED, so it holds after-shapes
 * and this operation needs a before-shape. Five candidates were ranked by external
 * dependency and every one died the same way — {@code factory-method},
 * {@code strategy}, {@code RequestStateMachine}, {@code DAOFactoryProvider},
 * {@code flyweight/PotionFactory}. The pattern under all five is intrinsic rather
 * than bad luck: this operation acts on a conditional whose arms name CONCRETE
 * TYPES, so any slice must include those types, and in a modern catalogue they
 * carry Lombok, JPA or a logger. Extract Class and Introduce Factory never met this
 * because they act on one type in isolation.</p>
 *
 * <p>The before-case that survives is one already vendored:
 * {@code DefaultCircuitBreaker.setState} in the slice Stage 7 copied — verbatim from
 * the pinned fork, {@code cmp}-verified, dependency-free. No new copying, no new
 * provenance risk.</p>
 *
 * <h2>Stated so it is not over-claimed</h2>
 *
 * <p>This demonstrates the OPERATION on code we did not author, which is what the
 * clause asks. It is NOT a claim that this switch ought to be refactored. Whether a
 * given switch should become a hierarchy is a judgement about the domain, not a
 * property of the AST — which is exactly why the stage's tiering puts this operation
 * at ADVISE rather than PERFORM.</p>
 */
class ReplaceConditionalForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool tool;
    private ObjectMapper mapper;
    private Path breakerFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("fork-circuit-breaker");
        tool = new RefactorToPatternTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        breakerFile = helper.getTempDirectory()
            .resolve("fork-circuit-breaker/src/main/java/com/iluwatar/circuitbreaker"
                + "/DefaultCircuitBreaker.java");
    }

    private long compileErrors(Path file) throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(file);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return java.util.Arrays.stream(ast.getProblems()).filter(IProblem::isError).count();
    }

    /** Zero-based line of the first occurrence, so the caret is not a magic number. */
    private static int lineOf(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("fixture no longer contains: " + needle);
    }

    private ObjectNode args(String kind) throws Exception {
        String source = Files.readString(breakerFile);
        int line = lineOf(source, "switch (state)");
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", kind);
        n.put("filePath", breakerFile.toString());
        n.put("line", line);
        n.put("column", source.split("\n", -1)[line].indexOf("switch"));
        return n;
    }

    private void assertStillUpstreamsFile(String source) {
        // PROVENANCE, asserted rather than trusted. Edit this fixture into a shape that
        // suits the operation and the clause it exists to satisfy is void — and MIT
        // requires the attribution retained besides.
        assertTrue(source.contains("The MIT License") && source.contains("Ilkka Seppälä"),
            "the fixture must still carry upstream's licence header");
        assertTrue(source.contains("CLOSED->OPEN-(retry_time_period)->HALF_OPEN->CLOSED"),
            "the fixture must still be the pattern's own class, unedited");
    }

    @Test
    @DisplayName("S8.11: the operation on a verbatim fork slice — code we did not author")
    void collapsesTheDispatchInForkCode() throws Exception {
        String before = Files.readString(breakerFile);
        assertStillUpstreamsFile(before);
        assertTrue(before.contains("case HALF_OPEN ->"),
            "PROOF OF LIFE: the arrow switch must still be here, or a green below would"
                + " mean the operation rewrote nothing");

        ToolResponse r = tool.execute(args("replace_conditional_with_polymorphism"));
        assertTrue(r.isSuccess(),
            () -> "the operation refused on real upstream code: " + r.getError());

        String after = Files.readString(breakerFile);
        assertFalse(after.contains("switch (state)"),
            () -> "the switch survived — the hierarchy was generated BESIDE the"
                + " conditional rather than replacing it:\n" + after);
        assertTrue(after.contains("interface SetStateBehaviour"),
            () -> "the behaviour interface must exist:\n" + after);
        assertTrue(after.contains("OpenSetStateBehaviour")
                && after.contains("HalfOpenSetStateBehaviour")
                && after.contains("DefaultSetStateBehaviour"),
            () -> "one implementation per arm, including the default:\n" + after);

        // PARITY on unfamiliar code, which is where it differs from a fixture: these arms
        // reach FOUR fields of mixed visibility — failureCount and lastFailureTime are
        // package-private, failureThreshold and retryTimePeriod are private final — and
        // every one of those accesses had to be redirected at the context parameter.
        assertEquals(0, compileErrors(breakerFile),
            () -> "the fork class must still compile after its dispatch moved out:\n"
                + after);
        assertTrue(after.contains("ctx.failureCount") && after.contains("ctx.failureThreshold"),
            () -> "both the QUALIFIED write (this.failureCount) and the BARE read"
                + " (failureThreshold) must be redirected at the context:\n" + after);
        assertStillUpstreamsFile(after);
    }

    /**
     * THE MEASUREMENT THAT JUSTIFIES THE OPERATION EXISTING, run on the same caret.
     *
     * <p>The stage's design argued from reading preconditions that rank 2 is not a
     * laxer {@code refactor_to_state}. This asserts it instead of arguing it: the
     * State tool REFUSES this exact switch, on three counts at once — the
     * discriminator is an ENUM rather than a private int field, the syntax is an
     * ARROW switch rather than the old labelled form its case parser walks, and the
     * selector is a PARAMETER rather than a field of the context.</p>
     *
     * <p>Without this test the operation's justification is a paragraph. With it,
     * the day someone widens the State tool far enough to accept this, the overlap
     * announces itself instead of accumulating silently.</p>
     */
    @Test
    @DisplayName("S8.11: refactor_to_state refuses the same caret — the two do not overlap")
    void theStateToolRefusesWhatThisOperationHandles() throws Exception {
        String before = Files.readString(breakerFile);

        ToolResponse r = tool.execute(args("refactor_to_state"));
        assertFalse(r.isSuccess(),
            "refactor_to_state must REFUSE this switch. If it now accepts it, the two"
                + " operations overlap and the stage's scope argument needs redoing —"
                + " that is a real finding, not a test to relax");
        assertEquals(before, Files.readString(breakerFile),
            "a REFUSED refactoring must leave the source byte-identical");
    }
}
