package org.jawata.mcp.tools.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.InsertEdit;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.change.ChangeEngine;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.JavaLifecycle;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ApplyRefactoringTool;
import org.jawata.mcp.tools.UndoRefactoringTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.spi.host.WorkspaceWrites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, the 28k ledger row A18-2 (the double undo). A change the person keeps is one step on the host's undo history. When the engine
 * itself takes that change back, the take-back is not a step the person made, so the engine says so to the host: it performs the take-back
 * in its own write mode, {@code UNDO}, which a host with an undo history of its own keeps out of it. Before, the take-back went in as an
 * ordinary accepted change, so a host listed it as a second step beside the first, which no longer applied.
 */
class AnEnginesUndoIsNotAStepOnTheHostsHistoryTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** Writes that behave as the host-less default does and write down the mode each change was performed in. */
    private static final class Recording implements WorkspaceWrites {
        private final WorkspaceWrites inner;
        final List<WriteMode> modes = new CopyOnWriteArrayList<>();

        Recording(WorkspaceWrites inner) {
            this.inner = inner;
        }

        @Override
        public Applied perform(Change change, IProgressMonitor monitor) throws CoreException {
            return perform(change, WriteMode.ACCEPTED, monitor);
        }

        @Override
        public Applied perform(Change change, WriteMode mode, IProgressMonitor monitor) throws CoreException {
            modes.add(mode);
            return inner.perform(change, mode, monitor);
        }

        @Override
        public boolean keep(Applied applied, String name) {
            return inner.keep(applied, name);
        }

        @Override
        public Optional<String> refusal(Collection<IFile> targets) {
            return inner.refusal(targets);
        }
    }

    @Test
    @DisplayName("undo_refactoring performs the take-back in the UNDO mode, so a host keeps it off the person's undo history, and the file is restored")
    void theTakeBackIsPerformedInTheUndoMode() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        ChangeContext base = JavaLifecycle.changes(service).get();
        Recording writes = new Recording(base.writes());
        ChangeContext context = new ChangeContext(writes, base.reconciler(), base.verdict(), base.paths());
        RefactoringChangeCache cache = new RefactoringChangeCache();
        ApplyRefactoringTool apply = new ApplyRefactoringTool(() -> service, cache, () -> context);
        UndoRefactoringTool undo = new UndoRefactoringTool(() -> service, cache, () -> context);
        Path target = helper.getTempDirectory().resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
        String original = Files.readString(target);

        ICompilationUnit unit = service.getCompilationUnit(target);
        TextFileChange change = new TextFileChange("insert marker", (IFile) unit.getResource());
        change.setEdit(new InsertEdit(0, "// a-take-back-marker\n"));
        String changeId = cache.put(RefactoringChangeCache.Kind.STAGED, change, "insert marker", ChangeEngine.previewDiff(change, context),
            List.of(target.toString()));
        ObjectMapper mapper = new ObjectMapper();

        ToolResponse applied = apply.execute(mapper.createObjectNode().put("changeId", changeId));
        assertTrue(applied.isSuccess(), () -> String.valueOf(applied.getError()));
        int performedByTheApply = writes.modes.size();
        String undoId = (String) ((java.util.Map<?, ?>) applied.getData()).get("undoChangeId");

        ObjectNode arguments = mapper.createObjectNode().put("undoChangeId", undoId);
        ToolResponse undone = undo.execute(arguments);

        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(target), "the take-back restores the file");
        List<WorkspaceWrites.WriteMode> afterTheApply = writes.modes.subList(performedByTheApply, writes.modes.size());
        assertEquals(List.of(WorkspaceWrites.WriteMode.UNDO), afterTheApply, "the take-back was performed in some other mode: " + writes.modes);
    }

    @Test
    @DisplayName("CONTROL: the change the person keeps is not performed in the UNDO mode")
    void theChangeTheEngineKeepsIsNotATakeBack() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        ChangeContext base = JavaLifecycle.changes(service).get();
        Recording writes = new Recording(base.writes());
        ChangeContext context = new ChangeContext(writes, base.reconciler(), base.verdict(), base.paths());
        RefactoringChangeCache cache = new RefactoringChangeCache();
        ApplyRefactoringTool apply = new ApplyRefactoringTool(() -> service, cache, () -> context);
        Path target = helper.getTempDirectory().resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
        ICompilationUnit unit = service.getCompilationUnit(target);
        TextFileChange change = new TextFileChange("insert marker", (IFile) unit.getResource());
        change.setEdit(new InsertEdit(0, "// a-kept-change-marker\n"));
        String changeId = cache.put(RefactoringChangeCache.Kind.STAGED, change, "insert marker", ChangeEngine.previewDiff(change, context),
            List.of(target.toString()));

        ToolResponse applied = apply.execute(new ObjectMapper().createObjectNode().put("changeId", changeId));

        assertTrue(applied.isSuccess(), () -> String.valueOf(applied.getError()));
        assertFalse(writes.modes.contains(WorkspaceWrites.WriteMode.UNDO), "an applied change was performed as a take-back: " + writes.modes);
    }
}
