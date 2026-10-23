package org.jawata.spi.host;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

/**
 * How a change reaches the files: the host performs it and hands back the change that undoes it. Headless, {@link #DIRECT}
 * performs it straight into the workspace; in Eclipse the host routes it through the editors a person has open (Stage 18, W3).
 */
public interface WorkspaceWrites {

    /** Performs the change; the answer carries its undo and LTK's validation of the change, which may have refused it. */
    Applied perform(Change change, IProgressMonitor monitor) throws CoreException;

    /** What performing a change left: the change that undoes it (null when nothing was done) and LTK's validation status. */
    record Applied(Change undo, RefactoringStatus validation) {
    }

    /** Straight into the workspace, through LTK's own perform operation. */
    WorkspaceWrites DIRECT = (change, monitor) -> {
        PerformChangeOperation operation = new PerformChangeOperation(change);
        operation.run(monitor);
        return new Applied(operation.getUndoChange(), operation.getValidationStatus());
    };
}
