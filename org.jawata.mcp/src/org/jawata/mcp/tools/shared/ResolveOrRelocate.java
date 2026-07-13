package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.fqn.FqnResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Sprint 24 (D2) — <b>stale memory repairs itself</b>. The human loop is: go
 * where you think it is; on a miss, search; when the search finds it elsewhere,
 * REPLACE the stale memory with the finding. An agent addressing a symbol by
 * name (its stable memory key) should get that whole loop in ONE call, not a
 * bare "not found" that costs a second round trip and leaves the repair
 * undone.
 *
 * <p>So a name that no longer resolves is answered with the indexed best-guess
 * relocation in the SAME response — "not there; found here", explicitly flagged
 * as a correction ({@code SYMBOL_RELOCATED}).</p>
 *
 * <p><b>The correction is never acted upon.</b> The call still fails: silently
 * retargeting a refactoring at a symbol the caller did not name would be far
 * worse than a failed call. The agent re-issues once with the corrected name —
 * one hop, and its memory is now right.</p>
 */
public final class ResolveOrRelocate {

    private static final Logger log = LoggerFactory.getLogger(ResolveOrRelocate.class);

    /** Distinct from SYMBOL_NOT_FOUND: the symbol EXISTS, under another name. */
    public static final String RELOCATED = "SYMBOL_RELOCATED";

    private static final int MAX_CANDIDATES = 3;

    private ResolveOrRelocate() {
    }

    /**
     * The honest answer for a name that did not resolve: a correction when the
     * index can find it elsewhere, a plain not-found when it genuinely cannot.
     */
    public static ToolResponse miss(IJdtService service, String name, String scopeLabel) {
        List<String> candidates = relocate(service, name);
        if (candidates.isEmpty()) {
            return ToolResponse.symbolNotFound(
                "'" + name + "' not found in " + scopeLabel + " scope, and nothing "
                    + "similarly named exists — it is gone, not moved.");
        }
        String best = candidates.get(0);
        String others = candidates.size() > 1
            ? " Other candidates: " + String.join(", ", candidates.subList(1, candidates.size())) + "."
            : "";
        return ToolResponse.error(RELOCATED,
            "'" + name + "' is not there any more — it appears to have moved or been "
                + "renamed. Found: '" + best + "'.",
            "STALE MEMORY: re-issue with '" + best + "' and remember THAT name from now on."
                + others);
    }

    /**
     * Best-guess relocations for a name that no longer resolves, most likely
     * first. Empty when the index knows nothing by that name.
     */
    public static List<String> relocate(IJdtService service, String name) {
        try {
            int hash = name.indexOf('#');
            String typePart = hash < 0 ? name : name.substring(0, hash);
            String memberPart = hash < 0 ? null : name.substring(hash + 1);
            int paren = memberPart == null ? -1 : memberPart.indexOf('(');
            String memberName = paren < 0 ? memberPart : memberPart.substring(0, paren);

            // Did the TYPE move? (The common case: a class was moved or renamed.)
            if (FqnResolver.resolveWorkspace(typePart, service).isEmpty()) {
                return relocatedTypes(service, simpleName(typePart), memberName);
            }
            // The type is there, so the MEMBER is what changed. Name its siblings —
            // the agent picks the one it meant.
            return siblingMembers(service, typePart, memberName);
        } catch (Exception e) {
            log.debug("Relocation search for '{}' failed: {}", name, e.getMessage());
            return List.of();
        }
    }

    /** Types now living under a different FQN but the same simple name. */
    private static List<String> relocatedTypes(IJdtService service, String simpleName,
                                               String memberName) throws Exception {
        Set<String> found = new LinkedHashSet<>();
        List<SearchMatch> matches = new ArrayList<>(service.getSearchService()
            .searchSymbolsInSource(simpleName, IJavaSearchConstants.TYPE, MAX_CANDIDATES * 3));
        for (SearchMatch match : matches) {
            if (!(match.getElement() instanceof IType type)) {
                continue;
            }
            String fqn = type.getFullyQualifiedName();
            // Only offer the member suffix when that member really is on the new type.
            if (memberName != null && !memberName.isBlank()) {
                if (FqnResolver.resolveWorkspace(fqn + "#" + memberName, service).isEmpty()) {
                    continue;
                }
                fqn = fqn + "#" + memberName;
            }
            found.add(fqn);
            if (found.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return new ArrayList<>(found);
    }

    /** The type is still there — say which members it actually has. */
    private static List<String> siblingMembers(IJdtService service, String typeFqn,
                                               String memberName) {
        if (memberName == null || memberName.isBlank()) {
            return List.of();
        }
        Optional<IJavaElement> typeEl = FqnResolver.resolveWorkspace(typeFqn, service);
        if (typeEl.isEmpty() || !(typeEl.get() instanceof IType type)) {
            return List.of();
        }
        List<String> siblings = new ArrayList<>();
        try {
            for (IMethod m : type.getMethods()) {
                siblings.add(typeFqn + "#" + m.getElementName());
            }
            for (IField f : type.getFields()) {
                siblings.add(typeFqn + "#" + f.getElementName());
            }
        } catch (Exception e) {
            log.debug("Listing members of {} failed: {}", typeFqn, e.getMessage());
        }
        // Closest by name first — a rename usually keeps most of the word.
        siblings.sort((a, b) -> Integer.compare(
            distance(memberName, member(b)), distance(memberName, member(a))));
        return siblings.size() > MAX_CANDIDATES ? siblings.subList(0, MAX_CANDIDATES) : siblings;
    }

    private static String member(String fqn) {
        int hash = fqn.indexOf('#');
        return hash < 0 ? fqn : fqn.substring(hash + 1);
    }

    /** Crude shared-prefix/substring affinity — enough to rank a rename first. */
    private static int distance(String wanted, String candidate) {
        String a = wanted.toLowerCase();
        String b = candidate.toLowerCase();
        if (a.equals(b)) return 100;
        if (b.contains(a) || a.contains(b)) return 50;
        int common = 0;
        while (common < a.length() && common < b.length() && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common;
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
