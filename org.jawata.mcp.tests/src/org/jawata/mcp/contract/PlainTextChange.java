package org.jawata.mcp.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

/**
 * A change that writes one file with plain text (Sprint 28k, Stage 8; contract §18 ring 1). It is the change the contract tests hand to a
 * host's writes and the language-free lifecycle tests apply: no language is needed to make it, and no language is needed to undo it.
 * Performing it returns the change that puts the file back as it was, which is no file at all when there was none.
 */
public final class PlainTextChange extends Change {

    private final Path file;
    private final String text;

    /**
     * @param file the file to write; it and its folders are created when absent
     * @param text what the file is to hold
     */
    public PlainTextChange(Path file, String text) {
        this.file = file;
        this.text = text;
    }

    @Override
    public String getName() {
        return "write " + file.getFileName();
    }

    @Override
    public void initializeValidationData(IProgressMonitor pm) {
        // Nothing is cached: the file is read when the change is performed.
    }

    @Override
    public RefactoringStatus isValid(IProgressMonitor pm) {
        return new RefactoringStatus();
    }

    @Override
    public Change perform(IProgressMonitor pm) throws CoreException {
        try {
            String before = Files.isRegularFile(file) ? Files.readString(file) : null;
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
            return new Restore(file, before);
        } catch (IOException e) {
            throw new CoreException(new Status(IStatus.ERROR, "org.jawata.mcp.tests", "cannot write " + file, e));
        }
    }

    @Override
    public Object getModifiedElement() {
        return file;
    }

    /** The undo of a {@link PlainTextChange}: the file as it was, or no file. */
    public static final class Restore extends Change {
        private final Path file;
        private final String before;

        Restore(Path file, String before) {
            this.file = file;
            this.before = before;
        }

        @Override
        public String getName() {
            return "restore " + file.getFileName();
        }

        @Override
        public void initializeValidationData(IProgressMonitor pm) {
            // Nothing is cached.
        }

        @Override
        public RefactoringStatus isValid(IProgressMonitor pm) {
            return new RefactoringStatus();
        }

        @Override
        public Change perform(IProgressMonitor pm) throws CoreException {
            try {
                String current = Files.isRegularFile(file) ? Files.readString(file) : null;
                if (before == null) {
                    Files.deleteIfExists(file);
                } else {
                    Files.writeString(file, before);
                }
                return new Restore(file, current);
            } catch (IOException e) {
                throw new CoreException(new Status(IStatus.ERROR, "org.jawata.mcp.tests", "cannot restore " + file, e));
            }
        }

        @Override
        public Object getModifiedElement() {
            return file;
        }
    }
}
