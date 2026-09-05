package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;

/**
 * The AST declaration of a method you already hold as a model element — {@link TypeLookup}'s
 * sibling, and beside it for the same reason.
 *
 * <p>{@code TypeLookup} exists because five code generators each re-derived a type from its
 * simple name; the same shape then appeared on methods. <b>Measured, because the first count of it
 * was wrong:</b> FOUR declarations serving FIVE call sites, across three packages that cannot see
 * each other ({@code tools}, {@code tools.api}, {@code tools.data}) — and they had already drifted
 * into TWO implementations, three joining on the element's source range and one on the resolved
 * binding. Both are identity-preserving and correct, which is what made the drift invisible:
 * nothing was wrong, so nothing complained.</p>
 *
 * <p>The fifth call site is the one that says why a shared home rather than four tidy privates:
 * {@code ChangeReferenceToValueTool} reached across into {@code RemoveSettingMethodTool}'s copy,
 * so one row's private helper had already become another row's dependency by being made
 * package-visible. That reach-through is gone.</p>
 *
 * <h2>The range join is the one that survived, and the choice is measured rather than aesthetic</h2>
 *
 * <p>{@link IMethod#getNameRange()} is model data and needs no bindings, so it answers on a unit
 * whose imports do not resolve. The binding join needs {@code resolveBinding()} to succeed, and the
 * vendored fork slices are exactly the case where it may not: they declare no dependencies, so
 * upstream's Lombok and slf4j references are unresolved. A lookup that quietly returns null there
 * would read as "this method has no declaration".</p>
 *
 * <h2>What this is NOT, so the name is not read too widely</h2>
 *
 * <p>Three other private helpers in this repository are also called {@code declarationOf} and none
 * of them belongs here: two find a FIELD by name, one finds a LOCAL VARIABLE. They share this
 * name and not this job, and merging them because a symbol search returned them together would be
 * the mistake this class exists to undo, made in the other direction.</p>
 *
 * <p>One more DOES share the job and is deliberately not repointed:
 * {@code MoveStatementsIntoFunctionTool} resolves its target method by SIMPLE NAME over the whole
 * unit, which cannot tell two same-named methods apart — the by-name-key defect this sprint has
 * already fixed twice elsewhere. Whether it can actually mis-resolve depends on how its target is
 * constrained upstream of that call, which has not been established. It is recorded rather than
 * changed blind.</p>
 */
public final class MethodLookup {

    private MethodLookup() {
    }

    /**
     * The declaration of THIS method, found by its own source range rather than by its name.
     *
     * @return the declaration, or {@code null} when the element carries no usable name range
     *         (a binary method, or one whose source is not available)
     */
    public static MethodDeclaration declaration(CompilationUnit ast, IMethod method)
            throws Exception {
        ISourceRange range = method.getNameRange();
        if (range == null || range.getOffset() < 0) {
            return null;
        }
        ASTNode node = NodeFinder.perform(ast, range.getOffset(), range.getLength());
        while (node != null && !(node instanceof MethodDeclaration)) {
            node = node.getParent();
        }
        return (MethodDeclaration) node;
    }
}
