package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.project.SourceRootClassifier;
import org.jawata.core.project.SourceRootClassifier.Verdict;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

/**
 * jawata-mcp#9, the REAL fix (Sprint 28 Stage 3): main-vs-test classification
 * read from the model, asserted against LOADED projects.
 *
 * <p>This file's previous content is the exhibit for why it was rewritten: a
 * matrix of {@code matchesScope(path, projectName, scope)} calls fed
 * HAND-WRITTEN names like {@code "org.jawata.mcp.tests"} — and passed — while
 * the branch it exercised was unreachable in every live workspace, where
 * loaded projects are named {@code jawata-<dir>-<session>}. The green unit
 * test is what closed the issue while it stayed broken. Every assertion here
 * therefore goes through a real load: fixture → importer → model →
 * {@link SourceRootClassifier}, the same path the live tool takes. The
 * live-workspace acceptance — {@code scope=main} and {@code scope=test}
 * differing on jawata's own repository — runs against the BUILT dist at this
 * stage's checkpoint, not here.</p>
 */
class ScopeClassificationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Maven convention roots classify from the model tag")
    void mavenRootsClassifyFromTheModel() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path root = helper.getFixturePath("simple-maven");
        assertEquals(Verdict.MAIN,
            classify(service, root.resolve("src/main/java/com/example/HelloWorld.java")),
            "a src/main/java file did not classify MAIN");
        assertEquals(Verdict.TEST,
            classify(service, root.resolve("src/test/java/com/example/SampleTest.java")),
            "a src/test/java file did not classify TEST");
    }

    @Test
    @DisplayName("the PDE shape that WAS the defect: flat-src test fragment classifies TEST, main bundle MAIN")
    void pdeFragmentShapeClassifies() throws Exception {
        // Both bundles keep sources flat under src/ — the layout where the old
        // path convention said "neither", the name check could not fire, and
        // the .java catch-all swept everything into MAIN.
        JdtServiceImpl service = helper.loadProject("pde-external");
        Path testsRoot = helper.getFixturePath("pde-external-tests");
        service.addProject(testsRoot);

        Path mainRoot = helper.getFixturePath("pde-external");
        assertEquals(Verdict.MAIN,
            classify(service, mainRoot.resolve("src/com/example/ext/ExtLib.java")),
            "the main bundle's flat-src file did not classify MAIN");
        assertEquals(Verdict.TEST,
            classify(service, testsRoot.resolve("src/com/example/exttests/ExtLibTest.java")),
            "the test fragment's flat-src file did not classify TEST — the mcp#9 shape");
    }

    @Test
    @DisplayName("project-level files: cross-cutting in a main project, TEST in an all-test project")
    void projectLevelFilesFollowTheProjectsNature() throws Exception {
        // The pinned C8-F4 semantics, now derived from the MODEL: a project
        // whose every source root is test code IS the test half, manifest
        // included; a mixed or main project's project-level files stay
        // visible in both scopes.
        JdtServiceImpl service = helper.loadProject("pde-external");
        Path testsRoot = helper.getFixturePath("pde-external-tests");
        service.addProject(testsRoot);

        IResource mainManifest = projectResource(service,
            helper.getFixturePath("pde-external").resolve("src/com/example/ext/ExtLib.java"),
            "META-INF/MANIFEST.MF");
        assertEquals(Verdict.CROSS_CUTTING, SourceRootClassifier.classify(mainManifest),
            "a main bundle's manifest must stay visible in both scopes");

        IResource testsManifest = projectResource(service,
            testsRoot.resolve("src/com/example/exttests/ExtLibTest.java"),
            "META-INF/MANIFEST.MF");
        assertEquals(Verdict.TEST, SourceRootClassifier.classify(testsManifest),
            "an all-test project's manifest belongs to the test scope, not to both");
    }

    @Test
    @DisplayName("a null or un-rooted resource falls OPEN, never hidden")
    void unclassifiableFallsOpen() {
        assertEquals(Verdict.CROSS_CUTTING, SourceRootClassifier.classify(null),
            "a classification tool must degrade to visible, never to hidden");
    }

    // ================= helpers =================

    /** Classify the workspace resource behind an on-disk source file. */
    private Verdict classify(JdtServiceImpl service, Path onDisk) throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(onDisk);
        assertNotNull(cu, "no compilation unit for " + onDisk
            + " — the file never reached the model, so classification is untestable");
        return SourceRootClassifier.classify(cu.getResource());
    }

    /** A project-level (non-source) resource of the project owning {@code anySourceFile}. */
    private IResource projectResource(JdtServiceImpl service, Path anySourceFile,
            String relative) throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(anySourceFile);
        assertNotNull(cu, "no compilation unit for " + anySourceFile);
        return cu.getResource().getProject().getFile(
            org.eclipse.core.runtime.Path.fromOSString(relative));
    }
}
