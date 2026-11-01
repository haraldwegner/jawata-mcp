package org.jawata.mcp.change;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.MultiTextEdit;
import org.jawata.spi.host.WorkspaceWrites;
import org.jawata.spi.lang.ChangePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 14b — the shared perform/preview engine behind the refactoring
 * apply contract. Tools build a {@link Change}; this class previews its diff
 * (without applying), performs it (capturing the undo-Change), and reports
 * the touched files.
 *
 * <p>JDT-LTK supplies the heavy machinery; this is orchestration only.</p>
 */
public final class ChangeEngine {

    private static final Logger log = LoggerFactory.getLogger(ChangeEngine.class);

    private ChangeEngine() {}

    /**
     * Result of {@link #perform}. {@code validationError} is non-null when
     * LTK rejected the change — nothing was modified in that case and
     * {@code undoChange} is null.
     */
    public record ApplyOutcome(
        Change undoChange,
        List<String> modifiedFilePaths,
        String validationError
    ) {}

    /**
     * Render a unified diff of what the change WOULD do, without applying.
     * Covers every {@link TextChange} in the tree via
     * {@code getCurrentContent} / {@code getPreviewContent}; non-text changes
     * (file moves, renames) contribute no diff text — they still surface in
     * the modified-file list after apply.
     */
    public static String previewDiff(Change change, org.jawata.mcp.change.ChangeContext context) {
        List<DiffRenderer.FileDiff> diffs = new ArrayList<>();
        collectTextDiffs(change, context.paths(), diffs);
        return DiffRenderer.unifiedDiff(diffs);
    }

