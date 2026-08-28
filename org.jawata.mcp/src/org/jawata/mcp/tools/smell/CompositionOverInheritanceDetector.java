package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprint 28d — <b>Prefer composition over inheritance</b> (Gamma et al.; Bloch,
 * Effective Java item 18). A class that {@code extends} a <em>concrete</em> class
 * is using inheritance to acquire an implementation, and pays for it with a
 * permanent, unbreakable coupling to that implementation's internals. Pointed
 * refactoring: <b>Replace Inheritance with Delegation</b> — hold the former
 * superclass as a field and forward what you actually need.
 *
 * <h2>The decision rule</h2>
 * <p>A class {@code S extends C} is flagged when C is a <b>concrete class
 * declared in the scanned source</b> and at least one of:</p>
 * <ol>
 *   <li><b>S overrides nothing of C.</b> Then no call through a {@code C}
 *       reference can behave differently because the object is an S: the
 *       dispatch table is unchanged, so nothing in the program can be
 *       <em>treating S as a C</em> in any way that matters. The {@code extends}
 *       is buying implementation, not participating in a hierarchy — the
 *       {@code class Stack extends Vector} shape.</li>
 *   <li><b>S touches fewer than {@code threshold}% of C's inherited surface</b>
 *       (default 25). S has taken on every one of C's members, and its
 *       obligation to keep working when any of them changes, in exchange for the
 *       few it uses. The surface is C's non-private, non-static, non-constructor
 *       methods and fields, plus the same from C's own source ancestors.</li>
 * </ol>
 *
 * <p>The percentage arm additionally requires a surface of at least
 * {@value #MIN_SURFACE} members. Below that the fraction is not a measurement:
 * on a two-member parent the only possible values are 0%, 50% and 100%, and
 * "uses 0 of 1" says nothing about whether the relationship is right.</p>
 *
 * <h2>How this differs from {@code refused_bequest} — they answer different questions</h2>
 * <p>{@code refused_bequest} fires on ONE METHOD: an {@code @Override} whose body
 * only throws {@code UnsupportedOperationException}. It says <em>this member does
 * not belong in this hierarchy</em>, and it points at the member. This detector
 * fires on ONE CLASS and says <em>this hierarchy is not the right
 * relationship</em>. A class can genuinely exhibit both, and reporting it twice
 * for two readings of the same override would be noise, so a class containing any
 * refused-bequest-shaped override is left entirely to {@code refused_bequest} —
 * the finding that names the smaller, more actionable unit wins. (The shape check
 * is {@link RefusedBequestDetector#rejectsInheritance}, called here, so the two
 * kinds cannot drift apart about what "refusing" means.)</p>
 *
 * <h2>The exclusions, and why each one is excluded</h2>
 * <ul>
 *   <li><b>Abstract superclasses.</b> An abstract parent is a Template Method or
 *       a partial implementation offered for extension — inheritance is the
 *       relationship it was designed for, and delegation cannot replace it
 *       without also removing the abstraction. Only CONCRETE parents are the
 *       "prefer composition" question.</li>
 *   <li><b>Superclasses outside the scanned source</b> (the JDK, a dependency
 *       jar). Extending {@code ASTVisitor}, {@code Exception} or a framework base
 *       class is that framework's protocol, not a modelling choice the reader can
 *       revisit — and its surface would dominate every percentage. Like
 *       {@link CouplingDetector}, this measures OUR boundaries.</li>
 *   <li><b>Throwable hierarchies.</b> Subclassing an exception IS the language's
 *       mechanism for distinguishing failures; a marker subtype that overrides
 *       nothing is the correct shape, not a smell, and it is used polymorphically
 *       by every {@code catch} clause.</li>
 *   <li><b>Interfaces, enums, records and annotation types</b> — only
 *       {@code class} declarations are visited (JDT models the others as distinct
 *       AST nodes), and {@code implements} is not the relationship in question:
 *       inheriting a contract costs nothing, inheriting an implementation is what
 *       does.</li>
 *   <li><b>Anonymous classes.</b> An anonymous subclass exists precisely to be
 *       substituted at its point of creation, so it is polymorphic use by
 *       construction. They are not {@code TypeDeclaration}s and are never
 *       visited, and the walk that measures usage stops at one so a nested type's
 *       uses are never credited to its enclosing class.</li>
 *   <li><b>Unresolvable bindings.</b> No verdict is guessed from an unresolved
 *       superclass — the candidate is skipped and the suppression is REPORTED to
 *       the scan's degradation channel, because a suppressed candidate is not an
 *       absence.</li>
 * </ul>
 *
 * <p>Precision over recall throughout: an override of {@code Object}'s methods
 * ({@code equals}, {@code hashCode}, {@code toString}) does not count as
 * overriding C, because every class may do that and it says nothing about the
 * hierarchy — {@code Object} is not part of any surface here.</p>
 */
public final class CompositionOverInheritanceDetector extends AbstractAstDetector {

    /**
     * The smallest inherited surface over which a PERCENTAGE is a measurement
     * rather than an artefact of a tiny denominator. Not tuned on a corpus: it is
     * the point below which the fraction's possible values (0, 33, 50, 66, 100)
     * are too coarse to separate "uses almost none of it" from "uses some of it".
     */
    static final int MIN_SURFACE = 4;

    public CompositionOverInheritanceDetector() {
        super("composition_over_inheritance",
            "Prefer composition over inheritance — a class extending a CONCRETE class declared in "
                + "the scanned source that either overrides nothing of it (so nothing can treat "
                + "the subclass as its supertype differently) or touches fewer than `threshold`% "
                + "(default 25) of the inherited surface. Points to Replace Inheritance with "
                + "Delegation. Excludes abstract and non-source superclasses, Throwable "
                + "hierarchies, and any class that already refuses a bequest (left to "
                + "refused_bequest).",
            25);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        analyze(ast, filePath, service, threshold, out, new ScanDegradation());
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out, ScanDegradation degraded) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                Finding finding = examine(ast, filePath, node, threshold, degraded);
                if (finding != null) {
                    out.add(finding);
                }
                return true;
            }
        });
    }

    /** The rule for one class; null when it is fine, or an excluded shape. */
    private static Finding examine(CompilationUnit ast, String filePath, TypeDeclaration node,
                                   int threshold, ScanDegradation degraded) {
        Type superclassType = node.getSuperclassType();
        if (node.isInterface() || superclassType == null) {
            return null;
        }
        ITypeBinding parent = erasureOf(superclassType.resolveBinding());
        if (parent == null) {
            degraded.report("composition_over_inheritance candidate '" + node.getName() + "' ("
                + filePath + ") skipped: its superclass type did not resolve, so neither the "
                + "inherited surface nor its concreteness could be read");
            return null;
        }
        if (!parent.isClass() || !parent.isFromSource()
            || Modifier.isAbstract(parent.getModifiers()) || isThrowable(parent)) {
            return null;
        }
        if (refusesABequest(node)) {
            return null;   // refused_bequest owns this class — see the class javadoc
        }

        Surface surface = surfaceOf(parent);
        if (surface.keys.isEmpty()) {
            return null;   // a concrete parent with nothing to inherit is not this smell
        }
        Used used = usedBy(node, surface);

        boolean overridesNothing = used.overrides == 0;
        int percent = used.keys.size() * 100 / surface.keys.size();
        boolean barelyTouched = surface.keys.size() >= MIN_SURFACE && percent < threshold;
        if (!overridesNothing && !barelyTouched) {
            return null;
        }

        String name = node.getName().getIdentifier();
        return new Finding("composition_over_inheritance", filePath,
            ast.getLineNumber(node.getName().getStartPosition()), -1, "warning",
            message(name, parent.getName(), overridesNothing, barelyTouched,
                used.keys.size(), surface.keys.size(), percent, threshold),
            name);
    }

    private static String message(String subclass, String superclass, boolean overridesNothing,
                                  boolean barelyTouched, int touched, int inheritable,
                                  int percent, int threshold) {
        StringBuilder message = new StringBuilder()
            .append("Class '").append(subclass).append("' extends the CONCRETE class '")
            .append(superclass).append("', so it inherits that implementation and every future "
                + "change to it. ");
        if (overridesNothing) {
            message.append("It overrides NONE of the ").append(inheritable)
                .append(" inherited member(s), so no caller holding a '").append(superclass)
                .append("' can observe any difference — the inheritance is buying implementation, "
                    + "not polymorphism. ");
        }
        if (barelyTouched) {
            message.append("It touches ").append(touched).append(" of ").append(inheritable)
                .append(" inherited member(s) (").append(percent)
                .append("%, below the ").append(threshold)
                .append("% threshold) — the coupling is to the whole surface, the benefit to a "
                    + "fraction of it. ");
        }
        return message.append("Consider Replace Inheritance with Delegation: hold a '")
            .append(superclass).append("' as a field and forward only what is used.").toString();
    }

    /** True when any method of this class has the refused-bequest shape. */
    private static boolean refusesABequest(TypeDeclaration node) {
        for (MethodDeclaration method : node.getMethods()) {
            if (RefusedBequestDetector.rejectsInheritance(method)) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------- surface

    /** The inheritable members of the superclass chain, as stable keys. */
    private record Surface(Set<String> keys, List<IMethodBinding> methods, Set<String> owners) {
    }

    /** What the subclass actually engages with: which surface keys, and how many overrides. */
    private record Used(Set<String> keys, int overrides) {
    }

    /**
     * The surface {@code parent} bequeaths: its non-private, non-static,
     * non-constructor methods and fields, and the same from every SOURCE ancestor
     * up to (but never including) {@code java.lang.Object}. The walk stops at the
     * first non-source ancestor for the reason the class javadoc gives.
     */
    private static Surface surfaceOf(ITypeBinding parent) {
        Set<String> keys = new LinkedHashSet<>();
        List<IMethodBinding> methods = new ArrayList<>();
        Set<String> owners = new LinkedHashSet<>();
        for (ITypeBinding type = parent;
             type != null && type.isFromSource() && !isObject(type);
             type = erasureOf(type.getSuperclass())) {
            owners.add(type.getQualifiedName());
            for (IMethodBinding method : type.getDeclaredMethods()) {
                if (method.isConstructor() || method.isSynthetic() || hidden(method.getModifiers())) {
                    continue;
                }
                methods.add(method);
                keys.add(methodKey(method));
            }
            for (IVariableBinding field : type.getDeclaredFields()) {
                if (field.isSynthetic() || hidden(field.getModifiers())) {
                    continue;
                }
                keys.add("field:" + field.getName());
            }
        }
        return new Surface(keys, methods, owners);
    }

    /** Private and static members are not bequeathed to a subclass's instances. */
    private static boolean hidden(int modifiers) {
        return Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers);
    }

    /**
     * Which of the surface's members this class engages with: the ones it
     * overrides, plus the ones its own bodies call or read. The walk stops at a
     * nested or anonymous type, whose uses belong to that type and not to this
     * one.
     */
    private static Used usedBy(TypeDeclaration node, Surface surface) {
        Set<String> used = new LinkedHashSet<>();
        int overrides = 0;
        for (MethodDeclaration declared : node.getMethods()) {
            IMethodBinding binding = declared.resolveBinding();
            if (binding == null) {
                continue;
            }
            for (IMethodBinding inherited : surface.methods()) {
                if (binding.overrides(inherited)) {
                    used.add(methodKey(inherited));
                    overrides++;
                    break;
                }
            }
        }

        ASTVisitor uses = new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration nested) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration anonymous) {
                return false;
            }

            @Override
            public boolean visit(SuperMethodInvocation call) {
                recordMethod(call.resolveMethodBinding());
                return true;
            }

            @Override
            public boolean visit(MethodInvocation call) {
                // Only an UNQUALIFIED (or `this.`) call can be a use of the
                // inherited surface; `other.foo()` is a call on someone else.
                if (call.getExpression() == null
                    || call.getExpression() instanceof ThisExpression) {
                    recordMethod(call.resolveMethodBinding());
                }
                return true;
            }

            @Override
            public boolean visit(SimpleName name) {
                recordField(name.resolveBinding());
                return true;
            }

            @Override
            public boolean visit(FieldAccess access) {
                recordField(access.resolveFieldBinding());
                return true;
            }

            @Override
            public boolean visit(SuperFieldAccess access) {
                recordField(access.resolveFieldBinding());
                return true;
            }

            private void recordMethod(IMethodBinding binding) {
                if (binding == null) {
                    return;
                }
                IMethodBinding declaration = binding.getMethodDeclaration();
                ITypeBinding owner = erasureOf(declaration.getDeclaringClass());
                if (owner != null && surface.owners().contains(owner.getQualifiedName())) {
                    used.add(methodKey(declaration));
                }
            }

            private void recordField(IBinding binding) {
                if (!(binding instanceof IVariableBinding variable) || !variable.isField()) {
                    return;
                }
                ITypeBinding owner = erasureOf(variable.getDeclaringClass());
                if (owner != null && surface.owners().contains(owner.getQualifiedName())) {
                    used.add("field:" + variable.getName());
                }
            }
        };
        for (Object declaration : node.bodyDeclarations()) {
            if (!(declaration instanceof TypeDeclaration)) {
                ((BodyDeclaration) declaration).accept(uses);
            }
        }
        // Only members the surface actually contains count toward the fraction.
        used.retainAll(surface.keys());
        return new Used(used, overrides);
    }

    // ------------------------------------------------------------------- keys

    /** Name + erased parameter types — stable across the subclass/superclass pair. */
    private static String methodKey(IMethodBinding method) {
        StringBuilder key = new StringBuilder(method.getName()).append('(');
        ITypeBinding[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                key.append(',');
            }
            ITypeBinding erasure = erasureOf(parameters[i]);
            key.append(erasure != null ? erasure.getQualifiedName() : "?");
        }
        return key.append(')').toString();
    }

    private static ITypeBinding erasureOf(ITypeBinding binding) {
        if (binding == null) {
            return null;
        }
        ITypeBinding erasure = binding.getErasure();
        return erasure != null ? erasure : binding;
    }

    private static boolean isObject(ITypeBinding type) {
        return "java.lang.Object".equals(type.getQualifiedName());
    }

    /** Exception hierarchies are excluded — see the class javadoc. */
    private static boolean isThrowable(ITypeBinding type) {
        for (ITypeBinding t = type; t != null; t = erasureOf(t.getSuperclass())) {
            if ("java.lang.Throwable".equals(t.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }
}
