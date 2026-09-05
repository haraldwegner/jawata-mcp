package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.IJdtService;

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
 * members of different types. So the first hit is NOT the only hit, and a caller that means
 * "the member of THIS type" must say which type — which is what {@link #memberOf} is for.
 * These lookups answer "a type of this name somewhere in this file", which is the right
 * question only when the name is already known to be unique, such as a top-level type or a
 * caller-supplied target the caller then re-checks.</p>
 */
public final class TypeLookup {

    private TypeLookup() {
    }

    /** The DOM declaration of this simple name, top-level or nested, or null. */
    public static AbstractTypeDeclaration declaration(CompilationUnit unit, String simpleName) {
        return searchDom(unit.types(), simpleName);
    }

    /** The Java-model type of this simple name declared in this file, or null. */
    public static IType model(IJdtService service, String filePath, String simpleName)
            throws Exception {
        ICompilationUnit unit =
            service.getCompilationUnit(service.getPathUtils().resolve(filePath));
        if (unit == null) {
            return null;
        }
        return searchModel(unit.getTypes(), simpleName);
    }

    /**
     * The body declarations of the type that OWNS a member — the scope a member lookup must
     * use.
     *
     * <p>Searching the whole file for a method or field by name and taking the first match is
     * the ambiguity above, wearing a convenience's clothes: a row can then check a
     * precondition on one class and build its edit against another. Callers that hold the
     * declaring type pass its simple name here and search what comes back.</p>
     */
    public static AbstractTypeDeclaration memberOf(CompilationUnit unit, String owningType) {
        return declaration(unit, owningType);
    }

    private static AbstractTypeDeclaration searchDom(java.util.List<?> declarations,
                                                     String simpleName) {
        for (Object each : declarations) {
            if (!(each instanceof AbstractTypeDeclaration declaration)) {
                continue;
            }
            if (simpleName.equals(declaration.getName().getIdentifier())) {
                return declaration;
            }
            AbstractTypeDeclaration nested =
                searchDom(declaration.bodyDeclarations(), simpleName);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static IType searchModel(IType[] types, String simpleName) throws Exception {
        for (IType type : types) {
            if (type.getElementName().equals(simpleName)) {
                return type;
            }
            IType nested = searchModel(type.getTypes(), simpleName);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
