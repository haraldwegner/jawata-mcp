package org.jawata.mcp.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.InsertEdit;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.java.JavaChange;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 28k ledger C2b-19: {@link GatedApply} holds the only undo handle of the change it just applied. If anything throws between
 * applying and answering, the change used to stay on disk with nobody able to undo it. Now it is undone and the throw goes on.
 */
class AThrowAfterTheApplyUndoesTheChangeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** The Java verdict, except that making the model see the written files fails. */
    private static final class VerdictThatFailsToSettle implements BuildVerdict {
        private final BuildVerdict real;

        VerdictThatFailsToSettle(BuildVerdict real) {
            this.real = real;
        }

        @Override
        public Map<String, Set<SourceProblem>> problemsIn(List<String> files) {
            return real.problemsIn(files);
        }

        @Override
        public void settle(List<String> files) {
            throw new IllegalStateException("the model could not be settled");
        }

        @Override
        public Optional<String> describeCreated(String file) {
            return real.describeCreated(file);
        }

        @Override
        public BuildScope owned() {
            return real.owned();
        }

        @Override
        public List<Diagnostic> errors(BuildScope scope, IProgressMonitor monitor) {
            return real.errors(scope, monitor);
        }
    }

    @Test
    @DisplayName("a throw after the change was applied undoes the change, byte for byte, and is rethrown")
    void aThrowAfterTheApplyUndoesTheChange() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("compose-clean");
        ChangeContext real = JavaChange.contextFor(service);
        ChangeContext failing = new ChangeContext(real.writes(), real.reconciler(),
            new VerdictThatFailsToSettle(real.verdict()), real.paths());
        Path target = helper.getTempDirectory().resolve("compose-clean/src/main/java/com/example/ComposeMethodTargets.java");
        String original = Files.readString(target);
        IFile file = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(target.toUri())[0];
        TextFileChange change = new TextFileChange("put a comment first", file);
        change.setEdit(new InsertEdit(0, "// a change that gets applied before the check throws\n"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> GatedApply.perform(change, failing, GatedApply.Mode.UNDO));

        assertEquals("the model could not be settled", thrown.getMessage());
        assertEquals(original, Files.readString(target), "the applied change is undone: the file is byte-identical");
    }
}
