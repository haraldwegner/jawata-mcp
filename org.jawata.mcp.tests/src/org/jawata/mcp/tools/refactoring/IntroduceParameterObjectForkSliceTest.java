package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
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
 * Row 21 ON CODE WE DID NOT AUTHOR — Introduce Parameter Object on upstream's rate limiter.
 *
 * <p>{@code RateLimiter.check(String serviceName, String operationName)} is Fowler's shape
 * without any help from us: two parameters that name ONE thing — which operation, on which
 * service — passed together everywhere and never apart. Upstream's own javadoc treats them as
 * one description ({@code "dynamodb"} / {@code "Query"}).</p>
 *
 * <h2>What makes it a demonstration rather than a fixture with a licence header</h2>
 *
 * <p>The caret is put on the INTERFACE declaration and on nothing else. Everything the
 * assertions below check is a file the test never names: three implementing classes that must
 * each have their {@code @Override} rewritten, and three call sites in three further files that
 * must each learn to construct the new class. A fixture of mine had one caller, in one file,
 * because that is what occurred to me to write.</p>
 *
 * <h2>Provenance, and why this slice cannot use the shared licence check</h2>
 *
 * <p>{@link ForkSliceSupport#load} asserts an MIT header per file. <b>Upstream ships this
 * module without one</b> — measured, not assumed: every file of the vendored slice is
 * byte-identical to the pinned commit's, and none of the originals carries a header either.
 * So the proof of life here is the SHAPE instead: the two-parameter declaration and the three
 * call sites are asserted to exist before anything is run, so a moved fork pin fails loudly
 * rather than passing over nothing.</p>
 */
class IntroduceParameterObjectForkSliceTest {

    private static final String PKG = "src/main/java/com/iluwatar/rate/limiting/pattern";

    /** Upstream's own declaration, verbatim — the shape this row exists to close. */
    private static final String UPSTREAM_DECL =
        "void check(String serviceName, String operationName)";

    /**
     * The name is ours and the collision is real: the slice already declares
     * {@code RateLimitOperation}, so the obvious name would have been rejected by the compiler
     * rather than by judgement.
     */
    private static final String NEW_CLASS = "ServiceCall";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-rate-limiting");
        tool = new ChangeMethodSignatureTool(() -> service, new RefactoringChangeCache());
        pkg = service.allProjects().iterator().next().projectRoot().resolve(PKG);
    }

    private String read(String file) throws Exception {
        Path path = pkg.resolve(file);
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "(absent)";
    }

    @Test
    @DisplayName("row 21 groups upstream's two parameters into a class, and three call sites "
        + "in three files nobody pointed at construct it")
    void groupsUpstreamsParameterPair() throws Exception {
        String before = read("RateLimiter.java");
        assertTrue(before.contains(UPSTREAM_DECL),
            "PROOF OF LIFE: the vendored slice must still declare " + UPSTREAM_DECL
                + ". If the fork pin moved, everything below would pass over nothing.");
        for (String caller : new String[] {
            "FindCustomerRequest.java", "App.java", "AdaptiveRateLimiter.java"}) {
            assertTrue(read(caller).contains(".check("),
                "PROOF OF LIFE: " + caller + " must still call check — the cross-file rewrite"
                    + " is the half a single-file demonstration cannot show");
        }

        String[] lines = before.split("\n", -1);
        int line = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(UPSTREAM_DECL)) {
                line = i;
                break;
            }
        }

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "introduce_parameter_object");
        args.put("className", NEW_CLASS);
        args.put("filePath", pkg.resolve("RateLimiter.java").toString());
        args.put("line", line);
        args.put("column", lines[line].indexOf("check"));

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the row must run on foreign code; got: " + r.getError());

        String klass = read(NEW_CLASS + ".java");
        assertTrue(klass.contains("String serviceName") && klass.contains("String operationName"),
            "the created class carries a field per parameter of upstream's group:\n" + klass);

        String after = read("RateLimiter.java");
        assertFalse(after.contains(UPSTREAM_DECL),
            "upstream's two-parameter declaration must be gone:\n" + after);
        assertTrue(after.contains(NEW_CLASS),
            "and the interface method must now take the class:\n" + after);

        // THE THREE IMPLEMENTATIONS. Nothing pointed the tool at any of them; an engine that
        // rewrote only the interface would leave three classes failing to implement it, and the
        // compile gate would have refused the whole change — so each is asserted by what it
        // BECAME rather than merely that it changed.
        for (String impl : new String[] {
            "FixedWindowRateLimiter.java", "TokenBucketRateLimiter.java",
            "AdaptiveRateLimiter.java"}) {
            String source = read(impl);
            assertFalse(source.contains(UPSTREAM_DECL),
                impl + " must no longer declare the two-parameter override:\n" + source);
            assertTrue(source.contains("check(" + NEW_CLASS + " "),
                impl + "'s override must DECLARE the new class as its parameter. Searching for"
                    + " the bare name would pass on an import line, or on a nested class"
                    + " reached through its outer type:\n" + source);
        }

        // THE THREE CALL SITES, in three separate files, pinned as WHOLE EXPRESSIONS.
        //
        // `contains("new ServiceCall(")` was the first version and it is too weak to be worth
        // keeping: MEASURED, it survives the class being created nested instead of top-level,
        // so it says the object is constructed somewhere without saying WITH WHAT. Each
        // expression below names both arguments in order, which is the claim that actually
        // distinguishes a completed grouping from a half-done one.
        assertTrue(read("FindCustomerRequest.java").contains(
                "rateLimiter.check(new ServiceCall(getServiceName(), getOperationName()));"),
            "the call in FindCustomerRequest must pass both of upstream's arguments through the"
                + " new class:\n" + read("FindCustomerRequest.java"));
        assertTrue(read("App.java").contains(
                "limiter.check(new ServiceCall(service, operation));"),
            "and so must App's, whose arguments are locals rather than calls:\n" + read("App.java"));

        // The richest of the three, and it is upstream's own shape rather than one we arranged:
        // AdaptiveRateLimiter both IMPLEMENTS check and DELEGATES to another limiter, so it now
        // receives the object and has to take it apart again to build the inner call. That reads
        // the GENERATED GETTERS, so this one line also proves getters=true reached the engine.
        assertTrue(read("AdaptiveRateLimiter.java").contains(
                "limiter.check(new ServiceCall(parameterObject.getServiceName(), "
                    + "parameterObject.getOperationName()));"),
            "the delegating limiter must unpack the object it received and rebuild one for the"
                + " limiter it forwards to:\n" + read("AdaptiveRateLimiter.java"));
    }
}
