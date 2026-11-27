package org.jawata.mcp.tools;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jawata.mcp.access.AccessCheck;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reading and searching any file, and writing a document (Sprint 28h, D57 (j)). The principal changes no code, so its brain's own editors and
 * shell are withheld; what it still needs to look at the work and to write a plan or a sprint document comes from here. It reads every file,
 * searches every file under a folder, writes a {@code .md} file and refuses to write any other, so a document is the one thing a seat that
 * carries only this can change.
 */
public final class FilesTool implements Tool {

    /** The folders a search does not go into: version control's own and installed dependencies, which are not the work. */
    private static final Set<String> NOT_SEARCHED = Set.of(".git", "node_modules");

    /** A file larger than this is not searched: it is a build output or data, not text a person wrote. */
    private static final long LARGEST_SEARCHED = 2_000_000;

    private static final int DEFAULT_LINES = 2000;
    private static final int DEFAULT_MATCHES = 200;

    @Override
    public String getName() {
        return "files";
    }

    @Override
    public String getDescription() {
        return """
            Read any file, search a folder, and write or edit text documents (.md, .csv, .txt, .html ...); never code
            or configuration. Paths are absolute.

            - read: path; offset (0-based), limit.
            - search: folder, pattern (Java regex); glob, maxResults. Result lines are 1-based.
            - write: path, content (replaces the file).
            - edit: path, oldText (must occur exactly once), newText.
            - append_row: path, prefix, content with {row}: adds a plan row numbered one above the highest (zN).
              Use it instead of numbering by hand.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of("read", "search", "write", "edit", "append_row"), "description", "What to do."));
        properties.put("prefix", Map.of("type", "string", "description", "append_row: the letters the rows are numbered with, such as z."));
        properties.put("oldText", Map.of("type", "string", "description", "edit: exact passage, occurring once."));
        properties.put("newText", Map.of("type", "string", "description", "edit: replacement."));
        properties.put("path", Map.of("type", "string", "description", "Absolute file path."));
        properties.put("offset", Map.of("type", "integer", "description", "read: first line, 0-based."));
        properties.put("limit", Map.of("type", "integer", "description", "read: max lines (default 2000)."));
        properties.put("folder", Map.of("type", "string", "description", "search: absolute folder."));
        properties.put("pattern", Map.of("type", "string", "description", "search: Java regex."));
        properties.put("glob", Map.of("type", "string", "description", "search: e.g. *.java."));
        properties.put("maxResults", Map.of("type", "integer", "description", "search: default 200."));
        properties.put("content", Map.of("type", "string", "description", "write: whole text; append_row: the row."));
        return Map.of("type", "object", "properties", properties, "required", List.of("action"));
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        String action = arguments.path("action").asText("");
        String named = "search".equals(action) ? "folder" : "path";
        String place = arguments.path(named).asText("");
        // The access check judges a relative path in a workspace folder, and this tool would open it in the engine's own folder.
        if (!place.isBlank() && !Path.of(place).isAbsolute()) {
            return ToolResponse.invalidParameter(named, "'" + place + "' is not an absolute path; give the whole path from the root");
        }
        try {
            return switch (action) {
                case "read" -> read(arguments);
                case "search" -> search(arguments);
                case "write" -> write(arguments);
                case "edit" -> edit(arguments);
                case "append_row" -> appendRow(arguments);
                default -> ToolResponse.invalidParameter("action", "action must be read, search, write, edit or append_row, not '" + action + "'");
            };
        } catch (IOException e) {
            return ToolResponse.error("IO_ERROR", e.getMessage(), "Check the path and that the file can be read or written.");
        }
    }

    private static ToolResponse read(JsonNode arguments) throws IOException {
        String given = arguments.path("path").asText("");
        if (given.isBlank()) {
            return ToolResponse.invalidParameter("path", "read needs the file's path");
        }
        Path path = Path.of(given);
        if (!Files.isRegularFile(path)) {
            return Files.exists(path)
                ? ToolResponse.invalidParameter("path", "'" + given + "' is not a file; read needs a file")
                : ToolResponse.error(org.jawata.mcp.models.ErrorInfo.fileNotFound(given));
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int offset = Math.max(0, arguments.path("offset").asInt(0));
        int limit = Math.max(1, arguments.path("limit").asInt(DEFAULT_LINES));
        int end = Math.min(lines.size(), offset + limit);
        boolean whole = offset == 0 && end == lines.size();
        // the whole file is answered byte for byte, so a document read and written again does not gain or lose its last line break
        String content = whole ? Files.readString(path, StandardCharsets.UTF_8)
            : String.join("\n", lines.subList(Math.min(offset, lines.size()), end)) + (end > offset ? "\n" : "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path.toString());
        data.put("content", content);
        data.put("totalLines", lines.size());
        data.put("firstLine", offset);
        data.put("truncated", !whole);
        return ToolResponse.success(data);
    }

    private static ToolResponse search(JsonNode arguments) throws IOException {
        String folderGiven = arguments.path("folder").asText("");
        String patternGiven = arguments.path("pattern").asText("");
        if (folderGiven.isBlank() || patternGiven.isEmpty()) {
            return ToolResponse.invalidParameter(folderGiven.isBlank() ? "folder" : "pattern", "search needs a folder and a pattern");
        }
        Path folder = Path.of(folderGiven);
        if (!Files.isDirectory(folder)) {
            return Files.exists(folder)
                ? ToolResponse.invalidParameter("folder", "'" + folderGiven + "' is not a folder; search needs a folder")
                : ToolResponse.error(org.jawata.mcp.models.ErrorInfo.fileNotFound(folderGiven));
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternGiven);
        } catch (PatternSyntaxException e) {
            return ToolResponse.invalidParameter("pattern", "not a Java regular expression: " + e.getDescription());
        }
        String glob = arguments.path("glob").asText("");
        var matcher = glob.isBlank() ? null : folder.getFileSystem().getPathMatcher("glob:" + glob);
        int max = Math.max(1, arguments.path("maxResults").asInt(DEFAULT_MATCHES));
        List<Map<String, Object>> matches = new ArrayList<>();
        boolean[] truncated = {false};
        int[] leftOut = {0};
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return !dir.equals(folder) && NOT_SEARCHED.contains(String.valueOf(dir.getFileName())) ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile() || attributes.size() > LARGEST_SEARCHED || matcher != null && !matcher.matches(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                // 28h z70: a file the access policy does not let this call open is not opened, and the answer says how many there were.
                if (!AccessCheck.openable().test(file)) {
                    leftOut[0]++;
                    return FileVisitResult.CONTINUE;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (MalformedInputException e) {
                    return FileVisitResult.CONTINUE; // not text
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE;
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (pattern.matcher(lines.get(i)).find()) {
                        if (matches.size() == max) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        Map<String, Object> hit = new LinkedHashMap<>();
                        hit.put("path", file.toString());
                        hit.put("line", i + 1);
                        hit.put("text", lines.get(i));
                        matches.add(hit);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matches", matches);
        data.put("truncated", truncated[0]);
        data.put("leftOut", leftOut[0]);
        return ToolResponse.success(data);
    }

    private static ToolResponse write(JsonNode arguments) throws IOException {
        String given = arguments.path("path").asText("");
        if (given.isBlank() || !arguments.has("content")) {
            return ToolResponse.invalidParameter(given.isBlank() ? "path" : "content", "write needs the document's path and its content");
        }
        // The instruction refusal is asked first: such a file is no text document either, and its own reason is the one that applies.
        if (org.jawata.mcp.execution.OwnWrites.tellsAnAgentWhatItMayDo(given)) {
            return ToolResponse.invalidParameter("path", given + " tells an agent what it may do (its instructions, a seat or a skill), and a document"
                + " writer that could change it could widen what a hire may do: the person changes it, or a seat that changes code does", "NOT_A_DOCUMENT");
        }
        if (!org.jawata.mcp.execution.OwnWrites.isTextDocument(given)) {
            return ToolResponse.invalidParameter("path", "only a text document (.md, .csv, .txt, .html and the like) is written here, not " + given
                + ": code and every other file are changed by a seat that changes code, through a hire", "NOT_A_DOCUMENT");
        }
        // Written at the place that was judged, and a link at its end is not followed: a link the check did not see leads nowhere it did not judge.
        Path path = org.jawata.mcp.execution.OwnWrites.placeOf(given);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String content = arguments.get("content").asText();
        Files.writeString(path, content, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path.toString());
        data.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
        return ToolResponse.success(data);
    }

    /**
     * Replaces one exact passage of an existing document (Stage 27 (q)): the document is read, the passage must occur exactly once, and only
     * then is it written back. Held to the writer's refusals, so a plan too long to retype can still be changed by the seat whose own it is.
     */
    private static ToolResponse edit(JsonNode arguments) throws IOException {
        String given = arguments.path("path").asText("");
        String oldText = arguments.path("oldText").asText("");
        if (given.isBlank() || oldText.isEmpty() || !arguments.has("newText")) {
            return ToolResponse.invalidParameter(given.isBlank() ? "path" : oldText.isEmpty() ? "oldText" : "newText",
                "edit needs the document's path, the passage to replace (not empty) and what replaces it");
        }
        if (org.jawata.mcp.execution.OwnWrites.tellsAnAgentWhatItMayDo(given)) {
            return ToolResponse.invalidParameter("path", given + " tells an agent what it may do (its instructions, a seat or a skill), and a document"
                + " editor that could change it could widen what a hire may do: the person changes it, or a seat that changes code does", "NOT_A_DOCUMENT");
        }
        if (!org.jawata.mcp.execution.OwnWrites.isTextDocument(given)) {
            return ToolResponse.invalidParameter("path", "only a text document (.md, .csv, .txt, .html and the like) is edited here, not " + given
                + ": code and every other file are changed by a seat that changes code, through a hire", "NOT_A_DOCUMENT");
        }
        Path path = org.jawata.mcp.execution.OwnWrites.placeOf(given);
        if (!Files.isRegularFile(path)) {
            return ToolResponse.invalidParameter("path", given + " is not a document that exists: edit changes one, it does not make one (write does)", "FILE_NOT_FOUND");
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        String newText = arguments.get("newText").asText();
        // 28h z126: a passage written with plain line breaks is found in a document with Windows ones, which the edit keeps.
        if (text.indexOf(oldText) < 0 && text.contains("\r\n") && oldText.indexOf('\r') < 0) {
            oldText = oldText.replace("\n", "\r\n");
            newText = newText.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        int first = text.indexOf(oldText);
        if (first < 0) {
            return ToolResponse.invalidParameter("oldText", "the passage occurs nowhere in " + given + ", so nothing was changed. "
                + whereItParts(text, oldText), "PASSAGE_NOT_FOUND");
        }
        if (text.indexOf(oldText, first + 1) >= 0) {
            return ToolResponse.invalidParameter("oldText", "the passage occurs more than once in " + given + ", so nothing was changed: name more of the text around it so it occurs once",
                "PASSAGE_NOT_UNIQUE");
        }
        String edited = text.substring(0, first) + newText + text.substring(first + oldText.length());
        Files.writeString(path, edited, StandardCharsets.UTF_8);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path.toString());
        data.put("replaced", 1);
        data.put("bytes", edited.getBytes(StandardCharsets.UTF_8).length);
        return ToolResponse.success(data);
    }

    /**
     * Appends a numbered row to a plan (28h z127): the number is read and the row written while no other append runs, so two writers
     * never give out the same one. It is held to the writer's refusals, as an edit is.
     */
    private static ToolResponse appendRow(JsonNode arguments) throws IOException {
        String given = arguments.path("path").asText("");
        String prefix = arguments.path("prefix").asText("");
        String content = arguments.path("content").asText("");
        if (given.isBlank() || !prefix.matches("[A-Za-z]+") || !content.contains("{row}")) {
            return ToolResponse.invalidParameter(given.isBlank() ? "path" : !prefix.matches("[A-Za-z]+") ? "prefix" : "content",
                "append_row needs the plan's path, the letters its rows are numbered with, and the row with {row} where its number goes");
        }
        if (org.jawata.mcp.execution.OwnWrites.tellsAnAgentWhatItMayDo(given) || !org.jawata.mcp.execution.OwnWrites.isTextDocument(given)) {
            return ToolResponse.invalidParameter("path", "only a text document that tells no agent what it may do is appended to, not " + given,
                "NOT_A_DOCUMENT");
        }
        Path path = org.jawata.mcp.execution.OwnWrites.placeOf(given);
        if (!Files.isRegularFile(path)) {
            return ToolResponse.invalidParameter("path", given + " is not a document that exists", "FILE_NOT_FOUND");
        }
        synchronized (FilesTool.class) {
            List<String> lines = new ArrayList<>(List.of(Files.readString(path, StandardCharsets.UTF_8).split("\n", -1)));
            Pattern numbered = Pattern.compile("\\(" + prefix + "(\\d+)\\)");
            Pattern row = Pattern.compile("^- \\*\\*\\(" + prefix + "\\d+\\)");
            int highest = 0;
            int after = -1;
            for (int i = 0; i < lines.size(); i++) {
                Matcher found = numbered.matcher(lines.get(i));
                while (found.find()) {
                    highest = Math.max(highest, Integer.parseInt(found.group(1)));
                }
                if (row.matcher(lines.get(i)).find()) {
                    after = i;
                }
            }
            String number = prefix + (highest + 1);
            // After the last row and the indented lines that continue it; at the end where there is no row yet.
            int at = after < 0 ? lines.size() - (lines.get(lines.size() - 1).isEmpty() ? 1 : 0) : after + 1;
            while (after >= 0 && at < lines.size() && lines.get(at).startsWith("  ")) {
                at++;
            }
            lines.add(at, content.replace("{row}", number));
            Files.writeString(path, String.join("\n", lines), StandardCharsets.UTF_8);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", path.toString());
            data.put("row", number);
            data.put("line", at + 1);
            return ToolResponse.success(data);
        }
    }

    /** Where a passage the document does not hold parts from the nearest text it does: the line, and what each has next. */
    private static String whereItParts(String text, String passage) {
        int low = 0;
        int high = passage.length() - 1;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (text.contains(passage.substring(0, middle))) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        if (low == 0) {
            return "Not even its first character occurs there.";
        }
        int at = text.indexOf(passage.substring(0, low));
        int start = text.lastIndexOf('\n', at) + 1;
        int end = text.indexOf('\n', at);
        long line = 1 + text.substring(0, at).chars().filter(c -> c == '\n').count();
        return "Nearest: line " + line + " reads \"" + text.substring(start, end < 0 ? text.length() : end).strip() + "\". They agree for "
            + low + " character(s); then the document has " + shown(text, at + low) + " where the passage has " + shown(passage, low) + ".";
    }

    /** The few characters at a place, with the ones that cannot be seen written out. */
    private static String shown(String text, int at) {
        if (at >= text.length()) {
            return "nothing more";
        }
        return "\"" + text.substring(at, Math.min(text.length(), at + 12)).replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
