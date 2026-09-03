package org.jawata.mcp.tools.statements;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
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
 * Fowler — <b>Split Loop</b> (row 63).
 *
 * <p>A loop doing two things is two loops sharing a walk. The sharing looks like an
 * optimisation and costs a reader the ability to name what either half does, which is
 * why Fowler's advice is to split first and only then worry about the second pass —
 * and usually to find that the second pass costs nothing measurable.</p>
 *
 * <pre>
 *   for (Person p : people) {            for (Person p : people) {
 *       total += p.salary();                 total += p.salary();
 *       if (p.age() &gt; oldest) {   --&gt;    }
 *           oldest = p.age();            for (Person p : people) {
 *       }                                    if (p.age() &gt; oldest) { oldest = p.age(); }
 *   }                                    }
 * </pre>
 *
 * <h2>What makes a second pass equivalent, and what makes it not</h2>
 *
 * <p>Splitting means walking the collection twice, so three things have to hold:</p>
 *
 * <ul>
 *   <li><b>The walk is repeatable.</b> The loop must be over a {@code Collection},
 *       whose {@code iterator()} may be called again. A bare {@code Iterable} may be
 *       one-shot — a stream's iterator, a result-set wrapper — and walking it twice
 *       yields nothing the second time, silently.</li>
 *   <li><b>The two halves do not talk to each other.</b> Neither may read a variable
 *       the other writes. If they do, the second pass sees values the first pass
 *       finished producing rather than the values it had at that point in the walk,
 *       which is a different computation.</li>
 *   <li><b>No {@code break}, {@code continue} or {@code return}.</b> Each half would
 *       need its own copy of an exit whose meaning was defined over the whole body.</li>
 * </ul>
 *
 * <p>Exactly two statements, and only two. A three-statement body has three ways to be
 * grouped, and choosing between them is a judgement about what belongs together — the
 * part of this refactoring that is a person's, not a rule's.</p>
 */
public final class SplitLoopRule implements CleanupRule {

    @Override
    public String kind() {
        return "split_loop";
    }

    @Override
    public String describe() {
        return "split_loop          — Split Loop: a loop whose body does exactly TWO independent\n"
            + "                        things becomes two loops over the same collection, so each\n"
            + "                        can be named and moved. Requires a Collection (a bare\n"
            + "                        Iterable may be one-shot and would walk empty the second\n"
            + "                        time), two halves that neither read what the other writes,\n"
            + "                        and no break/continue/return. Only two statements: a longer\n"
            + "                        body has several ways to be grouped, and choosing is a\n"
            + "                        judgement rather than a rule.";
    }

    @Override
    public TextEdit edit(CompilationUnit ast) throws Exception {
        List<EnhancedForStatement> targets = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(EnhancedForStatement node) {
                if (isSplittable(node)) {
                    targets.add(node);
                }
                return true;
            }
        });
        // THE GUARD GuardClausesRule ALREADY LEARNED. A splittable loop nested inside a
        // splittable one would have its copy anchored on a node the outer split removes,
        // which is the shape whose one-pass version the compile gate reverted there. It
        // was absent here and untested, because the fixture held a single loop.
        targets.removeIf(SplitLoopRule::hasSplittableAncestor);
        if (targets.isEmpty()) {
            return null;
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        for (EnhancedForStatement loop : targets) {
            split(loop, rewrite);
        }
        Document document =
            new Document(String.valueOf(ast.getTypeRoot().getBuffer().getContents()));
        return rewrite.rewriteAST(document,
            org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(ast));
    }

    /** Is an enclosing loop also being split in this pass? */
    private static boolean hasSplittableAncestor(EnhancedForStatement loop) {
        for (ASTNode parent = loop.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof EnhancedForStatement enclosing && isSplittable(enclosing)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSplittable(EnhancedForStatement loop) {
        if (!(loop.getParent() instanceof Block)
                || !(loop.getBody() instanceof Block body)
                || body.statements().size() != 2) {
            return false;
        }
        ITypeBinding walked = loop.getExpression().resolveTypeBinding();
        if (walked == null || !isCollection(walked)) {
            return false;   // a bare Iterable may be one-shot
        }
        Statement first = (Statement) body.statements().get(0);
        Statement second = (Statement) body.statements().get(1);
        if (org.jawata.mcp.refactoring.Effects.containsJump(body)) {
            return false;
        }
        // A declaration in the first half is read by nothing else once they are apart,
        // and moving it would change what the second half can see.
        if (first instanceof VariableDeclarationStatement
                || second instanceof VariableDeclarationStatement) {
            return false;
        }
        return independent(first, second) && independent(second, first);
    }

    /**
     * Does {@code reader} read anything {@code writer} writes?
     *
     * <p>Asked of {@link org.jawata.mcp.refactoring.Effects} rather than answered here.
     * The private version recorded a write only for an assignment or an increment whose
     * target was a bare name, so a half mutating a collection through {@code add()}
     * reported NO writes and this check passed on a pair that genuinely communicates.
     * The split compiled, so nothing caught it.</p>
     */
    private static boolean independent(Statement writer, Statement reader) {
        return !org.jawata.mcp.refactoring.Effects.disturbs(writer, reader);
    }



    private static boolean isCollection(ITypeBinding type) {
        String name = type.getErasure() == null ? null : type.getErasure().getQualifiedName();
        if ("java.util.Collection".equals(name)) {
            return true;
        }
        for (ITypeBinding face : type.getInterfaces()) {
            if (isCollection(face)) {
                return true;
            }
        }
        ITypeBinding parent = type.getSuperclass();
        return parent != null && isCollection(parent);
    }


    /** The first half stays; a copy of the loop carrying the second half follows it. */
    private static void split(EnhancedForStatement loop, ASTRewrite rewrite) {
        Block body = (Block) loop.getBody();
        Statement second = (Statement) body.statements().get(1);

        EnhancedForStatement copy = (EnhancedForStatement) ASTNode.copySubtree(
            loop.getAST(), loop);
        Block copiedBody = (Block) copy.getBody();
        copiedBody.statements().remove(0);          // the copy keeps the SECOND half

        ListRewrite enclosing = rewrite.getListRewrite(
            (Block) loop.getParent(), Block.STATEMENTS_PROPERTY);
        enclosing.insertAfter(copy, loop, null);
        rewrite.remove(second, null);               // the original keeps the FIRST
    }
}
