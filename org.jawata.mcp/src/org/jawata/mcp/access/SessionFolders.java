package org.jawata.mcp.access;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The workspace the access policy hands to a session (Sprint 28h, S13, D20, ACL4, replaced by Stage 27 (p) on his rulings 2026-11-18 and
 * 2026-11-20). Every session, a hire's or a person's own client, sees the same workspace: the union of the projects the engine loaded and the
 * folders the editor windows hold (a TypeScript or documents folder is not a Java project, and the windows name it). A folder a hire is told
 * to work in is never added, so naming one is never a way to reach a place the workspace does not hold.
 */
public final class SessionFolders {

    /**
     * The folders of the workspace {@code session} is concerned with: the loaded projects and the editor's folders, all of them, each listed
     * once (his ruling 2026-11-18: "always the union of the VSCode workspace and the jawata workspace"). The answer is the same for every
     * session; a hire told to work outside the workspace is refused that folder and keeps the workspace (audit 2026-11-20).
     */
    public List<Path> workspaceOf(String session, List<Path> loadedProjects, List<Path> editorFolders) {
        LinkedHashSet<Path> workspace = new LinkedHashSet<>();
        loadedProjects.forEach(project -> workspace.add(PlaceNames.normalise(project)));
        editorFolders.forEach(folder -> workspace.add(PlaceNames.normalise(folder)));
        return List.copyOf(workspace);
    }
}
