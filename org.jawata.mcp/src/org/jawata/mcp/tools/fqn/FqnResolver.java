package org.jawata.mcp.tools.fqn;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 14 Phase B.2 (v1.8.0) — bugs.md #12 capability half: resolve a
 * fully-qualified-name string into an {@link IJavaElement} so the
 * {@code find_*} family can search by FQN instead of requiring callers to
 * pin a {@code (filePath, line, column)} triple. The "single most-repeated
 * agent ask" across EXECSIM-Java sessions.
 *
 * <h2>Supported FQN forms</h2>
 *
 * <ul>
 *   <li><b>Type</b>: {@code com.foo.Bar}</li>
 *   <li><b>Method</b> (any overload): {@code com.foo.Bar#methodName}</li>
 *   <li><b>Method</b> (specific overload):
 *       {@code com.foo.Bar#methodName(int,java.lang.String)}</li>
 *   <li><b>Field</b>: {@code com.foo.Bar#fieldName}</li>
 * </ul>
 *
 * <p>Method-with-args matching is done by parameter-arity + name + erased
 * FQN comparison via {@link Signature#toString(String)} — robust against
 * JDT's source-vs-resolved signature conventions, where building exact JDT
 * type signatures by hand is brittle.</p>
 *
 * <h2>Scope</h2>
 *
 * <ul>
 *   <li>{@code "workspace"} (default): iterate every loaded project, first
 *       match wins.</li>
 *   <li>{@code "project"}: search only the named project (caller supplies
 *       {@code projectKey}). Returns empty if the projectKey doesn't
 *       resolve.</li>
 * </ul>
 */
public final class FqnResolver {

    private static final Logger log = LoggerFactory.getLogger(FqnResolver.class);

    public enum Scope { WORKSPACE, PROJECT }

    private FqnResolver() {}

    /**
     * Resolve an FQN string against a workspace-scoped search.
     *
     * @param fqn      the symbol FQN, in one of the supported forms.
     * @param service  the JDT service (provides loaded projects).
     * @return         the matching {@link IJavaElement}, or empty if none.
     */
    public static Optional<IJavaElement> resolveWorkspace(String fqn, IJdtService service) {
        return resolve(fqn, service, Scope.WORKSPACE, null);
    }

    /**
     * Resolve an FQN string. When {@code scope} is {@link Scope#PROJECT},
     * the lookup is scoped to {@code projectKey}; otherwise the whole
     * workspace is searched.
     */
    public static Optional<IJavaElement> resolve(String fqn, IJdtService service,
                                                  Scope scope, String projectKey) {
        return resolveAll(fqn, service, scope, projectKey).stream().findFirst();
    }

    /**
     * EVERY element the FQN names — which for the bare {@code Type#member} form means
     * every overload, not the first one (jawata-mcp#46).
     *
     * <p>The schema documents that form as <i>"com.foo.Bar#member (method any overload /
     * field)"</i>, and {@link #resolveMemberByName}'s own comment said "method (any
     * overload) first" — but its loop returned on the first name match. So a search over a
     * member with two overloads searched ONE and reported its correct zero as the answer
     * for the member. Measured: {@code find_references} on
     * {@code FindDuplicateCodeTool#collectPool} answered {@code totalReferences: 0} while
     * a caller of the four-argument overload existed.</p>
     *
     * <p><b>A bare zero is indistinguishable from a genuine absence</b>, which is the
     * defect class this product fights first — an agent deciding whether a member is safe
     * to delete gets a confident empty answer from a partial search. Returning the union
     * is the fix rather than a caveat, because the published contract already promised it.</p>
     *
     * <p>{@link #resolve} is derived from this rather than the other way round, so the
     * single-answer callers and the complete answer cannot disagree: the first element of
     * this list is exactly what that method returned before, including the
     * methods-before-fields precedence.</p>
     */
    public static List<IJavaElement> resolveAllWorkspace(String fqn, IJdtService service) {
        return resolveAll(fqn, service, Scope.WORKSPACE, null);
    }

    /** {@link #resolveAllWorkspace}, scoped. */
    public static List<IJavaElement> resolveAll(String fqn, IJdtService service,
                                                 Scope scope, String projectKey) {
        if (fqn == null || fqn.isBlank()) return List.of();

        List<IJavaProject> projects = collectProjects(service, scope, projectKey);
        if (projects.isEmpty()) return List.of();

        int hashIdx = fqn.indexOf('#');
        if (hashIdx < 0) {
            // Type-only form (e.g. "com.foo.Bar").
            Optional<IType> typeOnly = resolveType(fqn, projects);
            if (typeOnly.isPresent()) {
                return List.of(typeOnly.get());
            }
            // Sprint 15 DX#1: dot-form member fallback. Agents naturally write
            // "com.foo.Bar.method" / "com.foo.Bar.field" instead of the
            // "#" separator form. When the whole string is not a type, treat
            // the last dot segment as a member of the type formed by the
            // preceding segments.
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot > 0) {
                Optional<IType> outer = resolveType(fqn.substring(0, lastDot), projects);
                if (outer.isPresent()) {
                    // The dot form is ambiguous for exactly the same reason (mcp#46):
                    // "com.foo.Bar.method" names every overload of `method`.
                    return resolveMembersByName(outer.get(), fqn.substring(lastDot + 1));
                }
            }
            return List.of();
        }

        String typeFqn = fqn.substring(0, hashIdx);
        String memberPart = fqn.substring(hashIdx + 1);

        Optional<IType> typeOpt = resolveType(typeFqn, projects);
        if (typeOpt.isEmpty()) return List.of();
        IType type = typeOpt.get();

        int parenIdx = memberPart.indexOf('(');
        if (parenIdx >= 0) {
            // Method with explicit param list
            String methodName = memberPart.substring(0, parenIdx);
            int closeIdx = memberPart.lastIndexOf(')');
            if (closeIdx <= parenIdx) {
                log.debug("FQN method form missing ')': {}", fqn);
                return List.of();
            }
            String paramList = memberPart.substring(parenIdx + 1, closeIdx).trim();
            String[] paramFqns = paramList.isEmpty()
                ? new String[0]
                : paramList.split("\\s*,\\s*");
            return resolveMethod(type, methodName, paramFqns)
                .<IJavaElement>map(m -> m)
                .map(List::of)
                .orElseGet(List::of);
        }

        // Member name alone — EVERY method overload of that name, else the field.
        return resolveMembersByName(type, memberPart);
    }

    /**
     * Every member of {@code type} that a bare name denotes: ALL method overloads of that
     * name, or — only when no method matches — the field.
     *
     * <p><b>This used to return the FIRST overload and its comment called that "any
     * overload" (jawata-mcp#46).</b> The two readings differ exactly when it matters: a
     * reference search over a member with two overloads searched one of them and reported
     * its correct zero as the member's answer. A bare zero is indistinguishable from a real
     * absence, and an agent deciding whether a member is safe to delete acts on it.</p>
     *
     * <p>The methods-before-fields precedence is unchanged, and is why a field is only
     * consulted when the method list is empty: {@link #resolve} takes the first element of
     * this list, so every existing single-answer caller sees exactly what it saw before.</p>
     */
    private static List<IJavaElement> resolveMembersByName(IType type, String memberName) {
        List<IJavaElement> overloads = new ArrayList<>();
        try {
            for (IMethod m : type.getMethods()) {
                if (memberName.equals(m.getElementName())) {
                    overloads.add(m);
                }
            }
        } catch (Exception e) {
            log.debug("Error iterating methods of {}: {}", type.getElementName(), e.getMessage());
        }
        if (!overloads.isEmpty()) {
            return List.copyOf(overloads);
        }
        IField field = type.getField(memberName);
        if (field != null && field.exists()) {
            return List.of(field);
        }
        return List.of();
    }

    private static List<IJavaProject> collectProjects(IJdtService service,
                                                       Scope scope, String projectKey) {
        List<IJavaProject> projects = new ArrayList<>();
        if (scope == Scope.PROJECT) {
            if (projectKey == null || projectKey.isBlank()) {
                return projects;
            }
            service.getProject(projectKey).ifPresent(lp -> projects.add(lp.javaProject()));
            return projects;
        }
        for (LoadedProject lp : service.allProjects()) {
            projects.add(lp.javaProject());
        }
        return projects;
    }

    private static Optional<IType> resolveType(String typeFqn, List<IJavaProject> projects) {
        for (IJavaProject jp : projects) {
            try {
                IType t = jp.findType(typeFqn);
                if (t != null && t.exists()) {
                    return Optional.of(t);
                }
            } catch (Exception e) {
                log.debug("findType('{}') in project '{}' failed: {}",
                    typeFqn, jp.getElementName(), e.getMessage());
            }
        }
        return secondaryType(typeFqn, projects);
    }

    /**
     * A TOP-LEVEL TYPE THAT DOES NOT OWN ITS FILE — which {@code findType} cannot return.
     *
     * <p>{@link IJavaProject#findType(String)} resolves a source type through the classpath's
     * name environment, and that maps {@code com.foo.Bar} to {@code com/foo/Bar.java}. Java
     * allows any number of package-private top-level types beside the public one, and every
     * one of them has a perfectly ordinary fully-qualified name that this lookup answers
     * "not found" for.</p>
     *
     * <p><b>Measured, and it is why this exists:</b> S8b step 9's INVARIANT A drove each
     * routed cure's door with its own finding's address and three smells failed identically —
     * {@code com.example.Rejecter#op} declared in {@code LspTargets.java},
     * {@code com.example.IspClientAbc#use} in {@code IspTargets.java}. The detector had
     * resolved the binding and rendered the correct name; the resolver could not take it, and
     * the product's own answer was <i>"no type … was found, and nothing similarly named
     * either"</i> about a type sitting in the workspace.</p>
     *
     * <p>The scan is the package fragment's own compilation units rather than a search index:
     * it is exact, it needs no index to be warm, and it runs only after {@code findType} has
     * already failed — so the common case pays nothing.</p>
     */
    private static Optional<IType> secondaryType(String typeFqn, List<IJavaProject> projects) {
        int lastDot = typeFqn.lastIndexOf('.');
        if (lastDot <= 0) {
            return Optional.empty();   // the default package has no fragment to scan
        }
        String packageName = typeFqn.substring(0, lastDot);
        String simpleName = typeFqn.substring(lastDot + 1);
        for (IJavaProject jp : projects) {
            try {
                for (org.eclipse.jdt.core.IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                    if (root.getKind() != org.eclipse.jdt.core.IPackageFragmentRoot.K_SOURCE) {
                        continue;
                    }
                    org.eclipse.jdt.core.IPackageFragment fragment =
                        root.getPackageFragment(packageName);
                    if (fragment == null || !fragment.exists()) {
                        continue;
                    }
                    for (org.eclipse.jdt.core.ICompilationUnit unit
                            : fragment.getCompilationUnits()) {
                        for (IType candidate : unit.getTypes()) {
                            if (simpleName.equals(candidate.getElementName())) {
                                return Optional.of(candidate);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("secondary-type scan for '{}' in project '{}' failed: {}",
                    typeFqn, jp.getElementName(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static Optional<IMethod> resolveMethod(IType type, String methodName,
                                                    String[] paramFqns) {
        try {
            for (IMethod method : type.getMethods()) {
                if (!methodName.equals(method.getElementName())) continue;
                String[] mSigs = method.getParameterTypes();
                if (mSigs.length != paramFqns.length) continue;
                boolean matches = true;
                for (int i = 0; i < paramFqns.length; i++) {
                    if (!paramMatches(mSigs[i], paramFqns[i].trim())) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return Optional.of(method);
            }
        } catch (Exception e) {
            log.debug("Error matching method {}({}) on type {}: {}",
                methodName, String.join(",", paramFqns),
                type.getFullyQualifiedName(), e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Match a single JDT parameter signature against a user-supplied FQN.
     *
     * <p>JDT's source-level {@link IMethod#getParameterTypes()} returns
     * unresolved type signatures ({@code QString;}, {@code QList<QString;>;},
     * {@code I}, …); {@link Signature#toString(String)} renders them as
     * source-form names ({@code "String"}, {@code "List<String>"},
     * {@code "int"}). Callers, however, naturally pass fully-qualified names
     * like {@code "java.lang.String"} or primitives like {@code "int"}.
     *
     * <p>We accept both forms: a match on the full source-form rendering
     * wins outright; failing that, simple-name comparison handles the
     * common case of an FQN supplied for an unresolved JDT signature.</p>
     */
    private static boolean paramMatches(String jdtSig, String expected) {
        String rendered = Signature.toString(jdtSig);
        if (rendered.equals(expected)) return true;
        String renderedSimple = simpleName(rendered);
        String expectedSimple = simpleName(expected);
        return renderedSimple.equals(expectedSimple);
    }

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }
}
