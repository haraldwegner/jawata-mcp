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
        ACCEPTED,
        /**
         * The engine takes back a change it kept (its own undo). It is not a step the person made, so a host with an undo history of its
         * own must not list it there, and the step the kept change left is the host's to settle (28k ledger row A18-2, the double undo).
         */
        UNDO
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

    /**
     * Tells the host that a change it performed as a {@link WriteMode#TRIAL} was accepted, and is to stay as an ordinary step on the
     * person's undo history from now on (28k contract §16, ledger 28k-A18-1). The engine applies every change forward as a trial, so that a
     * change its gate refuses and takes back never reaches the person's Edit &gt; Undo, and calls this once when the gate accepts it. A
     * change the gate refused is never kept.
     *
     * <p>The default does nothing and answers true: a host with no undo history of its own has nothing to keep.</p>
     *
     * @param applied what {@link #perform(Change, WriteMode, IProgressMonitor)} answered for this change
     * @param name    the change's name, for the label of the step on the person's undo history
     * @return true when the host now holds the change as an ordinary undoable edit, false when it could not keep it
     */
    default boolean keep(Applied applied, String name) {
        return true;
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
