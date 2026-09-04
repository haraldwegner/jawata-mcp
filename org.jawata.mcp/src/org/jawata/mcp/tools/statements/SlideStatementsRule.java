package org.jawata.mcp.tools.statements;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fowler — <b>Slide Statements</b> (row 62), in the form that pays for itself.
 *
 * <p>Fowler's mechanic is general — move related code together — and most of it is a
 * judgement about what "related" means. One case is not a judgement at all: a local
 * declared at the top of a method and first used thirty lines down. The declaration is
 * not where the variable lives, it is where somebody's habit put it, and moving it to
 * its first use is the step that makes the rest of the refactoring possible. It is also
 * what turns a long method into something Extract Function can act on, because a
 * candidate range cannot be extracted while a declaration sits outside it.</p>
 *
 * <h2>When moving a declaration down cannot change anything</h2>
 *
 * <p>Two things could go wrong, and each has a check:</p>
 *
 * <ul>
 *   <li><b>The initializer could compute something different later.</b> So it must read
 *       nothing but LOCALS and parameters — no field, no {@code this}, no array element,
 *       no call, no construction. A local cannot be changed by anything except code in
 *       this method, which the next check covers; everything else can be changed by a
 *       call the declaration slides past.</li>
 *   <li><b>Something in between could change what it reads.</b> So no statement being
 *       slid past may assign to, increment or decrement any local the initializer
 *       reads.</li>
 * </ul>
 *
 * <p>Both hold, and the declaration is moved to sit directly before the first statement
 * that mentions the variable. It never moves INTO a nested block: the destination is the
 * top-level statement of the same block that contains the first use, so the variable's
 * scope can only shrink, never move sideways.</p>
 *
 * <p>The initializer being call-free is what makes this conservative rather than clever.
 * A declaration whose initializer calls anything is left alone even when the call is
 * plainly harmless, because "plainly" is a reading and this rewrite does not get one.</p>
 */
public final class SlideStatementsRule implements CleanupRule {

    @Override
    public String kind() {
        return "slide_declaration";
    }

    @Override
    public String kindSummary() {
        return """
            Slide Statements: a local declared well above its first
            use moves down to sit directly before it, which is what
            lets Extract Function act on the range afterwards.
            Requires an initializer that reads only locals and
            parameters — no field, this, array element, call or
            construction — and no statement in between that assigns
            to or increments any local it reads. Never moves into a
            nested block: scope only shrinks.""";
    }

    @Override
    public TextEdit edit(CompilationUnit ast) throws Exception {
        List<Move> moves = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(Block node) {
                collectMoves(node, moves);
                return true;
            }
        });
        if (moves.isEmpty()) {
            return null;
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        for (Move move : moves) {
            ListRewrite statements =
                rewrite.getListRewrite(move.block(), Block.STATEMENTS_PROPERTY);
            ASTNode moved = rewrite.createMoveTarget(move.declaration());
            statements.insertBefore(moved, move.firstUse(), null);
        }
        Document document =
            new Document(String.valueOf(ast.getTypeRoot().getBuffer().getContents()));
        return rewrite.rewriteAST(document,
            org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(ast));
    }

    /** A declaration, the block it lives in, and the statement it should sit before. */
    private record Move(Block block, VariableDeclarationStatement declaration,
                        Statement firstUse) {
    }

    private static void collectMoves(Block block, List<Move> moves) {
        List<?> statements = block.statements();
        for (int i = 0; i < statements.size(); i++) {
            if (!(statements.get(i) instanceof VariableDeclarationStatement declaration)
                    || declaration.fragments().size() != 1) {
                continue;
            }
            VariableDeclarationFragment fragment =
                (VariableDeclarationFragment) declaration.fragments().get(0);
            Expression initializer = fragment.getInitializer();
            if (initializer == null
                    || !(fragment.resolveBinding() instanceof IVariableBinding variable)) {
                continue;
            }
            Set<IVariableBinding> reads = localsRead(initializer);
            if (reads == null) {
                continue;   // reads something a slide could change — see localsRead
            }

            int use = firstUseAfter(statements, i, variable);
            if (use < 0 || use == i + 1) {
                continue;   // never used, or already next to its use
            }
            if (anythingBetweenWrites(statements, i, use, reads)) {
                continue;
            }
            moves.add(new Move(block, declaration, (Statement) statements.get(use)));
        }
    }

    /**
     * The locals this initializer reads, or null when it reads anything else.
     *
     * <p>Null is the refusal, and it is returned for a field, {@code this}, an array
     * element, a call or a construction. Each of those can be changed by something the
     * declaration would slide past; a local cannot, except by a statement in this same
     * block, which the caller checks.</p>
     */
    private static Set<IVariableBinding> localsRead(Expression initializer) {
        Set<IVariableBinding> reads = new LinkedHashSet<>();
        boolean[] refused = {false};
        initializer.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation node) { return refuse(); }
            @Override public boolean visit(SuperMethodInvocation node) { return refuse(); }
            @Override public boolean visit(ClassInstanceCreation node) { return refuse(); }
            @Override public boolean visit(FieldAccess node) { return refuse(); }
            @Override public boolean visit(SuperFieldAccess node) { return refuse(); }
            @Override public boolean visit(ThisExpression node) { return refuse(); }
            @Override public boolean visit(ArrayAccess node) { return refuse(); }
            @Override public boolean visit(QualifiedName node) { return refuse(); }
            @Override public boolean visit(Assignment node) { return refuse(); }

            @Override
            public boolean visit(SimpleName node) {
                if (node.resolveBinding() instanceof IVariableBinding variable) {
                    if (variable.isField()) {
                        return refuse();
                    }
                    reads.add(variable);
                }
                return true;
            }

            private boolean refuse() {
                refused[0] = true;
                return false;
            }
        });
        return refused[0] ? null : reads;
    }

    /** The index of the first statement after {@code from} that mentions the variable. */
    private static int firstUseAfter(List<?> statements, int from, IVariableBinding variable) {
        for (int i = from + 1; i < statements.size(); i++) {
            if (mentions((ASTNode) statements.get(i), variable)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean mentions(ASTNode node, IVariableBinding variable) {
        boolean[] found = {false};
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (!found[0] && name.resolveBinding() instanceof IVariableBinding bound
                        && bound.isEqualTo(variable)) {
                    found[0] = true;
                }
                return !found[0];
            }
        });
        return found[0];
    }

    /** Does anything the declaration slides past change what its initializer reads? */
    private static boolean anythingBetweenWrites(List<?> statements, int from, int to,
            Set<IVariableBinding> reads) {
        for (int i = from + 1; i < to; i++) {
            boolean[] writes = {false};
            ((ASTNode) statements.get(i)).accept(new ASTVisitor() {
                @Override
                public boolean visit(Assignment node) {
                    return check(node.getLeftHandSide());
                }

                @Override
                public boolean visit(PostfixExpression node) {
                    return check(node.getOperand());
                }

                @Override
                public boolean visit(PrefixExpression node) {
                    PrefixExpression.Operator op = node.getOperator();
                    return op == PrefixExpression.Operator.INCREMENT
                        || op == PrefixExpression.Operator.DECREMENT
                        ? check(node.getOperand()) : true;
                }

                private boolean check(Expression target) {
                    if (target instanceof SimpleName name
                            && name.resolveBinding() instanceof IVariableBinding bound) {
                        for (IVariableBinding read : reads) {
                            if (bound.isEqualTo(read)) {
                                writes[0] = true;
                                return false;
                            }
                        }
                    }
                    return true;
                }
            });
            if (writes[0]) {
                return true;
            }
        }
        return false;
    }
}
