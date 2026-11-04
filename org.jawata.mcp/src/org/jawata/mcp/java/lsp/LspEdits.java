package org.jawata.mcp.java.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the language server's editing requests share (Sprint 28h, S16e d and S16f 2): which document the editor has open and whether it has
 * unsaved text, a change to a text as a protocol edit, a staged change of the engine as protocol edits, and the check that refuses a file
 * with unsaved editor text. One instance is built by the server and handed to every request family that edits.
 */
final class LspEdits {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<String, ICompilationUnit> unitOf;
    private final Predicate<String> unsaved;

    /**
     * @param unitOf  the unit a request reads for a document: its working copy when open, else the saved unit; null for a file the model
     *                does not hold
     * @param unsaved whether the document has text in the editor that is not saved
     */
    LspEdits(Function<String, ICompilationUnit> unitOf, Predicate<String> unsaved) {
        this.unitOf = unitOf;
        this.unsaved = unsaved;
    }

    /** The unit a request reads for the document; null for a file the model does not hold. */
    ICompilationUnit unit(String uri) {
        return unitOf.apply(uri);
    }

    /** Whether the editor holds text for the document that is not saved. */
    boolean hasUnsavedText(String uri) {
        return unsaved.test(uri);
    }

    void add(List<Object> out, String text, JavaRanges.Lines lines, int offset, int length, String newText) {
        if (text.startsWith(newText, offset) && newText.length() == length) {
            return;
        }
        JavaRanges.Range span = lines.span(offset, length);
        out.add(Map.of("range", span.toProtocol(), "newText", newText));
    }

    Map<String, Object> rangeOf(JsonNode edit) {
        return new JavaRanges.Range(edit.path("line").asInt(), edit.path("column").asInt(), edit.path("endLine").asInt(), edit.path("endColumn").asInt())
            .toProtocol();
    }

    /** Refuses a change to a file the editor holds unsaved text for, naming the file, because the positions answered are in the saved text. */
    void refuseIfUnsaved(String uri, Path file, String what) {
        if (unsaved.test(uri)) {
            throw new IllegalStateException("The " + what + " changes " + file.getFileName() + ", which has unsaved text in the editor: "
                + "save or close it first. Nothing was changed.");
        }
    }

    /** Adds the staged edits of a change to the edits answered, refusing when one of the files has unsaved editor text. */
    void addStaged(ToolResponse staged, Map<String, List<Object>> changes, String what) {
        for (JsonNode file : JSON.valueToTree(staged.getData()).path("edits")) {
            Path path = Path.of(file.path("filePath").asText());
            String uri = path.toUri().toString();
            refuseIfUnsaved(uri, path, what);
            List<Object> edits = changes.computeIfAbsent(uri, key -> new ArrayList<>());
            for (JsonNode edit : file.path("edits")) {
                edits.add(Map.of("range", rangeOf(edit), "newText", edit.path("newText").asText()));
            }
        }
    }
}
