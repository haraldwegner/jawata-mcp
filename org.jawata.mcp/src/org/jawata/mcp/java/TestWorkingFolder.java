package org.jawata.mcp.java;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.LoadedProject;
import org.jawata.core.project.ProjectImporter;
import org.jawata.core.project.SourceRootClassifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The folder a forked test JVM starts in (S19-17).
 *
 * <p>Maven and Gradle start a module's tests in the module's own folder, and tests read files by
 * paths relative to it. The runner used to start them in the JDT project's own folder, which for a
 * studio-managed project is a link folder in the studio's cache: design-patterns' page-object read
 * {@code ../sample-application/...} and failed 4 of 4, while singleton, which reads nothing
 * relative, passed 19 of 19.</p>
 *
 * <p>The folder is the module that owns the test source roots. Which folder owns a root is the
 * importer's own question ({@link ProjectImporter#owningModuleDir}); this class does not walk a
 * second time. When the roots belong to several modules, or one of them belongs to no module, one
 * JVM cannot start in all of them and the project's real root is used.</p>
 */
public final class TestWorkingFolder {

    /** What marks a folder as a module for a test run: Maven, Gradle, or an Eclipse project. */
    private static final List<String> MODULE_FILES =
        List.of("pom.xml", "build.gradle", "build.gradle.kts", ".classpath");

    private TestWorkingFolder() {}

    /**
     * The folder for a loaded project: the module owning its test source roots, as the model
     * classifies them, else the project's real root.
     */
    public static Path of(LoadedProject loaded) throws JavaModelException {
        IWorkspaceRoot workspace = ResourcesPlugin.getWorkspace().getRoot();
        List<Path> testRoots = new ArrayList<>();
        for (IClasspathEntry entry : loaded.javaProject().getRawClasspath()) {
            if (entry.getEntryKind() != IClasspathEntry.CPE_SOURCE
                    || !SourceRootClassifier.isTestEntry(entry)) {
                continue;
            }
            IResource folder = workspace.findMember(entry.getPath());
            if (folder != null && folder.getLocation() != null) {
                testRoots.add(folder.getLocation().toFile().toPath());
            }
        }
        return choose(loaded.projectRoot(), testRoots);
    }

    /**
     * @param projectRoot the project's real folder
     * @param testRoots the real folders of its test source roots
     * @return the one module folder that owns every test root, else {@code projectRoot}
     */
    public static Path choose(Path projectRoot, Collection<Path> testRoots) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Set<Path> modules = new LinkedHashSet<>();
        for (Path testRoot : testRoots) {
            Path module = ProjectImporter.owningModuleDir(
                testRoot.toAbsolutePath().normalize(), root, MODULE_FILES);
            if (module == null) {
                return root;
            }
            modules.add(module);
        }
        return modules.size() == 1 ? modules.iterator().next() : root;
    }
}
