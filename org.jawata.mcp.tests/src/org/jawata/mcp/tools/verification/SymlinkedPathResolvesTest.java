package org.jawata.mcp.tools.verification;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONE DIRECTORY UNDER TWO NAMES STILL RESOLVES TO ONE COMPILATION UNIT — jawata-mcp#69.
 *
 * <p>Two rows failed on the macOS matrix job and passed on Linux in the same run. The cause
 * is not the operating system as such: on macOS {@code /var} is a symlink to
 * {@code /private/var}, so a path handed in as {@code /var/folders/…/x} and the same
 * directory as the resource layer knows it are unequal to every string comparison in this
 * product while naming one file. Measured in the v4.1.1 run: 2376 log lines carry the first
 * spelling and 1906 the second, from the same process — a project loaded from {@code /var}
 * whose workspace initialised at {@code /private/var}. Linux puts temporary files under
 * {@code /tmp}, which is not a symlink, so both spellings agree and the question never
 * arises there.</p>
 *
 * <h2>Which is why this test can exist here at all, and it is the point</h2>
 *
 * <p>The defect is a SYMLINKED PATH, not a platform. Linux makes symlinks perfectly well —
 * macOS merely ships one in the place the temporary directory lives. So the failing condition
 * is reproducible on any machine that can call {@code Files.createSymbolicLink}, and this
 * test creates the very thing macOS provides by accident.</p>
 *
 * <p>An earlier attempt at #69 shipped a fix whose window "does not open on Linux", with a
 * written note that its only control was the next macOS run. That was true of the fix I had
 * written and false of the DEFECT — the condition was constructible the whole time, and
 * saying it was not is what let a wrong fix ship twice.</p>
 */
class SymlinkedPathResolvesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path projectRoot;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        projectRoot = service.allProjects().iterator().next().projectRoot();
    }

    @Test
    @DisplayName("a file reached through a symlinked directory resolves to the same unit as the real path")
    void aSymlinkedPathFindsTheSameUnit() throws Exception {
        Path real = projectRoot.resolve("src/main/java/com/example/FeatureEnvyTargets.java");
        assertTrue(Files.exists(real), "PROOF OF LIFE: the fixture file must be there: " + real);

        ICompilationUnit direct = service.getCompilationUnit(real);
        assertNotNull(direct, "PROOF OF LIFE: the real path must resolve, or the comparison "
            + "below measures nothing: " + real);

        // The condition macOS supplies for free: a second name for the project directory.
        Path link = projectRoot.getParent().resolve("linked-" + projectRoot.getFileName());
        try {
            Files.createSymbolicLink(link, projectRoot);
        } catch (UnsupportedOperationException | java.io.IOException cannotLink) {
            Assumptions.abort("NOT RUN — this filesystem will not create a symlink ("
                + cannotLink.getClass().getSimpleName() + "), so the condition under test "
                + "cannot be constructed here: " + cannotLink.getMessage());
            return;
        }

        Path throughLink = link.resolve("src/main/java/com/example/FeatureEnvyTargets.java");
        assertNotEquals(real.toString(), throughLink.toString(),
            "PROOF OF LIFE: the two spellings must actually differ as strings, or this is "
                + "asserting that a path equals itself");
        assertTrue(Files.exists(throughLink),
            "and both must name a real file — that is what makes them two names for one "
                + "thing rather than one good path and one broken one: " + throughLink);

        ICompilationUnit viaLink = service.getCompilationUnit(throughLink);

        assertNotNull(viaLink,
            "a path that names an existing source file must resolve however it is spelled. "
                + "Without the canonical retry this is null, and every caller downstream — "
                + "111 of them reach this method — is told the file does not exist: "
                + throughLink);
        assertEquals(direct.getElementName(), viaLink.getElementName(),
            "and it must be the SAME unit, not merely some unit: resolving a second copy "
                + "would be a different defect wearing this one's green");
    }
}
