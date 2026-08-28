package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractTool;
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
 * Sprint 28d Stage 7 / S7.4 — Extract Class ON CODE WE DID NOT AUTHOR.
 *
 * <h2>Why a separate test, when the operation is already covered</h2>
 *
 * <p>C7 does not accept the coverage that already exists, and says why in terms:
 * "each op's before-case is pre-existing code (a fork slice or equivalent), named
 * per op — <b>a fixture written for the test does not satisfy the clause</b>".</p>
 *
 * <p>That is not pedantry. A fixture written to exercise a refactoring is written,
 * consciously or not, in the shape that refactoring handles. It cannot fail the way
 * real code fails. This before-case is a byte-identical copy of
 * {@code circuit-breaker} from the pinned fork at
 * {@code 22a34127d0b08449c24cf7e230c04a097deca2f3}, written to demonstrate the
 * Circuit Breaker pattern by someone who had never heard of this operation — see
 * {@code PROVENANCE.md} beside the fixture.</p>
 *
 * <h2>What makes it a real case</h2>
 *
 * <p>{@code DefaultCircuitBreaker} carries a field cluster that genuinely travels
 * together — {@code failureCount}, {@code lastFailureTime},
 * {@code lastFailureResponse}: {@code recordFailure} mutates all three,
 * {@code recordSuccess} resets two. Their visibility is MIXED (two package-private,
 * one private), which is the sort of untidiness a hand-written fixture would have
 * smoothed away without noticing.</p>
 *
 * <p><b>What it does NOT exercise, stated rather than left to assumption:</b> no
 * other file in the slice touches those three fields (checked), so this case does
 * not demonstrate cross-file reference migration. It demonstrates the operation on
 * unfamiliar code, which is the clause's actual demand.</p>
 */
class ExtractClassForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ExtractTool tool;
    private ObjectMapper mapper;
    private Path breakerFile;
    private Path pkgDir;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("fork-circuit-breaker");
        tool = new ExtractTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        pkgDir = helper.getTempDirectory()
            .resolve("fork-circuit-breaker/src/main/java/com/iluwatar/circuitbreaker");
        breakerFile = pkgDir.resolve("DefaultCircuitBreaker.java");
    }

    private long compileErrors(Path file) throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(file);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return java.util.Arrays.stream(ast.getProblems()).filter(IProblem::isError).count();
    }

    @Test
    @DisplayName("S7.4: Extract Class on a verbatim fork slice — code we did not author")
    void extractsAFieldClusterFromForkCode() throws Exception {
        String before = Files.readString(breakerFile);

        // PROVENANCE, asserted rather than trusted. If someone edits this fixture into
        // a shape that suits the operation, the clause it exists to satisfy is void —
        // and the licence header is the marker that it is still upstream's file.
        assertTrue(before.contains("The MIT License") && before.contains("Ilkka Seppälä"),
            "the fixture must still carry upstream's licence header. Without it this is no"
                + " longer evidence about anybody's code but our own, and the MIT terms"
                + " require the attribution retained besides");
        assertTrue(before.contains("CLOSED->OPEN-(retry_time_period)->HALF_OPEN->CLOSED"),
            "the fixture must still be the pattern's own class, unedited");

        ICompilationUnit cu = service.getCompilationUnit(breakerFile);
        IType breaker = cu.getType("DefaultCircuitBreaker");
        assertTrue(breaker.exists(), "PROOF OF LIFE: the fork type must resolve");
        for (String f : new String[] {"failureCount", "lastFailureTime", "lastFailureResponse"}) {
            assertTrue(breaker.getField(f).exists(),
                "PROOF OF LIFE: '" + f + "' must be on the type before the move");
        }

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "class");
        args.put("filePath", breakerFile.toString());
        args.put("line", breaker.exists() ? lineOf(before, "public class DefaultCircuitBreaker") : 0);
        args.put("column", 13);
        args.put("newTypeName", "FailureRecord");
        // MEASURED, not chosen for convenience: with accessors ON, JDT refuses this
        // exact class — see theAccessorConversionRefusesOnThisRealCode below, which
        // pins that refusal so it cannot regress into a silent success.
        args.put("createGetterSetter", false);
        ArrayNode fields = args.putArray("fields");
        fields.add("failureCount");
        fields.add("lastFailureTime");
        fields.add("lastFailureResponse");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            () -> "Extract Class refused on real upstream code: " + r.getError());

        // The done-definition, on code nobody shaped for us.
        IType after = service.getCompilationUnit(breakerFile).getType("DefaultCircuitBreaker");
        for (String f : new String[] {"failureCount", "lastFailureTime", "lastFailureResponse"}) {
            assertFalse(after.getField(f).exists(),
                "'" + f + "' still lives on DefaultCircuitBreaker — the state now has two"
                    + " homes and the next reader cannot tell which is authoritative");
        }
        Path created = pkgDir.resolve("FailureRecord.java");
        assertTrue(Files.exists(created), "the extracted class must exist");
        IType record = service.getCompilationUnit(created).getType("FailureRecord");
        for (String f : new String[] {"failureCount", "lastFailureTime", "lastFailureResponse"}) {
            assertTrue(record.getField(f).exists(),
                "'" + f + "' must have ARRIVED on FailureRecord — gone-from-the-original is"
                    + " only half the done-definition, and deleting them would satisfy the"
                    + " other half");
        }

        // Parity, which is where real code differs from a fixture: the remaining methods
        // reached this state directly, and every one of those accesses had to be rewritten.
        assertEquals(0, compileErrors(breakerFile),
            () -> "the fork class must still compile after its state moved out:\n"
                + readQuietly(breakerFile));
        assertEquals(0, compileErrors(created),
            () -> "the extracted class must compile:\n" + readQuietly(created));
    }

    /**
     * THE LIMITATION THIS FORK SLICE EXPOSED, pinned rather than worked around.
     *
     * <p>With accessor generation ON — which is the tool's default — JDT's engine
     * REFUSES this class: {@code "Unable to convert node to getter/setter"}. It is
     * exactly the kind of thing a hand-written fixture never surfaces, because a
     * fixture is written in shapes the engine handles. Real code was written by
     * someone solving a different problem, and it contains a construct the accessor
     * rewriter cannot express.</p>
     *
     * <p><b>Why this is a test and not a bug report.</b> The refusal is CORRECT
     * behaviour: JDT declines, names the reason, and modifies nothing — which is the
     * contract a refactoring engine owes when it cannot preserve meaning. What would
     * be wrong is for it to half-convert and leave the file compiling but different.
     * So the refusal is asserted, including that the source is byte-identical
     * afterwards, and the sibling test above passes {@code createGetterSetter=false}
     * with a comment pointing here.</p>
     *
     * <p>If a future JDT gains the conversion, this test goes red — which is the
     * point. It is then a deliberate act to widen the tool's default, not a silent
     * change of behaviour nobody noticed.</p>
     */
    @Test
    @DisplayName("S7.4: JDT's accessor conversion refuses on this real code, and names why")
    void theAccessorConversionRefusesOnThisRealCode() throws Exception {
        String before = Files.readString(breakerFile);

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "class");
        args.put("filePath", breakerFile.toString());
        args.put("line", lineOf(before, "public class DefaultCircuitBreaker"));
        args.put("column", 13);
        args.put("newTypeName", "FailureRecordWithAccessors");
        // createGetterSetter is NOT set: the tool defaults it to true, and the default
        // is what this pins.
        ArrayNode fields = args.putArray("fields");
        fields.add("failureCount");
        fields.add("lastFailureTime");
        fields.add("lastFailureResponse");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "JDT cannot convert every access in this class to accessors; if it now can,"
                + " that is a real improvement — update this test and consider the default");
        assertTrue(r.getError().getMessage().contains("getter/setter"),
            () -> "the refusal must carry JDT's own reason, or a caller cannot tell this"
                + " apart from any other failure: " + r.getError());

        assertEquals(before, Files.readString(breakerFile),
            "a REFUSED refactoring must leave the source byte-identical. Half-applying and"
                + " then failing is the one outcome worse than refusing");
        assertFalse(Files.exists(pkgDir.resolve("FailureRecordWithAccessors.java")),
            "a refusal must not leave a half-created class behind");
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

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return "<unreadable: " + e + ">";
        }
    }
}
