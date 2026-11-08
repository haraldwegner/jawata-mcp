package org.jawata.mcp.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.execution.TreeAttribution;

/**
 * What the person's "reject" does to a hire's proposal (Sprint 28h S21, open point 8 of S17): the files the hire changed are put back as they were when
 * it began. A file is put back only when that is exact and nothing else is lost, and every file that is not put back is named with the reason:
 *
 * <ul>
 *   <li>a file that held anything beyond HEAD before the hire ran is kept, because the proposal's old text is HEAD and writing it would take the
 *       person's own earlier edit with it;</li>
 *   <li>a file that no longer holds what the hire left is kept, because someone has changed it since and the proposal is about the other text;</li>
 *   <li>a file too large to carry, and every file when the hire's folder is not known, is kept and named.</li>
 * </ul>
 *
 * A tracked file is put back by git itself ({@code git checkout HEAD -- path}), so its bytes are exactly those of HEAD, line endings included; a
 * file the hire created is deleted.
 */
final class TakeBack {

    private static final Logger log = Logger.getLogger(TakeBack.class.getName());

    /** What was put back, and what was kept with the reason for each. */
    record Result(List<String> putBack, List<String> kept) {

        /** One plain sentence for the person, shown on the hire's card. */
        String sentence() {
            StringBuilder said = new StringBuilder("Rejected. ");
            said.append(putBack.isEmpty() ? "Nothing was put back." : "Put back: " + String.join(", ", putBack) + ".");
            if (!kept.isEmpty()) {
                said.append(" Kept: ").append(String.join("; ", kept)).append('.');
            }
            return said.toString();
        }
    }

    private TakeBack() {
    }

    /**
     * Puts back what the proposal's files allow, in {@code directory}.
     *
     * @param host      runs git; the process boundary of the engine
     * @param directory the folder the hire ran in; empty or not a folder keeps every file
     * @param proposal  the hire's proposal: its changes and the names of the files too large to carry
     */
    static Result of(HostProcesses host, String directory, HireWatch.Proposal proposal) {
        List<String> putBack = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        Path ran = null;
        try {
            ran = directory == null || directory.isBlank() ? null : Path.of(directory);
        } catch (java.nio.file.InvalidPathException unreadable) {
            // a folder name this machine cannot read is no folder the hire ran in: every file is kept and named
            log.log(Level.WARNING, "the folder the hire ran in is not a path: " + directory, unreadable);
        }
        // git names a changed path from the top folder of the working tree, which is not the folder a hire ran in when that was a subfolder
        Path root = ran == null || !Files.isDirectory(ran) ? ran : TreeAttribution.repositoryRoot(host, ran);
        for (TreeAttribution.FileChange change : proposal.changes()) {
            if (root == null || !Files.isDirectory(root)) {
                kept.add(change.path() + " (the folder the hire ran in is not known)");
            } else {
                String why = putBack(host, root, change);
                if (why.isEmpty()) {
                    putBack.add(change.path());
                } else {
                    kept.add(change.path() + " (" + why + ")");
                }
            }
        }
        proposal.omitted().forEach(file -> kept.add(file + " (too large to compare, put it back yourself)"));
        return new Result(List.copyOf(putBack), List.copyOf(kept));
    }

    /** Puts one file back; the empty string when it was, otherwise the reason it was not. */
    private static String putBack(HostProcesses host, Path root, TreeAttribution.FileChange change) {
        if (!change.cleanBefore()) {
            return "you had changes in it before the hire ran";
        }
        Path file = root.resolve(change.path()).normalize();
        if (!file.startsWith(root.normalize())) {
            return "its path leaves the hire's folder";
        }
        try {
            String now = Files.exists(file) ? Files.readString(file) : null;
            if (!java.util.Objects.equals(now, change.newText())) {
                return "it has changed since the hire";
            }
            if (change.oldText() == null) {
                Files.deleteIfExists(file);
                return "";
            }
            HostProcessOutcome outcome = host.run(HostCommand.of(List.of("git", "checkout", "HEAD", "--", change.path())).in(root));
            return outcome.succeeded() ? "" : "git could not put it back";
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "could not put back " + change.path() + " in " + root, e);
            return "it could not be read or written";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "it was interrupted";
        }
    }
}
