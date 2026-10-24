package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.spi.lang.BuildVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 28k ledger C2b-12: a project is built on the compiled output of the projects it requires, so those must be built first.
 * {@code rebuildAfter} used to list the requirers in the order the workspace happened to hold them.
 */
class JavaBuildVerdictBuildOrderTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("projects are listed required-first, whatever order they were loaded in")
    void aRequiredProjectIsBuiltBeforeItsRequirers() throws Exception {
        // Loaded last-requirer first, so the order the workspace holds them in is the reverse of the order they must be built in.
        JdtServiceImpl service = helper.loadWorkspaceCopy(new String[] {"compose-clean", "extract-crossfile", "compile-clean"});
        List<LoadedProject> loaded = new ArrayList<>(service.allProjects());
        LoadedProject last = loaded.get(0);
        LoadedProject middle = loaded.get(1);
        LoadedProject first = loaded.get(2);
        requires(middle, first);
        requires(last, first);
        requires(last, middle);
        BuildVerdict verdict = JavaChange.contextFor(service).verdict();

        BuildVerdict.Rebuild rebuild = verdict.rebuildAfter(
            List.of(first.projectRoot().resolve("src/main/java/com/example/Anything.java").toString()));

        assertEquals(List.of(name(first), name(middle), name(last)), rebuild.scope().projects(),
            "the changed project, then the one that requires only it, then the one that requires both");
    }

    private static void requires(LoadedProject requirer, LoadedProject required) throws Exception {
        IJavaProject project = requirer.javaProject();
        List<IClasspathEntry> classpath = new ArrayList<>(List.of(project.getRawClasspath()));
        classpath.add(JavaCore.newProjectEntry(required.javaProject().getPath()));
        project.setRawClasspath(classpath.toArray(new IClasspathEntry[0]), null);
    }

    private static String name(LoadedProject project) {
        return project.javaProject().getProject().getName();
    }
}
