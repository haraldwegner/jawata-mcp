package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprint 20 — shared JDT-search helpers for the smell / SOLID detectors,
 * extracted from the fan-in logic that was copy-pasted in {@code GodClassDetector},
 * {@code LazyClassDetector}, and {@code ShotgunSurgeryDetector}.
 */
public final class SmellSearch {

    private static final int MAX_REFS = 1000;

    private SmellSearch() {
    }

    /**
     * Count of distinct <em>other</em> types that reference {@code type} (its fan-in).
     *
     * @return the distinct referencing-type count (&ge; 0), or {@code -1} if the
     *         search could not be performed (null type / search failure). Callers
     *         whose gate fires on a <em>low</em> count (e.g. Lazy Class) must treat
     *         {@code -1} as "unknown — do not flag"; callers whose gate fires on a
     *         <em>high</em> count are naturally safe (-1 never exceeds a threshold).
     */
    public static int referencingTypeCount(IType type, IJdtService service) {
        if (type == null) {
            return -1;
        }
        try {
            String selfFqn = type.getFullyQualifiedName();
            List<SearchMatch> refs = service.getSearchService().findAllReferences(type, MAX_REFS);
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
            return -1;
        }
    }

    /** Convenience: resolve {@code binding} to its IType, then count referencing types. */
    public static int referencingTypeCount(ITypeBinding binding, IJdtService service) {
        if (binding == null || !(binding.getJavaElement() instanceof IType type)) {
            return -1;
        }
        return referencingTypeCount(type, service);
    }
}
