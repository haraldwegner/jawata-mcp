package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sprint 20 (SOLID) — <b>Interface Segregation</b>. A fat interface (&ge;
 * {@code threshold} methods, default 6) whose callers use <em>disjoint</em>
 * subsets of its methods is forcing clients to depend on operations they don't
 * use. Detected by clustering the interface's methods on shared callers (a caller
 * links the methods it calls): &ge;2 disjoint client-usage clusters ⇒ split the
 * interface. Pointed refactoring: <b>Interface Segregation</b> (split along the
 * clusters). Distinct from LSP/Refused-Bequest (which is about implementors).
 */
public final class IspDetector extends AbstractAstDetector {

    public IspDetector() {
        super("isp",
            "Interface Segregation — a fat interface (>= `threshold` methods, default 6) whose "
                + "callers use disjoint method subsets; split it. Clusters methods by shared caller.",
            6);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (!node.isInterface() || node.getMethods().length < threshold) {
                    return true;
                }
                ITypeBinding binding = node.resolveBinding();
                if (binding == null || !(binding.getJavaElement() instanceof IType itype)) {
                    return true;
                }
                try {
                    String selfFqn = itype.getFullyQualifiedName();
                    // method name -> distinct caller types
                    Map<String, Set<String>> clientsByMethod = new LinkedHashMap<>();
                    for (IMethod m : itype.getMethods()) {
                        Set<String> clients = new HashSet<>();
                        for (SearchMatch match : service.getSearchService()
                                .findReferences(m, IJavaSearchConstants.REFERENCES, 200)) {
                            if (match.getElement() instanceof IJavaElement el) {
                                IType enc = (IType) el.getAncestor(IJavaElement.TYPE);
                                if (enc != null && !enc.getFullyQualifiedName().equals(selfFqn)) {
                                    clients.add(enc.getFullyQualifiedName());
                                }
                            }
                        }
                        if (!clients.isEmpty()) {
                            clientsByMethod.put(m.getElementName(), clients);
                        }
                    }
                    if (clientsByMethod.size() < 2) {
                        return true; // not enough used methods to be "disjoint"
                    }
                    List<Set<String>> clusters = clusterBySharedClient(clientsByMethod);
                    if (clusters.size() >= 2) {
                        int line = ast.getLineNumber(node.getStartPosition());
                        String name = node.getName().getIdentifier();
                        out.add(new Finding(
                            "isp", filePath, line, -1, "warning",
                            "Interface '" + name + "' (" + node.getMethods().length + " methods) serves "
                                + clusters.size() + " disjoint client groups " + render(clusters)
                                + ". Consider Interface Segregation — split along the clusters.",
                            name));
                    }
                } catch (Exception e) {
                    // search failure — do not flag
                }
                return true;
            }
        });
    }

    /** Union-find over method names; two methods merge when they share a caller. */
    private static List<Set<String>> clusterBySharedClient(Map<String, Set<String>> clientsByMethod) {
        Map<String, String> parent = new HashMap<>();
        for (String m : clientsByMethod.keySet()) {
            parent.put(m, m);
        }
        List<String> methods = new ArrayList<>(clientsByMethod.keySet());
        for (int i = 0; i < methods.size(); i++) {
            for (int j = i + 1; j < methods.size(); j++) {
                if (intersects(clientsByMethod.get(methods.get(i)), clientsByMethod.get(methods.get(j)))) {
                    union(parent, methods.get(i), methods.get(j));
                }
            }
        }
        Map<String, Set<String>> byRoot = new LinkedHashMap<>();
        for (String m : methods) {
            byRoot.computeIfAbsent(find(parent, m), k -> new TreeSet<>()).add(m);
        }
        return new ArrayList<>(byRoot.values());
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.put(find(parent, a), find(parent, b));
    }

    private static String render(List<Set<String>> clusters) {
        List<String> parts = new ArrayList<>();
        for (Set<String> c : clusters) {
            parts.add(c.toString());
        }
        return String.join(" / ", parts);
    }
}
