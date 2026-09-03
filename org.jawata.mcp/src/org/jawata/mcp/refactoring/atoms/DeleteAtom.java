package org.jawata.mcp.refactoring.atoms;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.internal.corext.refactoring.reorg.IConfirmQuery;
import org.eclipse.jdt.internal.corext.refactoring.reorg.IReorgQueries;
import org.eclipse.jdt.internal.corext.refactoring.reorg.JavaDeleteProcessor;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.RefactoringEngine;

/**
 * DELETE, as an ATOM — an internal step, not a published operation.
 *
 * <p>Three of Fowler's rows end by removing something the rest of the row has just made
 * unnecessary: row 2 (Change Reference to Value) drops the setters, row 34 (Remove Dead
 * Code) drops what the unused check found, row 37 (Remove Setting Method) drops a setter
 * nobody writes through. None of them wants a general "delete this" tool on the published
 * surface — the value is in the reasoning that decided the thing was removable, and a
 * bare delete offers that reasoning to nobody.</p>
 *
 * <p>So this publishes NOTHING. It exists to be composed, and the operations that compose
 * it carry the judgement.</p>
 *
 * <h2>Why the Eclipse engine rather than an edit of our own</h2>
 *
 * <p>Deleting a member is not deleting text. {@link JavaDeleteProcessor} knows that
 * emptying a compilation unit means removing the FILE, that deleting a field may mean
 * deleting its accessors, that a package left empty may itself go, and that a read-only
 * resource needs a different answer than a writable one. Every one of those is a case a
 * text edit gets wrong silently, and the engine already has them.</p>
 *
 * <h2>The headless answer to every confirmation</h2>
 *
 * <p>The engine is written for an IDE and ASKS: delete the getters too? delete the
 * now-empty compilation unit? proceed on a read-only file? There is nobody to ask here,
 * so {@link #REFUSE} answers NO to all of it.</p>
 *
 * <p><b>No is the only safe default, and yes would be the dangerous one.</b> Each of
 * those questions offers to delete something the CALLER did not name — accessors it did
 * not list, a file it asked to empty rather than remove, a read-only resource somebody
 * protected on purpose. A composed row that wants the accessors gone names them; that is
 * row 2's job and it does it explicitly. An atom that quietly widened its own blast
 * radius would make every composition's undo bigger than its diff.</p>
 */
public final class DeleteAtom {

    /**
     * Every confirmation answered NO — see the class note on why this direction.
     *
     * <p>A single instance: it holds no state, and the engine only ever reads answers
     * from it.</p>
     */
    static final IReorgQueries REFUSE = new IReorgQueries() {
        @Override
        public IConfirmQuery createYesNoQuery(String question, boolean allowCancel, int id) {
            return no();
        }

        @Override
        public IConfirmQuery createYesYesToAllNoNoToAllQuery(
                String question, boolean allowCancel, int id) {
            return no();
        }

        @Override
        public IConfirmQuery createSkipQuery(String question, int id) {
            return no();
        }

        // NOT a lambda: IConfirmQuery declares confirm(String) AND
        // confirm(String, Object[]), so it is not a functional interface. Both
        // answer the same way, and they must — a query that refused the plain
        // question and accepted the one carrying elements would widen the
        // deletion on exactly the calls that name what they would take.
        private IConfirmQuery no() {
            return new IConfirmQuery() {
                @Override
                public boolean confirm(String question) {
                    return false;
                }

                @Override
                public boolean confirm(String question, Object[] elements) {
                    return false;
                }
            };
        }
    };

    private DeleteAtom() {
    }

    /**
     * A staged, undoable deletion of exactly these elements.
     *
     * @param elements what to delete — nothing else is touched, because every question
     *                 about widening the set is answered no
     * @param label    what the change is called in the undo history; the COMPOSED row's
     *                 name belongs here, not "delete", so the history reads as the
     *                 refactoring the human asked for
     * @return the checked change, refusals included — the caller decides whether a
     *         failed precondition ends its whole composition
     */
    public static CheckedChange delete(
            IJavaElement[] elements, String label, RefactoringEngine engine) throws Exception {
        JavaDeleteProcessor processor = new JavaDeleteProcessor(elements);
        processor.setQueries(REFUSE);
        // The caller names what it wants gone. Left true, the engine ADDS the accessors
        // of any field in the set — which is right in an IDE where a human sees the
        // preview, and wrong here where the composition already decided its own scope.
        processor.setSuggestGetterSetterDeletion(false);
        // Same argument one level up: deleting a package must not take packages the
        // caller never named.
        processor.setDeleteSubPackages(false);
        return engine.propose(new ProcessorBasedRefactoring(processor), label);
    }
}
