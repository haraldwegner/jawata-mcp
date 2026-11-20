package org.jawata.mcp.access;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The folders the engine's access check allows, for the brains the engine starts (28h-C27-31, his word 2026-11-20: "the workspace of VSCode
 * has to be included. Otherwise the jawata agents have no access"). A hired brain runs in a client of its own whose guard keeps its own rule;
 * the engine tells each brain it starts where to read what the engine allows, so that guard allows what the engine allows.
 *
 * <p><b>Nothing a brain is told is a picture of its start</b> (second audit 2026-11-20, finding 1). The folders of the loaded Java projects
 * are kept in a file the engine rewrites whenever they change, and the editor windows' folders in the windows' own lists; the guard reads
 * both on every call, so a folder a window closes, or a project that is unloaded, stops being allowed while the brain still runs. Only a host
 * with no place for that file tells a brain the projects' folders as they stand at its start, which does not hold the windows' folders.</p>
 */
public final class BrainAccess {

    /** The variable naming the file of the loaded projects' folders, one to a line; the studio's guard (jawata-hook {@code local.rs}) reads it. */
    public static final String ACCESS_FOLDERS_FILE = "JAWATA_ACCESS_FOLDERS_FILE";

    /** The variable naming those folders as a path list of the platform, for a host with no place for the file. */
    public static final String ACCESS_FOLDERS = "JAWATA_ACCESS_FOLDERS";

    /** The variable naming the folder of the editor windows' lists (jawata-studio {@code EDITOR_FOLDERS_DIR_ENV}, jawata-hook {@code local.rs}). */
    public static final String EDITOR_FOLDERS_DIR = "JAWATA_EDITOR_FOLDERS_DIR";

    /** The file's name, beside the folder of the windows' lists. */
    static final String ACCESS_FILE_NAME = "engine-access.txt";

    /** The loaded projects' folders as they stand now, without the windows'; null when no access check is installed. */
    private static volatile Supplier<List<Path>> projectFolders;

    /** The folder of the editor windows' lists; null when there is none. */
    private static volatile Path editorLists;

    /** What the file holds, so it is rewritten only when the folders changed. */
    private static String written;

    private BrainAccess() {
    }

    /**
     * Publishes how the engine reads the loaded projects' folders now and where the editor windows' lists are; nulls withdraw them, which
     * the engine does when it stops, so nothing answers for a workspace that is gone.
     */
    public static void publish(Supplier<List<Path>> projectFoldersNow, Path editorListsFolder) {
        synchronized (BrainAccess.class) {
            projectFolders = projectFoldersNow;
            editorLists = editorListsFolder;
            written = null;
        }
    }

    /**
     * Rewrites the file of the projects' folders when they changed; the engine's access check hands it each reading it makes, so a project
     * loaded or unloaded while a brain runs reaches that brain's guard at the next call.
     */
    public static void refresh(List<Path> folders) {
        Path file = accessFile();
        if (file != null) {
            write(file, folders);
        }
    }

    /**
     * What a brain the engine starts is told: where the projects' folders and the windows' lists are read; none when no access check is
     * installed. A reading that fails tells the brain no folder rather than the wrong ones.
     */
    public static Map<String, String> environment() {
        Map<String, String> told = new LinkedHashMap<>();
        Supplier<List<Path>> reading = projectFolders;
        if (reading == null) {
            return told;
        }
        List<Path> folders;
        try {
            folders = reading.get();
        } catch (RuntimeException unreadable) {
            folders = List.of();
        }
        Path file = accessFile();
        if (file != null && write(file, folders)) {
            told.put(ACCESS_FOLDERS_FILE, file.toString());
        } else if (!folders.isEmpty()) {
            told.put(ACCESS_FOLDERS, String.join(File.pathSeparator, folders.stream().map(Path::toString).toList()));
        }
        Path lists = editorLists;
        if (lists != null) {
            told.put(EDITOR_FOLDERS_DIR, lists.toString());
        }
        return told;
    }

    private static Path accessFile() {
        Path lists = editorLists;
        return lists == null ? null : lists.resolveSibling(ACCESS_FILE_NAME);
    }

    /** Writes the folders, one to a line, replacing the file in one step so a reader never sees half of it; false when it cannot be written. */
    private static synchronized boolean write(Path file, List<Path> folders) {
        String text = String.join("\n", folders.stream().map(Path::toString).toList());
        if (text.equals(written) && Files.exists(file)) {
            return true;
        }
        try {
            Files.createDirectories(file.getParent());
            Path partial = file.resolveSibling(ACCESS_FILE_NAME + ".partial");
            Files.writeString(partial, text, StandardCharsets.UTF_8);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            written = text;
            return true;
        } catch (IOException | RuntimeException unwritable) {
            return false;
        }
    }
}
