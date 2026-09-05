package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.NodeFinder;

/**
 * Find a type declared in one file by its simple name — over the DOM tree or over the Java
 * model, with one statement about ambiguity that is actually true.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Every generator in {@code tools.codegen} carried its own five-line
 * {@code findTypeDeclaration} over {@link CompilationUnit#types()}, which is the file's
 * TOP-LEVEL types only — so none of them could write into a member class, one of the
 * commonest shapes there is. Sprint 28d-rescue Stage 5 closed that as a class over an
 * enumerated population, and then immediately created the same helper TWICE: a DOM one in
 * {@code codegen} and a Java-model one in {@code data}, in packages that cannot see each
 * other, so a row in {@code data} that needed the DOM version hand-rolled a third. Five
 * copies became two, one level up, in the commit that closed the five. Both live here now.</p>
 *
 * <h2>A SIMPLE NAME CAN REPEAT IN ONE FILE, and the earlier helpers said it could not</h2>
 *
 * <p>Both replaced javadocs justified taking the first hit with the claim that a simple name
 * cannot occur twice in a compilation unit. That is false, and it compiles:</p>
 *
 * <pre>{@code
 * public class Outer {
 *     static class Dup   { void setX(int x) {} }
 *     static class Other { void setX(int x) {} }
 * }
 * class Sibling { static class Dup { } }
 * }</pre>
 *
 * <p>Two member types in different enclosing types may share a simple name, and so may two
 * members of different types. So the first hit is NOT the only hit. These lookups answer "a
 * type of this name somewhere in this file", which is the right question only when the name is
 * already known to be unique, such as a top-level type or a caller-supplied target the caller
 * then re-checks.</p>
 *
 * <p><b>A MEMBER is a different question and this class deliberately does not answer it.</b>
 * "The method or field called X" needs the declaring type, and a caller that holds one holds
 * something better than a name: the element itself, whose source range names the exact
 * declaration. {@code RemoveSettingMethodTool} does that — {@code NodeFinder} over the
 * method's own range, then its enclosing type's body declarations for everything else. A
 * by-name member lookup here was written and then deleted unused, because offering one invites
 * the very first-match ambiguity the paragraph above is about.</p>
 */
public final class TypeLookup {

    private TypeLookup() {
    }

    /**
     * The DOM declaration of THIS type inside a parse of its own file, or null.
     *
     * <p>Joined on the element's source range rather than its name, so it is the declaration of
     * the type the caller is holding and not of whichever type of that name comes first. Every
     * caller has an {@link IType} already — the by-name form this replaced made five of them
     * throw that identity away and ask for it back.</p>
     */
    public static AbstractTypeDeclaration declaration(CompilationUnit unit, IType type) {
        try {
            ISourceRange range = type.getNameRange();
            if (range == null || range.getOffset() < 0) {
                return null;
            }
            ASTNode node = NodeFinder.perform(unit, range.getOffset(), range.getLength());
            while (node != null && !(node instanceof AbstractTypeDeclaration)) {
                node = node.getParent();
            }
            return (AbstractTypeDeclaration) node;
        } catch (JavaModelException e) {
            return null;
        }
    }
}
