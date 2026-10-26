package org.jawata.resident.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The source folders a member says it has (Sprint 28k, Stage 8; contract S4, engine half): the main ones and the test ones, as the
 * studio read them from an IntelliJ module's {@code .iml} for a module that has no build file to say so. When a member has this, the
 * importer mounts exactly these folders, the test ones marked test, instead of discovering roots.
 *
 * @param main the folders that hold production code
 * @param test the folders that hold test code
 */
public record DeclaredSourceRoots(List<Path> main, List<Path> test) {

    /** Takes private copies of the folder lists, and an absent list as an empty one. */
    public DeclaredSourceRoots {
        main = main == null ? List.of() : List.copyOf(main);
        test = test == null ? List.of() : List.copyOf(test);
    }

    /**
     * @return every declared folder, the main ones first
     */
    public List<Path> all() {
        List<Path> all = new ArrayList<>(main);
        all.addAll(test);
        return List.copyOf(all);
    }

    /**
     * @param folder a folder this declaration names
     * @return true when the member declared it as a test folder
     */
    public boolean isTest(Path folder) {
        return test.contains(folder);
    }

    /**
     * The declared folders that exist, which is all that can be mounted. A folder the {@code .iml} names and the disk no longer has is
     * left out, as a source folder that is not there has nothing to mount.
     *
     * @return the existing declared folders, the main ones first
     */
    public List<Path> existing() {
        return all().stream().filter(Files::isDirectory).toList();
    }
}
