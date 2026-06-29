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
 * Sprint 17 (Fowler) — <b>Shotgun Surgery</b>. The structural dual of Divergent
 * Change: where Divergent Change is one class touched for many reasons, Shotgun
 * Surgery is one change that ripples across many classes. Proxy: a type
 * referenced from more than {@code threshold} distinct other types (default 5) —
 * a change to its surface forces edits in all of them. Pointed refactoring:
 * <b>Move Method/Field</b> (gather the scattered responsibility) / Inline Class.
 */
public final class ShotgunSurgeryDetector extends AbstractAstDetector {

    public ShotgunSurgeryDetector() {
        super("shotgun_surgery",
            "Shotgun Surgery — a type referenced from > `threshold` distinct other types (default 5); "
                + "a change to it ripples across all of them. Points to Move Method/Field to gather the "
                + "responsibility.",
            5);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                ITypeBinding binding = node.resolveBinding();
                if (binding == null || !(binding.getJavaElement() instanceof IType type)) {
                    return true;
                }
                int spread = referencingTypeCount(type, service);
                if (spread > threshold) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "shotgun_surgery", filePath, line, -1, "warning",
                        "Type '" + name + "' is referenced from " + spread + " distinct types (threshold "
                            + threshold + "); a change to it ripples widely. Consider Move Method/Field "
                            + "to gather the scattered responsibility.",
                        name));
                }
                return true;
            }
        });
    }

    private int referencingTypeCount(IType type, IJdtService service) {
        try {
            String selfFqn = type.getFullyQualifiedName();
            List<SearchMatch> refs = service.getSearchService().findAllReferences(type, 1000);
            Set<String> referencingTypes = new HashSet<>();
            for (SearchMatch match : refs) {
                if (match.getElement() instanceof IJavaElement el) {
                    IType enclosing = (IType) el.getAncestor(IJavaElement.TYPE);
                    if (enclosing != null && !enclosing.getFullyQualifiedName().equals(selfFqn)) {
                        referencingTypes.add(enclosing.getFullyQualifiedName());
                    }
                }
            }
            return referencingTypes.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
