package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.NodeFinder;

/**
 * Join a {@link IType} the caller already holds to its declaration in a parse of its own file.
 *
 * <h2>What this replaces, and the two wrong answers it went through</h2>
 *
 * <p>Every generator in {@code tools.codegen} carried its own five-line
 * {@code findTypeDeclaration} over {@link CompilationUnit#types()}, which is the file's
 * TOP-LEVEL types only — so none of them could write into a member class, one of the commonest
 * shapes there is. Sprint 28d-rescue Stage 5 closed that as a class, and then immediately
 * created the same helper TWICE: a DOM one in {@code codegen} and a Java-model one in
 * {@code data}, in packages that cannot see each other, so a row in {@code data} needing the
 * DOM version hand-rolled a third. Five copies became two, one level up.</p>
 *
 * <p>Both of those, and the merged class that replaced them, still joined on the SIMPLE NAME
 * and took the first hit — with a javadoc claiming a simple name cannot occur twice in one
 * compilation unit. That claim is false and it compiles:</p>
 *
 * <pre>{@code
 * public class Outer {
 *     static class Dup   { void setX(int x) {} }
 *     static class Other { void setX(int x) {} }
 * }
 * class Sibling { static class Dup { } }
 * }</pre>
 *
 * <p>So the second version documented the hazard and left its callers inside it, which is the
 * shape an audit refused: two member types in different enclosing types may share a simple
 * name, and the search returns whichever is declared first.</p>
 *
 * <h2>The answer is not to warn about the name — it is to stop taking one</h2>
 *
 * <p>Every caller HELD the element and handed over its name. This class takes the element, so
 * there is nothing left to be ambiguous about, and the by-name entry points are deleted rather
 * than deprecated: an entry point that cannot be spelled cannot be misused. The same join, on
 * a method's range rather than a type's, is what {@code RemoveSettingMethodTool} uses, and the
 * recipes in {@code tools.data} carry {@code getHandleIdentifier()} between steps for the same
 * reason.</p>
 *
 * <p><b>A MEMBER is a different question and this class deliberately does not answer it.</b>
 * "The method or field called X" needs the declaring type, and a caller that holds one holds
 * the element itself. A by-name member lookup here was written and then deleted unused, because
 * offering one invites exactly the ambiguity above.</p>
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
