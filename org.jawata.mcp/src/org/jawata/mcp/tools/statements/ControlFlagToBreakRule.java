package org.jawata.mcp.tools.statements;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Fowler — <b>Replace Control Flag with Break</b> (row 44).
 *
 * <p>A boolean whose only job is to stop a loop is the loop's exit written in code that
 * does not say so. The language has a word for leaving a loop, and using it removes both
 * the variable and the reader's need to work out what it means.</p>
 *
 * <pre>
 *   boolean done = false;                    while (true) {
 *   while (!done) {                              ...
 *       ...                          --&gt;         break;
 *       done = true;                         }
 *   }
 * </pre>
 *
 * <h2>The narrow case, and why it is narrow</h2>
 *
 * <p>This fires on one shape and refuses everything else, because a control flag is
 * usually also carrying information — Fowler's own example uses the flag as a RESULT as
 * well as a guard, and rewriting that needs a judgement about what the loop is for. What
 * is mechanical is the flag that carries nothing:</p>
 *
 * <ol>
 *   <li>a {@code while} loop whose condition is exactly {@code !flag};</li>
 *   <li>{@code flag} is a boolean LOCAL declared immediately before the loop and
 *       initialised {@code false};</li>
 *   <li>the loop body's LAST statement assigns {@code true} to it — so nothing else in
 *       the body would have run before the condition was checked again, which is what
 *       makes {@code break} equivalent rather than merely similar;</li>
 *   <li>nothing outside the loop ever READS it. A flag that is read afterwards is
 *       carrying an answer, and deleting it would delete the answer.</li>
 * </ol>
 *
 * <p>All four hold or nothing is touched. The rewrite then does three things at once and
 * has to: the condition becomes {@code true}, the assignment becomes {@code break}, and
 * the declaration goes. Doing any two of them alone leaves code that does not compile or
 * a variable nothing sets.</p>
 */
public final class ControlFlagToBreakRule implements CleanupRule {

    @Override
    public String kind() {
        return "control_flag_to_break";
    }

    @Override
    public String kindSummary() {
        return """
            Replace Control Flag with Break: `boolean done = false;
            while (!done) { ...; done = true; }` becomes
            `while (true) { ...; break; }` and the flag goes. Requires
            all four: the condition is exactly !flag, the flag is a
            local initialised false directly above the loop, the
            assignment is the body's LAST statement, and nothing
            outside the loop reads it. A flag that is read afterwards
            is carrying an answer, not just an exit.""";
    }

