package org.jawata.mcp.java;

import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.spi.lang.RunnerSpec;
import org.jawata.spi.lang.TestRuntime;

/**
 * Java's {@link TestRuntime}: the forked runner's classpath and the build before a run, for a project the loaded model holds by
 * name. It asks {@link RunnerClasspath} for both (E10 moves that class beside this one and makes {@code run_tests} ask here).
 */
public final class JavaTestRuntime implements TestRuntime {

    private final IJdtService service;

    public JavaTestRuntime(IJdtService service) {
        this.service = service;
    }

    @Override
    public RunnerSpec specFor(String projectName, boolean includeTestNg) {
        IJavaProject project = projectNamed(projectName);
        if (project == null) {
            return new RunnerSpec(java.util.List.of(), "no project named " + projectName + " is loaded");
        }
        RunnerClasspath.Assembled assembled = RunnerClasspath.assemble(project, includeTestNg);
        return new RunnerSpec(assembled.isRefused() ? java.util.List.of() : assembled.classpath, assembled.refusalReason);
    }

    @Override
    public Optional<String> buildAndCheck(String projectName, IProgressMonitor monitor) {
        IJavaProject project = projectNamed(projectName);
        if (project == null) {
            return Optional.of("no project named " + projectName + " is loaded");
        }
        return Optional.ofNullable(RunnerClasspath.buildAndCheck(project, monitor));
    }

    private IJavaProject projectNamed(String name) {
        for (LoadedProject loaded : service.allProjects()) {
            if (loaded.javaProject().getProject().getName().equals(name)) {
                return loaded.javaProject();
            }
        }
        return null;
    }
}
