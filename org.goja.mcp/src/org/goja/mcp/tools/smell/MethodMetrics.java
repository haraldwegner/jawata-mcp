package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

/**
 * Sprint 17 — shared per-method AST metrics (cyclomatic complexity, cognitive
 * complexity, physical LOC, parameter count). Extracted verbatim from
 * {@code GetComplexityMetricsTool} so the Fowler smell detectors (Long Method,
 * God Class, Long Parameter List) reuse exactly one definition rather than
 * re-deriving the heuristics. {@code GetComplexityMetricsTool} now delegates
 * here; its behaviour is unchanged (parity-gated).
 */
public final class MethodMetrics {

    private MethodMetrics() {
    }

    /**
     * Cyclomatic complexity: {@code 1 + number of decision points}
     * (if / for / while / do / case / catch / ternary / {@code &&} / {@code ||} / throw).
     */
    public static int cyclomaticComplexity(MethodDeclaration method) {
        final int[] complexity = {1}; // Base complexity

        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(IfStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(ForStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(EnhancedForStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(WhileStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(DoStatement node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(SwitchCase node) {
                if (!node.isDefault()) {
                    complexity[0]++;
                }
                return true;
            }

            @Override
            public boolean visit(CatchClause node) {
                complexity[0]++;
                return true;
            }

            @Override
            public boolean visit(ConditionalExpression node) {
                complexity[0]++; // Ternary operator
                return true;
            }

            @Override
            public boolean visit(InfixExpression node) {
                // && and || add to complexity
                if (node.getOperator() == InfixExpression.Operator.CONDITIONAL_AND ||
                    node.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
                    complexity[0]++;
                }
                return true;
            }

            @Override
            public boolean visit(ThrowStatement node) {
                complexity[0]++;
                return true;
            }
        });

        return complexity[0];
    }

    /**
     * Cognitive complexity: penalizes nesting and breaks in linear flow.
     */
    public static int cognitiveComplexity(MethodDeclaration method) {
        final int[] complexity = {0};
        final int[] nestingLevel = {0};
        final String methodName = method.getName().getIdentifier();

        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(IfStatement node) {
                // +1 for if, +nesting penalty
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(IfStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(ForStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(ForStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(EnhancedForStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(EnhancedForStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(WhileStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(WhileStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(DoStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(DoStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(TryStatement node) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }

            @Override
            public void endVisit(TryStatement node) {
                nestingLevel[0]--;
            }

            @Override
            public boolean visit(CatchClause node) {
                complexity[0] += 1 + nestingLevel[0];
                return true;
            }

            @Override
            public boolean visit(ConditionalExpression node) {
                complexity[0] += 1 + nestingLevel[0];
                return true;
            }

            @Override
            public boolean visit(InfixExpression node) {
                // Binary logical operators (first in sequence only)
                if (node.getOperator() == InfixExpression.Operator.CONDITIONAL_AND ||
                    node.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
                    // Check if parent is also a logical expression
                    if (!(node.getParent() instanceof InfixExpression parent) ||
                        (parent.getOperator() != InfixExpression.Operator.CONDITIONAL_AND &&
                         parent.getOperator() != InfixExpression.Operator.CONDITIONAL_OR)) {
                        complexity[0]++;
                    }
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                // Recursion adds complexity
                if (node.getName().getIdentifier().equals(methodName)) {
                    complexity[0]++;
                }
                return true;
            }
        });

        return complexity[0];
    }

    /**
     * Physical lines of code spanned by the method declaration (inclusive),
     * using the compilation unit's line index. {@code ast} must be the
     * {@link CompilationUnit} the method belongs to.
     */
    public static int physicalLoc(CompilationUnit ast, MethodDeclaration method) {
        int startLine = ast.getLineNumber(method.getStartPosition());
        int endLine = ast.getLineNumber(method.getStartPosition() + method.getLength());
        return endLine - startLine + 1;
    }

    /** Number of declared parameters. */
    public static int parameterCount(MethodDeclaration method) {
        return method.parameters().size();
    }
}
