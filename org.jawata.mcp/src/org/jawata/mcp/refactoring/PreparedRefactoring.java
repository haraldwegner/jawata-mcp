package org.jawata.mcp.refactoring;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

/**
 * A {@link Change} that has already been built, presented as an LTK {@link Refactoring} so
 * it can go through the standard apply pipeline.
 *
 * <p>The pipeline in {@code AbstractApplyingRefactoringTool} takes a {@code Refactoring} —
 * that is what gives every operation its compile gate, its parity check and its undo
 * handle. An operation that assembles its own composite change (edits in one file plus a
 * deletion of another, say) has the change in hand and nothing to run its conditions
 * against, and the pipeline's own {@code respondForChange} is private on purpose. This is
 * the adapter: no conditions of its own, because the caller already checked everything it
 * could check, and the compile gate is what proves the result.</p>
 *
 * <p>Both preconditions return an empty (OK) status deliberately. A refusal here would be
 * invisible to the caller, which has already produced its own refusal messages naming the
 * member and the reason; a second, vaguer one from an adapter would be worse than none.</p>
 */
public final class PreparedRefactoring extends Refactoring {

    private final Change change;
    private final String label;

    public PreparedRefactoring(Change change, String label) {
        this.change = change;
        this.label = label;
    }

    @Override
    public String getName() {
        return label;
    }

    @Override
    public RefactoringStatus checkInitialConditions(IProgressMonitor pm) {
        return new RefactoringStatus();
    }

    @Override
    public RefactoringStatus checkFinalConditions(IProgressMonitor pm) {
        return new RefactoringStatus();
    }

    @Override
    public Change createChange(IProgressMonitor pm) {
        return change;
    }
}
