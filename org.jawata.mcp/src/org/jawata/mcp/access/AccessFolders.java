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
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Where any seat may read and write files, derived from the disk and never from a launch event (Sprint 28h Stage 27 (z4), his rulings 2026-11-19
 * and 2026-11-20, replacing (p)): the folders that enclose the loaded Java projects (the repository each project lies in), the folders the editor
 * window has open, and the folders listed in the VS Code workspace file those repositories belong to, each read again whenever its file changes.
 * Access is not tool reach: widening it loads nothing into the Java model, and a Java tool on a folder that is no Java project says so.
 *
 * <p>The editor windows' folders are the lists the studio writes, one per window, every time a window starts or its folders change and
 * removes when it closes, whether the engine is already running or not; they are covered as they are. The workspace file is found in the folder
 * above a repository, where a window of several repositories keeps it. It belongs to the workspace when one of its folders is a loaded
 * project's repository or a folder above one; a file that lists none of them is not read for its folders, one inside a repository (it may have
 * come with a clone) is not read, and a file that cannot be read is skipped without costing the other sources their cover.</p>
 */
public final class AccessFolders {

    /** What was read from one file, and the file's modification time and size at that moment: a change of either is a rewrite (audit bug G). */
    private record Read(long modified, long size, List<Path> folders) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

    private final Map<Path, Read> read = new ConcurrentHashMap<>();

    /** The folder of the editor windows' lists, one `<window>.txt` per window, as the studio writes them; null when there is none to read. */
    private final Path editorLists;

    /** Access from the disk alone, with no list from the editor. */
    public AccessFolders() {
        this(null, null);
    }

    /**
     * Access that also covers the folders every editor window has open (his word 2026-11-20): the lists the studio writes into
     * {@code editorLists}, one per window, every time a window starts or its folders change, whether the engine is running or not, and
     * removes when a window closes. Each reading's projects' folders (the part without the windows' folders) are handed to
     * {@code projectFoldersRead} when it is given, which keeps the brains the engine starts told what this reading allows (second audit
     * 2026-11-20).
     */
    public AccessFolders(Path editorLists, Consumer<List<Path>> projectFoldersRead) {
        this.editorLists = editorLists;
        this.projectFoldersRead = projectFoldersRead;
    }

    /** Told each reading's projects' folders; null when nobody listens. */
    private final Consumer<List<Path>> projectFoldersRead;

    /** The folders access covers for these loaded Java projects, each under its one name; the projects' own folders are always among them. */
    public List<Path> of(List<Path> loadedProjectRoots) {
        List<Path> projects = withoutEditorWindows(loadedProjectRoots);
        if (projectFoldersRead != null) {
            projectFoldersRead.accept(projects);
        }
        LinkedHashSet<Path> covered = new LinkedHashSet<>(projects);
        // The folders the editor windows have open, whichever of the editor and the engine started first (his word 2026-11-20): what a window
        // has open is the person's own choice, so nothing of it is filtered out.
        covered.addAll(editorFolders());
        return List.copyOf(covered);
    }

    /**
     * The part of {@link #of} that does not change with the editor windows: the loaded projects, the repositories around them and the folders
     * of a VS Code workspace file kept beside those repositories.
     */
    public List<Path> withoutEditorWindows(List<Path> loadedProjectRoots) {
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

    /** The folders every editor window has open, one to a line in each window's list; none when there are no lists. */
    private List<Path> editorFolders() {
        if (editorLists == null) {
            return List.of();
        }
        List<Path> folders = new ArrayList<>();
        try (DirectoryStream<Path> lists = Files.newDirectoryStream(editorLists, "*.txt")) {
            for (Path list : lists) {
                folders.addAll(read(list, text -> text.lines().filter(line -> !line.isBlank()).map(line -> PlaceNames.normalise(Path.of(line.strip()))).toList()));
            }
        } catch (IOException | RuntimeException unreadable) {
            // no folder of lists yet: no window has handed its folders
        }
        return folders;
    }

    /** How a file's text is read into folders; it may fail, and the file then names none. */
    @FunctionalInterface
    private interface Parse {
        List<Path> folders(String text) throws IOException;
    }

    /** A file read into folders, read again only when its modification time or its size is not the one last read; none for a file that cannot be read. */
    private List<Path> read(Path file, Parse parse) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            Read earlier = read.get(file);
            if (earlier != null && earlier.modified() == modified && earlier.size() == size) {
                return earlier.folders();
            }
            Read fresh = new Read(modified, size, List.copyOf(parse.folders(Files.readString(file))));
            read.put(file, fresh);
            return fresh.folders();
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    /** The folders one workspace file lists; none for a file that cannot be read. */
    private List<Path> foldersOf(Path file) {
        return read(file, text -> {
            List<Path> folders = new ArrayList<>();
            for (JsonNode entry : JSON.readTree(text).path("folders")) {
                folderOf(file, entry).ifPresent(folders::add);
            }
            return folders;
        });
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
