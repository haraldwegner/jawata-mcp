package org.jawata.mcp.catalogue;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a repository file is, for the catalogue tests — asked once rather than walked three
 * times.
 *
 * <h2>Why this exists, which is the same reason as everything else in this package</h2>
 *
 * <p>The suite runs from the built dist, whose working directory is not reliably the repository
 * root, so a test that needs a committed artifact — the pinned pattern snapshot, a manifest, a
 * table being transcribed from — has to search upward for it rather than resolve a relative
 * path. Three classes here were doing that walk, and a fourth copy was one move away: the
 * permanent pattern control moved out of the expiring scaffold class and took its dependency on
 * that class's private helper with it, which is what surfaced this.</p>
 *
 * <p>So the walk has one owner. That is the same judgement {@code Catalogue} made for
 * {@code withOrigin} and {@code byKey} one commit earlier, and the one five code generators,
 * six {@code typeNamed} copies and thirty-three private parse helpers in this repository each
 * had to be made the expensive way.</p>
 *
 * <h2>A MISS IS NOT AN ERROR HERE</h2>
 *
 * <p>{@link #sourceOf} answers {@code null} when it finds nothing, deliberately: its callers
 * assert on the absence and name what they looked for, which is a far better failure than a
 * bare missing-file exception from somewhere inside a walk. {@link #root} throws instead,
 * because a caller asking for the root has nothing useful to say about its absence.</p>
 */
final class RepoFiles {

    /** The marker that identifies the repository root: the shared catalogue's own module. */
    private static final String MARKER = "org.jawata.catalogue";

    private RepoFiles() {}

    /**
     * A file or directory named relative to the repository root, or {@code null} if absent.
     *
     * @param repoRelative e.g. {@code build/acceptance} or a path under a bundle
     */
    static Path sourceOf(String repoRelative) {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve(repoRelative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The repository root, found by the marker module; throws rather than guessing. */
    static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(MARKER))) {
                return p;
            }
        }
        throw new IllegalStateException(
                "could not find " + MARKER + " above " + Path.of("").toAbsolutePath());
    }
}
