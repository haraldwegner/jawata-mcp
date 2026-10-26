package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jawata.spi.host.HostWorkspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every host's workspace promises the engine (Sprint 28k, Stage 8; contract §18 ring 1), whoever the host is: the headless
 * resident, the Eclipse plug-in or a host that is nothing but memory. The engine reads the projects the host has open and asks whether
 * it may change the workspace; nothing else about a host is the engine's business.
 */
public abstract class HostWorkspaceContract {

    /**
     * @return the workspace under test, in the state the implementation's test leaves it in, with the folders {@link #expectedFolders()} names
     * @throws Exception when the implementation cannot be built
     */
    protected abstract HostWorkspace workspace() throws Exception;

    /**
     * @return the names of the projects the workspace under test has open; empty for a workspace with none
     */
    protected abstract Set<String> expectedFolders();

    @Test
    @DisplayName("the workspace answers a list, and it holds the projects the host has open")
    void theWorkspaceListsWhatIsOpen() throws Exception {
        List<HostWorkspace.ProjectFolder> folders = workspace().folders();

        assertNotNull(folders, "no list is an absent workspace, which the engine cannot tell from an empty one");
        assertEquals(expectedFolders(), names(folders));
    }

    @Test
    @DisplayName("every folder has a name, a path that is absolute, and the names are unique")
    void everyFolderCanBeAddressed() throws Exception {
        List<HostWorkspace.ProjectFolder> folders = workspace().folders();

        Set<String> seen = new HashSet<>();
        for (HostWorkspace.ProjectFolder folder : folders) {
            assertAll(folder.name(),
                () -> assertNotNull(folder.name()),
                () -> assertFalse(folder.name().isBlank(), "a project without a name cannot be asked about"),
                () -> assertNotNull(folder.path()),
                () -> assertTrue(folder.path().isAbsolute(), "a relative path means whatever the process's folder is: " + folder.path()));
            assertTrue(seen.add(folder.name()), "two projects named " + folder.name() + " cannot be told apart");
        }
    }

    @Test
    @DisplayName("the list is a snapshot: the engine cannot change the workspace by changing the list it was handed")
    void theListIsASnapshot() throws Exception {
        HostWorkspace workspace = workspace();
        List<HostWorkspace.ProjectFolder> folders = workspace.folders();

        assertThrows(UnsupportedOperationException.class, () -> folders.add(
            new HostWorkspace.ProjectFolder("intruder", Path.of("/intruder").toAbsolutePath(), true)));
        assertEquals(expectedFolders(), names(workspace.folders()), "the workspace is as it was");
    }

    @Test
    @DisplayName("whether the engine may change the workspace is one answer, not a coin that lands differently each time")
    void ownershipIsStable() throws Exception {
        HostWorkspace workspace = workspace();

        assertEquals(workspace.ownsWorkspace(), workspace.ownsWorkspace());
    }

    private static Set<String> names(List<HostWorkspace.ProjectFolder> folders) {
        Set<String> names = new HashSet<>();
        folders.forEach(folder -> names.add(folder.name()));
        return names;
    }
}
