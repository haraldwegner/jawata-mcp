package org.jawata.spi.host;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What a host says about its workspace before anything is loaded (Sprint 28k, Stage 8; contract §4.2): the name the workspace goes by
 * and the project folders it was started with.
 *
 * <p>This is not {@link HostWorkspace#folders()}. Those are the projects that are open now, and a host that loads its projects
 * itself has none until the load is done. The declaration is what the engine can state about itself while the load is still running:
 * who it is, for the other residents on the machine, and which project the knowledge it records belongs to.</p>
 *
 * @param name     the workspace's name; null when the host has none
 * @param projects the project folders the workspace was configured with; empty when it has none
 */
public record DeclaredWorkspace(String name, List<Path> projects) {

    /** Takes a private copy of the folders, and an absent list as an empty one. */
    public DeclaredWorkspace {
        projects = projects == null ? List.of() : List.copyOf(projects);
    }

    /** The first folder it was configured with, which the knowledge store records as the project it belongs to. */
    public Optional<Path> firstProject() {
        return projects.isEmpty() ? Optional.empty() : Optional.of(projects.get(0));
    }
}
