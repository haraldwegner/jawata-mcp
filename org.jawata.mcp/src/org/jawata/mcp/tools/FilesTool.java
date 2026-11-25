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
            Read any file, search the files under a folder, and write or edit a text document (.md, .csv, .txt, .html and the like). Writes nothing else.

            USAGE: files(action="read",   path="/abs/file" [, offset=0] [, limit=2000])
                   files(action="search", folder="/abs/folder", pattern="<Java regex>" [, glob="*.java"] [, maxResults=200])
                   files(action="write",  path="/abs/notes/plan.md", content="...")
                   files(action="edit",   path="/abs/notes/plan.md", oldText="...", newText="...")

            edit replaces ONE exact passage of an existing document and leaves the rest as it was: oldText must occur exactly once, so a passage
            found twice or not at all is refused and nothing is written, and an empty one is refused. It answers {path, replaced: 1, bytes}.
            It is held to the same refusals as write (only a text document; never one that tells an agent what it may do) and does not make a file.

            read answers {path, content, totalLines, firstLine, truncated}: lines from offset (0-based), at most limit of them.
            search answers {matches: [{path, line, text}], truncated, leftOut}: line is 1-based; .git and node_modules are not searched, nor a
            file over 2 MB or one that is not text. A file the access policy does not let the call open is not searched, and leftOut counts them.
            write creates the folders it needs and replaces the file; a path that is not a text document (a picture, code, a build or
            configuration file) is REFUSED and nothing is written, because a seat that carries this tool and no editor changes documents and
            never code. The kinds are the ones the main seat and a lead may write, named once in the engine.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of("read", "search", "write", "edit"), "description", "What to do."));
        properties.put("oldText", Map.of("type", "string", "description", "edit: the exact passage to replace; it must occur exactly once in the document."));
        properties.put("newText", Map.of("type", "string", "description", "edit: what replaces it."));
        properties.put("path", Map.of("type", "string", "description", "read/write: the file, as an absolute path."));
        properties.put("offset", Map.of("type", "integer", "description", "read: the first line to answer, 0-based (default 0)."));
        properties.put("limit", Map.of("type", "integer", "description", "read: how many lines at most (default 2000)."));
        properties.put("folder", Map.of("type", "string", "description", "search: the folder searched with everything under it."));
        properties.put("pattern", Map.of("type", "string", "description", "search: a Java regular expression matched against each line."));
        properties.put("glob", Map.of("type", "string", "description", "search: only file names matching this glob, such as *.java."));
        properties.put("maxResults", Map.of("type", "integer", "description", "search: how many matching lines at most (default 200)."));
        properties.put("content", Map.of("type", "string", "description", "write: the document's whole text."));
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
                default -> ToolResponse.invalidParameter("action", "action must be read, search, write or edit, not '" + action + "'");
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
        int first = text.indexOf(oldText);
        if (first < 0) {
            return ToolResponse.invalidParameter("oldText", "the passage occurs nowhere in " + given + ", so nothing was changed", "PASSAGE_NOT_FOUND");
        }
        if (text.indexOf(oldText, first + 1) >= 0) {
            return ToolResponse.invalidParameter("oldText", "the passage occurs more than once in " + given + ", so nothing was changed: name more of the text around it so it occurs once",
                "PASSAGE_NOT_UNIQUE");
        }
        String edited = text.substring(0, first) + arguments.get("newText").asText() + text.substring(first + oldText.length());
        Files.writeString(path, edited, StandardCharsets.UTF_8);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path.toString());
        data.put("replaced", 1);
        data.put("bytes", edited.getBytes(StandardCharsets.UTF_8).length);
        return ToolResponse.success(data);
    }
}
