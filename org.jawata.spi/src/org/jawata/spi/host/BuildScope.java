package org.jawata.spi.host;

import java.util.List;

/**
 * Sprint 28k (contract step E6): which projects a host builds, by the project names the host knows them by. Neutral: no
 * language's types, so a host builds what it owns and the engine names what it wants built.
 */
public record BuildScope(List<String> projects) {

    public BuildScope {
        projects = List.copyOf(projects);
    }

    /** The named projects. */
    public static BuildScope of(String... projects) {
        return new BuildScope(List.of(projects));
    }
}
