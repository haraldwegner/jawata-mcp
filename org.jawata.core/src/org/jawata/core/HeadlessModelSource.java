package org.jawata.core;

import java.nio.file.Path;

import org.eclipse.core.runtime.CoreException;

/**
 * The Java model of an engine that runs alone: a {@link JdtServiceImpl} that is built from a project folder (Sprint 28k, E14).
 *
 * <p>{@link #install} is for the engine's own boot paths, which build a service themselves (the workspace file, the environment
 * variable) and put it in place; {@link #open} is for a caller who asks for a project at run time.</p>
 */
public final class HeadlessModelSource implements ModelSource {

    private volatile IJdtService current;

    @Override
    public IJdtService current() {
        return current;
    }

    /** Put {@code service} in place as the model, replacing whatever was there. */
    public void install(IJdtService service) {
        current = service;
    }

    @Override
    public IJdtService open(Path projectRoot) throws CoreException {
        // The service already in place is reused so that add_project, remove_project, list_projects and any running analysis
        // keep operating on the same workspace. Its loadProject() clears what was loaded and installs this project as the
        // default (Sprint 10 ADR Q6). It is installed BEFORE the load, so a load that fails part-way leaves the service
        // where the tools that hold it can still see it.
        JdtServiceImpl service = current instanceof JdtServiceImpl existing ? existing : new JdtServiceImpl();
        current = service;
        service.loadProject(projectRoot);
        return service;
    }
}
