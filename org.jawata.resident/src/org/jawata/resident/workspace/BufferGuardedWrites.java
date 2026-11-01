package org.jawata.resident.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.jawata.core.BufferStore;
import org.jawata.core.IJdtService;
import org.jawata.spi.host.WorkspaceWrites;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the headless engine's changes reach files an editor also has open (Sprint 28h, S16a): a change over a file whose editor text is
 * not saved is refused by name before anything is touched, so the person's unsaved text is never written over, exactly as the Eclipse
 * host refuses it (the same {@value #UNSAVED_IN_EDITOR} code). A file the editor has open and saved is written, and the engine's copy
 * of its text follows the disk afterwards, so the next tool call reads what was written and not the text from before.
 *
 * <p>Writing itself is the wrapped writes' ({@link WorkspaceWrites#DIRECT} in the resident). The files a change acts on are read from the
 * change before it is performed. The same collection exists in the Eclipse host's writes, which is another repository on Eclipse-only
 * types; the language-neutral SPI cannot hold it because one of its branches names a Java element.</p>
 */
public final class BufferGuardedWrites implements WorkspaceWrites {

    /** The refusal for a file whose editor holds text that is not saved yet; the Eclipse host uses the same code. */
    public static final String UNSAVED_IN_EDITOR = "UNSAVED_IN_EDITOR";

    private static final String PLUGIN_ID = "org.jawata.resident";
    private static final Logger log = LoggerFactory.getLogger(BufferGuardedWrites.class);

    private final Supplier<IJdtService> service;
    private final WorkspaceWrites writes;

    /**
     * @param service the engine's Java model, asked at each use because a workspace may load after the host exists
     * @param writes  the writes that actually put a change on disk
     */
    public BufferGuardedWrites(Supplier<IJdtService> service, WorkspaceWrites writes) {
        this.service = service;
        this.writes = writes;
    }

    @Override
    public Applied perform(Change change, IProgressMonitor monitor) throws CoreException {
        Set<IFile> targets = targetsOf(change);
        Optional<String> refused = refusal(targets);
        if (refused.isPresent()) {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, refused.get()));
        }
        Applied applied = writes.perform(change, monitor);
        followTheDisk(targets);
        return applied;
    }

    @Override
    public Optional<String> refusal(Collection<IFile> targets) {
        BufferStore buffers = buffers();
        if (buffers == null) {
            return Optional.empty();
        }
        for (IFile file : targets) {
            ICompilationUnit unit = unitOf(file);
            try {
                if (unit != null && buffers.isDirty(unit)) {
                    return Optional.of(UNSAVED_IN_EDITOR + ": " + file.getFullPath() + " has text in an editor that is not saved; save or close it "
                        + "and ask again. Nothing was changed.");
                }
            } catch (JavaModelException unreadable) {
                return Optional.of(UNSAVED_IN_EDITOR + ": whether " + file.getFullPath() + " has unsaved text in an editor cannot be told ("
                    + unreadable.getMessage() + "). Nothing was changed.");
            }
        }
        return Optional.empty();
    }

    /** Brings the engine's copy of every open, saved file the change touched up to what is on disk now. */
    private void followTheDisk(Set<IFile> targets) {
        BufferStore buffers = buffers();
        if (buffers == null) {
            return;
        }
        for (IFile file : targets) {
            ICompilationUnit unit = unitOf(file);
            if (unit == null || buffers.view(unit).isEmpty() || file.getLocation() == null) {
                continue;
            }
            try {
                if (Files.exists(file.getLocation().toFile().toPath())) {
                    buffers.change(unit, Files.readString(file.getLocation().toFile().toPath(), StandardCharsets.UTF_8));
                }
            } catch (IOException | JavaModelException e) {
                log.warn("The open copy of {} could not follow the write: {}", file.getFullPath(), e.getMessage());
            }
        }
    }

    private BufferStore buffers() {
        IJdtService engine = service.get();
        return engine == null ? null : engine.buffers().orElse(null);
    }

    private static ICompilationUnit unitOf(IFile file) {
        return JavaCore.createCompilationUnitFrom(file);
    }

    /** The files a change acts on, read from the change itself, so the check is made before anything is written. */
    private static Set<IFile> targetsOf(Change change) {
        Set<IFile> files = new LinkedHashSet<>();
        collect(change, files);
        return files;
    }

    private static void collect(Change change, Set<IFile> into) {
        if (change == null) {
            return;
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collect(child, into);
            }
            return;
        }
        if (change instanceof TextFileChange textFile) {
            into.add(textFile.getFile());
            return;
        }
        Object modified = change.getModifiedElement();
        IResource resource = modified instanceof IJavaElement element ? element.getResource() : modified instanceof IResource r ? r : null;
        if (resource instanceof IFile file) {
            into.add(file);
        }
    }
}
