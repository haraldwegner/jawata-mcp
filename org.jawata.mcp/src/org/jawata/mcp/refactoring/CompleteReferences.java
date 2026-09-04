package org.jawata.mcp.refactoring;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.core.search.ReferenceSearch;

import java.util.List;

/**
 * EVERY reference, or a refusal — never a page presented as a whole.
 *
 * <p>{@code findAllReferences(element, 500)} returns a bare list, so a truncated result and
 * a complete one are the same object. For a tool that only DISPLAYS references that is
 * fine: it shows what it found and lies about nothing. Sprint 28d-rescue Stage 6
 * introduced a third kind of caller the existing note did not anticipate — <b>one that
 * decides SAFETY from completeness</b>.</p>
 *
 * <p>Those operations rewrite or delete based on having seen every use. {@code move
 * kind=statements_into_function} refuses when the statement is beside only some calls;
 * at 501 references it would check 500, find them consistent, and apply, ADDING behaviour
 * at the calls it never looked at. {@code inline kind=middle_man} rewrites every call site
 * it enumerated and would leave the 501st calling a method that no longer exists.</p>
 *
 * <p>So this asks the truncation-aware search — which has existed all along and reports the
 * true total beside the page — and REFUSES rather than returning a short list. An operation
 * that cannot see all the uses declines to change any of them, and says how many there
 * were, which is the number that tells a caller to narrow the scope instead.</p>
 *
 * <p>An architect watch at C6 found the eight call sites. The cap is not raised here: a
 * bigger number moves the cliff, it does not remove it.</p>
 */
public final class CompleteReferences {

    /**
     * The cap. It is a guard against a runaway search rather than a display page — nothing
     * here shows a page — so it is set where a genuine refactoring target stops being one:
     * a member with two thousand references is not something to rewrite in one call.
     */
    public static final int CAP = 2000;

    private CompleteReferences() {
    }

    /** Thrown when the element has more references than the search would look at. */
    public static final class TooManyReferences extends Exception {

        private static final long serialVersionUID = 1L;

        private final int total;

        TooManyReferences(IJavaElement element, int total) {
            super(element.getElementName() + " has " + total + " references, more than the "
                + CAP + " this operation will examine. It rewrites or removes based on"
                + " having seen EVERY use, so acting on a partial list would change the"
                + " ones it looked at and break the ones it did not. Narrow the scope, or"
                + " reduce the references first.");
            this.total = total;
        }

        /** How many there actually are — the number that says how far off the cap it is. */
        public int total() {
            return total;
        }
    }

    /**
     * Every reference to the element.
     *
     * @throws TooManyReferences when the search was truncated, so the caller cannot decide
     *                           anything from completeness
     */
    public static List<SearchMatch> of(IJdtService service, IJavaElement element)
            throws Exception {
        ReferenceSearch found = service.getSearchService()
            .searchReferences(element, IJavaSearchConstants.REFERENCES, CAP);
        if (found.truncated()) {
            throw new TooManyReferences(element, found.totalMatched());
        }
        return found.matches();
    }
}
