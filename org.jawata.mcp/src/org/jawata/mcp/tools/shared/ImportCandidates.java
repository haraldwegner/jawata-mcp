package org.jawata.mcp.tools.shared;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.jawata.core.IJdtService;
import org.jawata.core.TypeLookupException;

/**
 * The one search for the types a simple name could be imported from (Sprint 28h, S16f 4): the project's sources, its libraries and the
 * JDK, with the packages that cannot or should not be imported left out, ranked so the commonly needed package comes first. The quick-fix
 * list and the import suggestion tool both ask here, so what one offers the other offers.
 */
public final class ImportCandidates {

    /**
     * The search for types of a given simple name, replaceable so a search that FAILS can be shown without breaking the real index. The
     * default asks the project's own type index.
     */
    public interface Search {
        void find(IJdtService service, String typeName, TypeNameMatchRequestor requestor) throws Exception;
    }

    /** One type a name could be imported from, with how likely it is the one wanted. */
    public record Candidate(IType type, String fullyQualifiedName, String packageName, int relevance) { }

    private ImportCandidates() { }

    /** The search of the project's own type index, sources and libraries together. */
    public static Search inProject() {
        return (service, typeName, requestor) -> {
            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                new IJavaElement[] { service.getJavaProject() },
                IJavaSearchScope.SOURCES | IJavaSearchScope.APPLICATION_LIBRARIES | IJavaSearchScope.SYSTEM_LIBRARIES);
            new SearchEngine().searchAllTypeNames(
                null,
                SearchPattern.R_EXACT_MATCH,
                typeName.toCharArray(),
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                IJavaSearchConstants.TYPE,
                scope,
                requestor,
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                new NullProgressMonitor());
        };
    }

    /**
     * The types named {@code typeName} that can be imported, most likely first, at most {@code limit} of them. A search that fails is a
     * {@link TypeLookupException}, which is not the answer "no such type": that answer is an empty list.
     */
    public static List<Candidate> find(IJdtService service, String typeName, int limit, Search search) {
        List<Candidate> candidates = new ArrayList<>();
        TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {
            @Override
            public void acceptTypeNameMatch(TypeNameMatch match) {
                if (candidates.size() >= limit * 2) { // collect extra so the sort has a choice
                    return;
                }
                try {
                    IType type = match.getType();
                    String packageName = type.getPackageFragment().getElementName();
                    if (!importable(packageName)) {
                        return;
                    }
                    candidates.add(new Candidate(type, type.getFullyQualifiedName(), packageName, relevanceOf(packageName)));
                } catch (Exception e) {
                    // a match that cannot be read is left out; the search as a whole did not fail
                }
            }
        };
        try {
            search.find(service, typeName, requestor);
        } catch (Exception e) {
            throw new TypeLookupException(typeName, e);
        }
        candidates.sort(Comparator.comparingInt(Candidate::relevance).reversed());
        return candidates.size() > limit ? candidates.subList(0, limit) : candidates;
    }

    /** A type in the default package cannot be imported, and the internal, implementation and vendor packages are not for use. */
    private static boolean importable(String packageName) {
        return !packageName.isEmpty()
            && !packageName.contains(".internal.")
            && !packageName.contains(".impl.")
            && !packageName.startsWith("sun.")
            && !packageName.startsWith("com.sun.");
    }

    /** How commonly the package is the one wanted: higher is likelier. */
    private static int relevanceOf(String packageName) {
        if (packageName.equals("java.util")) return 100;
        if (packageName.startsWith("java.util.")) return 95;
        if (packageName.equals("java.io")) return 90;
        if (packageName.equals("java.nio.file")) return 88;
        if (packageName.startsWith("java.io.")) return 85;
        if (packageName.startsWith("java.time")) return 85;
        if (packageName.startsWith("java.nio.")) return 82;
        if (packageName.equals("java.lang")) return 80;
        if (packageName.startsWith("java.math")) return 75;
        if (packageName.startsWith("java.")) return 70;
        if (packageName.startsWith("javax.")) return 65;
        if (packageName.startsWith("org.springframework.")) return 60;
        if (packageName.startsWith("org.junit.")) return 60;
        if (packageName.startsWith("org.mockito.")) return 55;
        if (packageName.startsWith("org.slf4j.")) return 55;
        if (packageName.startsWith("jakarta.")) return 50;
        if (packageName.startsWith("org.")) return 45;
        if (packageName.startsWith("com.")) return 40;
        return 30;
    }
}
