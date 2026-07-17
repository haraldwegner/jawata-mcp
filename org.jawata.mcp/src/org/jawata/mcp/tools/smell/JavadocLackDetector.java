package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 25 (spec D3a) — <b>javadoc_lack</b>: undocumented PUBLIC API as a
 * smell. The missing-entirely counterpart to {@code analyze_javadocs}
 * (whose {@code validate} kind deliberately checks only DOCUMENTED members).
 *
 * <p>Public API = a type whose whole enclosing chain is exported
 * (public/protected), and its public/protected members — methods,
 * constructors, fields, enum constants, annotation members. Interface and
 * annotation members are implicitly public. Skipped by design, so the
 * documentation ratchet can actually reach zero: {@code @Override} methods
 * (doc is inherited) and trivial single-statement accessors
 * (get/is/set — the "don't spam trivial getters" rule this tool family
 * already follows). {@code threshold} has no meaning for this kind.</p>
 */
public final class JavadocLackDetector extends AbstractAstDetector {

    /** Declares the kind, its contract and its skip rules to the catalog base. */
    public JavadocLackDetector() {
        super("javadoc_lack",
            "Undocumented PUBLIC API — public/protected types (exported enclosing chain) and "
                + "their public/protected methods, constructors, fields, enum constants and "
                + "annotation members carrying NO Javadoc. Interface/annotation members count "
                + "as public. Skips what a documentation pass should not chase: @Override "
                + "methods (doc inherited), trivial single-statement get/is/set accessors, and "
                + "trivial constructors (empty, or a single delegation/assignment statement) — "
                + "so the documentation ratchet counts substance. threshold: unused.",
            0);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        for (Object o : ast.types()) {
            if (o instanceof AbstractTypeDeclaration td) {
                walkType(td, "", ast, filePath, out);
            }
        }
    }

    private void walkType(AbstractTypeDeclaration td, String enclosing,
                          CompilationUnit ast, String filePath, List<Finding> out) {
        walkType(td, enclosing, ast, filePath, out, false);
    }

    private void walkType(AbstractTypeDeclaration td, String enclosing,
                          CompilationUnit ast, String filePath, List<Finding> out,
                          boolean implicitlyExported) {
        // A type nested in an interface or annotation is implicitly public (JLS)
        // regardless of its declared modifiers — v2.14.1, audit finding: the
        // modifier-only check silently dropped that population.
        if (!implicitlyExported && !isExported(td.getModifiers())) {
            return; // nothing inside a non-exported type is public API
        }
        String typeName = enclosing.isEmpty()
            ? td.getName().getIdentifier()
            : enclosing + "." + td.getName().getIdentifier();
        if (td.getJavadoc() == null) {
            out.add(finding(ast, td, filePath, typeName,
                kindWord(td) + " '" + typeName + "' is public API and has no Javadoc."));
        }
        boolean membersImplicitlyPublic =
            (td instanceof TypeDeclaration t && t.isInterface())
                || td instanceof AnnotationTypeDeclaration;
        if (td instanceof EnumDeclaration ed) {
            for (Object c : ed.enumConstants()) {
                EnumConstantDeclaration constant = (EnumConstantDeclaration) c;
                if (constant.getJavadoc() == null) {
                    String symbol = typeName + "#" + constant.getName().getIdentifier();
                    out.add(finding(ast, constant, filePath, symbol,
                        "Enum constant '" + symbol + "' is public API and has no Javadoc."));
                }
            }
        }
        for (Object o : td.bodyDeclarations()) {
            BodyDeclaration bd = (BodyDeclaration) o;
            if (bd instanceof AbstractTypeDeclaration nested) {
                walkType(nested, typeName, ast, filePath, out, membersImplicitlyPublic);
                continue;
            }
            boolean exported = membersImplicitlyPublic || isExported(bd.getModifiers());
            if (!exported || bd.getJavadoc() != null) {
                continue;
            }
            if (bd instanceof MethodDeclaration m) {
                if (hasOverrideAnnotation(m) || isTrivialAccessor(m) || isTrivialConstructor(m)) {
                    continue;
                }
                String symbol = typeName + "#" + m.getName().getIdentifier();
                out.add(finding(ast, m, filePath, symbol,
                    (m.isConstructor() ? "Constructor" : "Method") + " '" + symbol
                        + "' is public API and has no Javadoc."));
            } else if (bd instanceof FieldDeclaration f) {
                for (Object frag : f.fragments()) {
                    String symbol = typeName + "#"
                        + ((VariableDeclarationFragment) frag).getName().getIdentifier();
                    out.add(finding(ast, f, filePath, symbol,
                        "Field '" + symbol + "' is public API and has no Javadoc."));
                }
            } else if (bd instanceof AnnotationTypeMemberDeclaration a) {
                String symbol = typeName + "#" + a.getName().getIdentifier();
                out.add(finding(ast, a, filePath, symbol,
                    "Annotation member '" + symbol + "' is public API and has no Javadoc."));
            }
        }
    }

