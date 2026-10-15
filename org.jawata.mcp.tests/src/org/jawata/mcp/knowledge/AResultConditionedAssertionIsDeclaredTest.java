package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.jawata.mcp.catalogue.RepoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REL44-22 — a test does not check its subject only IF the result happened to contain it.
 *
 * <p>The shape: {@code if (!fixes.isEmpty()) { assertNotNull(fixes.get(0).get("fixId")); }}. On
 * the day the tool returns nothing, the check is skipped and the test is green. That is how
 * GetQuickFixesToolTest stayed green while the tool answered {@code problemCount=0} for every
 * file (REL44-26). The census behind this guard found eighteen such assertions; twelve were
 * converted to unconditional ones, one redundant guard was removed, and the five whose
 * condition is a genuine part of the claim are declared below with the reason.</p>
 *
 * <p>The guard reads SOURCE: an assertion inside an {@code if} with no {@code else}, in a test
 * method, whose condition asks whether a result is empty, sized, null or contains something.
 * A new site fails it. If the condition really is part of what is being claimed, declare it
 * here with the reason — a declaration nobody can read the reason for is the bypass this
 * exists to refuse.</p>
 */
class AResultConditionedAssertionIsDeclaredTest {

    private static final String TEST_SOURCES = "org.jawata.mcp.tests/src";

    /** Declared sites, as {@code <file>#<method>}, each with why its condition is the claim. */
    private static final Map<String, String> DECLARED = Map.of(
        "org/jawata/mcp/models/WorkspaceIdentityTest.java#uninstalled_isSilent",
        "a null hint IS the silent outcome under test; the check applies only when a hint exists",
        "org/jawata/mcp/tools/navigation/FindReferencesToolTest.java#everyReferenceRowIsUsableOrDeclaresItselfUnresolved",
        "per-row: an unresolved row carries no path by design, and the row's own clause covers it",
        "org/jawata/mcp/tools/verification/PdeExternalPoolTest.java#externalPool_compilesAndRunsPdeTests",
        "the if only builds a richer failure message; the assertNull inside always fails when entered",
        "org/jawata/mcp/tools/verification/RunTestsToolTest.java#schema_methodScopeDescription_doesNotImplyFilePathMethodName",
        "the phrase is allowed to be absent; the claim is about the context IF it appears",
        "org/jawata/mcp/tools/refactoring/OrganizeImportsToolTest.java#organizeImports_appliesOrShortCircuits",
        "an ordering between two import groups is only defined when both groups are present");

    @Test
    @DisplayName("REL44-22: no test checks its subject only if the result happened to contain it")
    void everyResultConditionedAssertionIsDeclared() throws IOException {
        Path root = RepoFiles.sourceOf(TEST_SOURCES);
        assertNotNull(root, "looked for " + TEST_SOURCES + " above " + Path.of("").toAbsolutePath());

        TreeSet<String> found = new TreeSet<>();
        List<String> lines = new ArrayList<>();
        int parsed = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                parsed++;
                String rel = root.relativize(file).toString().replace('\\', '/');
                for (String[] hit : sites(Files.readString(file))) {
                    found.add(rel + "#" + hit[0]);
                    lines.add(rel + ":" + hit[1] + " " + hit[0] + " if (" + hit[2] + ")");
                }
            }
        }
        assertTrue(parsed > 500, "only " + parsed + " test sources parsed — the walk is wrong");

        TreeSet<String> undeclared = new TreeSet<>(found);
        undeclared.removeAll(DECLARED.keySet());
        assertTrue(undeclared.isEmpty(), () -> undeclared.size() + " test(s) check their subject"
            + " only if the result happened to contain it, so an empty answer passes them."
            + " Assert the condition instead, or declare it here with why it is the claim:\n  "
            + String.join("\n  ", lines.stream()
                .filter(l -> undeclared.stream().anyMatch(u -> l.startsWith(u.substring(0,
                    u.indexOf('#'))) && l.contains(" " + u.substring(u.indexOf('#') + 1) + " ")))
                .toList()));

        // A declaration whose site has gone is a stale exemption, waiting to cover a new one.
        TreeSet<String> stale = new TreeSet<>(DECLARED.keySet());
        stale.removeAll(found);
        assertEquals(List.of(), List.copyOf(stale),
            "these declared sites no longer exist; remove them from DECLARED");
    }

    /** Each site as {method, line, condition}. */
    private static List<String[]> sites(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        List<String[]> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() == null || !isTest(method)) {
                    return true;
                }
                method.getBody().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(IfStatement guard) {
                        String condition = guard.getExpression().toString();
                        if (guard.getElseStatement() == null && asksAboutTheResult(condition)
                                && asserts(guard)) {
                            out.add(new String[] {method.getName().getIdentifier(),
                                String.valueOf(unit.getLineNumber(guard.getStartPosition())),
                                condition.replace("\n", " ")});
                        }
                        return true;
                    }
                });
                return false;
            }
        });
        return out;
    }

    private static boolean asksAboutTheResult(String condition) {
        return condition.contains("isEmpty()") || condition.contains("size()")
            || condition.contains("!= null") || condition.contains("== null")
            || condition.contains(".contains(") || condition.contains("isPresent()");
    }

    private static boolean asserts(IfStatement guard) {
        boolean[] found = {false};
        guard.getThenStatement().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation call) {
                String name = call.getName().getIdentifier();
                if (name.startsWith("assert") || name.startsWith("fail")) {
                    found[0] = true;
                }
                return true;
            }
        });
        return found[0];
    }

    private static boolean isTest(MethodDeclaration method) {
        for (Object modifier : method.modifiers()) {
            if (modifier instanceof Annotation a) {
                String name = a.getTypeName().getFullyQualifiedName();
                String simple = name.substring(name.lastIndexOf('.') + 1);
                if (List.of("Test", "ParameterizedTest", "RepeatedTest").contains(simple)) {
                    return true;
                }
            }
        }
        return false;
    }
}
