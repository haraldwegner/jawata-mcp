package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.InsertEdit;
import org.jawata.core.BufferStore;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.resident.workspace.BufferGuardedWrites;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.spi.host.WorkspaceWrites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 28h S16a: the engine never writes over text a person has typed and not saved. A change over a file with unsaved editor text is refused
 * by name and changes nothing; a change over a file the editor has open and saved lands, and the engine's open copy follows the disk;
 * a file nobody has open is written as before. The fixture is {@code Calculator.java} of simple-maven, and the change inserts one line
 * at the top.
 */
class BufferGuardedWritesTest {

    private static final String LINE = "// guarded\n";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path calculator;
    private String saved;

    private void load() throws Exception {
        service = helper.loadProject("simple-maven");
        calculator = helper.getFixturePath("simple-maven").resolve("src/main/java/com/example/Calculator.java");
        saved = Files.readString(calculator);
    }

    private BufferGuardedWrites guarded() {
        return new BufferGuardedWrites(() -> service, WorkspaceWrites.DIRECT);
    }

    private ICompilationUnit unit() {
        return service.getCompilationUnit(calculator).getPrimary();
    }

    private BufferStore store() {
        return service.buffers().orElseThrow();
    }

    private Change insertAtTop() throws CoreException {
        TextFileChange change = new TextFileChange("top", (IFile) unit().getResource());
        change.setEdit(new InsertEdit(0, LINE));
        change.initializeValidationData(new NullProgressMonitor()); // a refactoring's change arrives initialised
        return change;
    }

    @Test
    @DisplayName("a change over a file with unsaved editor text is refused by name, and neither the disk nor the editor's text is touched")
    void unsavedTextIsNeverWrittenOver() throws Exception {
        load();
        String unsaved = "// typed, not saved\n" + saved;
        store().open(unit(), unsaved);

        CoreException refused = assertThrows(CoreException.class, () -> guarded().perform(insertAtTop(), new NullProgressMonitor()));

        assertTrue(refused.getMessage().contains(BufferGuardedWrites.UNSAVED_IN_EDITOR), refused.getMessage());
        assertEquals(saved, Files.readString(calculator), "nothing was written");
        assertEquals(unsaved, store().view(unit()).orElseThrow().getSource(), "the person's text is as they left it");
        store().close(unit());
    }

    @Test
    @DisplayName("a change over a file the editor has open and saved lands on disk, and the engine's open copy follows it")
    void anOpenSavedFileIsWrittenAndTheCopyFollows() throws Exception {
        load();
        store().open(unit(), saved);

        guarded().perform(insertAtTop(), new NullProgressMonitor());

        String onDisk = Files.readString(calculator);
        assertTrue(onDisk.startsWith(LINE), "the change landed");
        assertEquals(onDisk, store().view(unit()).orElseThrow().getSource(), "the next tool call reads what was written");
        store().close(unit());
    }

    @Test
    @DisplayName("a file nobody has open is written as before, and the undo the write hands back restores it")
    void aFileNobodyHasOpenIsWrittenAsBefore() throws Exception {
        load();

        WorkspaceWrites.Applied applied = guarded().perform(insertAtTop(), new NullProgressMonitor());
        assertTrue(Files.readString(calculator).startsWith(LINE));

        guarded().perform(applied.undo(), new NullProgressMonitor());
        assertEquals(saved, Files.readString(calculator), "the undo put the world back");
    }
}
