package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jawata.mcp.java.JavaRanges;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16a: the edits of a didChange applied to the text the server holds. Pure text, no engine. Positions are zero-based lines and
 * UTF-16 columns.
 */
class DocumentChangesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static DocumentChanges.Change edit(int line, int column, int endLine, int endColumn, String text) {
        return new DocumentChanges.Change(new JavaRanges.Range(line, column, endLine, endColumn), text);
    }

    private static String apply(String text, DocumentChanges.Change... changes) {
        return DocumentChanges.apply(text, List.of(changes));
    }

    @Test
    @DisplayName("an insert, a delete across a line break and a replace each change exactly the text their range names")
    void aRangeIsWhatChanges() {
        assertEquals("aXb\ncd", apply("ab\ncd", edit(0, 1, 0, 1, "X")), "insert");
        assertEquals("ad", apply("ab\ncd", edit(0, 1, 1, 1, "")), "delete across the line break");
        assertEquals("ab\nZ", apply("ab\ncd", edit(1, 0, 1, 2, "Z")), "replace");
    }

    @Test
    @DisplayName("several edits apply in order, each to the text the one before produced")
    void editsApplyInOrder() {
        assertEquals("12c", apply("abc", edit(0, 0, 0, 0, "12"), edit(0, 2, 0, 4, "")));
    }

    @Test
    @DisplayName("an edit with no range replaces the whole text, and a later ranged edit applies to that")
    void aChangeWithoutARangeIsTheWholeText() {
        assertEquals("new!", apply("old text", new DocumentChanges.Change(null, "new"), edit(0, 3, 0, 3, "!")));
    }

    @Test
    @DisplayName("a carriage return before a newline stays in its line, and a character outside the basic plane is two columns")
    void lineEndingsAndUtf16() {
        assertEquals("a\r\nc", apply("a\r\nb", edit(1, 0, 1, 1, "c")), "second line of a CRLF text");
        assertEquals("a😀X", apply("a😀b", edit(0, 3, 0, 4, "X")), "the b after a surrogate pair is column 3");
    }

    @Test
    @DisplayName("a position past the end of the text is the end, and a range whose end comes before its start is an insert at the start")
    void positionsOutsideTheTextAreMovedToItsEdges() {
        assertEquals("abc!", apply("abc", edit(5, 0, 9, 9, "!")));
        assertEquals("aXbc", apply("abc", edit(0, 1, 0, 0, "X")));
    }

    @Test
    @DisplayName("the contentChanges of a notification are read with and without a range, and anything that is not an array has no edits")
    void theProtocolShapeIsRead() throws Exception {
        List<DocumentChanges.Change> changes = DocumentChanges.parse(JSON.readTree(
            "[{\"range\":{\"start\":{\"line\":1,\"character\":2},\"end\":{\"line\":3,\"character\":4}},\"text\":\"x\"},{\"text\":\"whole\"}]"));

        assertEquals(2, changes.size());
        assertEquals(new JavaRanges.Range(1, 2, 3, 4), changes.get(0).range());
        assertEquals("x", changes.get(0).text());
        assertNull(changes.get(1).range());
        assertEquals("whole", changes.get(1).text());
        assertTrue(DocumentChanges.parse(JSON.readTree("{}")).isEmpty());
        assertTrue(DocumentChanges.parse(null).isEmpty());
    }
}
