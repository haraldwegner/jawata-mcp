package org.jawata.mcp.java;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.core.project.ProjectImporter;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Java provider's {@link BuildVerdict}: per file, a parse with bindings of each {@code .java} file and its error-severity
 * problems (syntax ones flagged); per project, the error markers the last build left. The model it reads is the one the headless
 * host loaded (contract revision 2.1, E7). The bodies are the ones {@code CompileVerify} and {@code ParityGate} ran before the
 * split, moved here with the move tool; the lifecycle keeps the policy (what counts as introduced, the {@code SYNTAX: } rendering).
 */
public final class JavaBuildVerdict implements BuildVerdict {

    private static final Logger log = LoggerFactory.getLogger(JavaBuildVerdict.class);

    private final IJdtService service;

    public JavaBuildVerdict(IJdtService service) {
        this.service = service;
    }

    /**
     * Only {@code .java} files are verified — a change that triggers a build sweeps {@code bin/**.class} outputs into the
     * modified-file delta, and "verifying" a binary is meaningless (it produced 171 false findings on the first type-with-file
     * rename). A file that is not there has no entry: that absence is how a created file is told from an existing one.
     */
    @Override
    public Map<String, Set<SourceProblem>> problemsIn(List<String> files) {
        Map<String, Set<SourceProblem>> out = new LinkedHashMap<>();
        for (String fp : files) {
            if (fp != null && fp.endsWith(".java")) {
                Set<SourceProblem> problems = problems(fp);
                if (problems != null) {
                    out.put(fp, problems);
                }
            }
        }
        return out;
    }

    /**
     * MAKE THE MODEL SEE WHAT THE CHANGE JUST WROTE, before anything reads errors from it.
     *
     * <p>jawata-mcp#69. The lifecycle skips everything but syntax in a CREATED file, and justifies that by saying the created
     * type's semantic validity is "proven transitively by the MODIFIED files that reference them, which do resolve them". <b>That
     * sentence is the one this method exists to make true.</b> A file is written to disk and the Java model does not necessarily
     * know it yet; a modified file re-parsed in that window resolves the type it references to nothing, and the gate reports an
     * error the change did not cause.</p>
     *
     * <p>It surfaced as a platform difference, which is what a timing gap looks like from outside: on the macOS matrix job at the
     * v4.1.0 tag, {@code combine_functions} was refused on upstream code with <i>"App.java: the method
     * getGroupingOfCarsByCategory() from the type CarQueries refers to the missing type Car"</i>, where {@code CarQueries} is the
     * class the operation had just created and {@code Car} sits in its own package. The same operation on the same slice passed on
     * Linux in the same run — measured: both loaded it identically, as 7 source files in 1 package with 0 unresolved requirements.
     * So the input was equal and the model's readiness was not.</p>
     *
     * <p><b>It cannot turn a correct result wrong, which is why it is safe to ship against a platform this machine cannot
     * run.</b> Refreshing a resource and making a unit consistent only ever gives the compiler MORE of what is genuinely on disk; a
     * real error stays an error, and the errors it removes are ones about a file that exists. The failure it prevents is the
     * expensive direction: a correct refactoring refused and undone.</p>
     *
     * <p>Settling failures are swallowed on purpose. This is preparation for the measurement, not the measurement — a workspace
     * that cannot be refreshed will produce its own honest problems a line later, and failing here would replace a real finding
     * with a plumbing one.</p>
     */
    @Override
    public void settle(List<String> files) {
        for (String fp : files) {
            if (fp == null || !fp.endsWith(".java")) {
                continue;
            }
            try {
                Path p = resolve(fp);
                if (!Files.exists(p)) {
                    continue;
                }
                ICompilationUnit cu = service.getCompilationUnit(p);
                if (cu == null) {
                    continue;
                }
                // THE PARENT, NOT THE FILE. A created unit's own resource may not exist in the model yet — that is the state
                // being repaired — so refreshing it alone can be a no-op on exactly the case this is for. Refreshing the
                // package it landed in is what makes the model notice a NEW member.
                IResource resource = cu.getResource();
                if (resource != null && resource.getParent() != null) {
                    resource.getParent().refreshLocal(IResource.DEPTH_ONE, null);
                }
                cu.makeConsistent(null);
            } catch (Exception settling) {
                // Deliberately silent — see the javadoc's last paragraph.
            }
        }
    }

