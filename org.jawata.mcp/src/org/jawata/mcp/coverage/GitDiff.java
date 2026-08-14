package org.jawata.mcp.coverage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 23 (D3, Stage 8) — changed NEW-side line numbers from a git diff,
 * rename-aware: {@code -M} makes a moved-but-unchanged file produce NO
 * hunks, so relocated covered code never shows up as a "changed uncovered"
 * false gap. Parsed from {@code git diff -U0 -M}.
 */
public final class GitDiff {

    private static final Logger log = LoggerFactory.getLogger(GitDiff.class);
    private static final Pattern HUNK = Pattern.compile(
        "^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");
    private static final Pattern NEW_FILE = Pattern.compile("^\\+\\+\\+ b/(.*)$");

    /** new-side repo-relative path → changed (added/modified) new-side lines. */
    public final Map<String, Set<Integer>> changedLinesByFile = new LinkedHashMap<>();

    /**
     * @param kind  worktree (vs HEAD) | staged | range
     * @param range required for kind=range, e.g. "HEAD~1..HEAD"
     */
    public static GitDiff read(Path repoRoot, String kind, String range) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repoRoot.toString(),
            "diff", "-U0", "-M"));
        switch (kind) {
            case "worktree" -> cmd.add("HEAD");
            case "staged" -> cmd.add("--cached");
            case "range" -> {
                if (range == null || range.isBlank()) {
                    throw new IllegalArgumentException("diff=range requires 'range' (e.g. A..B)");
                }
                cmd.add(range);
            }
            default -> throw new IllegalArgumentException(
                "diff must be worktree, staged, or range; got '" + kind + "'");
        }
        try {
            HostProcessOutcome outcome = HostProcesses.system()
                .run(HostCommand.of(cmd).waitingAtMost(Duration.ofSeconds(20)));
            // Each outcome keeps the diagnosis it deserves. A git that is not
            // installed used to arrive here as the same "git diff failed" as a
            // git that ran and refused the range.
            if (outcome instanceof HostProcessOutcome.CannotLaunch cannotLaunch) {
                throw new IOException(cannotLaunch.describe());
            }
            if (outcome instanceof HostProcessOutcome.TimedOut) {
                throw new IOException("git diff timed out");
            }
            HostProcessOutcome.Completed completed = (HostProcessOutcome.Completed) outcome;
            if (completed.exitCode() != 0) {
                throw new IOException("git diff failed (exit " + completed.exitCode() + "): "
                    + completed.output().lines().findFirst().orElse(""));
            }
            return parse(completed.output());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running git diff", ie);
        }
    }

    static GitDiff parse(String unified) {
        GitDiff diff = new GitDiff();
        String currentFile = null;
        for (String line : unified.split("\\R")) {
            Matcher file = NEW_FILE.matcher(line);
            if (file.matches()) {
                currentFile = file.group(1);
                diff.changedLinesByFile.computeIfAbsent(currentFile,
                    k -> new LinkedHashSet<>());
                continue;
            }
            Matcher hunk = HUNK.matcher(line);
            if (hunk.find() && currentFile != null) {
                int start = Integer.parseInt(hunk.group(1));
                int count = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
                Set<Integer> lines = diff.changedLinesByFile.get(currentFile);
                for (int i = 0; i < count; i++) {
                    lines.add(start + i);
                }
            }
        }
        log.debug("git diff: {} file(s) with changes", diff.changedLinesByFile.size());
        return diff;
    }
}
