package org.jawata.mcp.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.core.ModelSource;
import org.jawata.spi.host.HostWorkspace;

/**
 * The workspace of an engine that runs alone (Sprint 28k, E14; contract §5): the projects its Java model holds, which the engine
 * itself may load, add and remove.
 */
public final class ResidentHostWorkspace implements HostWorkspace {

    private final ModelSource model;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** @param model where the projects are read from */
    public ResidentHostWorkspace(ModelSource model) {
        this.model = model;
    }

    @Override
    public List<ProjectFolder> folders() {
        IJdtService service = model.current();
        List<ProjectFolder> folders = new ArrayList<>();
        if (service != null) {
            for (LoadedProject project : service.allProjects()) {
                folders.add(new ProjectFolder(project.projectKey(), project.projectRoot(), true));
            }
        }
        return List.copyOf(folders);
    }

    @Override
    public boolean ownsWorkspace() {
        return true;
    }

    @Override
    public void onFoldersChanged(Runnable listener) {
        listeners.add(listener);
    }
}
