package org.jawata.mcp.execution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;

/**
 * What a hired run CHANGED in the directory it ran in — or why that cannot be said (Sprint 28g
 * S16, C16).
 *
 * <p>C16's first clause ends with <i>"every changed file attributed to the run"</i>, and until
 * this class nothing in the product attributed any. The difficulty is that a brain edits through
 * its OWN tools, so jawata never sees the writes: no gate reports them, no tap records them, and
 * the brain's answer is prose. The only thing that can see them is the directory itself, before
 * and after.</p>
 *
 * <h2>Absence is reported as absence, never as an empty list</h2>
 *
 * <p>{@link #changed()} is an {@code Optional}, which is the whole design. A run whose directory
 * could not be read must not answer "no files changed" — that is the same defect
 * {@link org.jawata.mcp.learn.ResponseFacts} exists to prevent one layer down, where a gate that
 * could not answer reported zero errors exactly as a clean one did. Here the cost of collapsing
 * them is higher: a reader deciding whether to accept a proposal would be told the run touched
 * nothing when in truth nobody looked.</p>
 *
 * <h2>Git is the instrument, and when there is no git there is no claim</h2>
 *
 * <p>{@code git status --porcelain} is exact about tracked files, respects the project's own
 * ignores, and costs milliseconds on a tree where a recursive walk would cost seconds and would
 * drown in {@code target/} and {@code node_modules/}. A working directory that is not a git
 * repository therefore gets a STATED ABSENCE rather than a walk of unknown cost whose result
 * nobody could trust.</p>
 *
 * <h2>The bound, stated rather than discovered later</h2>
 *
 * <p>The attribution is the set of paths whose git status CHANGED across the run. A file that was
 * ALREADY modified before the hire and modified again by it is invisible to this: porcelain
 * reports a status letter and not a content hash, so both readings are {@code " M"} and the two
 * cases cannot be told apart. {@link #basis()} says so in words, because a reader who takes this
 * list for "everything the brain touched" would be wrong in exactly that case.</p>
 */
public final class TreeAttribution {

    private static final Logger log = Logger.getLogger(TreeAttribution.class.getName());

    /** A census is a dirty-path → status-letter map; a null map means the census could not run. */
    private final Map<String, String> before;
    private final Path directory;
    private final HostProcesses host;

    private TreeAttribution(HostProcesses host, Path directory, Map<String, String> before) {
        this.host = host;
        this.directory = directory;
        this.before = before;
    }

    /**
     * Take the BEFORE census. Call this immediately before the brain is spawned.
     *
     * <p>It never throws: a hire must not fail because its attribution could not be set up, and an
     * un-takeable census is a reportable state rather than an error. The reason travels to
     * {@link #after()} in the only way it can — as a null census.</p>
     */
    public static TreeAttribution before(HostProcesses host, Path directory) {
        return new TreeAttribution(host, directory, census(host, directory));
    }

    /**
     * Take the AFTER census and answer what changed between the two.
     *
     * <p>A path appears when its status is new, gone, or different. "Gone" counts: a file the brain
     * reverted or deleted was changed by the run as surely as one it wrote.</p>
     */
    public Attribution after() {
        if (before == null) {
            return new Attribution(Optional.empty(), notAttributable());
        }
        Map<String, String> now = census(host, directory);
        if (now == null) {
            return new Attribution(Optional.empty(),
                "the directory could be read before the run and not after it, so nothing here can"
                    + " be attributed; the run's own record still stands");
        }
        List<String> changed = new ArrayList<>();
        now.forEach((path, status) -> {
            if (!status.equals(before.get(path))) {
                changed.add(path);
            }
        });
        before.keySet().stream().filter(path -> !now.containsKey(path)).forEach(changed::add);
        changed.sort(String::compareTo);
        return new Attribution(Optional.of(List.copyOf(changed)),
            "git status --porcelain in " + directory + ", before and after the run. TWO BOUNDS, and"
                + " an EMPTY list means neither was crossed rather than \"the brain wrote nothing\"."
                + " FIRST: a file ALREADY modified before the hire and modified again by it is"
                + " NOT in this list — a status letter is not a content hash, so the two cases are"
                + " indistinguishable. SECOND: this census sees ONLY this directory, so a write to"
                + " any absolute path outside it is invisible here. That is not hypothetical — a"
                + " brain under a read-only mode was measured writing its own plan file outside the"
                + " hire's tree on every run, and an instrument scoped like this one reported the"
                + " run as having changed nothing.");
    }

    /** Why no claim can be made — read only when the census could not be taken. */
    private String notAttributable() {
        if (!Files.isDirectory(directory)) {
            return "NOT ATTRIBUTABLE: " + directory + " is not a directory, so there was nothing"
                + " to census";
        }
        return "NOT ATTRIBUTABLE: " + directory + " is not a git working tree, and git is the only"
            + " instrument here that can see what a brain changed through its own tools. A"
            + " recursive walk was rejected deliberately — on a build tree it costs seconds and"
            + " reports churn nobody asked about.";
    }

    /**
     * One {@code git status --porcelain} reading, or NULL when it could not be taken.
     *
     * <p>Null rather than an empty map, for this class's own reason: an empty map is a clean tree,
     * which is a different fact from a tree nobody could read.</p>
     */
    private static Map<String, String> census(HostProcesses host, Path directory) {
        if (host == null || directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        try {
            HostProcessOutcome outcome = host.run(HostCommand
                .of(List.of("git", "status", "--porcelain", "--untracked-files=all"))
                .in(directory));
            if (!outcome.succeeded()) {
                // Not a repository, no git on PATH, or a refused directory. All one answer here:
                // the instrument is unavailable, so there is no claim to make.
                return null;
            }
            Map<String, String> dirty = new LinkedHashMap<>();
            for (String line : outcome.output().split("\\R")) {
                // Porcelain v1: two status characters, a space, then the path. A rename carries
                // "old -> new"; the NEW name is what a reader goes and looks at.
                if (line.length() < 4) {
                    continue;
                }
                String status = line.substring(0, 2);
                String path = line.substring(3).trim();
                int arrow = path.indexOf(" -> ");
                if (arrow >= 0) {
                    path = path.substring(arrow + 4);
                }
                dirty.put(unquote(path), status);
            }
            return dirty;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not census " + directory, e);
            return null;
        }
    }

    /** Porcelain quotes a path containing unusual bytes; the quotes are not part of the name. */
    private static String unquote(String path) {
        return path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")
            ? path.substring(1, path.length() - 1)
            : path;
    }

    /**
     * What the run changed, and on what basis.
     *
     * @param changed the paths, or ABSENT when no census could be taken — never an empty list
     *     standing in for "nobody looked"
     * @param basis how the answer was reached, including the case it cannot see. Always present,
     *     because a list with no stated basis invites a reader to assume exactness it does not have
     */
    public record Attribution(Optional<List<String>> changed, String basis) {

        /** How many files, or ABSENT. Separate from {@code changed} so a renderer need not unwrap. */
        public Optional<Integer> count() {
            return changed.map(List::size);
        }
    }
}
