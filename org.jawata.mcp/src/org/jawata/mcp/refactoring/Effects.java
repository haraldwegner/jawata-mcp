package org.jawata.mcp.refactoring;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.ThrowStatement;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WHAT A PIECE OF CODE READS, WRITES AND DOES.
 *
 * <p>Every rewrite that moves, merges, duplicates or drops a statement needs the same three
 * answers, and Sprint 28d-rescue's stage 3 shipped six rules that each answered them
 * privately. Two reviews found the same thing independently: six answers to three
 * questions, and the copies already disagreed — one refused a {@code super} call and
 * another did not, in the same package, in the same week.</p>
 *
 * <p>One of the copies was also wrong in a way that compiles, which is why this class
 * exists rather than six patches. {@code SplitLoopRule} recorded a write only for an
 * assignment or an increment whose target was a bare name, so a half mutating a
 * collection through {@code add()} reported no writes at all and a loop whose two halves
 * genuinely communicate was split. Nothing failed: the result compiles, and the compile
 * gate is the only automatic check on that path.</p>
 *
 * <h2>The three questions are genuinely three</h2>
 *
 * <p>They are kept apart because they are not the same question asked at different
 * strengths:</p>
 *
 * <ul>
 *   <li>{@link #isSideEffectFree} — may this expression be evaluated fewer times, or not
 *       at all? That is what short-circuiting a condition into an {@code ||} chain
 *       does.</li>
 *   <li>{@link #reads} / {@link #writes} — what does this statement depend on and
 *       disturb? That is what deciding whether two statements may be reordered or
 *       separated needs.</li>
 *   <li>{@link #alwaysExits} — does control leave the METHOD here? That is what makes an
 *       {@code else} redundant, and what makes two conditional bodies mutually
 *       exclusive.</li>
 * </ul>
 *
 * <p>An expression can be side-effect-free and still unsafe to relocate, because it reads
 * something a later statement writes. Collapsing the two would make one of the rules
 * wrong.</p>
 *
 * <h2>Conservative in one direction, always</h2>
 *
 * <p>Every answer errs toward "this might do something". A method call is a write to its
 * receiver and is never side-effect-free, even when it is obviously a getter, because
 * "obviously" is a reading and a rewrite does not get one. The cost is a refusal that a
 * person can see is unnecessary; the alternative cost is a silent wrong rewrite of
 * somebody's code.</p>
 */
public final class Effects {

    private Effects() {
    }

    /**
     * May this expression be evaluated fewer times without changing the program?
     *
     * <p>False for a call, a construction, an assignment, an increment or a decrement.
     * A {@code super} call counts, which one of the private copies missed.</p>
     */
    public static boolean isSideEffectFree(Expression expression) {
        if (expression == null) {
            return true;
        }
        boolean[] pure = {true};
        expression.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation node) { return impure(); }
            @Override public boolean visit(SuperMethodInvocation node) { return impure(); }
            @Override public boolean visit(ClassInstanceCreation node) { return impure(); }
            @Override public boolean visit(Assignment node) { return impure(); }
            @Override public boolean visit(PostfixExpression node) { return impure(); }

            @Override
            public boolean visit(PrefixExpression node) {
                PrefixExpression.Operator op = node.getOperator();
                return op == PrefixExpression.Operator.INCREMENT
                    || op == PrefixExpression.Operator.DECREMENT ? impure() : true;
            }

            private boolean impure() {
                pure[0] = false;
                return false;
            }
        });
        return pure[0];
    }

    /**
     * May this expression be MOVED — evaluated at a different point?
     *
     * <p>Stricter than {@link #isSideEffectFree}, and the difference is the point of
     * having both: reading a field or an array element has no side effect at all, and is
     * still unsafe to relocate, because anything the expression moves past may change it.
     * Only locals and parameters survive, and whether THEY are disturbed in between is the
     * caller's question to ask with {@link #writes}.</p>
     */
    public static boolean isRelocatable(Expression expression) {
        if (expression == null) {
            return false;
        }
        boolean[] movable = {true};
        expression.accept(new ASTVisitor() {
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
                if (node.resolveBinding() instanceof IVariableBinding variable
                        && variable.isField()) {
                    return refuse();
                }
                return true;
            }

            private boolean refuse() {
                movable[0] = false;
                return false;
            }
        });
        return movable[0];
    }

    /** Every local, parameter or field this node mentions. */
    public static Set<IVariableBinding> reads(org.eclipse.jdt.core.dom.ASTNode node) {
        Set<IVariableBinding> out = new LinkedHashSet<>();
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (name.resolveBinding() instanceof IVariableBinding variable) {
                    out.add(variable);
                }
                return true;
            }
        });
        return out;
    }

    /**
     * Every variable this node may CHANGE.
     *
     * <p>Three ways, and the third is the one a hand-written copy missed:</p>
     *
     * <ol>
     *   <li>an assignment's target, including {@code this.field = x} and {@code arr[i] = v},
     *       whose innermost name is what gets recorded;</li>
     *   <li>an increment or decrement's operand;</li>
     *   <li><b>a method call's RECEIVER.</b> {@code seen.add(x)} changes {@code seen}, and
     *       in Java that is how collections, builders and most domain objects are changed
     *       at all. A model that sees only assignment sees almost no mutation.</li>
     * </ol>
     */
    public static Set<IVariableBinding> writes(org.eclipse.jdt.core.dom.ASTNode node) {
        Set<IVariableBinding> out = new LinkedHashSet<>();
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment assignment) {
                record(assignment.getLeftHandSide());
                return true;
            }

            @Override
            public boolean visit(PostfixExpression postfix) {
                record(postfix.getOperand());
                return true;
            }

            @Override
            public boolean visit(PrefixExpression prefix) {
                PrefixExpression.Operator op = prefix.getOperator();
                if (op == PrefixExpression.Operator.INCREMENT
                        || op == PrefixExpression.Operator.DECREMENT) {
                    record(prefix.getOperand());
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation call) {
                // The receiver may be changed by the call. An unqualified call has an
                // implicit `this` receiver and may change any field, which no set of
                // names can express — the caller's own check has to be stricter there,
                // and every current caller refuses a bare call for other reasons.
                record(call.getExpression());
                return true;
            }

            /** The variable an expression ultimately names, however it is spelled. */
            private void record(Expression target) {
                Expression current = target;
                while (true) {
                    if (current instanceof ArrayAccess access) {
                        current = access.getArray();
                    } else if (current instanceof FieldAccess access) {
                        current = access.getName();
                    } else {
                        break;
                    }
                }
                if (current instanceof SimpleName name
                        && name.resolveBinding() instanceof IVariableBinding variable) {
                    out.add(variable);
                }
            }
        });
        return out;
    }

    /** Does anything {@code reader} reads get changed by {@code writer}? */
    public static boolean disturbs(org.eclipse.jdt.core.dom.ASTNode writer,
                                   org.eclipse.jdt.core.dom.ASTNode reader) {
        Set<IVariableBinding> written = writes(writer);
        if (written.isEmpty()) {
            return false;
        }
        for (IVariableBinding read : reads(reader)) {
            for (IVariableBinding write : written) {
                if (read.isEqualTo(write)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Does every path through this statement leave the METHOD?
     *
     * <p>A {@code break} or {@code continue} is deliberately NOT an exit: it leaves a
     * loop, and statements after the enclosing construct still run.</p>
     */
    public static boolean alwaysExits(Statement statement) {
        if (statement instanceof ReturnStatement || statement instanceof ThrowStatement) {
            return true;
        }
        if (statement instanceof Block block) {
            java.util.List<?> statements = block.statements();
            return !statements.isEmpty()
                && alwaysExits((Statement) statements.get(statements.size() - 1));
        }
        if (statement instanceof IfStatement nested) {
            return nested.getElseStatement() != null
                && alwaysExits(nested.getThenStatement())
                && alwaysExits(nested.getElseStatement());
        }
        return false;
    }

    /** Any jump out of the enclosing construct — break, continue or return. */
    public static boolean containsJump(org.eclipse.jdt.core.dom.ASTNode node) {
        boolean[] found = {false};
        node.accept(new ASTVisitor() {
            @Override public boolean visit(BreakStatement statement) { return mark(); }
            @Override public boolean visit(ContinueStatement statement) { return mark(); }
            @Override public boolean visit(ReturnStatement statement) { return mark(); }

            private boolean mark() {
                found[0] = true;
                return false;
            }
        });
        return found[0];
    }
}
