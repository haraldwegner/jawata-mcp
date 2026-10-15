package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.jawata.mcp.catalogue.RepoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REL44-21 — a test that needs the embedder ABORTS when it is missing; it never returns early
 * and passes.
 *
 * <p>JUnit records a bare {@code return} as a PASS, so a test that leaves before its assertions
 * when {@code EmbeddingService.shared().available()} is false reads as a check that held, while
 * an assumption reads as a skip and is counted against the suite's accepted-abort budget. A
 * test-manager hire found four of these on 2026-10-15; this guard's first run found
 * twenty-seven, in eight classes, two of them under a comment reading "not an abort dressed as a
 * pass".</p>
 *
 * <p>This reads the test SOURCES rather than running them, because the behaviour it guards only
 * shows on a machine where the embedder is absent — never on this one or in CI, where it loads.
 * So the shape is refused where it is written: an {@code if} inside a test method whose
 * condition negates an {@code available()} or {@code embedderAvailable()} call, and whose branch
 * returns without asserting, failing or assuming anything. A branch that asserts the degraded
 * answer before it returns ({@code EmbeddingStoreTest}'s write-without-a-vector case) is a real
 * check and is not refused.</p>
 *
 * <p>What it does NOT see, stated so a green run is not read as more: a test that FAILS rather
 * than passes when the embedder is missing, and an assertion wrapped in a POSITIVE guard
 * ({@code if (covered) assert…}). Both were found by the same embedder-off run and fixed by hand;
 * only a run with {@code -Djawata.embed.disabled=true} would catch a new one.</p>
 */
class AMissingEmbedderIsASkipNotAPassTest {

    private static final String TEST_SOURCES = "org.jawata.mcp.tests/src";

    /** Annotations that make a method a test JUnit will report on. */
    private static final List<String> TEST_ANNOTATIONS =
        List.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

    @Test
    @DisplayName("REL44-21: no test returns as a pass when the embedder is missing")
    void noTestPassesSilentlyWithoutTheEmbedder() throws IOException {
        Path root = RepoFiles.sourceOf(TEST_SOURCES);
        assertNotNull(root, "looked for " + TEST_SOURCES + " above " + Path.of("").toAbsolutePath());

        List<String> silent = new ArrayList<>();
        int parsed = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                parsed++;
                silent.addAll(silentSkips(root.relativize(file).toString(), Files.readString(file)));
            }
        }
        // PROOF OF LIFE: a walk that found nothing would certify every test in the tree.
        assertTrue(parsed > 500, "only " + parsed + " test sources parsed under " + root
            + " — the walk is wrong, not the tree");
        int scanned = parsed;
        assertTrue(silent.isEmpty(), () -> silent.size() + " test(s) of " + scanned
            + " sources return as a PASS when the embedder is missing. Replace the early return"
            + " with Assumptions.assumeTrue(<available>, \"<what did not run>\"), so a run without"
            + " the embedder reports them as skipped:\n  " + String.join("\n  ", silent));
    }

    /** Every embedder guard in {@code source} that leaves a test method as a silent pass. */
    static List<String> silentSkips(String file, String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        List<String> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() == null || !isTest(method)) {
                    return true;
                }
                method.getBody().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(IfStatement guard) {
                        if (asksWhetherItIsMissing(guard.getExpression())
                                && leavesWithoutChecking(guard.getThenStatement())) {
                            out.add(file + ":" + unit.getLineNumber(guard.getStartPosition())
                                + " " + method.getName().getIdentifier());
                        }
                        return true;
                    }
                });
                return false;
            }
        });
        return out;
    }

    private static boolean isTest(MethodDeclaration method) {
        for (Object modifier : method.modifiers()) {
            if (modifier instanceof Annotation a) {
                String name = a.getTypeName().getFullyQualifiedName();
                if (TEST_ANNOTATIONS.contains(name.substring(name.lastIndexOf('.') + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A negated {@code available()} / {@code embedderAvailable()} call anywhere in it. */
    private static boolean asksWhetherItIsMissing(Expression condition) {
        boolean[] found = {false};
        condition.accept(new ASTVisitor() {
            @Override
            public boolean visit(PrefixExpression not) {
                if (not.getOperator() == PrefixExpression.Operator.NOT
                        && namesAvailability(not.getOperand())) {
                    found[0] = true;
                }
                return true;
            }
        });
        return found[0];
    }

    private static boolean namesAvailability(Expression e) {
        while (e instanceof ParenthesizedExpression p) {
            e = p.getExpression();
        }
        if (!(e instanceof MethodInvocation call)) {
            return false;
        }
        String name = call.getName().getIdentifier();
        return name.equals("available") || name.equals("embedderAvailable");
    }

    /** It returns, and nothing in it asserts, fails, assumes or throws first. */
    private static boolean leavesWithoutChecking(Statement branch) {
        boolean[] returns = {false};
        boolean[] checks = {false};
        branch.accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement r) {
                returns[0] = true;
                return true;
            }

            @Override
            public boolean visit(ThrowStatement t) {
                checks[0] = true;
                return true;
            }

            @Override
            public boolean visit(MethodInvocation call) {
                String name = call.getName().getIdentifier();
                if (name.startsWith("assert") || name.startsWith("fail")
                        || name.startsWith("assume") || name.startsWith("abort")) {
                    checks[0] = true;
                }
                return true;
            }
        });
        return returns[0] && !checks[0];
    }
}
