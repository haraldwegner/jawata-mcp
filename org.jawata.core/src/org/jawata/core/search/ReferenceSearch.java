package org.jawata.core.search;

import java.util.List;

import org.eclipse.jdt.core.search.SearchMatch;

/**
 * A reference search that still knows how many matches there were.
 *
 * <p>A capped search has two numbers — what you got and what exists — and
 * every consumer that reports only the first is reporting a page size as a
 * total. Measured on a 28-reference symbol: {@code find_references} with
 * {@code maxResults=2} answered {@code totalReferences: 2} and
 * {@code meta.totalCount: 2}, in <em>both</em> fields, with
 * {@code truncated: true} beside them. A caller reading either number learns
 * the wrong thing about the blast radius of a change, and the flag that
 * contradicts it is easy to miss and impossible to act on — "2, but actually
 * more" is not a number anyone can plan a refactoring around.</p>
 *
 * <p>The honest total costs nothing: the search requestor already sees every
 * match and simply drops the ones past the cap. It is counted on the way
 * past. {@code find_quality_issue} is the model — {@code count: 47,
 * returnedCount: 3}.</p>
 *
 * <p><strong>Why {@link SearchService#findAllReferences} still exists.</strong>
 * Five of jawata's seven internal callers only consume the list — a call
 * hierarchy, a smell scan, a landmark ranking — and publish no total at all.
 * They are not lying about anything, so they are not migrated. This type is
 * for callers that PUT A TOTAL IN THEIR ANSWER, and those must use it.</p>
 *
 * @param matches the matches kept, at most the caller's cap
 * @param totalMatched how many matches the search actually found
 */
public record ReferenceSearch(List<SearchMatch> matches, int totalMatched) {

    /** Were matches dropped to honour the cap? */
    public boolean truncated() {
        return totalMatched > matches.size();
    }
}
