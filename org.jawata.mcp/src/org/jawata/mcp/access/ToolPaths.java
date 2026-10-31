package org.jawata.mcp.access;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The places one tool call names (Sprint 28h, S13, D20, ACL7): the rule is about a PATH, not about a substring of whatever the call
 * carries. Prose that mentions a forbidden file (a commit message, a summary, a quoted sentence, the body of a here-document) names
 * nothing; a call that opens the file names it.
 *
 * <p>Two things carry a place. The arguments that are paths by their name ({@link #PATH_KEYS}, and the lists in {@link #PATH_LIST_KEYS});
 * and a shell {@code command}, whose WORDS are read as a shell reads them, so a path counts when a whole word is one. A quoted
 * sentence with spaces in it is one word and not a path. Over-matching on a whole word is the safe direction: a word that is not
 * really a path resolves somewhere the policy does not allow and is refused with its name in the answer.</p>
 */
final class ToolPaths {

    private static final Set<String> PATH_KEYS = Set.of("file_path", "filePath", "path", "notebook_path", "projectPath", "cwd",
        "workingDirectory", "directory", "dir", "file", "root");
    private static final Set<String> PATH_LIST_KEYS = Set.of("paths", "files");

    /**
     * What a call names.
     *
     * @param places     each place, as an absolute path
     * @param unresolved each relative path that no base could place; the caller must refuse these, a place nobody can locate is not
     *                   one the policy can allow
     */
    record Named(List<Path> places, List<String> unresolved) {
    }

    private ToolPaths() {
    }

    /**
     * @param arguments the call's arguments
     * @param bases     where a relative path may be meant: each folder of the workspace the caller works in; every reading is returned
     * @param home      the person's home, for the {@code ~} and {@code $HOME} spellings, or null
     */
    static Named of(JsonNode arguments, List<Path> bases, Path home) {
        List<Path> places = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        if (arguments == null || !arguments.isObject()) {
            return new Named(places, unresolved);
        }
        arguments.properties().forEach(field -> {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (PATH_KEYS.contains(key) && value.isTextual()) {
                place(value.asText(), bases, home, places, unresolved);
            } else if (PATH_LIST_KEYS.contains(key) && value.isArray()) {
                value.forEach(item -> {
                    if (item.isTextual()) {
                        place(item.asText(), bases, home, places, unresolved);
                    }
                });
            } else if ("command".equals(key) && value.isTextual()) {
                for (String word : Shell.words(value.asText())) {
                    String candidate = pathInside(word);
                    if (candidate != null) {
                        place(candidate, bases, home, places, unresolved);
                    }
                }
            }
        });
        return new Named(places, unresolved);
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