    private static Finding finding(CompilationUnit ast, org.eclipse.jdt.core.dom.ASTNode node,
                                   String filePath, String symbol, String message) {
        int line = ast.getLineNumber(node.getStartPosition());
        return new Finding("javadoc_lack", filePath, line, -1, "warning", message, symbol);
    }

    private static boolean isExported(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String kindWord(AbstractTypeDeclaration td) {
        if (td instanceof TypeDeclaration t) {
            return t.isInterface() ? "Interface" : "Class";
        }
        if (td instanceof EnumDeclaration) {
            return "Enum";
        }
        if (td instanceof AnnotationTypeDeclaration) {
            return "Annotation";
        }
        return "Type";
    }

    private static boolean hasOverrideAnnotation(MethodDeclaration m) {
        for (Object mod : m.modifiers()) {
            if (mod instanceof Annotation a
                    && "Override".equals(a.getTypeName().getFullyQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * get/is with no params or set with one param, and a single-statement body.
     * The prefix must be a real accessor prefix — followed by an uppercase
     * letter or nothing — so {@code issue()}, {@code isolate()} or
     * {@code settle(x)} are NOT skipped (v2.14.1, audit finding: the bare
     * startsWith over-matched).
     */
    private static boolean isTrivialAccessor(MethodDeclaration m) {
        if (m.isConstructor() || m.getBody() == null || m.getBody().statements().size() != 1) {
            return false;
        }
        String name = m.getName().getIdentifier();
        int params = m.parameters().size();
        boolean getter = params == 0 && (hasAccessorPrefix(name, "get") || hasAccessorPrefix(name, "is"));
        boolean setter = params == 1 && hasAccessorPrefix(name, "set");
        return getter || setter;
    }

    private static boolean hasAccessorPrefix(String name, String prefix) {
        if (!name.startsWith(prefix)) {
            return false;
        }
        return name.length() == prefix.length()
            || Character.isUpperCase(name.charAt(prefix.length()));
    }

    /**
     * Empty, or a single delegation ({@code super(...)}/{@code this(...)}) or
     * assignment statement — the DI-plumbing shape whose only possible Javadoc
     * restates the signature (ratchet decision, 2026-07-16). Constructors with
     * real bodies still count.
     */
    private static boolean isTrivialConstructor(MethodDeclaration m) {
        if (!m.isConstructor() || m.getBody() == null) {
            return false;
        }
        List<?> statements = m.getBody().statements();
        if (statements.isEmpty()) {
            return true;
        }
        if (statements.size() != 1) {
            return false;
        }
        Object only = statements.get(0);
        if (only instanceof org.eclipse.jdt.core.dom.SuperConstructorInvocation
                || only instanceof org.eclipse.jdt.core.dom.ConstructorInvocation) {
            return true;
        }
        return only instanceof org.eclipse.jdt.core.dom.ExpressionStatement es
            && es.getExpression() instanceof org.eclipse.jdt.core.dom.Assignment;
    }
}
