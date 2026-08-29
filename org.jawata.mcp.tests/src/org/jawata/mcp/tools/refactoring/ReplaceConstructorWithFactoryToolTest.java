package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
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
 * Sprint 28d Stage 8 — Replace Constructor with Factory Method, through the FRONT
 * DOOR, with the done-definition asserted by query rather than by reading a diff.
 *
 * <p>Every test here goes through {@code refactor_to_pattern}, not through the
 * delegate. A unit test that constructs the operation itself supplies the very
 * wiring production might be missing: v3.4.0 shipped a central feature 1591/1591
 * green and inert for exactly that reason.</p>
 *
 * <p><b>The fixture is authored, and that is a stated limit.</b> C8 rules an
 * authored before-case insufficient for the "on code we did not author" clause;
 * that case takes a slice of the fork's own {@code factory-method} module and lives
 * beside this. What an authored fixture buys is a CONTROLLED shape, so a failure
 * here means the operation failed rather than the input being unusual — and, unlike
 * either Stage 7 fixture, it has call sites in a SECOND FILE.</p>
 */
class ReplaceConstructorWithFactoryToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool tool;
    private ObjectMapper mapper;
    private Path shipmentFile;
    private Path dispatcherFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("factory-target");
        tool = new RefactorToPatternTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        Path pkg = helper.getTempDirectory()
            .resolve("factory-target/src/main/java/com/example/factory");
        shipmentFile = pkg.resolve("Shipment.java");
        dispatcherFile = pkg.resolve("Dispatcher.java");
    }

    /** Zero-based line of the first occurrence, so no caret is a magic number. */
    private static int lineOf(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("fixture no longer contains: " + needle);
    }

    private ObjectNode args(String factoryMethodName) throws Exception {
        String source = Files.readString(shipmentFile);
        int line = lineOf(source, "public Shipment(String destination");
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "replace_constructor_with_factory");
        n.put("filePath", shipmentFile.toString());
        n.put("line", line);
        // The caret sits on the constructor's own NAME, past "    public ".
        n.put("column", source.split("\n", -1)[line].indexOf("Shipment("));
        n.put("factoryMethodName", factoryMethodName);
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
     * THE DONE-DEFINITION, and both halves of it.
     *
     * <p>Half one: the factory EXISTS. Half two, which is the half that makes this
     * an operation rather than a code generator: <b>the old shape is GONE</b> — no
     * {@code new Shipment(...)} survives in the calling file, and the constructor is
     * no longer public, so a caller CANNOT go back to it. Asserted by querying the
     * model and the source text, never by reading the returned diff: a diff shows
     * what the tool believes it did.</p>
     */
    @Test
    @DisplayName("S8.3: the call sites move to the factory and the old path is closed")
    void theConstructorCallsBecomeFactoryCalls() throws Exception {
        String callersBefore = Files.readString(dispatcherFile);
        assertEquals(3, callersBefore.split("new Shipment\\(", -1).length - 1,
            "PROOF OF LIFE: the fixture must start with THREE constructor calls in the"
                + " calling file, or a green below would mean the operation rewrote nothing");

        ToolResponse r = tool.execute(args("of"));
        assertTrue(r.isSuccess(), () -> "the operation refused: " + r.getError());

        // HALF ONE — the factory arrived, as a real method on the model.
        IType shipment = service.getCompilationUnit(shipmentFile).getType("Shipment");
        boolean hasFactory = false;
        for (IMethod m : shipment.getMethods()) {
            if ("of".equals(m.getElementName())) {
                hasFactory = true;
            }
        }
        assertTrue(hasFactory, "the factory method 'of' must exist on Shipment");

        // HALF TWO — the old shape is GONE at every call site, in a DIFFERENT file.
        // This is the cross-file reference migration neither Stage 7 fixture could
        // demonstrate, and it is where a call-site rewrite most plausibly breaks.
        String callersAfter = Files.readString(dispatcherFile);
        assertFalse(callersAfter.contains("new Shipment("),
            () -> "a constructor call survived in Dispatcher.java — the factory then exists"
                + " beside the old path rather than replacing it:\n" + callersAfter);
        assertEquals(3, callersAfter.split("Shipment\\.of\\(", -1).length - 1,
            () -> "all THREE calls must have become factory calls. A partial rewrite is the"
                + " dangerous outcome: it compiles today and diverges forever:\n" + callersAfter);

        // AND THE OLD PATH IS IMPOSSIBLE, not merely unused — the architect seat's
        // standing rule. A private constructor is what stops the next caller
        // reintroducing the shape this operation just removed.
        String shipmentAfter = Files.readString(shipmentFile);
        assertFalse(shipmentAfter.contains("public Shipment(String destination"),
            () -> "the constructor is still PUBLIC, so nothing prevents a caller bypassing"
                + " the factory. Gone-from-the-call-sites is only half the job:\n"
                + shipmentAfter);
    }

    /**
     * PARITY — both touched files must still compile.
     *
     * <p>An operation that privatises the constructor and misses one call site leaves
     * the caller uncompilable; one that rewrites the calls and forgets the factory
     * leaves the declaring file uncompilable. Both are caught here, from the outside,
     * by parsing with bindings rather than trusting the engine's own verification.</p>
     */
    @Test
    @DisplayName("S8.3: both touched files still compile after the move")
    void bothFilesStillCompile() throws Exception {
        ToolResponse r = tool.execute(args("create"));
        assertTrue(r.isSuccess(), () -> "the operation refused: " + r.getError());

        assertEquals(0, compileErrors(shipmentFile),
            () -> "the declaring file must compile:\n" + readQuietly(shipmentFile));
        assertEquals(0, compileErrors(dispatcherFile),
            () -> "the CALLING file must compile — this is the assertion that catches a"
                + " partial call-site rewrite once the constructor is private:\n"
                + readQuietly(dispatcherFile));
    }

    /**
     * The caller keeps the choice this operation exists to make FOR them only when
     * they ask: with {@code protectConstructor=false} the factory is added and the
     * constructor stays reachable.
     *
     * <p>Pinned because the DEFAULT is the opposite, and a default that silently
     * flipped would turn "the old path is impossible" into "the old path is open"
     * with no visible change in the response.</p>
     */
    @Test
    @DisplayName("S8.3: protectConstructor=false leaves the old path open, deliberately")
    void theOldPathCanBeLeftOpenOnPurpose() throws Exception {
        ObjectNode a = args("make");
        a.put("protectConstructor", false);

        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "the operation refused: " + r.getError());

        String shipmentAfter = Files.readString(shipmentFile);
        assertTrue(shipmentAfter.contains("public Shipment(String destination"),
            () -> "with protectConstructor=false the constructor must remain public — the"
                + " operation then ADDS a factory rather than replacing the constructor:\n"
                + shipmentAfter);
    }

    /**
     * REFUSAL, and it is an ARGUMENT refusal — distinct from a shape refusal, which
     * is the engine declining because the code cannot be transformed. Both kinds
     * matter and they prove different things: this one proves the front door checks
     * its inputs before JDT is ever asked.
     */
    @Test
    @DisplayName("S8.3 refusal: a caret that is not on a constructor is refused, nothing written")
    void aCaretAwayFromTheConstructorIsRefused() throws Exception {
        String before = Files.readString(shipmentFile);
        ObjectNode a = args("of");
        // The `destination()` accessor — a real member, and not a constructor.
        a.put("line", lineOf(before, "public String destination()"));
        a.put("column", 4);

        ToolResponse r = tool.execute(a);
        assertFalse(r.isSuccess(),
            "a caret on an ordinary method must be refused. Silently walking to the nearest"
                + " constructor would rewrite call sites the caller never pointed at");
        assertEquals(before, Files.readString(shipmentFile),
            "a refused operation must leave the source byte-identical");
    }

    /**
     * A factory name is REQUIRED and has no default. The name is what every call site
     * will read forever, and inventing one would be this tool making the single
     * decision the caller is there to make.
     */
    @Test
    @DisplayName("S8.3 refusal: no factory name is refused rather than defaulted")
    void anUnnamedFactoryIsRefused() throws Exception {
        String before = Files.readString(shipmentFile);
        ObjectNode a = args("of");
        a.remove("factoryMethodName");

        ToolResponse r = tool.execute(a);
        assertFalse(r.isSuccess(), "an unnamed factory must be refused, never defaulted");
        assertEquals(before, Files.readString(shipmentFile),
            "a refused operation must leave the source byte-identical");
    }

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return "<unreadable: " + e + ">";
        }
    }
}