    private static void collectTextDiffs(Change change, org.jawata.spi.lang.ChangePaths paths, List<DiffRenderer.FileDiff> sink) {
        if (change == null) {
            return;
        }
        if (change instanceof TextChange textChange) {
            try {
                String oldContent = textChange.getCurrentContent(new NullProgressMonitor());
                String newContent = textChange.getPreviewContent(new NullProgressMonitor());
                sink.add(new DiffRenderer.FileDiff(
                    diffPathOf(textChange, paths), oldContent, newContent));
            } catch (CoreException e) {
                log.debug("Preview failed for '{}': {}", change.getName(), e.getMessage());
            }
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collectTextDiffs(child, paths, sink);
            }
        }
    }

    /** One edit of a text file: {@code length} characters from {@code offset} of the text the change starts from become {@code text}. */
    public record SpanEdit(int offset, int length, String text) {}

    /**
     * What a change does to one text file: its edits in the offsets of {@code content}, the text the change starts from. {@code file} is
     * the file's place on disk, or null when the change does not name one.
     */
    public record FileEdits(String path, java.nio.file.Path file, String content, List<SpanEdit> edits) {}

    /**
     * The edits a change would make, file by file, without applying it: the leaves of each text change's own edit tree, so one edit
     * per place the refactoring touches. This is what an editor applies in place of the engine writing the files. A file whose edit tree
     * holds an edit that moves or copies text (not a replace, insert or delete) is answered as one edit replacing the whole file, which
     * is exact if less fine.
     */
    public static List<FileEdits> textEdits(Change change, org.jawata.mcp.change.ChangeContext context) {
        List<FileEdits> sink = new ArrayList<>();
        collectTextEdits(change, context.paths(), sink);
        return sink;
    }

    private static void collectTextEdits(Change change, ChangePaths paths, List<FileEdits> sink) {
        if (change == null) {
            return;
        }
        if (change instanceof TextChange textChange) {
            try {
                String content = textChange.getCurrentContent(new NullProgressMonitor());
                List<SpanEdit> edits = new ArrayList<>();
                if (!leavesOf(textChange.getEdit(), edits)) {
                    edits = List.of(new SpanEdit(0, content.length(), textChange.getPreviewContent(new NullProgressMonitor())));
                }
                sink.add(new FileEdits(diffPathOf(textChange, paths), locationOf(textChange, paths), content, edits));
            } catch (CoreException e) {
                log.debug("Edits failed for '{}': {}", change.getName(), e.getMessage());
            }
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collectTextEdits(child, paths, sink);
            }
        }
    }

    /** Adds the replace, insert and delete edits under {@code edit}; false when there is one of another kind, which this cannot express. */
    private static boolean leavesOf(org.eclipse.text.edits.TextEdit edit, List<SpanEdit> into) {
        if (edit == null) {
            return true;
        }
        if (edit instanceof MultiTextEdit) {
            for (org.eclipse.text.edits.TextEdit child : edit.getChildren()) {
                if (!leavesOf(child, into)) {
                    return false;
                }
            }
            return true;
        }
        if (edit instanceof org.eclipse.text.edits.ReplaceEdit replace) {
            into.add(new SpanEdit(replace.getOffset(), replace.getLength(), replace.getText()));
            return true;
        }
        if (edit instanceof org.eclipse.text.edits.InsertEdit insert) {
            into.add(new SpanEdit(insert.getOffset(), 0, insert.getText()));
            return true;
        }
        if (edit instanceof org.eclipse.text.edits.DeleteEdit delete) {
            into.add(new SpanEdit(delete.getOffset(), delete.getLength(), ""));
            return true;
        }
        return false;
    }

    private static java.nio.file.Path locationOf(TextChange textChange, ChangePaths paths) {
        IFile file;
        if (textChange instanceof TextFileChange tfc) {
            file = tfc.getFile();
        } else if (textChange.getModifiedElement() instanceof IFile modified) {
            file = modified;
        } else {
            file = paths.fileOf(textChange.getModifiedElement()).orElse(null);
        }
        return file == null || file.getLocation() == null ? null : file.getLocation().toFile().toPath();
    }

    /**
     * Wrap per-file JDT text edits into a performable Change: one
     * {@link TextFileChange} per file (edits under a {@link MultiTextEdit}
     * root), composed into a {@link CompositeChange} when more than one file
     * is touched. The standard bridge for tools that compute raw
     * {@link org.eclipse.text.edits.TextEdit}s (Stage A3 migrations).
     */
    public static Change fromFileEdits(String changeName,
                                       Map<IFile, List<org.eclipse.text.edits.TextEdit>> editsByFile) {
        List<TextFileChange> fileChanges = new ArrayList<>();
        for (Map.Entry<IFile, List<org.eclipse.text.edits.TextEdit>> entry : editsByFile.entrySet()) {
            TextFileChange fileChange =
                new TextFileChange(changeName + ": " + entry.getKey().getName(), entry.getKey());
            MultiTextEdit root = new MultiTextEdit();
            for (org.eclipse.text.edits.TextEdit edit : entry.getValue()) {
                root.addChild(edit);
            }
            fileChange.setEdit(root);
            fileChanges.add(fileChange);
        }
        if (fileChanges.size() == 1) {
            return fileChanges.get(0);
        }
        CompositeChange composite = new CompositeChange(changeName);
        for (TextFileChange fileChange : fileChanges) {
            composite.add(fileChange);
        }
        return composite;
    }

    /** Files a change will touch, derived from the change tree (pre-apply). */
    public static List<String> affectedFilePaths(Change change, org.jawata.mcp.change.ChangeContext context) {
        Set<String> affected = new LinkedHashSet<>();
        collectAffectedPaths(change, context.paths(), affected);
        return new ArrayList<>(affected);
    }

    private static void collectAffectedPaths(Change change, org.jawata.spi.lang.ChangePaths paths, Set<String> sink) {
        if (change == null) {
            return;
        }
        Object modified = change.getModifiedElement();
        if (change instanceof TextFileChange tfc) {
            sink.add(formatPath(tfc.getFile(), paths));
        } else if (modified instanceof IFile file) {
            sink.add(formatPath(file, paths));
        } else {
            paths.fileOf(modified).ifPresent(file -> sink.add(formatPath(file, paths)));
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collectAffectedPaths(child, paths, sink);
            }
        }
    }

    /**
     * Perform the change against the workspace and capture its undo-Change.
     *
     * <p>Modified files are observed via a workspace resource listener — some
     * LTK changes (ProcessorChange) expose no children, so walking the tree
     * after perform misses their effects.</p>
     */
    public static ApplyOutcome perform(Change change, org.jawata.mcp.change.ChangeContext context) {
        ChangePaths paths = context.paths();
        try {
            change.initializeValidationData(new NullProgressMonitor());

            Set<String> modifiedFilePaths = new LinkedHashSet<>();
            IResourceChangeListener listener = event -> {
                IResourceDelta delta = event.getDelta();
                if (delta == null) {
                    return;
                }
                try {
                    delta.accept(d -> {
                        IResource resource = d.getResource();
                        if (resource instanceof IFile file) {
                            modifiedFilePaths.add(formatPath(file, paths));
                        }
                        return true;
                    });
                } catch (CoreException ignore) {
                    // Best-effort: a delta-walk failure must not fail the refactor.
                }
            };

            ResourcesPlugin.getWorkspace().addResourceChangeListener(
                listener, IResourceChangeEvent.POST_CHANGE);
            WorkspaceWrites.Applied applied;
            try {
                applied = context.writes().perform(change, new NullProgressMonitor());
            } finally {
                ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
            }

            RefactoringStatus validation = applied.validation();
            if (validation != null && (validation.hasFatalError() || validation.hasError())) {
                return new ApplyOutcome(null, List.of(),
                    validation.getMessageMatchingSeverity(validation.getSeverity()));
            }

            // THE CHANGE TREE IS THE TRUTH, not the event stream.
            //
            // modifiedFilePaths above is fed by an IResourceChangeListener — i.e. we were
            // learning what WE had just done by waiting for Eclipse to tell us. When the
            // workspace batches or defers the POST_CHANGE delta, the listener has not fired
            // by the time we read the set, and a refactor that HAS written the file reports
            // "filesModified: []". The work happened; the report said nothing happened. (It
            // did exactly that, intermittently, and was written off as a flaky test.)
            //
            // The change we just performed knows perfectly well which files it targets. Ask
            // it. The listener is kept, but only to ADD anything the tree does not name
            // (generated files, moves) — never as the sole source of truth.
            Set<String> modified = new LinkedHashSet<>(filesTargetedBy(change, paths));
            modified.addAll(modifiedFilePaths);

            Change undoChange = applied.undo();
            return new ApplyOutcome(undoChange, new ArrayList<>(modified), null);
        } catch (CoreException e) {
            log.warn("Change.perform failed for '{}': {}", change.getName(), e.getMessage(), e);
            return new ApplyOutcome(null, List.of(), e.getMessage());
        }
    }

    /**
     * The files this change tree targets — read from the change itself.
     *
     * <p>Deterministic, and available the instant the change has been performed. This is what
     * a refactoring should report as "what I modified": the thing it did, not the thing it
     * overheard.</p>
     */
    private static Set<String> filesTargetedBy(Change change, org.jawata.spi.lang.ChangePaths paths) {
        Set<String> targets = new LinkedHashSet<>();
        collectTargets(change, paths, targets);
        return targets;
    }

    private static void collectTargets(Change change, org.jawata.spi.lang.ChangePaths paths, Set<String> into) {
        if (change == null) {
            return;
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collectTargets(child, paths, into);
            }
            return;
        }
        if (change instanceof TextChange textChange) {
            String path = diffPathOf(textChange, paths);
            if (path != null) {
                into.add(path);
            }
            return;
        }
        // A non-text change (a file move, a rename, a delete) still names what it acts on.
        Object modified = change.getModifiedElement();
        if (modified instanceof IFile file) {
            into.add(formatPath(file, paths));
        } else {
            paths.fileOf(modified).ifPresent(file -> into.add(formatPath(file, paths)));
        }
    }

    private static String diffPathOf(TextChange textChange, ChangePaths paths) {
        if (textChange instanceof TextFileChange tfc) {
            return formatPath(tfc.getFile(), paths);
        }
        Object modified = textChange.getModifiedElement();
        if (modified instanceof IFile file) {
            return formatPath(file, paths);
        }
        return paths.fileOf(modified).map(file -> formatPath(file, paths)).orElse(textChange.getName());
    }

    private static String formatPath(IFile file, org.jawata.spi.lang.ChangePaths paths) {
        try {
            return paths.display(file.getLocation().toFile().toPath());
        } catch (RuntimeException e) {
            return file.getName();
        }
    }
}
