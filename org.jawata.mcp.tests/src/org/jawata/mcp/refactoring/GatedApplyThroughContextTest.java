package org.jawata.mcp.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.change.GatedApply;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.WorkspaceWrites;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;
import org.jawata.spi.lang.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 28k E7 (plan Stage 2, the test written first): the change gate asks its {@link ChangeContext} and nothing else — the errors
 * before and after come from the context's verdict, the change and its undo go through the context's writes. Every port here is a
 * fake, so no Java model is consulted; with the gate wired to anything but the context, the fake's error is never seen.
 */
class GatedApplyThroughContextTest {

    /** One file, named by the fake paths; it has no errors before the change and one after. */
    private static final String FILE = "A.java";

    @Test
    @DisplayName("an error the verdict reports after the change is introduced, and the change is undone through the context's writes")
    void introducedErrorIsReportedAndUndoneThroughTheContext() {
        RecordingWrites writes = new RecordingWrites();
        ErrorAfterChange verdict = new ErrorAfterChange();
        Change change = new MarkerChange("the change");

        GatedApply.Result result = GatedApply.perform(change, new ChangeContext(writes, null, verdict, new FixedPaths()),
            GatedApply.Mode.UNDO);

        assertTrue(result.refused(), "an introduced error refuses the change: " + result.failure());
        assertEquals(List.of(FILE + ": the change broke this"), result.introduced());
        assertTrue(result.undone(), "the undo went through: " + result.failure());
        assertEquals(List.of("the change", "the undo"), writes.performed, "the change, then its undo, both through the context");
        assertEquals(List.of(List.of(FILE)), verdict.settled, "the model was settled over the changed file before the second look");
    }

    @Test
    @DisplayName("a change the verdict finds nothing new in goes through and stays")
    void aCleanChangeStays() {
        RecordingWrites writes = new RecordingWrites();
        BuildVerdict clean = new ErrorAfterChange() {
            @Override
            public Map<String, Set<SourceProblem>> problemsIn(List<String> files) {
                return Map.of(FILE, Set.of());
            }
        };

        GatedApply.Result result = GatedApply.perform(new MarkerChange("the change"),
            new ChangeContext(writes, null, clean, new FixedPaths()), GatedApply.Mode.UNDO);

        assertTrue(!result.refused(), "nothing introduced, nothing refused: " + result.failure());
        assertEquals(List.of("the change"), writes.performed, "no undo when nothing broke");
        assertEquals(List.of(FILE), result.outcome().modifiedFilePaths());
    }

    /** Writes that perform nothing and record what they were asked to perform; the undo of a change is named "the undo". */
    private static final class RecordingWrites implements WorkspaceWrites {
        final List<String> performed = new ArrayList<>();

        @Override
        public Applied perform(Change change, IProgressMonitor monitor) {
            performed.add(change.getName());
            return new Applied(change.getName().equals("the undo") ? null : new MarkerChange("the undo"), new RefactoringStatus());
        }
    }

    /** The file has no errors on the first look and one on every later look. */
    private static class ErrorAfterChange implements BuildVerdict {
        final List<List<String>> settled = new ArrayList<>();
        private int looks;

        @Override
        public Map<String, Set<SourceProblem>> problemsIn(List<String> files) {
            return looks++ == 0 ? Map.of(FILE, Set.of())
                : Map.of(FILE, Set.of(new SourceProblem("the change broke this", false)));
        }

        @Override
        public void settle(List<String> files) {
            settled.add(List.copyOf(files));
        }

        @Override
        public Optional<String> describeCreated(String file) {
            return Optional.empty();
        }

        @Override
        public BuildScope owned() {
            return new BuildScope(List.of());
        }

        @Override
        public List<Diagnostic> errors(BuildScope scope, IProgressMonitor monitor) {
            return List.of();
        }
    }

    /** Names every element as {@link #FILE}: the marker change's element is the file the change touches. */
    private static final class FixedPaths implements ChangePaths {
        @Override
        public String display(Path absolute) {
            return FILE;
        }

        @Override
        public Optional<IFile> fileOf(Object modifiedElement) {
            return modifiedElement == MarkerChange.ELEMENT
                ? Optional.of(ResourcesPlugin.getWorkspace().getRoot().getFile(new org.eclipse.core.runtime.Path("/e7/" + FILE)))
                : Optional.empty();
        }
    }

    /** A change that does nothing itself (the fake writes never run it) and names one element of its own. */
    private static final class MarkerChange extends Change {
        static final Object ELEMENT = new Object();
        private final String name;

        MarkerChange(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void initializeValidationData(IProgressMonitor pm) {
        }

        @Override
        public RefactoringStatus isValid(IProgressMonitor pm) {
            return new RefactoringStatus();
        }

        @Override
        public Change perform(IProgressMonitor pm) {
            return null;
        }

        @Override
        public Object getModifiedElement() {
            return ELEMENT;
        }
    }
}
