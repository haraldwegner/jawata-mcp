package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.change.ParityGate;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.lang.Diagnostic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 28k Stage 2b (E7c), the measurement the step starts with: does an INCREMENTAL rebuild of a loaded project still report
 * the error of a file that was ADDED, MODIFIED or DELETED since the last build? Sprint 21d found the incremental builder
 * produced no marker for an added file in this headless runtime, which is why every plan step is built in full; whether that
 * still holds decides how much of the build {@code ParityGate} may skip. Each case builds in full first (a known-clean
 * baseline), changes the project on disk, rebuilds incrementally through the host's {@code Reconciler}, and reads the
 * errors through the language's {@code BuildVerdict}.
 */
class IncrementalRebuildReachTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ChangeContext context;
    private BuildScope scope;
    private Path sources;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("compile-clean");
        context = JavaChange.contextFor(service);
        scope = BuildScope.of(context.verdict().owned().projects().get(0));
        sources = service.getProjectRoot().resolve("src/main/java/com/example");
    }

    @Test
    @DisplayName("an error in a file ADDED since the last build is reported by an incremental rebuild")
    void addedFileErrorIsSeen() throws Exception {
        assertEquals(List.of(), rebuild(BuildKind.FULL), "precondition: a clean baseline");

        Files.writeString(sources.resolve("Added.java"), "package com.example;\npublic class Added {\n    int oops() { return ; }\n}\n");

        List<Diagnostic> errors = rebuild(BuildKind.INCREMENTAL);
        assertFalse(errors.isEmpty(), "an incremental rebuild must report the error of the added file");
    }

    @Test
    @DisplayName("an error introduced in a file MODIFIED since the last build is reported by an incremental rebuild")
    void modifiedFileErrorIsSeen() throws Exception {
        Files.writeString(sources.resolve("Helper.java"), "package com.example;\npublic class Helper {\n    static int one() { return 1; }\n}\n");
        assertEquals(List.of(), rebuild(BuildKind.FULL), "precondition: a clean baseline with the helper in it");

        Files.writeString(sources.resolve("Helper.java"), "package com.example;\npublic class Helper {\n    static int one() { return ; }\n}\n");

        assertFalse(rebuild(BuildKind.INCREMENTAL).isEmpty(), "an incremental rebuild must report the error of the modified file");
    }

    @Test
    @DisplayName("deleting a class another file uses is reported in the user by an incremental rebuild")
    void deletedFileBreaksItsUser() throws Exception {
        Files.writeString(sources.resolve("Helper.java"), "package com.example;\npublic class Helper {\n    static int one() { return 1; }\n}\n");
        Files.writeString(sources.resolve("User.java"), "package com.example;\npublic class User {\n    int x = Helper.one();\n}\n");
        assertEquals(List.of(), rebuild(BuildKind.FULL), "precondition: a clean baseline with the helper and its user");

        Files.delete(sources.resolve("Helper.java"));

        List<Diagnostic> errors = rebuild(BuildKind.INCREMENTAL);
        assertTrue(errors.stream().anyMatch(d -> d.filePath() != null && d.filePath().endsWith("User.java")),
            "an incremental rebuild must report the user of the deleted class: " + errors);
    }

    @Test
    @DisplayName("the plan gate, given the path of a file a step added, fails on its error through the incremental build the verdict chooses")
    void theGateSeesAnAddedFileThroughItsPath() throws Exception {
        assertEquals(List.of(), rebuild(BuildKind.FULL), "precondition: a clean baseline");
        Path added = sources.resolve("Added.java");
        Files.writeString(added, "package com.example;\npublic class Added {\n    int oops() { return ; }\n}\n");

        ParityGate.Result result = ParityGate.compile(context, List.of(added.toString()));

        assertFalse(result.clean(), "the gate must fail on the added file's error: " + result.errors());
    }

    @Test
    @DisplayName("an incremental rebuild leaves the class files of untouched sources alone, which is what makes it incremental")
    void anIncrementalRebuildLeavesUntouchedClassFilesAlone() throws Exception {
        assertEquals(List.of(), rebuild(BuildKind.FULL), "precondition: a clean baseline");
        List<Path> compiled = classFiles();
        assertFalse(compiled.isEmpty(), "precondition: the baseline build wrote class files under " + service.getProjectRoot());
        Path untouched = compiled.get(0);
        java.nio.file.attribute.FileTime longAgo = java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(untouched, longAgo);
        Files.writeString(sources.resolve("Added.java"), "package com.example;\npublic class Added {\n    int fine() { return 1; }\n}\n");

        assertEquals(List.of(), rebuild(BuildKind.INCREMENTAL), "the added source compiles");

        assertTrue(classFiles().stream().anyMatch(f -> f.getFileName().toString().equals("Added.class")),
            "the incremental build compiled the added source");
        assertEquals(longAgo, Files.getLastModifiedTime(untouched),
            "an incremental build must not rewrite the class file of a source it did not recompile: " + untouched);
    }

    /** Every compiled class in the project's compiler output folder. */
    private List<Path> classFiles() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = service.allProjects().iterator().next().javaProject();
        Path output = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
            .getFolder(project.getOutputLocation()).getLocation().toFile().toPath();
        if (!Files.isDirectory(output)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(output)) {
            return walk.filter(f -> f.getFileName().toString().endsWith(".class")).sorted().toList();
        }
    }

    /** Rebuild the one project the way {@code kind} says and answer the errors the language reads afterwards. */
    private List<Diagnostic> rebuild(BuildKind kind) throws CoreException {
        NullProgressMonitor monitor = new NullProgressMonitor();
        assertTrue(context.reconciler().rebuild(scope, kind, monitor).containsAll(scope.projects()), "the project was built");
        return context.verdict().errors(scope, monitor);
    }
}
