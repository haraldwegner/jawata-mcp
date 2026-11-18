package org.jawata.mcp.access;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The folder a session works in, and the workspace the access policy hands to it (Sprint 28h, S13, D20, ACL4, replaced by Stage 27 (p) on
 * his ruling 2026-11-18). A hire is bound to the working directory it was given for as long as its brain runs. The workspace every session
 * sees is the union of the projects the engine loaded and the folders the editor holds (a TypeScript or documents folder is not a Java
 * project and is named by a setting), and a session nobody bound (a person's own client) sees all of it too.
 *
 * <p><b>Naming a folder never widens.</b> The folder a session names counts only when it lies inside that workspace: a hire told to work
 * somewhere else gets no workspace at all, and the {@code ${workspace}} entries then allow it nothing. Naming a folder is therefore never
 * a way to reach a place the workspace does not hold.</p>
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
     * The folders of the workspace {@code session} is concerned with: the loaded projects and the editor's folders, all of them, when the
     * session has no folder or has one that lies inside any of them; nothing when it has a folder outside every one of them.
     */
    public List<Path> workspaceOf(String session, List<Path> loadedProjects, List<Path> editorFolders) {
        java.util.LinkedHashSet<Path> workspace = new java.util.LinkedHashSet<>();
        loadedProjects.forEach(project -> workspace.add(PlaceNames.normalise(project)));
        editorFolders.forEach(folder -> workspace.add(PlaceNames.normalise(folder)));
        Optional<Path> own = of(session);
        if (own.isEmpty()) {
            return List.copyOf(workspace);
        }
        for (Path place : workspace) {
            if (own.get().startsWith(place)) {
                return List.copyOf(workspace);
            }
        }
        return List.of();
    }

    /**
     * The folders a setting names: a path list ({@link java.io.File#pathSeparator}), blanks and repeats dropped, each under its one name.
     * A missing or blank setting names none.
     */
    public static List<Path> editorFolders(String setting) {
        if (setting == null || setting.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashSet<Path> named = new java.util.LinkedHashSet<>();
        for (String piece : setting.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!piece.isBlank()) {
                named.add(PlaceNames.normalise(Path.of(piece.strip())));
            }
        }
        return List.copyOf(named);
    }
}
