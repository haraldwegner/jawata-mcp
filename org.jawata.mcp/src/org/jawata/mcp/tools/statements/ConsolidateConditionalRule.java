package org.jawata.mcp.tools.statements;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Fowler — <b>Consolidate Conditional Expression</b> (row 7).
 *
 * <p>Several checks in a row, each doing the same thing, are one check written several
 * times. Fowler's point is that the separate conditions imply separate reasons, and when
 * the outcome is identical there is only one reason — so saying it once makes the code
 * state what is actually being decided.</p>
 *
 * <pre>
 *   if (seniority &lt; 2) return 0;          if (seniority &lt; 2
 *   if (monthsDisabled &gt; 12) return 0;  --&gt;     || monthsDisabled &gt; 12
 *   if (isPartTime) return 0;                  || isPartTime) return 0;
 * </pre>
 *
 * <h2>When this is safe, and why each condition is needed</h2>
 *
 * <ul>
 *   <li><b>Adjacent</b> — the {@code if}s must be consecutive statements in one block.
 *       Anything between them might change what a later condition reads.</li>
 *   <li><b>No {@code else}</b> — an else means the checks are not the same decision.</li>
 *   <li><b>Identical then-branches</b>, compared as source text after normalising
 *       whitespace. Two branches that merely look similar are two decisions.</li>
 *   <li><b>Side-effect-free conditions</b>, and this is the one that does the work.
 *       {@code ||} short-circuits: once the conditions are joined, a later one is not
 *       evaluated when an earlier one holds. If evaluating it did something — a method
 *       call, an assignment, an increment — that something stops happening, which is a
 *       different program. So a condition containing any of those is refused, and that
 *       refusal is conservative on purpose: a getter with no side effects is
 *       indistinguishable from one with them without reading it.</li>
 * </ul>
 *
 * <p>The consolidated condition keeps each original in parentheses and joins them with
 * {@code ||}, so precedence cannot shift under the rewrite.</p>
 */
public final class ConsolidateConditionalRule implements CleanupRule {

    @Override
    public String kind() {
        return "consolidate_conditional";
    }

    @Override
    public String describe() {
        return "consolidate_conditional — Consolidate Conditional Expression: adjacent `if`s\n"
            + "                        with no `else` and the SAME body become one `if` joined\n"
            + "                        by ||, each condition parenthesised so precedence cannot\n"
            + "                        shift. Refuses any condition containing a method call, an\n"
            + "                        assignment or an increment: || short-circuits, so a later\n"
            + "                        condition stops being evaluated and its side effect stops\n"
            + "                        happening.";
    }

    @Override
    public TextEdit edit(CompilationUnit ast) throws Exception {
        List<List<IfStatement>> runs = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(Block node) {
                collectRuns(node, runs);
                return true;
            }
        });
        if (runs.isEmpty()) {
            return null;
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        for (List<IfStatement> run : runs) {
            consolidate(run, rewrite);
        }
        Document document =
            new Document(String.valueOf(ast.getTypeRoot().getBuffer().getContents()));
        return rewrite.rewriteAST(document, null);
    }

    /** Runs of two or more adjacent, consolidatable ifs in one block. */
    private static void collectRuns(Block block, List<List<IfStatement>> runs) {
        List<?> statements = block.statements();
        int i = 0;
        while (i < statements.size()) {
            if (!(statements.get(i) instanceof IfStatement first) || !isEligible(first)) {
                i++;
                continue;
            }
            List<IfStatement> run = new ArrayList<>();
            run.add(first);
            int j = i + 1;
            while (j < statements.size() && statements.get(j) instanceof IfStatement next
                    && isEligible(next) && sameBody(first, next)) {
                run.add(next);
                j++;
            }
            if (run.size() > 1) {
                runs.add(run);
            }
            i = Math.max(j, i + 1);
        }
    }

    /**
     * No else, a condition that may be skipped, and a body that LEAVES the method.
     *
     * <p>The exit requirement was missing and it is the one that makes this a
     * refactoring. Fowler defines the mechanic over checks with the same RESULT — the
     * body returns or throws, so at most one can ever run. Without it:</p>
     *
     * <pre>
     *   if (a) sb.append('!');        joined:  if (a || b) sb.append('!');
     *   if (b) sb.append('!');
     * </pre>
     *
     * <p>and {@code a &amp;&amp; b} appends once where it appended twice. Both conditions are
     * plain names, the bodies normalise identically, and the result COMPILES — so neither
     * the suite nor the apply pipeline's compile gate could see it. A whole-project sweep
     * would have made that edit everywhere the shape occurs.</p>
     */
    private static boolean isEligible(IfStatement node) {
        return node.getElseStatement() == null
            && org.jawata.mcp.refactoring.Effects.isSideEffectFree(node.getExpression())
            && org.jawata.mcp.refactoring.Effects.alwaysExits(node.getThenStatement());
    }


    /** The same body, compared as normalised source — two similar bodies are two decisions. */
    private static boolean sameBody(IfStatement a, IfStatement b) {
        return normalise(a.getThenStatement()).equals(normalise(b.getThenStatement()));
    }

    private static String normalise(Statement statement) {
        return statement.toString().replaceAll("\\s+", " ").trim();
    }

    /** Join the run's conditions with ||, keep the first body, drop the rest. */
    private static void consolidate(List<IfStatement> run, ASTRewrite rewrite) {
        org.eclipse.jdt.core.dom.AST ast = run.get(0).getAST();
        InfixExpression joined = ast.newInfixExpression();
        joined.setOperator(InfixExpression.Operator.CONDITIONAL_OR);
        joined.setLeftOperand(parenthesised(run.get(0).getExpression(), rewrite, ast));
        joined.setRightOperand(parenthesised(run.get(1).getExpression(), rewrite, ast));
        for (int i = 2; i < run.size(); i++) {
            @SuppressWarnings("unchecked")
            List<Expression> extended = joined.extendedOperands();
            extended.add(parenthesised(run.get(i).getExpression(), rewrite, ast));
        }
        rewrite.set(run.get(0), IfStatement.EXPRESSION_PROPERTY, joined, null);
        for (int i = 1; i < run.size(); i++) {
            rewrite.remove(run.get(i), null);
        }
    }

    /**
     * Each original condition in its own parentheses.
     *
     * <p>Not cosmetic: {@code a && b} joined bare with {@code ||} reassociates, because
     * {@code &&} binds tighter. Parenthesising every operand means the rewrite cannot
     * change which expression each condition is.</p>
     */
    private static Expression parenthesised(Expression original, ASTRewrite rewrite,
                                            org.eclipse.jdt.core.dom.AST ast) {
        // UNCONDITIONALLY. The special case for InfixExpression left every other
        // loose-binding shape bare, and a conditional expression binds LOOSER than || —
        // `flag ? p : q` joined with `r` reassociates to `flag ? p : (q || r)`, a
        // different expression that compiles. The javadoc above promised the guarantee
        // without qualification; the code kept it for one node type. Wrapping everything
        // costs a pair of parentheses and cannot be wrong.
        ParenthesizedExpression wrapped = ast.newParenthesizedExpression();
        wrapped.setExpression((Expression) rewrite.createCopyTarget(original));
        return wrapped;
    }
}
