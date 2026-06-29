package org.goja.mcp.tools.smell;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sprint 17 — minimal git-history capability for the change-correlation smells
 * (Divergent Change, Shotgun Surgery). Shells out to {@code git log} once,
 * parses the per-commit file lists, and answers per-file questions:
 * how many commits touched a file, and across how many distinct areas it
 * co-changed.
 *
 * <p><b>Graceful by design.</b> If the project root is not a git work-tree, git
 * is absent, or the command fails/﻿times out, {@link #forRoot} returns an
 * {@linkplain #available() unavailable} instance that reports zero for
 * everything — detectors then produce no findings rather than erroring. This is
 * the same defensive shape as the manager's conditional Lombok agent.</p>
 *
 * <p>Paths are repo-relative-to-the-root ({@code git log --relative} run with
 * {@code -C <root>}), matching a project-root relativisation of source files.</p>
 */
public final class GitHistory {

    private final boolean available;
    private final Map<String, Integer> commitCount;
    private final Map<String, Set<Integer>> fileToCommits;
    private final List<List<String>> commits;

    private GitHistory(boolean available, List<List<String>> commits) {
        this.available = available;
        this.commits = commits;
        this.commitCount = new HashMap<>();
        this.fileToCommits = new HashMap<>();
        for (int i = 0; i < commits.size(); i++) {
            for (String file : commits.get(i)) {
                commitCount.merge(file, 1, Integer::sum);
                fileToCommits.computeIfAbsent(file, k -> new HashSet<>()).add(i);
            }
        }
    }

    /** An unavailable history (not a repo / git missing): reports zero for everything. */
    public static GitHistory unavailable() {
        return new GitHistory(false, List.of());
    }

    /** Build directly from parsed commits (file lists). Package-visible for tests. */
    static GitHistory of(List<List<String>> commits) {
        return new GitHistory(true, commits);
    }

    /**
     * Parse {@code git log} output produced with
     * {@code --no-merges --name-only --relative --format=%x1f%H}: each commit
     * begins with a {@code 0x1F} byte, then a hash line, then its file paths.
     */
    static GitHistory fromGitLog(String raw) {
        List<List<String>> commits = new ArrayList<>();
        List<String> current = null;
        for (String line : raw.split("\n", -1)) {
            if (!line.isEmpty() && line.charAt(0) == '\u001F') {
                // commit boundary: the rest of this line is the hash (ignored).
                current = new ArrayList<>();
                commits.add(current);
                continue;
            }
            String file = line.trim();
            if (!file.isEmpty() && current != null) {
                current.add(file);
            }
        }
        return new GitHistory(true, commits);
    }

    /** Run {@code git log} against {@code projectRoot}; unavailable on any failure. */
    public static GitHistory forRoot(Path projectRoot) {
        if (projectRoot == null) {
            return unavailable();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "git", "-C", projectRoot.toString(),
                "log", "--no-merges", "--name-only", "--relative", "--format=%x1f%H");
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return unavailable();
            }
            if (p.exitValue() != 0) {
                return unavailable();
            }
            return fromGitLog(out);
        } catch (Exception e) {
            return unavailable();
        }
    }

    public boolean available() {
        return available;
    }

    /** Number of commits that touched {@code relPath}. */
    public int commitCount(String relPath) {
        return commitCount.getOrDefault(relPath, 0);
    }

    /**
     * Number of distinct directory "areas" that {@code relPath} co-changed with
     * (parent directories of the other files in the commits touching it). A high
     * count means the file changes alongside many unrelated parts of the system —
     * the Divergent Change signal.
     */
    public int coChangeAreaCount(String relPath) {
        Set<Integer> idx = fileToCommits.get(relPath);
        if (idx == null) {
            return 0;
        }
        Set<String> areas = new HashSet<>();
        for (int i : idx) {
            for (String other : commits.get(i)) {
                if (!other.equals(relPath)) {
                    areas.add(area(other));
                }
            }
        }
        return areas.size();
    }

    private static String area(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }
}
