package org.jawata.mcp.tools.statements;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Fowler — <b>Return Modified Value</b> (row 60), the LOCAL cure for
 * {@code cqs}.
 *
 * <p>A method that answers a question should return the answer, not build it up in a
 * mutable local and hand that back at the end. The local form of the smell is a variable
 * whose entire purpose is to carry the return value:</p>
 *
 * <pre>
 *   String describe(int n) {                 String describe(int n) {
 *       String result;                           if (n &lt; 0) {
 *       if (n &lt; 0) {                                 return "negative";
 *           result = "negative";      --&gt;        } else {
 *       } else {                                     return "positive";
 *           result = "positive";                 }
 *       }                                    }
 *       return result;
 *   }
 * </pre>
 *
 * <p>Row 61, Separate Query from Modifier, is the cross-file half of the same smell —
 * that one splits a method whose answer and whose mutation are both wanted by callers.
 * This one never leaves the method: it removes a mutation the method was performing on
 * itself.</p>
 *
 * <h2>What must hold, and why each clause is there</h2>
 *
 * <p>Every one of these is a way the rewrite would otherwise change behaviour, and the
 * fixture carries a method for each:</p>
 *
 * <ul>
 *   <li><b>The method ends in {@code return v;}</b> and that is the LAST statement of
 *       its body. The transformation moves the return earlier, so anything after it is
 *       code that would stop running.</li>
 *   <li><b>The variable is READ nowhere else.</b> If any other statement reads it, the
 *       variable is carrying information and is not merely holding the answer — deleting
 *       its declaration would not compile, and returning early would skip the read.</li>
 *   <li><b>Every assignment is the LAST statement of its branch.</b> This is the clause
 *       that matters most. {@code if (a) { v = 1; log(); }} becomes {@code return 1;} and
 *       the logging never happens — the method compiles, the tests that do not assert on
 *       logging pass, and the behaviour is gone.</li>
 *   <li><b>Every path assigns.</b> Otherwise the trailing {@code return v} is still
 *       reachable, and with the declaration removed there is nothing to return. A
 *       partially-converted method is worse than an unconverted one, so the rule takes
 *       all of it or none.</li>
 *   <li><b>The declaration has no initializer that is itself an answer.</b> A variable
 *       declared with a value and then overwritten on every path is the same shape; a
 *       variable whose initializer SURVIVES on some path is the previous clause's case
 *       and is refused there.</li>
 * </ul>
 *
 * <p>Nested and labelled control flow is refused wholesale rather than analysed: an
 * assignment inside a loop is not the method's answer, it is an accumulation, and
 * Fowler's other rows own that shape.</p>
 */
public final class ReturnModifiedValueRule implements CleanupRule {

    @Override
    public String kind() {
        return "return_modified_value";
    }

    @Override
    public String describe() {
        return "return_modified_value — Return Modified Value: a local whose only job is to hold\n"
            + "                        the answer, assigned on every branch and returned at the end,\n"
            + "                        becomes a direct return per branch and the variable goes. The\n"
            + "                        local cure for the cqs smell — the method stops mutating state\n"
            + "                        to express its result. Refuses when the variable is read\n"
            + "                        anywhere else, when an assignment is not the last statement of\n"
            + "                        its branch (the statements after it would stop running), when\n"
            + "                        any path leaves it unassigned, or when the assignment is inside\n"
            + "                        a loop, which is an accumulation and not an answer.";
    }

