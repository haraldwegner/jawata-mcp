package org.jawata.mcp.tools.shared;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.mcp.refactoring.CompleteReferences;

/**
 * The questions a fold of one class into another has to ask, and the one rewrite it always
 * performs — shared by Fowler's <b>Remove Subclass</b> (row 38, {@code inline kind=subclass})
 * and <b>Collapse Hierarchy</b> (row 4, {@code hierarchy direction=collapse_hierarchy}).
 *
 * <h2>Why the two rows share this and still differ</h2>
 *
 * <p>They perform the same arithmetic — the subclass's members move up, every mention of its
 * type becomes the parent's, and its file is deleted — and they PARTITION on one precondition.
 * Row 38 refuses a subclass that has subtypes of its own, naming Collapse Hierarchy in the
 * refusal; row 4 exists for exactly that case and reparents them. Each row keeps its own
 * precondition ORDER and its own prose, because a refusal is what a caller reads and the two
 * rows are answering different questions; what lives here is only what would otherwise be
 * copied verbatim.</p>
 *
 * <p><b>Extracted at the SECOND caller, not in advance</b> — the move rows 55 and 27 made for
 * {@code IntroducedParameter} and rows 53 and 28 for {@code ParameterSubstitution}. Row 38's
 * own tests are the control that the extraction preserved its behaviour.</p>
 *
 * <p><b>This class deliberately does NOT hold a type lookup.</b> Row 38 resolves the
 * declaration it is about to rewrite by walking the unit's TOP-LEVEL types and matching a
 * simple name — a lookup that cannot see a nested class and that re-derives an identity the
 * caller already holds from a key that is not unique. Both are defects this repository has
 * closed as classes ({@link TypeLookup}, and the handle-identifier key). Hoisting it here
 * would have made a defect shared API, so callers pass their {@link IType} and use
 * {@link TypeLookup#declaration}.</p>
 */
public final class HierarchyFold {

    private HierarchyFold() {
    }

    /**
     * Every compilation unit mentioning {@code type}, except its own and {@code exclude}'s.
     *
     * <p>The excluded unit is the one whose edit the caller has already staged: a second edit
     * list for one file is two rewrites of one document, which is how conflicting edits
     * happen.</p>
     */
    public static Set<ICompilationUnit> referencingUnits(IJdtService service, IType type,
                                                         ICompilationUnit exclude)
            throws Exception {
        Set<ICompilationUnit> units = new LinkedHashSet<>();
        for (SearchMatch match : CompleteReferences.of(service, type)) {
            if (match.getElement() instanceof IJavaElement element) {
                ICompilationUnit unit = (ICompilationUnit) element
                    .getAncestor(IJavaElement.COMPILATION_UNIT);
                if (unit != null && !unit.equals(type.getCompilationUnit())
                        && !unit.equals(exclude)) {
                    units.add(unit);
                }
            }
        }
        return units;
    }

