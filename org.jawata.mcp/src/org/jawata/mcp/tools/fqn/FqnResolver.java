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
        if (fqn == null || fqn.isBlank()) return Optional.empty();

        List<IJavaProject> projects = collectProjects(service, scope, projectKey);
        if (projects.isEmpty()) return Optional.empty();

        int hashIdx = fqn.indexOf('#');
        if (hashIdx < 0) {
            // Type-only form (e.g. "com.foo.Bar").
            Optional<IType> typeOnly = resolveType(fqn, projects);
            if (typeOnly.isPresent()) {
                return typeOnly.map(t -> (IJavaElement) t);
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
                    return resolveMemberByName(outer.get(), fqn.substring(lastDot + 1));
                }
            }
            return Optional.empty();
        }

        String typeFqn = fqn.substring(0, hashIdx);
        String memberPart = fqn.substring(hashIdx + 1);

        Optional<IType> typeOpt = resolveType(typeFqn, projects);
        if (typeOpt.isEmpty()) return Optional.empty();
        IType type = typeOpt.get();

        int parenIdx = memberPart.indexOf('(');
        if (parenIdx >= 0) {
            // Method with explicit param list
            String methodName = memberPart.substring(0, parenIdx);
            int closeIdx = memberPart.lastIndexOf(')');
            if (closeIdx <= parenIdx) {
                log.debug("FQN method form missing ')': {}", fqn);
                return Optional.empty();
            }
            String paramList = memberPart.substring(parenIdx + 1, closeIdx).trim();
            String[] paramFqns = paramList.isEmpty()
                ? new String[0]
                : paramList.split("\\s*,\\s*");
            return resolveMethod(type, methodName, paramFqns).map(m -> (IJavaElement) m);
        }

        // Member name alone — method first (any overload), then field.
        return resolveMemberByName(type, memberPart);
    }

    /** Resolve a bare member name on a type: method (any overload) first, then field. */
    private static Optional<IJavaElement> resolveMemberByName(IType type, String memberName) {
        try {
            for (IMethod m : type.getMethods()) {
                if (memberName.equals(m.getElementName())) {
                    return Optional.of(m);
                }
            }
        } catch (Exception e) {
            log.debug("Error iterating methods of {}: {}", type.getElementName(), e.getMessage());
        }
        IField field = type.getField(memberName);
        if (field != null && field.exists()) {
            return Optional.of(field);
        }
        return Optional.empty();
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
