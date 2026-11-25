package org.jawata.mcp.access;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The places one tool call names (Sprint 28h, S13, D20, ACL7): the rule is about a PATH, not about a substring of whatever the call
 * carries. Prose that mentions a forbidden file (a commit message, a summary, a quoted sentence, the body of a here-document) names
 * nothing; a call that opens the file names it.
 *
 * <p>These carry a place. The arguments that are paths by their name ({@link #PATH_KEYS}, the lists in {@link #PATH_LIST_KEYS}, the seat
 * files in {@link #SEAT_FILE_KEYS}, a {@code classpath}, each of whose entries is one); the same names inside a {@code scope} object; and a
 * shell {@code command}, whose WORDS are read as a shell reads them, so a path counts when a whole word is one. A quoted sentence with
 * spaces in it is one word and not a path. Over-matching on a whole word is the safe direction: a word that is not really a path resolves
 * somewhere the policy does not allow and is refused with its name in the answer.</p>
 *
 * <p>Every argument a shipped tool publishes that is named like a place must be among these (28h z70: {@code folder} was not, and a search
 * read what a read refused); a test walks the published schemas and fails on one that is not.</p>
 */
final class ToolPaths {

    private static final Set<String> PATH_KEYS = Set.of("file_path", "filePath", "path", "notebook_path", "projectPath", "cwd",
        "workingDirectory", "directory", "dir", "file", "root", "folder", "boardFile", "logFile", "alarmFile", "hsErrFile", "corePath",
        "execFile", "classFile", "launcherPath", "iniPath", "workspaceArea", "configArea");
    private static final Set<String> PATH_LIST_KEYS = Set.of("paths", "files", "filePaths", "extraClasspath");
    private static final Set<String> SEAT_FILE_KEYS = Set.of("seatFile", "observerSeatFile");

    /**
     * What a call names.
     *
     * @param places     each place, as an absolute path
     * @param unresolved each relative path that no base could place; the caller must refuse these, a place nobody can locate is not
     *                   one the policy can allow
     * @param seatFiles  the places among them that the call names as a seat's file
     */
    record Named(List<Path> places, List<String> unresolved, Set<Path> seatFiles) {
    }

    private ToolPaths() {
    }

    /**
     * @param arguments the call's arguments
     * @param bases     where a relative path may be meant: each folder of the workspace the caller works in; every reading is returned
     * @param home      the person's home, for the {@code ~} and {@code $HOME} spellings, or null
     */
    static Named of(JsonNode arguments, List<Path> bases, Path home) {
        Named named = new Named(new ArrayList<>(), new ArrayList<>(), new LinkedHashSet<>());
        if (arguments != null && arguments.isObject()) {
            read(arguments, bases, home, named);
        }
        return named;
    }

    private static void read(JsonNode arguments, List<Path> bases, Path home, Named named) {
        List<Path> places = named.places();
        List<String> unresolved = named.unresolved();
        arguments.properties().forEach(field -> {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (PATH_KEYS.contains(key) && value.isTextual()) {
                place(value.asText(), bases, home, places, unresolved);
            } else if (SEAT_FILE_KEYS.contains(key) && value.isTextual()) {
                int before = places.size();
                place(value.asText(), bases, home, places, unresolved);
                named.seatFiles().addAll(places.subList(before, places.size()));
            } else if ("classpath".equals(key) && value.isTextual()) {
                for (String entry : value.asText().split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    if (!entry.isBlank()) {
                        place(entry, bases, home, places, unresolved);
                    }
                }
            } else if (PATH_LIST_KEYS.contains(key) && value.isArray()) {
                value.forEach(item -> {
                    if (item.isTextual()) {
                        place(item.asText(), bases, home, places, unresolved);
                    }
                });
            } else if ("scope".equals(key) && value.isObject()) {
                read(value, bases, home, named);
            } else if ("command".equals(key) && value.isTextual()) {
                for (String word : Shell.words(value.asText())) {
                    String candidate = pathInside(word);
                    if (candidate != null) {
                        place(candidate, bases, home, places, unresolved);
                    }
                }
            }
        });
    }

    /** The path a shell word is, or null when it is not one: absolute or home-relative, with no space in it, or {@code --flag=} then one. */
    private static String pathInside(String word) {
        String candidate = word;
        if (candidate.startsWith("-")) {
            int equals = candidate.indexOf('=');
            if (equals < 0) {
                return null;
            }
            candidate = candidate.substring(equals + 1);
        }
        if (candidate.isEmpty() || candidate.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        boolean windows = candidate.length() > 2 && Character.isLetter(candidate.charAt(0)) && candidate.charAt(1) == ':'
            && (candidate.charAt(2) == '\\' || candidate.charAt(2) == '/');
        boolean absolute = candidate.startsWith("/") || candidate.equals("~") || candidate.startsWith("~/")
            || candidate.equals("$HOME") || candidate.startsWith("$HOME/") || candidate.equals("${HOME}") || candidate.startsWith("${HOME}/");
        return absolute || windows ? candidate : null;
    }

    private static void place(String text, List<Path> bases, Path home, List<Path> places, List<String> unresolved) {
        String expanded = text;
        if (home != null) {
            String h = home.toString();
            if (expanded.equals("~") || expanded.equals("$HOME") || expanded.equals("${HOME}")) {
                expanded = h;
            } else {
                expanded = expanded.replace("${HOME}/", h + "/").replace("$HOME/", h + "/");
                if (expanded.startsWith("~/")) {
                    expanded = h + expanded.substring(1);
                }
            }
        }
        Path path;
        try {
            path = Path.of(expanded);
        } catch (InvalidPathException e) {
            unresolved.add(text);
            return;
        }
        if (path.isAbsolute()) {
            places.add(path);
        } else if (bases.isEmpty()) {
            unresolved.add(text);
        } else {
            for (Path base : bases) {
                places.add(base.resolve(path));
            }
        }
    }
}
