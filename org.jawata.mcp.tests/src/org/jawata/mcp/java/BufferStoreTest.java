package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.BufferStore;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.GetDocumentSymbolsTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.resident.workspace.OwnedReconciler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28h S16a: the text an editor has open and has not saved is what every reader of the model sees, shared between everyone who has
 * the file open, and left alone by the disk sync that runs before every tool call. The fixture is {@code Calculator.java} of
 * simple-maven; the unsaved text adds a method, {@code negate}, that the file on disk does not have.
 */
class BufferStoreTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    private JdtServiceImpl service;
    private Path calculator;
    private String saved;
    private String unsaved;

    private void load() throws Exception {
        service = helper.loadProject("simple-maven");
        calculator = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        saved = Files.readString(calculator);
        int lastBrace = saved.lastIndexOf('}');
        unsaved = saved.substring(0, lastBrace) + "    public int negate(int a, int b) { return a * b; }\n}\n";
    }

    private BufferStore store() {
        return service.buffers().orElseThrow();
    }

    /** The names of the members a reader of the file gets from the engine, as the tools answer them. */
    @SuppressWarnings("unchecked")
    private List<String> namesInFile() {
        ObjectNode arguments = JSON.createObjectNode().put("filePath", calculator.toString());
        ToolResponse response = new GetDocumentSymbolsTool(() -> service).execute(arguments);
        assertTrue(response.isSuccess(), String.valueOf(response));
        List<Map<String, Object>> symbols = (List<Map<String, Object>>) ((Map<String, Object>) response.getData()).get("symbols");
        List<String> names = new ArrayList<>();
        for (Map<String, Object> type : symbols) {
            for (Map<String, Object> child : (List<Map<String, Object>>) type.get("children")) {
                names.add((String) child.get("name"));
            }
        }
        return names;
    }

    @Test
    @DisplayName("a tool reading the file sees the open buffer's text, and the saved text again once the buffer is closed")
    void aToolSeesTheUnsavedText() throws Exception {
        load();
        ICompilationUnit unit = service.getCompilationUnit(calculator);
        assertFalse(namesInFile().contains("negate"), "the file on disk has no negate");

        store().open(unit, unsaved);
        assertTrue(namesInFile().contains("negate"), "the open buffer has it: " + namesInFile());

        store().close(unit);
        assertFalse(namesInFile().contains("negate"), "closed, the saved file is the text again");
    }

    @Test
    @DisplayName("a second open of an open file shares the one working copy and does not replace its text; the last close discards it")
    void aSecondOpenSharesTheBuffer() throws Exception {
        load();
        ICompilationUnit unit = service.getCompilationUnit(calculator);

        ICompilationUnit first = store().open(unit, unsaved);
        ICompilationUnit second = store().open(unit, saved);

        assertSame(first, second, "one working copy per file");
        assertEquals(2, store().holders(unit));
        assertTrue(namesInFile().contains("negate"), "the second holder's older text did not replace the first one's");

        store().close(unit);
        assertEquals(1, store().holders(unit));
        assertTrue(namesInFile().contains("negate"), "one holder is still there");

        store().close(unit);
        assertEquals(0, store().holders(unit));
        assertFalse(store().view(unit).isPresent());
        assertFalse(namesInFile().contains("negate"));
    }

    @Test
    @DisplayName("a change replaces the text for every holder, and a change to a file nobody opened is refused")
    void aChangeReplacesTheText() throws Exception {
        load();
        ICompilationUnit unit = service.getCompilationUnit(calculator);
        store().open(unit, saved);
        assertFalse(namesInFile().contains("negate"));

        store().change(unit, unsaved);

        assertTrue(namesInFile().contains("negate"));
        store().close(unit);
        assertThrows(IllegalStateException.class, () -> store().change(unit, unsaved));
    }

    @Test
    @DisplayName("the disk sync that runs before every tool call leaves an open working copy and its text alone")
    void theDiskSyncLeavesTheBufferAlone() throws Exception {
        load();
        ICompilationUnit unit = service.getCompilationUnit(calculator);
        store().open(unit, unsaved);

        new OwnedReconciler(() -> service).syncBeforeCall();

        assertTrue(store().view(unit).isPresent(), "the working copy is still open");
        assertTrue(namesInFile().contains("negate"), "and still holds the unsaved text");
        store().close(unit);
    }

    @Test
    @DisplayName("an open buffer is dirty exactly when its text differs from the saved file's")
    void dirtyMeansDifferentFromDisk() throws Exception {
        load();
        ICompilationUnit unit = service.getCompilationUnit(calculator);
        assertFalse(store().isDirty(unit), "a file nobody has open is not dirty");

        store().open(unit, saved);
        assertFalse(store().isDirty(unit), "opened with the text on disk");

        store().change(unit, unsaved);
        assertTrue(store().isDirty(unit));

        store().change(unit, saved);
        assertFalse(store().isDirty(unit), "edited back to what is on disk");
        store().close(unit);
    }
}
