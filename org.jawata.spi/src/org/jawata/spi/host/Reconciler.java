package org.jawata.spi.host;

import org.eclipse.core.runtime.CoreException;

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
}
