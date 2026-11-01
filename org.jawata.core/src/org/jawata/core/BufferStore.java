package org.jawata.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaModelStatusConstants;
import org.eclipse.jdt.core.JavaModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The unsaved text of the files an editor has open (Sprint 28h, S16a): the file's own compilation unit, turned into JDT's PRIMARY working
 * copy for as long as someone has the file open, so a tool, the language server and a hired agent's tool call all read the same text and
 * none of them reads a stale saved file while the person is typing.
 *
 * <p>It is the primary working copy on purpose. JDT's search, its name resolution, its code assist and above all its refactoring engine
 * look at the primary owner's working copies and no other: a copy with an owner of its own (what {@code getWorkingCopy(monitor)} makes)
 * cannot be found by a rename's search, which then fails on a file that is merely open, and is invisible to the other open files. As the
 * primary working copy the file is the same object before, while and after it is open; what changes is the text it holds.</p>
 *
 * <p>Holders are counted. The first open of a file makes the working copy from the text given; a later open of the same file
 * shares it and does not replace the text, because the first holder's text may be newer than what the second one read from disk.
 * The working copy goes when the last holder closes, after which the saved file is the text again. A change replaces the text for
 * every holder: the later version wins.</p>
 *
 * <p>It is owned by the service that holds the projects, one per workspace, so a face that attaches and detaches does not take the
 * buffers with it. It reads the saved text from disk only to tell whether the open text differs, and never writes to disk.</p>
 */
public final class BufferStore {

    private static final Logger log = LoggerFactory.getLogger(BufferStore.class);

    /** One open file: its working copy (the file's own unit) and how many holders have it open. */
    private static final class Entry {
        final ICompilationUnit copy;
        int holders;

        Entry(ICompilationUnit copy) {
            this.copy = copy;
        }
    }

    private final Map<String, Entry> open = new HashMap<>();

    private static String keyOf(ICompilationUnit saved) {
        return saved.getPath().toString();
    }

    /**
     * Opens {@code saved} for one more holder and returns the working copy. The text is used only when this is the first holder.
     *
     * @param saved the compilation unit as the model holds it for the file on disk
     * @param text  what the editor sent when it opened the file
     */
    public synchronized ICompilationUnit open(ICompilationUnit saved, String text) throws JavaModelException {
        Entry entry = open.get(keyOf(saved));
        if (entry == null) {
            saved.becomeWorkingCopy(null);
            saved.getBuffer().setContents(text);
            saved.reconcile(ICompilationUnit.NO_AST, false, null, null); // the types and methods a reader walks follow the text only after this
            entry = new Entry(saved);
            open.put(keyOf(saved), entry);
        }
        entry.holders++;
        return entry.copy;
    }

    /**
     * Replaces the text of an open file for every holder.
     *
     * @throws IllegalStateException when the file is not open: a change to a file nobody opened is a protocol error of the caller
     */
    public synchronized void change(ICompilationUnit saved, String text) throws JavaModelException {
        Entry entry = open.get(keyOf(saved));
        if (entry == null) {
            throw new IllegalStateException("not open: " + keyOf(saved));
        }
        entry.copy.getBuffer().setContents(text);
        entry.copy.reconcile(ICompilationUnit.NO_AST, false, null, null); // so the structure a tool reads is the new text's
    }

    /**
     * Releases one holder. The last holder's close discards the working copy, and the saved file is the text again.
     *
     * @return whether the file was open
     */
    public synchronized boolean close(ICompilationUnit saved) {
        Entry entry = open.get(keyOf(saved));
        if (entry == null) {
            return false;
        }
        if (--entry.holders <= 0) {
            open.remove(keyOf(saved));
            discard(entry);
        }
        return true;
    }

    /** The working copy of an open file, or empty when the file is not open. */
    public synchronized Optional<ICompilationUnit> view(ICompilationUnit saved) {
        Entry entry = open.get(keyOf(saved));
        return entry == null ? Optional.empty() : Optional.of(entry.copy);
    }

    /** Whether the open file's text differs from the saved file's: a person has changes a tool must not write over. */
    public synchronized boolean isDirty(ICompilationUnit saved) throws JavaModelException {
        Entry entry = open.get(keyOf(saved));
        return entry != null && !entry.copy.getSource().equals(savedText(saved));
    }

    /** What the file holds on disk now; the unit itself answers with the open text while the file is open. */
    private static String savedText(ICompilationUnit unit) throws JavaModelException {
        try {
            IFile file = (IFile) unit.getResource();
            Charset charset = Charset.forName(file.getCharset());
            return Files.readString(file.getLocation().toFile().toPath(), charset);
        } catch (CoreException e) {
            throw new JavaModelException(e);
        } catch (IOException e) {
            throw new JavaModelException(e, IJavaModelStatusConstants.IO_EXCEPTION);
        }
    }

    /** How many holders have the file open; zero when it is not open. */
    public synchronized int holders(ICompilationUnit saved) {
        Entry entry = open.get(keyOf(saved));
        return entry == null ? 0 : entry.holders;
    }

    /** Discards every working copy, for the service that owns this store going away. */
    public synchronized void discardAll() {
        for (Entry entry : new ArrayList<>(open.values())) {
            discard(entry);
        }
        open.clear();
    }

    private static void discard(Entry entry) {
        try {
            entry.copy.discardWorkingCopy();
        } catch (JavaModelException e) {
            log.debug("A working copy could not be discarded: {}", e.getMessage());
        }
    }
}
