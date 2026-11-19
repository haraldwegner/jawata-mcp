package org.jawata.mcp.access;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Where any seat may read and write files, derived from the disk and never from a launch event (Sprint 28h Stage 27 (z4), his ruling 2026-11-19,
 * replacing (p)): the folders that enclose the loaded Java projects (the repository each project lies in), and the folders listed in the VS Code
 * workspace file those repositories belong to, read again whenever that file changes. Access is not tool reach: widening it loads nothing into
 * the Java model, and a Java tool on a folder that is no Java project says so.
 *
 * <p>The workspace file is found beside the repositories: in a repository's own folder or in the folder above it, where a window of several
 * repositories keeps it. A file belongs to the workspace when one of its folders holds a loaded project's repository; a file that lists none of
 * them is not read for its folders, and a file that cannot be read is skipped without costing the other sources their cover.</p>
 */
public final class AccessFolders {

    /** What was read from one workspace file, and the modification time of the file at that moment. */
    private record Read(long modified, List<Path> folders) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

    private final Map<Path, Read> read = new ConcurrentHashMap<>();

    /** The folders access covers for these loaded Java projects, each under its one name; the projects' own folders are always among them. */
    public List<Path> of(List<Path> loadedProjectRoots) {
        LinkedHashSet<Path> covered = new LinkedHashSet<>();
        LinkedHashSet<Path> repositories = new LinkedHashSet<>();
        for (Path root : loadedProjectRoots) {
            Path project = PlaceNames.normalise(root);
            covered.add(project);
            Path repository = repositoryOf(project);
            covered.add(repository);
            repositories.add(repository);
        }
        LinkedHashSet<Path> beside = new LinkedHashSet<>();
        for (Path repository : repositories) {
            // Only the folder above a repository: a workspace file inside one may have come with a clone and list any folder it likes.
            if (repository.getParent() != null) {
                beside.add(repository.getParent());
            }
        }
        for (Path folder : beside) {
            for (Path file : workspaceFilesIn(folder)) {
                List<Path> listed = foldersOf(file);
                if (repositories.stream().anyMatch(repository -> listed.stream().anyMatch(repository::startsWith))) {
                    // A file that came with a cloned repository may list anything: the person's home and everything above it is never taken from it.
                    Path home = PlaceNames.normalise(Path.of(System.getProperty("user.home")));
                    listed.stream().filter(place -> !home.startsWith(place)).forEach(covered::add);
                }
            }
        }
        return List.copyOf(covered);
    }

    /** The nearest folder above the project that holds a {@code .git}, or the project itself when none does or that folder is the person's home or above. */
    private static Path repositoryOf(Path project) {
        Path home = PlaceNames.normalise(Path.of(System.getProperty("user.home")));
        for (Path place = project; place != null; place = place.getParent()) {
            if (Files.exists(place.resolve(".git"))) {
                return home.startsWith(place) ? project : place;
            }
        }
        return project;
    }

    private static List<Path> workspaceFilesIn(Path folder) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(folder, "*.code-workspace")) {
            found.forEach(files::add);
        } catch (IOException | RuntimeException unreadable) {
            // a folder that cannot be listed has no workspace file to read
        }
        return files;
    }

    /** The folders one workspace file lists, read again when its modification time is not the one last read; none for a file that cannot be read. */
    private List<Path> foldersOf(Path file) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            Read earlier = read.get(file);
            if (earlier != null && earlier.modified() == modified) {
                return earlier.folders();
            }
            List<Path> folders = new ArrayList<>();
            for (JsonNode entry : JSON.readTree(Files.readString(file)).path("folders")) {
                folderOf(file, entry).ifPresent(folders::add);
            }
            Read fresh = new Read(modified, List.copyOf(folders));
            read.put(file, fresh);
            return fresh.folders();
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    private static java.util.Optional<Path> folderOf(Path file, JsonNode entry) {
        try {
            if (entry.path("path").isTextual()) {
                Path named = Path.of(entry.path("path").asText());
                return java.util.Optional.of(PlaceNames.normalise(named.isAbsolute() ? named : file.toAbsolutePath().getParent().resolve(named)));
            }
            if (entry.path("uri").isTextual()) {
                URI uri = URI.create(entry.path("uri").asText());
                return "file".equals(uri.getScheme()) ? java.util.Optional.of(PlaceNames.normalise(Path.of(uri))) : java.util.Optional.empty();
            }
        } catch (RuntimeException unreadable) {
            // an entry that names no place is skipped
        }
        return java.util.Optional.empty();
    }
}
