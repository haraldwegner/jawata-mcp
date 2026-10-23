package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.refactoring.ParityGate;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.lang.BuildVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 28k Stage 2b (E7c): after a change, Java rebuilds the projects that own the changed files and the projects that require
 * them, incrementally, and the compiler decides which files an interface change reaches. Two real loaded projects, the second
 * requiring the first, so a change in the first reaches both and a change in the second reaches only itself.
 */
class JavaBuildVerdictRebuildAfterTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeContext context;
    private BuildVerdict verdict;
    private String first;
    private String second;
    private Path firstRoot;
    private Path secondRoot;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy(new String[] {"compile-clean", "compose-clean"});
        List<LoadedProject> loaded = new ArrayList<>(service.allProjects());
        LoadedProject a = loaded.get(0);
        LoadedProject b = loaded.get(1);
        first = a.javaProject().getProject().getName();
        second = b.javaProject().getProject().getName();
        firstRoot = a.projectRoot().toAbsolutePath().normalize();
        secondRoot = b.projectRoot().toAbsolutePath().normalize();
        // The second project requires the first: a change to the first can break files in the second.
        IJavaProject requirer = b.javaProject();
        List<IClasspathEntry> classpath = new ArrayList<>(List.of(requirer.getRawClasspath()));
        classpath.add(JavaCore.newProjectEntry(a.javaProject().getPath()));
        requirer.setRawClasspath(classpath.toArray(new IClasspathEntry[0]), null);
        context = JavaChange.contextFor(service);
        verdict = context.verdict();
    }

    @Test
    @DisplayName("an interface change in the first project is caught in the requiring project's own file by the plan gate")
    void anInterfaceChangeBreaksTheRequirersFileAndTheGateSeesIt() throws Exception {
        Path helperFile = firstRoot.resolve("src/main/java/com/example/Helper.java");
        Files.createDirectories(helperFile.getParent());
        Files.writeString(helperFile, "package com.example;\npublic class Helper {\n    public static int one() { return 1; }\n}\n");
        Path userFile = secondRoot.resolve("src/main/java/com/example/User.java");
        Files.createDirectories(userFile.getParent());
        Files.writeString(userFile, "package com.example;\npublic class User {\n    int x = Helper.one();\n}\n");
        ParityGate.Result before = ParityGate.compile(context, List.of());
        assertTrue(before.clean(), "precondition: both projects build clean in full: " + before.errors());

        Files.writeString(helperFile, "package com.example;\npublic class Helper {\n}\n");
        ParityGate.Result result = ParityGate.compile(context, List.of(helperFile.toString()));

        assertFalse(result.clean(), "removing a public method must break its user: " + result.errors());
        assertTrue(result.errors().stream().anyMatch(d -> d.filePath() != null && d.filePath().endsWith("User.java")),
            "the error is in the requiring project's file: " + result.errors());
    }

    @Test
    @DisplayName("a changed source file rebuilds its project and the project that requires it, incrementally")
    void aSourceChangeReachesTheRequirer() {
        BuildVerdict.Rebuild rebuild = verdict.rebuildAfter(List.of(firstRoot.resolve("src/main/java/com/example/Anything.java").toString()));

        assertEquals(List.of(first, second), rebuild.scope().projects());
        assertEquals(BuildKind.INCREMENTAL, rebuild.kind());
    }

    @Test
    @DisplayName("a change in the requiring project reaches that project only")
    void aChangeInTheRequirerStaysThere() {
        BuildVerdict.Rebuild rebuild = verdict.rebuildAfter(List.of(secondRoot.resolve("src/main/java/com/example/Anything.java").toString()));

        assertEquals(List.of(second), rebuild.scope().projects());
        assertEquals(BuildKind.INCREMENTAL, rebuild.kind());
    }

    @Test
    @DisplayName("a changed build file moves the classpath, so the project it belongs to is built in full — and only the projects it reaches")
    void aBuildFileForcesAFullBuild() {
        BuildVerdict.Rebuild rebuild = verdict.rebuildAfter(List.of(secondRoot.resolve("pom.xml").toString()));

        assertEquals(List.of(second), rebuild.scope().projects(), "the build file of the requiring project reaches that project only");
        assertEquals(BuildKind.FULL, rebuild.kind());
    }

    @Test
    @DisplayName("a file no loaded project owns, or no file at all, means every project in full")
    void withNothingToGoByEverythingIsBuiltInFull() {
        BuildVerdict.Rebuild unknown = verdict.rebuildAfter(List.of(firstRoot.getParent().resolve("elsewhere/X.java").toString()));
        BuildVerdict.Rebuild none = verdict.rebuildAfter(List.of());

        assertEquals(new BuildScope(List.of(first, second)), unknown.scope());
        assertEquals(BuildKind.FULL, unknown.kind());
        assertEquals(new BuildScope(List.of(first, second)), none.scope());
        assertEquals(BuildKind.FULL, none.kind());
    }
}
