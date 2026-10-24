package org.jawata.core;

import java.nio.file.Path;

import org.eclipse.jdt.core.IJavaProject;

/** The Java runtime a project runs on, as the host knows it (Sprint 28k, E14; contract §4.1). */
public interface JavaRuntimes {

    /**
     * The home of the runtime for {@code project}.
     *
     * @param project the project asking
     * @return the runtime's home directory; a host that knows no per-project runtime answers the one the engine runs on
     */
    Path jreFor(IJavaProject project);
}
