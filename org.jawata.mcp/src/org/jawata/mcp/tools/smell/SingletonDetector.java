package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 19 (Kerievsky) — detects the GoF <b>Singleton</b> shape: a class with
 * (a) at least one constructor and <em>all</em> constructors private, (b) a
 * {@code static} field of the class's own type (the instance holder), and (c) a
 * {@code static} accessor method returning the class's own type ({@code getInstance}).
 *
 * <p>It is the trigger for {@code refactor_to_pattern(kind=inline_singleton)} — the
 * Kerievsky "away from pattern" move when the class's uniqueness/lifecycle no longer
 * matters (e.g. it is effectively stateless or now DI-managed). Detection is
 * structural; whether inlining is <em>safe</em> (no relied-upon shared state) is the
 * agent's call.</p>
 */
public final class SingletonDetector extends AbstractAstDetector {

    public SingletonDetector() {
        super("singleton",
            "Singleton (GoF) — a class with only private constructors, a static self-typed holder "
                + "field, and a static accessor returning itself. Candidate for Inline Singleton "
                + "(refactor_to_pattern kind=inline_singleton) when its uniqueness no longer matters.",
            0);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface()) {
                    return true;
                }
                if (isSingleton(node)) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "singleton", filePath, line, -1, "info",
                        "Class '" + name + "' is a GoF Singleton (private ctor + static self-holder + "
                            + "static accessor). If its uniqueness no longer matters, Inline Singleton "
                            + "(refactor_to_pattern kind=inline_singleton).",
                        name));
                }
                return true;
            }
        });
    }

    /** The three structural marks of a GoF singleton, all required. */
    private static boolean isSingleton(TypeDeclaration node) {
        ITypeBinding self = node.resolveBinding();
        if (self == null) {
            return false;
        }
        boolean hasConstructor = false;
        boolean allConstructorsPrivate = true;
        boolean hasStaticAccessor = false;
        for (MethodDeclaration m : node.getMethods()) {
            if (m.isConstructor()) {
                hasConstructor = true;
                if (!Modifier.isPrivate(m.getModifiers())) {
                    allConstructorsPrivate = false;
                }
            } else if (Modifier.isStatic(m.getModifiers())) {
                IMethodBinding mb = m.resolveBinding();
                if (mb != null && self.isEqualTo(mb.getReturnType())) {
                    hasStaticAccessor = true;
                }
            }
        }
        if (!hasConstructor || !allConstructorsPrivate || !hasStaticAccessor) {
            return false;
        }
        for (FieldDeclaration f : node.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            ITypeBinding ft = f.getType().resolveBinding();
            if (ft != null && self.isEqualTo(ft)) {
                return true; // the static self-typed holder
            }
        }
        return false;
    }
}
