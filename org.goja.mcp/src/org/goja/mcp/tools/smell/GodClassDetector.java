package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.search.SearchMatch;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprint 17 (Fowler) — <b>Large / God Class</b>. Distinct from the size-only
 * {@code large_classes} quality kind: a God Class is both <em>bloated</em>
 * (member count &gt; {@code threshold}, default 20) AND <em>central</em> (high
 * fan-in — referenced by &ge; {@value #FANIN_TRIGGER} other source files). The
 * fan-in signal is what separates a genuine God Class from a merely large leaf
 * class. Pointed refactoring: <b>Extract Class</b> / Extract Subclass / Interface.
 */
public final class GodClassDetector extends AbstractAstDetector {

    /** A class referenced by at least this many distinct other types is "central". */
    private static final int FANIN_TRIGGER = 8;

    public GodClassDetector() {
        super("god_class",
            "Large/God Class — a class that is both large (members > `threshold`, default 20) AND "
                + "high fan-in (referenced by >= " + FANIN_TRIGGER + " other types); points to Extract "
                + "Class. Unlike large_classes (size only), god_class also requires centrality.",
            20);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface()) {
                    return true; // God Class is a class smell
                }
                int members = node.getMethods().length + node.getFields().length;
                if (members <= threshold) {
                    return true;
                }
                int fanIn = fanIn(node, service);
                if (fanIn >= FANIN_TRIGGER) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "god_class", filePath, line, -1, "warning",
                        "Class '" + name + "' has " + members + " members (threshold " + threshold
                            + ") and high fan-in (" + fanIn + " referencing types >= " + FANIN_TRIGGER
                            + "). Consider Extract Class to split responsibilities.",
                        name));
                }
                return true;
            }
        });
    }

    /** Distinct other types that reference this type; 0 on any failure. */
    private int fanIn(TypeDeclaration node, IJdtService service) {
        try {
            ITypeBinding binding = node.resolveBinding();
            if (binding == null) {
                return 0;
            }
            IJavaElement element = binding.getJavaElement();
            if (!(element instanceof IType type)) {
                return 0;
            }
            String selfFqn = type.getFullyQualifiedName();
            List<SearchMatch> refs = service.getSearchService().findAllReferences(type, 1000);
            Set<String> referencingTypes = new HashSet<>();
            for (SearchMatch match : refs) {
                if (match.getElement() instanceof IJavaElement el) {
                    IType enclosing = (IType) el.getAncestor(IJavaElement.TYPE);
                    if (enclosing != null) {
                        String fqn = enclosing.getFullyQualifiedName();
                        if (!fqn.equals(selfFqn)) {
                            referencingTypes.add(fqn);
                        }
                    }
                }
            }
            return referencingTypes.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