    @Override
    public TextEdit edit(CompilationUnit ast) throws Exception {
        List<MethodDeclaration> targets = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (answerHolder(node) != null) {
                    targets.add(node);
                }
                return true;
            }
        });
        if (targets.isEmpty()) {
            return null;
        }
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        for (MethodDeclaration method : targets) {
            convert(method, rewrite);
        }
        Document document =
            new Document(String.valueOf(ast.getTypeRoot().getBuffer().getContents()));
        return rewrite.rewriteAST(document, null);
    }

    /**
     * The variable this method uses only to carry its return value, or null.
     *
     * <p>Returns the binding rather than a boolean because every later step needs it, and
     * re-deriving it per step is how two copies of one condition start disagreeing.</p>
     */
    private static IVariableBinding answerHolder(MethodDeclaration method) {
        Block body = method.getBody();
        if (body == null || body.statements().size() < 3) {
            return null;   // declaration + at least one branch + return
        }
        List<?> statements = body.statements();
        Statement last = (Statement) statements.get(statements.size() - 1);
        if (!(last instanceof ReturnStatement ret)
                || !(ret.getExpression() instanceof SimpleName returned)) {
            return null;
        }
        IVariableBinding held = asLocal(returned.resolveBinding());
        if (held == null) {
            return null;
        }
        // The declaration must be the first statement, so that everything between it and
        // the return is a candidate branch. A declaration further down means statements
        // precede it that this rule has not looked at.
        if (!(statements.get(0) instanceof VariableDeclarationStatement decl)
                || decl.fragments().size() != 1) {
            return null;
        }
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) decl.fragments().get(0);
        if (!held.isEqualTo(fragment.resolveBinding())) {
            return null;
        }

        // READ NOWHERE ELSE. The final return is the only permitted read, and it is
        // excluded by identity rather than by position: a second `return v` inside a
        // branch is a read this rule has not planned for.
        if (readsOutsideOf(body, held, returned)) {
            return null;
        }

        // Every assignment must be the last statement of its branch, and every branch
        // must assign. `assignments` gathers them; `allPathsAssign` is the completeness
        // half, and both must hold or the method is left alone.
        List<Assignment> assignments = tailAssignments(body, held);
        if (assignments.isEmpty()) {
            return null;
        }
        for (int i = 1; i < statements.size() - 1; i++) {
            if (!branchAssignsOnEveryPath((Statement) statements.get(i), held, assignments)) {
                return null;
            }
        }
        return held;
    }

    private static IVariableBinding asLocal(Object binding) {
        if (binding instanceof IVariableBinding variable
                && !variable.isField()
                && !variable.isParameter()) {
            return variable;
        }
        return null;
    }

    /** Is the variable read anywhere except the one return we are about to remove? */
    private static boolean readsOutsideOf(Block body, IVariableBinding held, SimpleName allowed) {
        boolean[] found = { false };
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (name == allowed || !held.isEqualTo(name.resolveBinding())) {
                    return true;
                }
                // The left-hand side of an assignment to it is a WRITE, not a read.
                if (name.getParent() instanceof Assignment assignment
                        && assignment.getLeftHandSide() == name) {
                    return true;
                }
                if (name.getParent() instanceof VariableDeclarationFragment) {
                    return true;   // its own declaration
                }
                found[0] = true;
                return false;
            }
        });
        return found[0];
    }

    /**
     * Assignments to the variable that are the LAST statement of their enclosing block,
     * and are not inside a loop.
     *
     * <p>Anything failing either test is deliberately absent from the list, which makes
     * {@link #branchAssignsOnEveryPath} refuse the method: a branch whose assignment is
     * not convertible must not be half-converted.</p>
     */
    private static List<Assignment> tailAssignments(Block body, IVariableBinding held) {
        List<Assignment> out = new ArrayList<>();
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                if (!(node.getLeftHandSide() instanceof SimpleName name)
                        || !held.isEqualTo(name.resolveBinding())
                        || node.getOperator() != Assignment.Operator.ASSIGN) {
                    return true;   // a compound assignment accumulates; not an answer
                }
                if (org.jawata.mcp.refactoring.Effects.containsJump(node)) {
                    return true;
                }
                ASTNode statement = node.getParent();
                if (!(statement instanceof org.eclipse.jdt.core.dom.ExpressionStatement)) {
                    return true;
                }
                if (insideLoop(statement)) {
                    return true;   // accumulation, not the method's answer
                }
                if (!(statement.getParent() instanceof Block enclosing)) {
                    return true;   // `if (a) v = 1;` without braces — leave it
                }
                List<?> siblings = enclosing.statements();
                if (siblings.get(siblings.size() - 1) == statement) {
                    out.add(node);
                }
                return true;
            }
        });
        return out;
    }

    private static boolean insideLoop(ASTNode node) {
        for (ASTNode p = node.getParent(); p != null; p = p.getParent()) {
            if (p instanceof org.eclipse.jdt.core.dom.ForStatement
                    || p instanceof org.eclipse.jdt.core.dom.EnhancedForStatement
                    || p instanceof org.eclipse.jdt.core.dom.WhileStatement
                    || p instanceof org.eclipse.jdt.core.dom.DoStatement) {
                return true;
            }
            if (p instanceof MethodDeclaration) {
                return false;
            }
        }
        return false;
    }

    /**
     * Does this statement assign the variable on EVERY path through it, using only
     * assignments from the convertible list?
     *
     * <p>Conservative by construction: an {@code if} without an {@code else} does not
     * qualify, because the missing branch falls through to a return this rule is about to
     * delete.</p>
     */
    private static boolean branchAssignsOnEveryPath(
            Statement statement, IVariableBinding held, List<Assignment> convertible) {
        if (statement instanceof Block block) {
            List<?> inner = block.statements();
            return !inner.isEmpty()
                && branchAssignsOnEveryPath(
                    (Statement) inner.get(inner.size() - 1), held, convertible);
        }
        if (statement instanceof org.eclipse.jdt.core.dom.IfStatement branch) {
            return branch.getElseStatement() != null
                && branchAssignsOnEveryPath(branch.getThenStatement(), held, convertible)
                && branchAssignsOnEveryPath(branch.getElseStatement(), held, convertible);
        }
        if (statement instanceof org.eclipse.jdt.core.dom.ExpressionStatement expression) {
            return expression.getExpression() instanceof Assignment assignment
                && convertible.contains(assignment);
        }
        return false;
    }

    /** Each tail assignment becomes a return; the declaration and final return go. */
    private static void convert(MethodDeclaration method, ASTRewrite rewrite) {
        Block body = method.getBody();
        List<?> statements = body.statements();
        IVariableBinding held = answerHolder(method);
        for (Assignment assignment : tailAssignments(body, held)) {
            ReturnStatement replacement = body.getAST().newReturnStatement();
            replacement.setExpression(
                (Expression) ASTNode.copySubtree(body.getAST(), assignment.getRightHandSide()));
            rewrite.replace(assignment.getParent(), replacement, null);
        }
        rewrite.remove((ASTNode) statements.get(0), null);
        rewrite.remove((ASTNode) statements.get(statements.size() - 1), null);
    }
}