    @Override
    public TextEdit edit(CompilationUnit ast) throws Exception {
        List<Candidate> candidates = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(WhileStatement node) {
                Candidate candidate = candidateFor(node);
                if (candidate != null) {
                    candidates.add(candidate);
                }
                return true;
            }
        });
        if (candidates.isEmpty()) {
            return null;
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        for (Candidate candidate : candidates) {
            rewrite(candidate, rewrite);
        }
        Document document =
            new Document(String.valueOf(ast.getTypeRoot().getBuffer().getContents()));
        return rewrite.rewriteAST(document,
            org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(ast));
    }

    /** The loop, its flag's declaration, and the assignment that becomes the break. */
    private record Candidate(WhileStatement loop, VariableDeclarationStatement declaration,
                             ExpressionStatement assignment) {
    }

    private static Candidate candidateFor(WhileStatement loop) {
        IVariableBinding flag = negatedLocal(loop.getExpression());
        if (flag == null || !(loop.getParent() instanceof Block enclosing)) {
            return null;
        }
        VariableDeclarationStatement declaration = declarationAbove(enclosing, loop, flag);
        if (declaration == null) {
            return null;
        }
        ExpressionStatement assignment = trailingAssignment(loop, flag);
        if (assignment == null) {
            return null;
        }
        MethodDeclaration method = enclosingMethod(loop);
        if (method == null || isReadOutsideTheLoop(method, loop, flag)) {
            return null;
        }
        return new Candidate(loop, declaration, assignment);
    }

    /** The local behind {@code !flag}, or null when the condition is anything else. */
    private static IVariableBinding negatedLocal(Expression condition) {
        if (!(condition instanceof PrefixExpression prefix)
                || prefix.getOperator() != PrefixExpression.Operator.NOT
                || !(prefix.getOperand() instanceof SimpleName name)) {
            return null;
        }
        return name.resolveBinding() instanceof IVariableBinding variable
            && !variable.isField() ? variable : null;
    }

    /** {@code boolean flag = false;} as the statement DIRECTLY above the loop. */
    private static VariableDeclarationStatement declarationAbove(Block block,
            WhileStatement loop, IVariableBinding flag) {
        List<?> statements = block.statements();
        int index = statements.indexOf(loop);
        if (index <= 0
                || !(statements.get(index - 1) instanceof VariableDeclarationStatement declaration)
                || declaration.fragments().size() != 1) {
            return null;
        }
        VariableDeclarationFragment fragment =
            (VariableDeclarationFragment) declaration.fragments().get(0);
        if (!(fragment.resolveBinding() instanceof IVariableBinding declared)
                || !declared.isEqualTo(flag)) {
            return null;
        }
        return fragment.getInitializer() instanceof BooleanLiteral literal
            && !literal.booleanValue() ? declaration : null;
    }

    /** {@code flag = true;} as the LAST statement of the loop body. */
    private static ExpressionStatement trailingAssignment(WhileStatement loop,
            IVariableBinding flag) {
        if (!(loop.getBody() instanceof Block body) || body.statements().isEmpty()) {
            return null;
        }
        Object last = body.statements().get(body.statements().size() - 1);
        if (!(last instanceof ExpressionStatement statement)
                || !(statement.getExpression() instanceof Assignment assignment)
                || assignment.getOperator() != Assignment.Operator.ASSIGN
                || !(assignment.getLeftHandSide() instanceof SimpleName name)
                || !(assignment.getRightHandSide() instanceof BooleanLiteral literal)
                || !literal.booleanValue()) {
            return null;
        }
        return name.resolveBinding() instanceof IVariableBinding bound && bound.isEqualTo(flag)
            ? statement : null;
    }

    /**
     * Does anything outside the loop read this flag?
     *
     * <p>The declaration and the one assignment inside the loop are the writes; every
     * other mention is a read, and a read means the flag is carrying an answer. Asked
     * over the whole method, because that is the variable's scope.</p>
     */
    private static boolean isReadOutsideTheLoop(MethodDeclaration method, WhileStatement loop,
            IVariableBinding flag) {
        boolean[] read = {false};
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (read[0] || !(node.resolveBinding() instanceof IVariableBinding bound)
                        || !bound.isEqualTo(flag)) {
                    return true;
                }
                for (ASTNode parent = node; parent != null; parent = parent.getParent()) {
                    if (parent == loop) {
                        return true;   // inside the loop: the guard and the assignment
                    }
                    if (parent instanceof VariableDeclarationFragment) {
                        return true;   // the declaration itself
                    }
                }
                read[0] = true;
                return false;
            }
        });
        return read[0];
    }

    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode parent = node; parent != null; parent = parent.getParent()) {
            if (parent instanceof MethodDeclaration method) {
                return method;
            }
        }
        return null;
    }

    /** Condition to {@code true}, assignment to {@code break}, declaration gone. */
    private static void rewrite(Candidate candidate, ASTRewrite rewrite) {
        org.eclipse.jdt.core.dom.AST ast = candidate.loop().getAST();

        BooleanLiteral always = ast.newBooleanLiteral(true);
        rewrite.set(candidate.loop(), WhileStatement.EXPRESSION_PROPERTY, always, null);

        Statement leave = ast.newBreakStatement();
        rewrite.replace(candidate.assignment(), leave, null);

        rewrite.remove(candidate.declaration(), null);
    }
}
