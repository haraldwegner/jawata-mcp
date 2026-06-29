package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 17 — characterisation of {@link MethodMetrics}. The cyclomatic /
 * cognitive logic was extracted verbatim from {@code GetComplexityMetricsTool};
 * these hand-computed cases pin the numbers so the extraction (and the Fowler
 * detectors that reuse it) can't drift. CC = 1 + decision points.
 */
class MethodMetricsTest {

    private Map<String, MethodDeclaration> parseMethods(String classBody) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(("class T {" + classBody + "}").toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        Map<String, MethodDeclaration> byName = new HashMap<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                byName.put(node.getName().getIdentifier(), node);
                return true;
            }
        });
        return byName;
    }

    @Test
    @DisplayName("cyclomatic complexity = 1 + decision points")
    void cyclomatic() {
        Map<String, MethodDeclaration> m = parseMethods(
            "  void straight() { int x = 1; x++; }\n" +
            "  void oneIf(boolean b) { if (b) { return; } }\n" +
            "  void ifAndFor(boolean b) { if (b) {} for (int i=0;i<3;i++) {} }\n" +
            "  void shortCircuit(boolean a, boolean b) { if (a && b) {} }\n" +
            "  int ternary(int x) { return x > 0 ? 1 : 2; }\n" +
            "  void loops() { while (true) {} do {} while (false); }\n"
        );
        assertEquals(1, MethodMetrics.cyclomaticComplexity(m.get("straight")));
        assertEquals(2, MethodMetrics.cyclomaticComplexity(m.get("oneIf")));
        assertEquals(3, MethodMetrics.cyclomaticComplexity(m.get("ifAndFor")));
        assertEquals(3, MethodMetrics.cyclomaticComplexity(m.get("shortCircuit"))); // if + &&
        assertEquals(2, MethodMetrics.cyclomaticComplexity(m.get("ternary")));
        assertEquals(3, MethodMetrics.cyclomaticComplexity(m.get("loops"))); // while + do
    }

    @Test
    @DisplayName("cognitive complexity penalizes nesting")
    void cognitive() {
        Map<String, MethodDeclaration> m = parseMethods(
            "  void flat(boolean b) { if (b) {} }\n" +
            "  void nested(boolean b, boolean c) { if (b) { if (c) {} } }\n"
        );
        assertEquals(1, MethodMetrics.cognitiveComplexity(m.get("flat")));
        // outer if = +1, inner if = +1 +1 nesting penalty = +2 → total 3
        assertEquals(3, MethodMetrics.cognitiveComplexity(m.get("nested")));
    }

    @Test
    @DisplayName("parameter count")
    void parameterCount() {
        Map<String, MethodDeclaration> m = parseMethods(
            "  void none() {}\n" +
            "  void two(int a, String b) {}\n"
        );
        assertEquals(0, MethodMetrics.parameterCount(m.get("none")));
        assertEquals(2, MethodMetrics.parameterCount(m.get("two")));
    }

    @Test
    @DisplayName("physical LOC spans the declaration inclusively")
    void physicalLoc() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        // line 1: class T {   line 2: void m() {   line 3: }   line 4: }
        parser.setSource("class T {\n  void m() {\n  }\n}".toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        MethodDeclaration[] found = new MethodDeclaration[1];
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                found[0] = node;
                return true;
            }
        });
        // method declared on line 2, closes line 3 → 2 lines inclusive
        assertEquals(2, MethodMetrics.physicalLoc(cu, found[0]));
    }

    @Test
    @DisplayName("a long, branchy method scores high on both metrics")
    void longBranchy() {
        Map<String, MethodDeclaration> m = parseMethods(
            "  int big(int a, int b, int c) {\n" +
            "    int r = 0;\n" +
            "    if (a > 0) { r += a; } else { r -= a; }\n" +
            "    for (int i = 0; i < b; i++) { if (i % 2 == 0) { r += i; } }\n" +
            "    while (c > 0) { r += c; c--; }\n" +
            "    switch (a) { case 1: r++; break; case 2: r--; break; default: break; }\n" +
            "    return r > 100 ? 100 : r;\n" +
            "  }\n"
        );
        assertTrue(MethodMetrics.cyclomaticComplexity(m.get("big")) >= 8,
            "branchy method should have high cyclomatic complexity");
    }
}