    /**
     * WHAT A CREATED FILE DECLARES ITSELF TO BE — the evidence a refusal about one needs (jawata-mcp#69, the half that is not the
     * timing). Bounded on purpose: the package declaration and the imports, never the body — they decide whether a same-package
     * type resolves, and they are two or three lines rather than a whole class in an error message.
     */
    @Override
    public Optional<String> describeCreated(String file) {
        if (file == null || !file.endsWith(".java")) {
            return Optional.empty();
        }
        try {
            Path p = resolve(file);
            if (!Files.exists(p)) {
                return Optional.empty();
            }
            StringBuilder head = new StringBuilder();
            for (String line : Files.readAllLines(p)) {
                String t = line.strip();
                if (t.startsWith("package ") || t.startsWith("import ")) {
                    head.append("\n    ").append(t);
                }
            }
            return Optional.of("\n  CREATED " + file
                + (head.isEmpty() ? "\n    (no package or import declarations)" : head));
        } catch (Exception unreadable) {
            return Optional.of("\n  CREATED " + file + "\n    (could not be read back: "
                + unreadable.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public BuildScope owned() {
        List<String> names = new ArrayList<>();
        for (LoadedProject loaded : service.allProjects()) {
            names.add(loaded.javaProject().getProject().getName());
        }
        return new BuildScope(names);
    }

    /**
     * "Only compile what is touched" (Sprint 28k E7c, his word), the Java way: the projects that own the changed files plus
     * every project that requires one of them, transitively — the compiler then decides, file by file, what an interface
     * change reaches — built incrementally. Measured (IncrementalRebuildReachTest) that an incremental build still reports an
     * added file, a modified file and the user of a deleted class, so adding and deleting no longer force a full build. A
     * change to a build-configuration file moves the classpath, so it is built in full; and when there is nothing to go by
     * (no files, a file no loaded project owns) the answer is every project, in full.
     */
    @Override
    public Rebuild rebuildAfter(List<String> changedFiles) {
        Set<String> affected = new LinkedHashSet<>();
        boolean configuration = false;
        for (String file : changedFiles) {
            List<String> owners = file == null ? List.of() : ownersOf(file);
            if (owners.isEmpty()) {
                return new Rebuild(owned(), BuildKind.FULL);
            }
            affected.addAll(owners);
            configuration |= ProjectImporter.BUILD_CONFIGURATION_FILES.contains(Path.of(file).getFileName().toString());
        }
        if (affected.isEmpty()) {
            return new Rebuild(owned(), BuildKind.FULL);
        }
        for (boolean grew = true; grew; ) {
            grew = false;
            for (LoadedProject loaded : service.allProjects()) {
                String name = loaded.javaProject().getProject().getName();
                if (!affected.contains(name) && requiresAnyOf(loaded, affected)) {
                    affected.add(name);
                    grew = true;
                }
            }
        }
        return new Rebuild(new BuildScope(new ArrayList<>(affected)), configuration ? BuildKind.FULL : BuildKind.INCREMENTAL);
    }

    /**
     * Every loaded project this file belongs to (a linked source folder counts as its project's, and one folder can be linked
     * into several), or none when no loaded project holds it.
     */
    private List<String> ownersOf(String filePath) {
        Path file = resolve(filePath);
        Set<String> owners = new LinkedHashSet<>();
        for (IFile candidate : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(file.toUri())) {
            if (projectNamed(candidate.getProject().getName()) != null) {
                owners.add(candidate.getProject().getName());
            }
        }
        if (owners.isEmpty()) {
            LoadedProject holder = LoadedProject.owning(service.allProjects(), file);
            if (holder != null) {
                owners.add(holder.javaProject().getProject().getName());
            }
        }
        return new ArrayList<>(owners);
    }

    /**
     * Whether the project takes in one of these projects: by a project entry on its resolved classpath, or by a jar or class
     * folder that lies inside one of them (another project's compiler output is this project's input). A classpath that cannot
     * be read counts as yes.
     */
    private boolean requiresAnyOf(LoadedProject loaded, Set<String> names) {
        try {
            for (IClasspathEntry entry : loaded.javaProject().getResolvedClasspath(true)) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT && names.contains(entry.getPath().segment(0))) {
                    return true;
                }
                if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY && insideAnyOf(entry.getPath(), names)) {
                    return true;
                }
            }
            return false;
        } catch (JavaModelException unreadable) {
            return true;
        }
    }

