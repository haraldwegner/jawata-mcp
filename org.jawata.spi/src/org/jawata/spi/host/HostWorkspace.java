package org.jawata.spi.host;

import java.nio.file.Path;
import java.util.List;

/**
 * Which folders the host's workspace holds, and whether the engine may change them (Sprint 28k, E14; contract §4.2).
 *
 * <p>Language-neutral. A host that runs the engine as its own process owns the workspace: the engine loads and drops projects at a
 * caller's request. A host that already has a workspace of its own (an IDE) does not: the engine follows it, and the workspace verbs
 * answer {@code HOST_OWNS_WORKSPACE}.</p>
 */
public interface HostWorkspace {

    /** The projects the workspace holds now, in the order the host lists them. */
    List<ProjectFolder> folders();

    /** Whether the engine may load, add and remove projects: true when the engine runs alone, false inside an IDE. */
    boolean ownsWorkspace();

    /** Ask to be told, on the thread that changed them, whenever the set of folders changes. */
    void onFoldersChanged(Runnable listener);

    /**
     * One project in the workspace.
     *
     * @param name the name the host knows the project by
     * @param path the absolute path of its root folder
     * @param open whether the project is open (a closed one is listed and not analysed)
     */
    record ProjectFolder(String name, Path path, boolean open) {
    }
}
