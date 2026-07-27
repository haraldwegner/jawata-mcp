package org.jawata.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 (v3.6.2) — the SCOPED view must resolve exactly what the unscoped
 * one does.
 *
 * <p><b>How this was missed.</b> {@code getCompilationUnit} existed twice: once
 * in {@link JdtServiceImpl} (used when a tool call carries no
 * {@code projectKey}) and once in {@link ScopedJdtService} (used when it does).
 * v3.6.1 taught the first to read a project's DECLARED source folders instead
 * of guessing Maven layouts, and left the second untouched. Every existing test
 * drove {@code JdtServiceImpl} directly, so the scoped copy had NO coverage at
 * all and the divergence was invisible: 1681 tests green, and the shipped fix
 * did nothing for any tool call that named its project.</p>
 *
 * <p><b>What it cost.</b> On a real 1040-source Eclipse plug-in, seconds apart
 * on the same resident: {@code find_tests} unscoped found the test classes;
 * {@code find_tests(projectKey=…)} found 1 of 126. The quality scans, which
 * always scope, reported 142 files "unresolvable" — every file under the one
 * source folder named {@code test/}.</p>
 *
 * <p>These tests drive the scoped view on purpose. Their job is to fail the
 * moment the two implementations diverge again.</p>
 */
class ScopedServiceSourceRootTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ScopedJdtService scoped(JdtServiceImpl service, String projectKey) {
        Optional<LoadedProject> project = service.getProject(projectKey);
        assertTrue(project.isPresent(), "fixture project '" + projectKey + "' must be loaded");
        return new ScopedJdtService(service, project.get());
    }

    /**
     * The regression: through the SCOPED view, a file in a source folder named
     * {@code test/} must resolve. This is the exact call shape every
     * {@code projectKey}-carrying tool uses.
     */
    @Test
    @DisplayName("scoped view resolves a file in a source folder named 'test'")
    void scopedViewResolvesNonConventionalSourceFolder() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path root = helper.getFixturePath("pde-nonstandard-layout");
        ScopedJdtService view = scoped(service, service.defaultProjectKey().orElseThrow());

        ICompilationUnit cu = view.getCompilationUnit(
            root.resolve("test/com/example/ns/GreeterTest.java"));

        assertNotNull(cu,
            "the scoped view must resolve a test/ file — it carried a stale COPY of the "
                + "Maven-prefix guess, so every projectKey-scoped call failed on this folder "
                + "while the unscoped path succeeded");
        assertEquals("GreeterTest.java", cu.getElementName());
    }

    /**
     * The two views must agree. This is the property that actually matters: a
     * tool call must not depend on whether the agent named the project.
     */
    @Test
    @DisplayName("scoped and unscoped views resolve the same files")
    void scopedAndUnscopedAgree() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path root = helper.getFixturePath("pde-nonstandard-layout");
        ScopedJdtService view = scoped(service, service.defaultProjectKey().orElseThrow());

        for (String relative : List.of(
                "src/com/example/ns/Greeter.java",
                "test/com/example/ns/GreeterTest.java")) {
            Path file = root.resolve(relative);
            ICompilationUnit unscoped = service.getCompilationUnit(file);
            ICompilationUnit scopedCu = view.getCompilationUnit(file);

            assertNotNull(unscoped, "unscoped must resolve " + relative);
            assertNotNull(scopedCu, "scoped must resolve " + relative);
            assertEquals(unscoped.getElementName(), scopedCu.getElementName(),
                "scoped and unscoped must agree on " + relative
                    + " — a projectKey must never change WHAT is found");
        }
    }

    /**
     * The scoped listing must see the {@code test/} folder too; otherwise a
     * scan reports a smaller universe and calls it complete.
     */
    @Test
    @DisplayName("scoped listing includes files from every declared source folder")
    void scopedListingCoversEverySourceFolder() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        ScopedJdtService view = scoped(service, service.defaultProjectKey().orElseThrow());

        List<String> listed = view.getAllJavaFiles().stream().map(String::valueOf).toList();

        assertTrue(listed.stream().anyMatch(p -> p.endsWith("Greeter.java")),
            "the src/ file must be listed, got: " + listed);
        assertTrue(listed.stream().anyMatch(p -> p.endsWith("GreeterTest.java")),
            "the test/ file must be listed — a listing that silently omits a source root is "
                + "how a scan reports 'nothing found' about files it never opened, got: " + listed);
    }

    /**
     * Every listed file must also resolve. Listing a file and then failing to
     * open it is exactly the shape that produced "142 unresolvable" in the
     * field, and it is the invariant the scan-honesty contract depends on.
     */
    @Test
    @DisplayName("every file the scoped view lists, it can also resolve")
    void everyListedFileResolves() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        ScopedJdtService view = scoped(service, service.defaultProjectKey().orElseThrow());

        for (Path file : view.getAllJavaFiles()) {
            assertNotNull(view.getCompilationUnit(file),
                "listed but unresolvable: " + file
                    + " — this gap is what a scan reports as filesMissed");
        }
    }
}
