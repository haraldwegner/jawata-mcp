package org.jawata.mcp.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.tools.junit.JUnitLaunchHelper;
import org.jawata.mcp.tools.shared.FrameworkDetection;
import org.jawata.spi.lang.ProjectRoots;
import org.jawata.spi.lang.RunnerSpec;
import org.jawata.spi.lang.TestRuntime;
import org.jawata.spi.lang.TestTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java's {@link TestRuntime}: the forked runner's classpath and the build before a run, for a project the loaded model holds by
 * name. It asks {@link RunnerClasspath} for both (E10 moves that class beside this one and makes {@code run_tests} ask here). Stage 9
 * adds what {@code run_tests} used to read from the Java model itself: the framework, the folder the run starts in, the test at a
 * position, the project's folders, and its runtime classpath.
 */
public final class JavaTestRuntime implements TestRuntime {

    private static final Logger log = LoggerFactory.getLogger(JavaTestRuntime.class);

    private final IJdtService service;

    JavaTestRuntime(IJdtService service) {
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

    @Override
    public Optional<String> frameworkFor(String projectName, String requested) {
        IJavaProject project = projectNamed(projectName);
        if (project == null) {
            return Optional.empty();
        }
        JUnitLaunchHelper.TestRunnerKind runner = FrameworkDetection.detect(requested, project);
        if (runner == null) {
            return Optional.empty();
        }
        return Optional.of(requested.equals("auto") ? shortName(runner) : requested);
    }

    @Override
    public Path workingFolder(String projectName) {
        LoadedProject loaded = loadedNamed(projectName);
        try {
            return loaded == null ? null : TestWorkingFolder.of(loaded);
        } catch (org.eclipse.jdt.core.JavaModelException e) {
            throw new IllegalStateException("the folder of " + projectName + " could not be read: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<TestTarget> testAt(Path file, int line, int column, boolean wantMethod) {
        try {
            if (!wantMethod) {
                IType type = service.getTypeAtPosition(file, line, column);
                return type == null ? Optional.empty() : Optional.of(new TestTarget(type.getFullyQualifiedName(), null));
            }
            IJavaElement element = service.getElementAtPosition(file, line, column);
            // Walk up if the position landed on a body element rather than the declaration itself.
            for (IJavaElement walk = element; walk != null; walk = walk.getParent()) {
                if (walk instanceof IMethod method) {
                    IType declaring = method.getDeclaringType();
                    return declaring == null ? Optional.empty()
                        : Optional.of(new TestTarget(declaring.getFullyQualifiedName(), method.getElementName()));
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("the model could not be read at " + file + ":" + line + ":" + column + ": " + e.getMessage(), e);
        }
    }

    @Override
    public ProjectRoots rootsOf(String projectName) {
        IJavaProject project = projectNamed(projectName);
        List<String> classRoots = new ArrayList<>();
        List<String> sourceRoots = new ArrayList<>();
        if (project == null) {
            return new ProjectRoots(classRoots, sourceRoots);
        }
        // Output folders = class roots (bundle identity); source folders for provenance.
        try {
            org.eclipse.core.resources.IWorkspaceRoot wsRoot = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
            addWorkspacePath(wsRoot, project.getOutputLocation(), classRoots);
            for (IClasspathEntry entry : project.getResolvedClasspath(true)) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                    addWorkspacePath(wsRoot, entry.getPath(), sourceRoots);
                    if (entry.getOutputLocation() != null) {
                        addWorkspacePath(wsRoot, entry.getOutputLocation(), classRoots);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("cannot collect class/source roots: {}", e.getMessage());
        }
        return new ProjectRoots(classRoots, sourceRoots);
    }

    @Override
    public List<String> runtimeClasspath(String projectName) {
        IJavaProject project = projectNamed(projectName);
        Set<String> classpath = new LinkedHashSet<>();
        if (project == null) {
            return List.of();
        }
        try {
            for (var entry : org.eclipse.jdt.launching.JavaRuntime.computeUnresolvedRuntimeClasspath(project)) {
                for (var resolved : org.eclipse.jdt.launching.JavaRuntime.resolveRuntimeClasspathEntry(entry, project)) {
                    if (resolved.getClasspathProperty() == org.eclipse.jdt.launching.IRuntimeClasspathEntry.USER_CLASSES
                            && resolved.getLocation() != null) {
                        classpath.add(resolved.getLocation());
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("the runtime classpath of " + projectName + " could not be computed: " + e.getMessage(), e);
        }
        return List.copyOf(classpath);
    }

    private static void addWorkspacePath(org.eclipse.core.resources.IWorkspaceRoot wsRoot, org.eclipse.core.runtime.IPath path,
            List<String> into) {
        if (path == null) {
            return;
        }
        org.eclipse.core.resources.IResource res = wsRoot.findMember(path);
        org.eclipse.core.runtime.IPath loc = res != null ? res.getLocation() : null;
        String value = loc != null ? loc.toOSString() : path.toOSString();
        if (!into.contains(value)) {
            into.add(value);
        }
    }

    private static String shortName(JUnitLaunchHelper.TestRunnerKind kind) {
        return switch (kind) {
            case JUNIT3 -> "junit3";
            case JUNIT4 -> "junit4";
            case JUNIT5 -> "junit5";
        };
    }

    private IJavaProject projectNamed(String name) {
        LoadedProject loaded = loadedNamed(name);
        return loaded == null ? null : loaded.javaProject();
    }

    private LoadedProject loadedNamed(String name) {
        for (LoadedProject loaded : service.allProjects()) {
            if (loaded.javaProject().getProject().getName().equals(name)) {
                return loaded;
            }
        }
        return null;
    }
}
