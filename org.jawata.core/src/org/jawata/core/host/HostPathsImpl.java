package org.jawata.core.host;

import java.nio.file.Path;

/**
 * Implementation of path utilities for JAWATA.
 * Provides consistent path formatting across platforms.
 */
public class HostPathsImpl implements HostPaths {

    private final Path projectRoot;
    private final boolean useAbsolutePaths;

    public HostPathsImpl(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.useAbsolutePaths = "true".equalsIgnoreCase(System.getenv("JAWATA_ABSOLUTE_PATHS"));
    }

    @Override
    public String formatPath(String absolutePath) {
        return formatPath(Path.of(absolutePath));
    }

    @Override
    public String formatPath(Path path) {
        // A RELATIVE input is resolved against the PROJECT ROOT, never the process
        // CWD — toAbsolutePath() would resolve it against the CWD (the AppImage
        // mount on a packaged resident), and startsWith(projectRoot) would then
        // fail and leak the mount path into the response (v2.14.1 audit finding).
        Path normalizedPath = (path.isAbsolute() ? path : projectRoot.resolve(path)).normalize();

        String result;
        if (useAbsolutePaths) {
            result = normalizedPath.toString();
        } else {
            result = relativizeOrAbsolute(normalizedPath);
        }

        // Use forward slashes for consistency
        return result.replace('\\', '/');
    }

    /**
     * Relativise against the project root, retrying on the canonical spellings
     * when the plain comparison fails.
     *
     * <p><b>Why the retry exists.</b> {@link Path#normalize()} collapses {@code .}
     * and {@code ..} and nothing else — it does not resolve a symlink, and it does
     * not expand a short name. So one directory can reach this method under two
     * spellings that are not {@code equals}, {@code startsWith} returns false, and
     * the branch below leaks a MACHINE-SPECIFIC ABSOLUTE PATH into the response.
     * Two spellings seen in the wild:</p>
     * <ul>
     *   <li><b>macOS:</b> {@code /var} is a symlink to {@code /private/var}, so a
     *       temp-rooted project root and the files under it disagree.</li>
     *   <li><b>Windows:</b> 8.3 short names — {@code C:\Users\RUNNER~1} for
     *       {@code C:\Users\runneradmin}.</li>
     * </ul>
     *
     * <p>Sprint 28a found this the first time the cross-platform CI matrix ever
     * ran: every refactoring diff on Windows carried absolute paths instead of
     * project-relative ones, which broke nineteen golden-file parity tests and
     * would have shown every Windows user their own home directory in each diff.
     * It is the SECOND instance of this exact fragility — the comment on
     * {@code formatPath} records the first, where a packaged resident leaked its
     * AppImage mount path (v2.14.1).</p>
     *
     * <p>Canonicalising is a filesystem call and can fail (a path that does not
     * exist yet, a permission error), so it is attempted only when the cheap
     * comparison has already failed, and a failure falls back to the previous
     * behaviour rather than throwing.</p>
     */
    private String relativizeOrAbsolute(Path normalizedPath) {
        if (normalizedPath.startsWith(projectRoot)) {
            return projectRoot.relativize(normalizedPath).toString();
        }
        Path realRoot = toRealQuietly(projectRoot);
        Path realPath = toRealQuietly(normalizedPath);
        if (realPath.startsWith(realRoot)) {
            return realRoot.relativize(realPath).toString();
        }
        return normalizedPath.toString();
    }

    /** Canonical form of {@code path}, or {@code path} itself if it cannot be resolved. */
    private static Path toRealQuietly(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException | RuntimeException e) {
            return path;
        }
    }

    @Override
    public Path getProjectRoot() {
        return projectRoot;
    }

    @Override
    public boolean isUsingAbsolutePaths() {
        return useAbsolutePaths;
    }

    @Override
    public Path resolve(String relativePath) {
        return projectRoot.resolve(relativePath).normalize();
    }

    @Override
    public boolean isWithinProject(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        return normalizedPath.startsWith(projectRoot);
    }
}