    /** Whether a library entry lies inside one of these projects, named in the workspace or as a path on disk. */
    private boolean insideAnyOf(IPath library, Set<String> names) {
        if (library.segmentCount() > 0 && names.contains(library.segment(0))) {
            return true;
        }
        Path onDisk = Path.of(library.toOSString()).toAbsolutePath().normalize();
        for (LoadedProject loaded : service.allProjects()) {
            if (names.contains(loaded.javaProject().getProject().getName())
                    && onDisk.startsWith(loaded.projectRoot().toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Diagnostic> errors(BuildScope scope, IProgressMonitor monitor) {
        List<Diagnostic> errors = new ArrayList<>();
        for (String name : scope.projects()) {
            IProject project = projectNamed(name);
            if (project == null) {
                errors.add(new Diagnostic(null, -1, "marker sweep failed on " + name + ": no such project is loaded"));
                continue;
            }
            try {
                for (IMarker marker : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
                    if (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO) != IMarker.SEVERITY_ERROR) {
                        continue;
                    }
                    String file = null;
                    IResource resource = marker.getResource();
                    if (resource != null && resource.getLocation() != null) {
                        file = resource.getLocation().toOSString();
                    }
                    errors.add(new Diagnostic(file, marker.getAttribute(IMarker.LINE_NUMBER, -1),
                        marker.getAttribute(IMarker.MESSAGE, "")));
                }
            } catch (Exception e) {
                log.warn("marker sweep failed on '{}': {}", name, e.getMessage());
                errors.add(new Diagnostic(null, -1, "marker sweep failed on " + name + ": " + e.getMessage()));
            }
        }
        return errors;
    }

    private IProject projectNamed(String name) {
        for (LoadedProject loaded : service.allProjects()) {
            IProject project = loaded.javaProject().getProject();
            if (project.getName().equals(name)) {
                return project;
            }
        }
        return null;
    }

    private Path resolve(String filePath) {
        Path p = Path.of(filePath);
        if (!p.isAbsolute() && service.getProjectRoot() != null) {
            p = service.getProjectRoot().resolve(p).normalize();
        }
        return p;
    }

    /** Null = the file does not exist at this path (distinct from "exists, no errors"). */
    private Set<SourceProblem> problems(String filePath) {
        Set<SourceProblem> problems = new LinkedHashSet<>();
        try {
            Path p = resolve(filePath);
            if (!Files.exists(p)) {
                // Before-pass: the file is yet to be created by the change. After-pass: the change removed or renamed it. Either
                // way there is nothing to verify AT this path — and the null (vs empty set) is how a created file is recognised.
                return null;
            }
            ICompilationUnit cu = service.getCompilationUnit(p);
            if (cu == null) {
                // The file EXISTS but the model cannot open it — that IS a finding for the gate: report it rather than silently
                // skipping (a skipped file would make a broken result look verified).
                problems.add(new SourceProblem("(file could not be opened for compile verification)", false));
                return problems;
            }
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            for (IProblem problem : ast.getProblems()) {
                if (problem.isError()) {
                    problems.add(new SourceProblem(problem.getMessage(), (problem.getID() & IProblem.Syntax) != 0));
                }
            }
        } catch (Exception e) {
            problems.add(new SourceProblem("(compile verification itself failed: " + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : "") + ")", false));
        }
        return problems;
    }
}
