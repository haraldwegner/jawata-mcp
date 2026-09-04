package org.jawata.mcp.tools.statements;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Fowler — <b>Replace Nested Conditional with Guard Clauses</b> (row 52).
 *
 * <p>A method that answers several special cases by nesting them reads as one shape with
 * the normal path buried at the bottom. Fowler's point is that those cases are not
 * alternatives of equal standing: each is a "this cannot go on" that should be said and
 * left, so the body that remains is the ordinary one.</p>
 *
 * <pre>
 *   if (isDead) { return deadAmount(); }        if (isDead) { return deadAmount(); }
 *   else {                                       if (isSeparated) { return sepAmount(); }
 *       if (isSeparated) { ... }        --&gt;      return normalAmount();
 *       else { ... }
 *   }
 * </pre>
 *
 * <h2>The one rewrite this performs, and why it is exactly this one</h2>
 *
 * <p>It removes an {@code else} whose {@code if} branch cannot fall through — every path
 * through the then-branch ends in a {@code return} or a {@code throw} — and lifts the
 * else's statements into the enclosing block. That is the whole mechanic, and applied
 * repeatedly from the outside in it is what turns a nest into a run of guards.</p>
 *
 * <p>It is behaviour-preserving for a reason worth stating rather than assuming: if the
 * condition holds, control left inside the then-branch and never reaches what follows, so
 * moving the else's statements after the if changes nothing about when they run. If the
 * condition does not hold, they ran before and they run now. The definite-exit check is
 * what makes that true, and it is why this refuses far more often than it fires.</p>
 *
 * <p><b>What it will not touch.</b> A then-branch that can fall through — the
 * transformation would then run the else's statements after the if's, which is a
 * different program. An {@code else if} chain: unwrapping one level there produces a
 * shape a reader has to re-derive, and the chain is usually the honest form already. A
 * branch containing a {@code continue} or {@code break}, which exits the enclosing loop
 * rather than the method and so is not an exit at this level at all.</p>
 */
public final class GuardClausesRule implements CleanupRule {

    @Override
    public String kind() {
        return "guard_clauses";
    }

    @Override
    public String kindSummary() {
        return """
            Replace Nested Conditional with Guard Clauses: where an
            `if` branch always returns or throws, drop the `else`
            and let its statements follow. Applied from the outside
            in, a nest becomes a run of guards with the normal path
            last. Refuses a branch that can fall through, an
            else-if chain, and a branch whose exit is a break or a
            continue (which leaves a loop, not the method).
            ONE LEVEL PER RUN: the outermost such `if` in each
            nest is unwrapped, which leaves the next one a sibling
            for the next run. CONVERGENT, not idempotent: running it
            twice is not the same as running it once, which is the
            point. A nest of depth N takes N runs — re-run until it
            reports no changes.""";
    }

    @Override
    public TextEdit edit(CompilationUnit ast) throws Exception {
        List<IfStatement> targets = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(IfStatement node) {
                if (isCandidate(node)) {
                    targets.add(node);
                }
                return true;
            }
        });
        // ONE LEVEL PER RUN, and this line is why. A nested candidate lives inside the
        // else block its ancestor is about to remove, so registering both rewrites in one
        // pass inserts statements into a block that is being deleted. The compile gate
        // caught exactly that — "This method must return a result of type int" — and undid
        // the change, which is the gate doing its job rather than a near miss.
        //
        // Unwrapping the OUTERMOST candidate leaves the next one a sibling in the parent
        // block, where the next run finds it. A three-level nest takes three runs, the
        // sweep is idempotent, and the description says so. Doing all levels at once needs
        // an apply-and-reparse loop, which is what refactoring(action=plan) already is.
        targets.removeIf(GuardClausesRule::hasCandidateAncestor);
        if (targets.isEmpty()) {
            return null;
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        for (IfStatement target : targets) {
            unwrap(target, rewrite);
        }
        Document document = new Document(String.valueOf(ast.getTypeRoot().getBuffer().getContents()));
        return rewrite.rewriteAST(document,
            org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(ast));
    }

    /** Is one of this statement's enclosing ifs also being unwrapped in this pass? */
    private static boolean hasCandidateAncestor(IfStatement node) {
        for (org.eclipse.jdt.core.dom.ASTNode parent = node.getParent(); parent != null;
                parent = parent.getParent()) {
            if (parent instanceof IfStatement enclosing && isCandidate(enclosing)) {
                return true;
            }
        }
        return false;
    }

    /** An if with an else, whose then-branch always leaves, and which is not a chain. */
    private static boolean isCandidate(IfStatement node) {
        Statement elseBranch = node.getElseStatement();
        if (elseBranch == null || elseBranch instanceof IfStatement) {
            return false;   // no else, or an else-if chain — see the class note
        }
        // The else's statements have to go somewhere, and that somewhere is the block
        // holding the if. Anything else (an if as the body of a loop, say) has no such
        // block and is left alone.
        if (!(node.getParent() instanceof Block)) {
            return false;
        }
        return org.jawata.mcp.refactoring.Effects.alwaysExits(node.getThenStatement());
    }


    /** Drop the else and put its statements after the if, in the enclosing block. */
    private static void unwrap(IfStatement node, ASTRewrite rewrite) {
        Block parent = (Block) node.getParent();
        Statement elseBranch = node.getElseStatement();
        ListRewrite statements = rewrite.getListRewrite(parent, Block.STATEMENTS_PROPERTY);

        List<Statement> lifted = new ArrayList<>();
        if (elseBranch instanceof Block block) {
            for (Object o : block.statements()) {
                lifted.add((Statement) o);
            }
        } else {
            lifted.add(elseBranch);
        }

        // After the if, in order. Each is MOVED rather than copied, so comments and
        // formatting travel with the statement instead of being regenerated.
        Statement after = node;
        for (Statement statement : lifted) {
            org.eclipse.jdt.core.dom.ASTNode moved = rewrite.createMoveTarget(statement);
            statements.insertAfter(moved, after, null);
            after = (Statement) moved;
        }
        rewrite.remove(elseBranch, null);
    }

}
