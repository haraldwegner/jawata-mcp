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
 * <p>Rows that end by removing something the rest of the row has just made unnecessary:
 * row 2 (Change Reference to Value) drops the setters, row 37 (Remove Setting Method)
 * drops a setter nobody writes through. Neither wants a general "delete this" tool on the
 * published surface — the value is in the reasoning that decided the thing was removable,
 * and a bare delete offers that reasoning to nobody.</p>
 *
 * <p>So this publishes NOTHING. It exists to be composed, and the operations that compose
 * it carry the judgement.</p>
 *
 * <p><b>IT HAS THREE PRODUCTION CALLERS, and this paragraph said it had none until a C7
 * architect watch read it.</b> Measured with {@code get_call_hierarchy} on
 * {@link #delete}: {@code InlineClassTool.inline} (row 17), {@code RemoveSubclassTool.remove}
 * (row 38) and {@code CollapseHierarchyTool} (row 4). None of the three is a consumer this
 * javadoc ever predicted — it named rows 34, 2 and 37, and all three of THOSE declined the
 * atom for their own written reasons. So the C3 open item "wire it, delete it, or baseline
 * it" is answered by measurement rather than by a decision: Stage 6 wired it, in a stage
 * nobody had listed.</p>
 *
 * <p>The lesson is the one this repository keeps paying for: a claim about the world, written
 * in a comment, with nothing that fails when it stops being true. It was false for a year and
 * grew louder the whole time.</p>
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
        // THE ONE WIDENING THE ENGINE NEVER ASKS ABOUT. Every question above arrives as a
        // confirmation and is answered no. Deleting a COMPILATION UNIT that declares more
        // than one top-level type is not a question — the caller named the file, and the
        // siblings go with it silently.
        //
        // This class promised otherwise ("nothing else is touched"; "an atom that quietly
        // widened its own blast radius…") and did not enforce it. A C7 architect watch found
        // the promise false and found the check written at ONE of the three production
        // callers, the newest; the other two — inline kind=class and inline kind=subclass —
        // could each take a sibling class with them, in shipped code. Asking here rather than
        // at each caller is the difference between a rule and a habit: the fourth caller
        // inherits it instead of having to remember it.
        //
        // What this CANNOT see is a caller deleting a unit because it wants a NESTED type
        // gone — the unit then has one top-level type and looks innocent. That intent lives
        // with the caller, so collapse_hierarchy keeps its own nested-class refusal.
        //
        // SO ONLY HALF THE PROMISE IS A RULE. A round-2 audit measured the other half and it
        // is still a habit: the nested guard occurs in ONE file across the bundle, and
        // `inline kind=class` and `inline kind=subclass` have none. That is pre-existing and
        // out of this repair's scope, but "the fourth caller inherits it" was written about
        // the whole check and is true only of the multi-top-level half.
        for (IJavaElement element : elements) {
            if (element instanceof org.eclipse.jdt.core.ICompilationUnit unit
                    && unit.getTypes().length > 1) {
                java.util.List<String> declared = new java.util.ArrayList<>();
                for (org.eclipse.jdt.core.IType declaredType : unit.getTypes()) {
                    declared.add(declaredType.getElementName());
                }
                return CheckedChange.refused(
                    org.eclipse.ltk.core.refactoring.RefactoringStatus.createFatalErrorStatus(
                        "refusing to delete " + unit.getElementName() + ": it declares "
                            + unit.getTypes().length + " top-level types " + declared
                            + ", and deleting the file takes all of them. Name the type you"
                            + " mean, or move it into a file of its own first."));
            }
        }
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
