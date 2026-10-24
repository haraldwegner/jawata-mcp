package org.jawata.spi.host;

import java.util.Collection;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

/**
 * How a change reaches the files: the host performs it and hands back the change that undoes it. Headless, {@link #DIRECT}
 * performs it straight into the workspace; in Eclipse the host routes it through the editors a person has open (Stage 18, W3).
 *
 * <p>The two default members below are what the Eclipse host overrides (contract §4.4, 28k E10b): they are in the interface from
 * the first release so that the SPI ships 1.0.0 without a later major bump. A host with no editors answers them as the defaults do.</p>
 */
public interface WorkspaceWrites {

    /** Performs the change; the answer carries its undo and LTK's validation of the change, which may have refused it. */
    Applied perform(Change change, IProgressMonitor monitor) throws CoreException;

    /** Whether a change is one the person will keep, or a trial that is applied only to be checked and undone. */
    enum WriteMode {
        /** Applied to be judged and normally undone; a host must not let it reach the person's Edit › Undo history. */
        TRIAL,
        /** Applied for good; the person may undo it. */
        ACCEPTED
    }

    /** Performs the change in this mode; a host with no undo history of its own performs it exactly as {@link #perform(Change, IProgressMonitor)}. */
    default Applied perform(Change change, WriteMode mode, IProgressMonitor monitor) throws CoreException {
        return perform(change, monitor);
    }

    /**
     * Why this host will not write over these files, or empty when it will — for example unsaved editor text the write would
     * lose. A host with no editors never refuses.
     */
    default Optional<String> refusal(Collection<IFile> targets) {
        return Optional.empty();
    }

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
