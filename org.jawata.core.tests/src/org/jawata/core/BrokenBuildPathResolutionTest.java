package org.jawata.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 28 — why v3.6.1's source-folder fix was a NO-OP in the field.
 *
 * <p>v3.6.1 taught {@code lookupCompilationUnit} to ask the Java model for its
 * declared source roots instead of guessing Maven layouts. That was verified
 * on a fixture with a clean build path, and on a real 1040-source Eclipse
 * plug-in it changed nothing: {@code find_tests} still reported 1 test class of
 * twenty-plus, and the quality scans still missed the same 142 files under
 * {@code test/}.</p>
 *
 * <p>The one property the field project has and the passing fixture did not is
 * an <b>incomplete build path</b> — it carries an unresolvable requirement, so
 * JDT refuses to build it. The hypothesis under test here is that a project in
 * that state does not hand out usable {@code IPackageFragmentRoot}s (or their
 * package fragments report {@code exists() == false}), so the model-driven
 * lookup finds nothing and falls through to the old convention guess, which is
 * precisely what could not see a {@code test/} folder in the first place.</p>
 *
 * <p>Note what still works in the field and pins the shape: files under
 * {@code src/} resolve fine, because the fallback's {@code findType} goes
 * through the search index and does not care whether the classpath is valid.
 * So the failure is specific to the folder the convention list cannot name.</p>
 */
class BrokenBuildPathResolutionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /**
     * The field case: a {@code test/} folder on a project whose build path is
     * incomplete. If this fails, the incomplete-build-path hypothesis is
     * confirmed and the fix must not depend on a resolvable classpath.
     */
    @Test
    @DisplayName("a test/ file resolves even when the project's build path is incomplete")
    void resolvesTestFolderFileOnProjectWithIncompleteBuildPath() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-broken-buildpath");
        Path projectRoot = helper.getFixturePath("pde-broken-buildpath");
        Path testFile = projectRoot.resolve("test/com/example/bb/ThingTest.java");

        ICompilationUnit cu = service.getCompilationUnit(testFile);

        assertNotNull(cu,
            "a file in a test/ source folder must resolve even when the project carries an "
                + "unresolvable requirement — this is the shape of the real Eclipse plug-in "
                + "project where v3.6.1's fix was measured to change nothing");
    }

    /**
     * The control: {@code src/} must resolve on the same broken project. In the
     * field it does, via the convention fallback, so if this fails the fixture
     * is not reproducing the field shape and the test above proves nothing.
     */
    @Test
    @DisplayName("a src/ file still resolves on the same incomplete-build-path project")
    void resolvesSrcFolderFileOnProjectWithIncompleteBuildPath() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-broken-buildpath");
        Path projectRoot = helper.getFixturePath("pde-broken-buildpath");
        Path mainFile = projectRoot.resolve("src/com/example/bb/Thing.java");

        ICompilationUnit cu = service.getCompilationUnit(mainFile);

        assertNotNull(cu,
            "src/ resolves in the field on the broken project (898 of 1040 files were read), "
                + "so it must resolve here too — otherwise this fixture is not the field shape");
    }
}
