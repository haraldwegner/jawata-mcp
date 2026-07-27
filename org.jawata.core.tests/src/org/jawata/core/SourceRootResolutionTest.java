package org.jawata.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 (v3.6.1) — a source file must resolve from ANY declared source
 * folder, not only from folders named by Maven/Gradle convention.
 *
 * <p><b>The defect.</b> {@code lookupCompilationUnit} derived a type name by
 * matching the file path against a fixed list of prefixes — {@code
 * src/main/java/}, {@code src/test/java/}, the Kotlin pair, and a bare {@code
 * src/}. An Eclipse plug-in project whose tests live in a top-level {@code
 * test/} folder matched none of them, so the whole absolute path was converted
 * into a "package name" and the lookup returned null for every file in it.</p>
 *
 * <p><b>What that cost.</b> Measured live on a real 1040-source plug-in
 * project: {@code find_tests} reported ONE test class out of twenty-plus, and
 * the quality scans reported {@code filesMissed: 142} — every one of them under
 * {@code test/}. The scans were honest about the gap, which is why this was
 * findable at all, but the answers were useless. The same files were readable
 * the whole time through the JDT search engine, which does not guess from
 * paths.</p>
 */
class SourceRootResolutionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /**
     * The regression: a file under a source folder named {@code test/} — the
     * Eclipse plug-in layout — must resolve.
     */
    @Test
    @DisplayName("a file in a source folder named 'test' resolves to its compilation unit")
    void resolvesFileInNonConventionalSourceFolder() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path projectRoot = helper.getFixturePath("pde-nonstandard-layout");
        Path testFile = projectRoot.resolve("test/com/example/ns/GreeterTest.java");

        ICompilationUnit cu = service.getCompilationUnit(testFile);

        assertNotNull(cu,
            "a file under a source folder named 'test' must resolve — before v3.6.1 the "
                + "path matched no Maven/Gradle prefix and the lookup returned null, which "
                + "made every file in the folder invisible to find_tests and the quality scans");
        assertEquals("GreeterTest.java", cu.getElementName());
        assertTrue(cu.exists(), "the resolved compilation unit must actually exist");
    }

    /**
     * The conventional folder must keep working — the fix adds a model-driven
     * lookup ahead of the convention guess, it does not replace the old
     * behaviour for paths that were already resolving.
     */
    @Test
    @DisplayName("a file in a conventional 'src' folder still resolves")
    void stillResolvesFileInConventionalSourceFolder() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path projectRoot = helper.getFixturePath("pde-nonstandard-layout");
        Path mainFile = projectRoot.resolve("src/com/example/ns/Greeter.java");

        ICompilationUnit cu = service.getCompilationUnit(mainFile);

        assertNotNull(cu, "the conventional src/ layout must not regress");
        assertEquals("Greeter.java", cu.getElementName());
    }

    /**
     * Both folders resolve in the SAME project — the point of the fix is that a
     * project can carry a conventional and a non-conventional source folder at
     * once, which is exactly the plug-in layout that failed.
     */
    @Test
    @DisplayName("both source folders of one project resolve together")
    void resolvesBothSourceFoldersOfTheSameProject() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path projectRoot = helper.getFixturePath("pde-nonstandard-layout");

        ICompilationUnit main = service.getCompilationUnit(
            projectRoot.resolve("src/com/example/ns/Greeter.java"));
        ICompilationUnit test = service.getCompilationUnit(
            projectRoot.resolve("test/com/example/ns/GreeterTest.java"));

        assertNotNull(main, "src/ file");
        assertNotNull(test, "test/ file");
        assertEquals(main.getJavaProject(), test.getJavaProject(),
            "both must resolve within the same project");
    }

    /**
     * A path that belongs to no source folder must still answer null — the fix
     * must not start inventing compilation units for arbitrary paths.
     */
    @Test
    @DisplayName("a path outside every source folder still resolves to nothing")
    void unknownPathStillResolvesToNull() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path projectRoot = helper.getFixturePath("pde-nonstandard-layout");

        ICompilationUnit cu = service.getCompilationUnit(
            projectRoot.resolve("META-INF/NotJava.java"));

        assertEquals(null, cu, "a file that is in no source folder has no compilation unit");
    }
}
