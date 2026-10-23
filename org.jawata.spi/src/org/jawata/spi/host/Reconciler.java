package org.jawata.spi.host;

import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Sprint 28k (contract step E4): what the engine asks of its host about files changed on disk. The headless host's
 * implementation is {@code StrictDiskSync}; an Eclipse host's waits for Eclipse's own build instead.
 */
public interface Reconciler {

    /**
     * Reconcile whatever changed on disk since the last call (files edited outside the engine, new project roots), before a
     * tool call computes anything, so every answer reflects the current tree.
     */
    SyncReport syncBeforeCall() throws CoreException;

    /**
     * Refresh the scope's projects from disk and build them the way {@code kind} says, so their problem markers describe the
     * files as they are now. A host that builds on its own (Eclipse) still answers only once that build has run.
     *
     * @return the names of the projects it built. A name it does not hold, or a project whose build failed, is LEFT OUT and
     *         never answered as built: a caller counts only what is here, so "0 errors" cannot mean "not built" (28k C1).
     */
    Set<String> rebuild(BuildScope scope, BuildKind kind, IProgressMonitor monitor) throws CoreException;
}
