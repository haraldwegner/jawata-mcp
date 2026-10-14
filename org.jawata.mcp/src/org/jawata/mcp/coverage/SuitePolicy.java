package org.jawata.mcp.coverage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcessOutcome;
import org.jawata.core.host.HostProcesses;

/**
 * The suite's standing policy, read as DATA (Sprint 28g D41, third clause: <i>"it holds suite
 * policy as data — every quarantined flake, accepted abort and changed threshold with its date and
 * reason, answerable as a list"</i>).
 *
 * <p><b>Where the policy already lives.</b> A project that accepts some aborts says so in
 * {@code build/expected-aborts.<os>}: a pattern list, each line a substring an abort may match,
 * with the reason written in the comment block above it ("Add a line only WITH the reason it is
 * acceptable"). The list exists and a gate reads it; what no instrument could do was ANSWER from
 * it. A hired test manager reads Java and the compiler's index only, so it reported "no queryable
 * quarantine list, every date and reason empty" about a repository that holds exactly that list in
 * three files — a wrong answer given honestly, because its tools could not see a non-Java file.</p>
 *
 * <p><b>What this reads, and what it refuses to claim.</b> It reads only {@code build/expected-
 * aborts.*} under the project root, so it is no general file reader: a hired brain cannot be
 * pointed at an arbitrary path through it. The date is the file's last commit
 * ({@code git log -1}), not the date of each line — that is the one date the repository
 * actually records, and the answer says so under {@code dateBasis} rather than presenting a file
 * date as a line date. A file with no git history has {@code lastChanged: null} and says why.
 * No such file at all is an answer too ({@code found: false}), never an empty list.</p>
 */
public final class SuitePolicy {

    /** The file-name prefix of an accepted-abort list; the rest of the name is the OS it applies to. */
    static final String ABORT_LIST_PREFIX = "expected-aborts.";

    private SuitePolicy() {
    }

    /**
     * Read the policy under {@code projectRoot}.
     *
     * @param projectRoot     the loaded project's root; the lists are looked for in its
     *                        {@code build/} directory
     */
    public static Map<String, Object> read(Path projectRoot) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path build = projectRoot.resolve("build");
        List<Path> files = abortLists(build);

        List<Map<String, Object>> fileRows = new ArrayList<>();
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            String os = name.substring(ABORT_LIST_PREFIX.length());
            List<Map<String, Object>> entries;
            try {
                entries = parse(Files.readAllLines(file, StandardCharsets.UTF_8), os, name);
            } catch (IOException e) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", "build/" + name);
                row.put("os", os);
                row.put("unreadable", e.getMessage());
                fileRows.add(row);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", "build/" + name);
            row.put("os", os);
            row.put("patternCount", entries.size());
            String changed = lastCommitDate(projectRoot, file);
            row.put("lastChanged", changed);
            if (changed == null) {
                row.put("lastChangedUnavailableBecause",
                    "no git history for this file (not a git working tree, or never committed)");
            }
            fileRows.add(row);
            for (Map<String, Object> entry : entries) {
                entry.put("fileLastChanged", changed);
                accepted.add(entry);
            }
        }

        out.put("found", !files.isEmpty());
        if (files.isEmpty()) {
            out.put("reason", "no build/" + ABORT_LIST_PREFIX + "* file under " + projectRoot
                + ". That is an absence of the list, not an empty list: this project may accept "
                + "aborts some other way, which this reading does not look at.");
        }
        out.put("dateBasis", "the file's last commit date; the repository records no date per line");
        out.put("files", fileRows);
        out.put("acceptedAborts", accepted);
        out.put("acceptedAbortCount", accepted.size());
        return out;
    }

    /**
     * The entries of one list. A pattern line takes the comment block directly above it as its
     * reason; the block survives across consecutive pattern lines and is dropped by a blank line,
     * so a header paragraph followed by a blank line is NOT mistaken for the reason of the first
     * pattern, and one reason may stand over several patterns written together.
     */
    static List<Map<String, Object>> parse(List<String> lines, String os, String fileName) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> block = new ArrayList<>();
        boolean lastWasPattern = false;
        int number = 0;
        for (String raw : lines) {
            number++;
            String line = raw.strip();
            if (line.isEmpty()) {
                block.clear();
                lastWasPattern = false;
            } else if (line.startsWith("#")) {
                if (lastWasPattern) {
                    block.clear();
                }
                lastWasPattern = false;
                block.add(line.replaceFirst("^#\\s?", ""));
            } else {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("os", os);
                entry.put("pattern", line);
                entry.put("reason", block.isEmpty() ? null : String.join(" ", block));
                entry.put("file", "build/" + fileName);
                entry.put("line", number);
                entries.add(entry);
                lastWasPattern = true;
            }
        }
        return entries;
    }

    /**
     * {@code expected-aborts.} followed by a plain OS name. A backup beside a list
     * ({@code expected-aborts.linux.bak}, {@code expected-aborts.linux~}) is not a list, and
     * reading it as one would put an old file's patterns into the answer under a made-up OS.
     */
    static boolean isAbortList(String fileName) {
        return fileName.startsWith(ABORT_LIST_PREFIX)
            && fileName.substring(ABORT_LIST_PREFIX.length()).matches("[a-z0-9]+");
    }

    private static List<Path> abortLists(Path build) {
        if (!Files.isDirectory(build)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.list(build)) {
            return listing
                .filter(p -> Files.isRegularFile(p) && isAbortList(p.getFileName().toString()))
                .sorted()
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String lastCommitDate(Path projectRoot, Path file) {
        try {
            // Through the host boundary: this is a spawn, and the boundary is where an
            // executable's spelling and a timeout are decided once (HostBoundaryRulesTest).
            HostProcessOutcome outcome = HostProcesses.system().run(HostCommand.of(List.of(
                "git", "-C", projectRoot.toString(), "log", "-1", "--format=%cs", "--",
                projectRoot.relativize(file).toString())).waitingAtMost(Duration.ofSeconds(5)));
            if (outcome instanceof HostProcessOutcome.Completed completed
                    && completed.exitCode() == 0) {
                // The boundary merges stderr into the output, so only an actual date counts:
                // a warning git printed beside it must never become the file's "date".
                String date = completed.output().strip();
                return date.matches("\\d{4}-\\d{2}-\\d{2}") ? date : null;
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
