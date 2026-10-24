package org.jawata.core;

import java.nio.file.Path;

import org.eclipse.core.runtime.CoreException;

/**
 * Where the engine gets its Java model, and how a Java project comes into it (Sprint 28k, E14; contract §4.1).
 *
 * <p>The host supplies it, through {@link JavaBinding}. A host that runs the engine alone builds the model itself from a project
 * folder; a host that has a Java model of its own (an IDE) hands that one over and refuses {@link #open}.</p>
 */
public interface ModelSource {

    /** The model now in place, or null until one exists. */
    IJdtService current();

    /**
     * Load the Java project at {@code projectRoot} into the model, replacing what was loaded: the wipe-and-load of {@code load_project}.
     * Reuses the model already in place when there is one, so the tools that hold it keep operating on the same workspace.
     *
     * @param projectRoot the absolute path of the project's root folder
     * @return the model, with the project loaded as its default
     * @throws CoreException if the project cannot be loaded
     */
    IJdtService open(Path projectRoot) throws CoreException;
}
