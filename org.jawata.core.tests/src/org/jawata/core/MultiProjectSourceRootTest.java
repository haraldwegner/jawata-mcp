package org.jawata.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 28 — the last untested structural difference between the passing
 * fixture and the failing field project.
 *
 * <p>In the field, {@code com.jats2.model} is ONE OF 29 loaded projects and is
 * NOT the default (Falcon is). Every fixture that passed loaded a single
 * project, which was therefore also the default. {@code getCompilationUnit}
 * tries the default project first and only then fans out over
 * {@code projectsByKey}, so the non-default path is a different code path and
 * it has never been exercised for a non-conventional source folder.</p>
 *
 * <p>If the {@code test/} file resolves when its project is the default but not
 * when it is a secondary project, the fan-out is the defect and the fixture
 * green was meaningless.</p>
 */
class MultiProjectSourceRootTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("a test/ file resolves when its project is NOT the default project")
    void resolvesTestFolderFileInNonDefaultProject() throws Exception {
        // simple-maven becomes the default project...
        JdtServiceImpl service = helper.loadProject("simple-maven");
        // ...and the plug-in-shaped project is a SECONDARY one, as in the field.
        Path pluginRoot = helper.getFixturePath("pde-nonstandard-layout");
        service.addProject(pluginRoot);

        ICompilationUnit cu = service.getCompilationUnit(
            pluginRoot.resolve("test/com/example/ns/GreeterTest.java"));

        assertNotNull(cu,
            "a test/ file must resolve when its project is a SECONDARY project — the field "
                + "project is 1 of 29 with a different default, and that fan-out path was "
                + "never covered by the single-project fixtures");
    }

    /**
     * Control: the same file resolves when its project IS the default. This
     * passed before; if it fails here the fixture set-up is at fault, not the
     * fan-out.
     */
    @Test
    @DisplayName("control: the same test/ file resolves when its project IS the default")
    void resolvesTestFolderFileInDefaultProject() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path pluginRoot = helper.getFixturePath("pde-nonstandard-layout");

        ICompilationUnit cu = service.getCompilationUnit(
            pluginRoot.resolve("test/com/example/ns/GreeterTest.java"));

        assertNotNull(cu, "control: single-project layout, known to pass since v3.6.1");
    }
}
