package org.jawata.mcp.access;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The folder a session works in, so that the workspace the access policy hands to that session is its own and not the whole manifest
 * (Sprint 28h, S13, D20, ACL4). A hire is bound to the working directory it was given for as long as its brain runs; a session nobody
 * bound (a person's own client) is concerned with every loaded project, which is the reading before this class existed.
 *
 * <p><b>A folder narrows, it never widens.</b> The folder a session names counts only when it lies inside a loaded project: a hire told to
 * work somewhere else gets no workspace at all, and the {@code ${workspace}} entries then allow it nothing. Naming a folder is therefore
 * never a way to reach a place the loaded projects do not hold.</p>
 */
public final class SessionFolders {

    /** A binding held for the length of one run; closing it frees the session's name for the next run. */
    public final class Binding implements AutoCloseable {

        private final String session;

        private Binding(String session) {
            this.session = session;
        }

        @Override
        public void close() {
            folders.remove(session);
        }
    }

    private final Map<String, Path> folders = new ConcurrentHashMap<>();

    /**
     * Binds {@code session} to {@code folder} until the returned binding is closed. A null folder binds nothing: a run that states no
     * folder keeps the whole manifest, and says so by having no binding to look for.
     */
    public Binding bind(String session, Path folder) {
        if (folder != null) {
            folders.put(session, PlaceNames.normalise(folder));
        }
        return new Binding(session);
    }

    /** The folder {@code session} is bound to, when it is bound to one. */
    public Optional<Path> of(String session) {
        return session == null ? Optional.empty() : Optional.ofNullable(folders.get(session));
    }

    /**
     * The folders of the workspace {@code session} is concerned with: its own folder when it has one and that folder lies inside a loaded
     * project, nothing when it has one outside every loaded project, and every loaded project when it has none.
     */
    public List<Path> workspaceOf(String session, List<Path> loadedProjects) {
        Optional<Path> own = of(session);
        if (own.isEmpty()) {
            return loadedProjects;
        }
        Path folder = own.get();
        for (Path project : loadedProjects) {
            if (folder.startsWith(PlaceNames.normalise(project))) {
                return List.of(folder);
            }
        }
        return List.of();
    }
}
