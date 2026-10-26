package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.spi.host.WorkspaceWrites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What every host's writes promise the engine (Sprint 28k, Stage 8; contract §18 ring 1): a change handed to the host is performed, the
 * host hands back the change that undoes it, and performing that puts the world back. The change here is {@link PlainTextChange}, a file
 * of plain text, so no language is involved.
 */
public abstract class WorkspaceWritesContract {

    /**
     * @return the writes under test
     * @throws Exception when the implementation cannot be built
     */
    protected abstract WorkspaceWrites writes() throws Exception;

    @Test
    @DisplayName("a change is performed, and the host hands back the change that undoes it")
    void aChangeIsPerformedAndUndoable(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("notes.txt");

        WorkspaceWrites.Applied applied = writes().perform(new PlainTextChange(file, "first"), new NullProgressMonitor());

        assertEquals("first", Files.readString(file), "the change was performed");
        assertNotNull(applied.undo(), "a change that cannot be undone cannot be gated");
        assertNotNull(applied.validation());
        assertFalse(applied.validation().hasFatalError(), "a clean change validates clean: " + applied.validation());
    }

    @Test
    @DisplayName("performing the undo restores the earlier text, and a file the change created is gone")
    void theUndoPutsTheWorldBack(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("deeper").resolve("notes.txt");
        WorkspaceWrites writes = writes();

        WorkspaceWrites.Applied created = writes.perform(new PlainTextChange(file, "first"), new NullProgressMonitor());
        WorkspaceWrites.Applied rewritten = writes.perform(new PlainTextChange(file, "second"), new NullProgressMonitor());
        assertEquals("second", Files.readString(file));

        writes.perform(rewritten.undo(), new NullProgressMonitor());
        assertEquals("first", Files.readString(file), "undoing the rewrite gives back the earlier text");

        writes.perform(created.undo(), new NullProgressMonitor());
        assertFalse(Files.exists(file), "undoing the creation gives back a world without the file");
    }

    @Test
    @DisplayName("a host that has no objection to a set of files says so by naming nothing")
    void noTargetsNoRefusal() throws Exception {
        assertTrue(writes().refusal(List.of()).isEmpty(), "no file was named, so there is nothing to refuse");
    }
}
