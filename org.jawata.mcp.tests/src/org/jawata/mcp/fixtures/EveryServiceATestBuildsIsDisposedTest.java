package org.jawata.mcp.fixtures;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.jawata.mcp.catalogue.RepoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REL44-29 — every {@code JdtServiceImpl} a test builds for itself is disposed.
 *
 * <p>{@code JdtServiceImpl.dispose()} says a test harness MUST call it: a service that is never
 * disposed leaves its projects in the workspace every test in the JVM shares, and they keep the
 * indexer and the delta broadcaster working under every later test. {@link TestProjectHelper}
 * disposes the services it loads. A test that builds its own with {@code new JdtServiceImpl()}
 * has to do it itself, and ten classes did not — found while reading the path of a test that
 * failed once and never again (REL44-5), and not shown to be its cause. Its first run named an
 * eleventh service that loaded nothing, and one leak a reading by hand had missed.</p>
 *
 * <p>This reads both test source trees. In each file, a {@code new JdtServiceImpl()} assigned to
 * a name must meet a {@code dispose()} called on that name somewhere in the same file. What it
 * does NOT see, so a green run is not read as more: a service built without a name (returned
 * from a helper, passed straight to a constructor); two locals that share a name in one file,
 * where one {@code dispose()} answers for both; and a {@code dispose()} that exists but is not
 * reached because an assertion before it threw. The last is why the fixes put it in a
 * {@code finally} or an {@code @AfterEach}.</p>
 */
class EveryServiceATestBuildsIsDisposedTest {

    private static final List<String> TEST_SOURCES =
        List.of("org.jawata.mcp.tests/src", "org.jawata.core.tests/src");

    @Test
    @DisplayName("REL44-29: a service a test builds for itself is disposed in the same file")
    void everyServiceATestBuildsIsDisposed() throws IOException {
        List<String> leaks = new ArrayList<>();
        int parsed = 0;
        for (String tree : TEST_SOURCES) {
            Path root = RepoFiles.sourceOf(tree);
            assertNotNull(root, "looked for " + tree + " above " + Path.of("").toAbsolutePath());
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    parsed++;
                    for (String name : undisposed(Files.readString(file))) {
                        leaks.add(tree + "/" + root.relativize(file) + " — " + name);
                    }
                }
            }
        }
        // PROOF OF LIFE: a walk that found nothing would certify every test in both trees.
        assertTrue(parsed > 600, "only " + parsed + " test sources parsed — the walk is wrong,"
            + " not the trees");
        int scanned = parsed;
        assertTrue(leaks.isEmpty(), () -> leaks.size() + " service(s) built in " + scanned
            + " test sources are never disposed. Dispose each one in a finally or an @AfterEach,"
            + " or load through TestProjectHelper, which disposes what it loads:\n  "
            + String.join("\n  ", leaks));
    }

    /**
     * The source check above is satisfied by a call to {@code disposeAfter}; this is what makes
     * that call worth satisfying it with. A project loaded into a service handed to the helper is
     * gone from the shared workspace once the helper's {@code afterEach} has run.
     */
    @Test
    @DisplayName("REL44-29: a service handed to disposeAfter leaves the shared workspace when the test ends")
    void aServiceHandedToTheHelperLeavesTheWorkspace() throws Exception {
        TestProjectHelper helper = new TestProjectHelper();
        helper.beforeEach(null);
        org.jawata.resident.workspace.JdtServiceImpl service =
            helper.disposeAfter(new org.jawata.resident.workspace.JdtServiceImpl());
        service.loadProject(helper.copyFixture("simple-maven"));
        org.eclipse.core.resources.IProject project = service.getJavaProject().getProject();
        assertTrue(project.exists(), "the fixture loaded into the shared workspace: " + project);

        helper.afterEach(null);

        assertTrue(!project.exists(), "the project the test's own service loaded is still in the"
            + " workspace every later test shares: " + project);
    }

    /** The names {@code source} binds to a new {@code JdtServiceImpl} and never disposes. */
    static Set<String> undisposed(String source) {
        if (!source.contains("JdtServiceImpl")) {
            return Set.of();
        }
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        Set<String> built = new LinkedHashSet<>();
        Set<String> disposed = new LinkedHashSet<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(ClassInstanceCreation creation) {
                if (!"JdtServiceImpl".equals(simpleName(creation.getType().toString()))) {
                    return true;
                }
                ASTNode parent = creation.getParent();
                if (parent instanceof VariableDeclarationFragment fragment) {
                    built.add(fragment.getName().getIdentifier());
                } else if (parent instanceof Assignment assignment) {
                    String name = nameOf(assignment.getLeftHandSide());
                    if (name != null) {
                        built.add(name);
                    }
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation call) {
                if ("dispose".equals(call.getName().getIdentifier()) && call.arguments().isEmpty()) {
                    String name = nameOf(call.getExpression());
                    if (name != null) {
                        disposed.add(name);
                    }
                }
                return true;
            }
        });
        built.removeAll(disposed);
        return built;
    }

    /** {@code x} or {@code this.x} as {@code x}; anything else has no name to pair. */
    private static String nameOf(Expression expression) {
        if (expression instanceof SimpleName name) {
            return name.getIdentifier();
        }
        if (expression instanceof FieldAccess access) {
            return access.getName().getIdentifier();
        }
        return null;
    }

    private static String simpleName(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }
}
