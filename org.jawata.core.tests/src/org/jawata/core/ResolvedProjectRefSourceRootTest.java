package org.jawata.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 28 — the last surviving difference between the sandbox that WORKS and
 * the fleet that does NOT.
 *
 * <p>Measured on the real project: a resident holding ONLY
 * {@code com.jats2.model} answers {@code find_tests} with 126 test classes; the
 * fleet resident, same v3.6.1 code and a freshly synthesized workspace, answers
 * 1. There is no cache — the boot deletes the session directory on shutdown, so
 * both were built from scratch by the same code.</p>
 *
 * <p>What the fleet has and the sandbox did not: the five projects that
 * {@code com.jats2.model} names as {@code kind="src"} PROJECT REFERENCES are
 * themselves loaded, so those references resolve to real projects and their
 * source roots join the referencing project's classpath. An earlier
 * multi-project test passed, but its two projects had no reference between
 * them — it never built this shape.</p>
 */
class ResolvedProjectRefSourceRootTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /**
     * The field shape: the referenced sibling IS loaded, so the project
     * reference resolves. If the {@code test/} file stops resolving here, this
     * is the reproduction.
     */
    @Test
    @DisplayName("a test/ file still resolves when the project's sibling reference IS loaded")
    void resolvesTestFileWhenProjectReferenceIsSatisfied() throws Exception {
        // The referenced sibling is loaded FIRST, so resolveBundle() finds it
        // and the reference becomes a real project entry — as in the fleet.
        JdtServiceImpl service = helper.loadProject("pde-sibling");
        Path pluginRoot = helper.getFixturePath("pde-nonstandard-layout");
        service.addProject(pluginRoot);

        ICompilationUnit cu = service.getCompilationUnit(
            pluginRoot.resolve("test/com/example/ns/GreeterTest.java"));

        assertNotNull(cu,
            "a test/ file must resolve when the project's sibling references are satisfied — "
                + "this is the only structural difference left between the sandbox that "
                + "reports 126 test classes and the fleet that reports 1");
    }

    /**
     * Control: the same file, same two projects, but loaded in the opposite
     * order so the sibling is NOT present when the plug-in project is imported
     * and the reference cannot resolve. This is the sandbox shape, known to
     * work.
     */
    @Test
    @DisplayName("control: the same test/ file resolves when the reference is unsatisfied")
    void resolvesTestFileWhenProjectReferenceIsUnsatisfied() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path pluginRoot = helper.getFixturePath("pde-nonstandard-layout");

        ICompilationUnit cu = service.getCompilationUnit(
            pluginRoot.resolve("test/com/example/ns/GreeterTest.java"));

        assertNotNull(cu, "control: the sandbox shape, which reports 126 in the field");
    }
}
