package org.jawata.mcp.execution;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which hire wrote which file (28h z120). A hire's changed files are what changed in the tree while it ran, which holds the work of every
 * hire that ran beside it; what a hire wrote itself is what its tool calls and its brain's own editor named, and a commit for one hire reads
 * this to leave another's files out. A write a brain made through its own shell is seen by neither and is recorded for nobody.
 */
public final class WrittenFiles {

    private static final WrittenFiles SHARED = new WrittenFiles();

    /** The store hires record into and a commit reads. */
    public static WrittenFiles shared() {
        return SHARED;
    }

    private final Map<String, Set<String>> byPlan = new ConcurrentHashMap<>();

    /** Keeps these paths as written by the hire of this plan. */
    public void record(String planId, Collection<String> paths) {
        if (planId == null || planId.isBlank()) {
            return;
        }
        for (String path : paths) {
            if (path != null && !path.isBlank()) {
                byPlan.computeIfAbsent(planId, id -> ConcurrentHashMap.newKeySet()).add(path.replace('\\', '/'));
            }
        }
    }

    /** The plans recorded as writing this file of the repository at {@code top}; none when no hire is recorded for it. */
    public Set<String> writersOf(Path top, String changed) {
        Path file = top.resolve(changed).normalize();
        String relative = changed.replace('\\', '/');
        Set<String> writers = new LinkedHashSet<>();
        byPlan.forEach((plan, paths) -> {
            if (paths.stream().anyMatch(written -> names(written, file, relative))) {
                writers.add(plan);
            }
        });
        return writers;
    }

    /** An absolute path names the one file it is; a relative one, which a tool gives from its project's folder, names a file it ends. */
    private static boolean names(String written, Path file, String relative) {
        Path path = Path.of(written);
        if (path.isAbsolute()) {
            return path.normalize().equals(file);
        }
        return relative.equals(written) || relative.endsWith("/" + written);
    }
}
