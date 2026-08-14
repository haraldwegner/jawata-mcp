package org.jawata.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.fixtures.TestProjectHelper;
import org.jawata.core.host.IPathUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 (v3.6.4) — a path jawata emits must be a path jawata accepts.
 *
 * <p>Found by working v3.6.3 in anger (Cursor dogfood, 2026-07-29). Responses format file
 * paths through {@link IPathUtils#formatPath}, which returns them RELATIVE to the project
 * root. Feeding that exact string back to a tool that resolves a file returned
 * {@code FILE_NOT_FOUND} — the lookup matched only ABSOLUTE paths against the source roots
 * and abandoned anything else. Measured live on a 1040-source project: the absolute form
 * resolved and reported its own path back in the relative form, which then did not resolve,
 * with or without {@code projectKey}.</p>
 *
 * <p>Why that is worse than untidy: the refusal says the file was not found, about a file
 * that exists, in response to a path jawata itself produced. An agent believes it. That is
 * the same failure as returning a failed lookup as an ordinary empty result — the answer is
 * indistinguishable from the truth and is not the truth.</p>
 *
 * <p>The invariant under test is a ROUND TRIP, not a path format: whatever form a response
 * carries, feeding it back must find the same compilation unit. It holds whichever
 * convention the formatter uses, so it keeps holding if the output form is ever changed.</p>
 */
class PathRoundTripTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** The round trip, asserted through whichever service view the caller holds. */
    private void assertRoundTrips(IJdtService service, Path absolute) {
        ICompilationUnit fromAbsolute = service.getCompilationUnit(absolute);
        assertNotNull(fromAbsolute, "precondition: the absolute path resolves — " + absolute);

        String asReported = service.getPathUtils().formatPath(absolute);
        ICompilationUnit fromReported = service.getCompilationUnit(Path.of(asReported));

        assertNotNull(fromReported,
            "jawata emitted '" + asReported + "' and then refused it. A path in a response must "
                + "be usable as a path in the next request; FILE_NOT_FOUND here tells the caller "
                + "that a file which exists does not.");
        assertEquals(fromAbsolute.getElementName(), fromReported.getElementName(),
            "and it must resolve to the SAME unit, not merely to something");
    }

    /**
     * The shape that failed in the field: an Eclipse plug-in project whose source folder is
     * a bare {@code test/}. Its reported form is {@code test/com/example/…}, and that is the
     * string that came back FILE_NOT_FOUND.
     */
    @Test
    @DisplayName("a reported path resolves back — source folder named 'test'")
    void reportedPathResolvesBackForANonStandardSourceRoot() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path root = helper.getFixturePath("pde-nonstandard-layout");
        assertRoundTrips(service, root.resolve("test/com/example/ns/GreeterTest.java"));
    }

    /**
     * And for a {@code src/}-rooted path. This one round-tripped BEFORE the fix as well —
     * the old convention fallback strips a {@code src/} prefix — which is exactly why the
     * defect went unseen: on a Maven project every reported path already worked. It broke
     * only for source roots the conventions do not name, the same class of project as the
     * v3.6.1 and v3.6.2 defects. Kept as the guard that the common case did not regress.
     */
    @Test
    @DisplayName("a reported path resolves back — src/ layout (worked before; must not regress)")
    void reportedPathResolvesBackForAMavenSourceRoot() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path root = helper.getFixturePath("pde-nonstandard-layout");
        assertRoundTrips(service, root.resolve("src/com/example/ns/Greeter.java"));
    }

    /**
     * Through the SCOPED view too — the shape every {@code projectKey}-carrying tool call
     * uses, and the one that made this defect reach a released build twice before.
     */
    @Test
    @DisplayName("a reported path resolves back through the scoped view")
    void reportedPathResolvesBackThroughTheScopedView() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        Path root = helper.getFixturePath("pde-nonstandard-layout");
        Optional<LoadedProject> project = service.getProject(service.defaultProjectKey().orElseThrow());
        assertTrue(project.isPresent(), "fixture project must be loaded");
        assertRoundTrips(new ScopedJdtService(service, project.get()),
            root.resolve("test/com/example/ns/GreeterTest.java"));
    }

    /**
     * A path that matches no source root still resolves to nothing. The fix widens what is
     * ACCEPTED; it must not start inventing matches for files that are not there.
     */
    @Test
    @DisplayName("a relative path under no source root still resolves to nothing")
    void aPathUnderNoSourceRootStillFindsNothing() throws Exception {
        JdtServiceImpl service = helper.loadProject("pde-nonstandard-layout");
        assertNotNull(service, "precondition: the fixture loads");
        assertEquals(null, service.getCompilationUnit(Path.of("test/com/example/ns/Absent.java")),
            "a file that does not exist must still resolve to null, not to a near miss");
        assertEquals(null, service.getCompilationUnit(Path.of("nowhere/com/example/ns/Greeter.java")),
            "and a path under no source root must not match by coincidence");
    }
}