    /**
     * {@code Sub x = new Sub()} becomes {@code Parent x = new Parent()}, wherever the name
     * appears as a type — which includes an {@code extends} clause, and that is what lets row
     * 4 reparent a subtype rather than needing a second mechanism for it.
     *
     * @return how many occurrences were rewritten, so a caller can skip a unit that had none
     *     rather than staging an empty edit.
     */
    public static int repointTypeReferences(CompilationUnit ast, String from, String to,
                                            ASTRewrite rewrite) {
        List<SimpleName> names = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleType node) {
                if (node.getName() instanceof SimpleName name
                        && from.equals(name.getIdentifier())) {
                    names.add(name);
                }
                return true;
            }
        });
        for (SimpleName name : names) {
            rewrite.replace(name, ast.getAST().newSimpleName(to), null);
        }
        return names.size();
    }

    /** Whether this method has the same signature as one the parent chain declares. */
    public static boolean overridesSomething(IMethodBinding method, ITypeBinding parent) {
        for (ITypeBinding type = parent; type != null; type = type.getSuperclass()) {
            for (IMethodBinding candidate : type.getDeclaredMethods()) {
                if (!candidate.isConstructor() && method.overrides(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Null when the constructor forwards its own parameters through to {@code super()}
     * unchanged, which is the only shape where {@code new Sub(args)} and
     * {@code new Parent(args)} are the same call. Otherwise the reason, phrased to complete
     * "the constructor ...".
     */
    public static String constructorForwardsUnchanged(MethodDeclaration ctor) {
        if (ctor.getBody() == null) {
            return null;
        }
        List<?> statements = ctor.getBody().statements();
        if (statements.isEmpty()) {
            return ctor.parameters().isEmpty() ? null
                : "takes parameters it does not pass on";
        }
        if (statements.size() > 1
                || !(statements.get(0) instanceof SuperConstructorInvocation up)) {
            return "does more than pass its parameters to super()";
        }
        List<?> parameters = ctor.parameters();
        List<?> arguments = up.arguments();
        if (parameters.size() != arguments.size()) {
            return "passes " + arguments.size() + " argument(s) to super() from "
                + parameters.size() + " parameter(s)";
        }
        for (int i = 0; i < parameters.size(); i++) {
            String parameter =
                ((SingleVariableDeclaration) parameters.get(i)).getName().getIdentifier();
            if (!(arguments.get(i) instanceof SimpleName argument)
                    || !parameter.equals(argument.getIdentifier())) {
                return "fixes the value of super()'s argument " + (i + 1);
            }
        }
        return null;
    }

    /**
     * Where the type's identity is asked about — an {@code instanceof} or a cast. Returns a
     * readable location, or null when nothing observes it.
     *
     * <p>Both rows refuse on a non-null answer, and for one reason: the fold makes the type
     * disappear, so code that asks what type something is would silently start seeing a
     * different answer.</p>
     */
    public static String observedAnywhere(IJdtService service, IType type) throws Exception {
        String name = type.getElementName();
        Set<ICompilationUnit> units = new LinkedHashSet<>();
        units.add(type.getCompilationUnit());
        for (SearchMatch match : CompleteReferences.of(service, type)) {
            if (match.getElement() instanceof IJavaElement element) {
                ICompilationUnit unit = (ICompilationUnit) element
                    .getAncestor(IJavaElement.COMPILATION_UNIT);
                if (unit != null) {
                    units.add(unit);
                }
            }
        }
        for (ICompilationUnit unit : units) {
            CompilationUnit ast = parse(unit);
            String[] found = { null };
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(InstanceofExpression node) {
                    if (found[0] == null && name.equals(node.getRightOperand().toString())) {
                        found[0] = unit.getElementName() + " (an instanceof check)";
                    }
                    return true;
                }

                @Override
                public boolean visit(CastExpression node) {
                    if (found[0] == null && name.equals(node.getType().toString())) {
                        found[0] = unit.getElementName() + " (a cast)";
                    }
                    return true;
                }
            });
            if (found[0] != null) {
                return found[0];
            }
        }
        return null;
    }

    /** The member names a type declares, for the collision check both rows make. */
    public static Set<String> memberNames(AbstractTypeDeclaration type) {
        Set<String> names = new LinkedHashSet<>();
        for (Object member : type.bodyDeclarations()) {
            names.addAll(namesOf((BodyDeclaration) member));
        }
        return names;
    }

    /** The names one declaration introduces — several, for a multi-fragment field. */
    public static List<String> namesOf(BodyDeclaration declaration) {
        List<String> names = new ArrayList<>();
        if (declaration instanceof MethodDeclaration method) {
            names.add(method.getName().getIdentifier());
        } else if (declaration instanceof FieldDeclaration field) {
            for (Object fragment : field.fragments()) {
                names.add(((VariableDeclarationFragment) fragment).getName().getIdentifier());
            }
        }
        return names;
    }

    /** A resolved, binding-recovering parse — the shape every row in this package uses. */
    public static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
