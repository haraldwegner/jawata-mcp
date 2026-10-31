package org.jawata.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The unsaved text of the files an editor has open (Sprint 28h, S16a): one JDT working copy per open file, shared by everyone who
 * has the file open, so a tool, the language server and a hired agent's tool call all read the same text and none of them reads a
 * stale saved file while the person is typing.
 *
 * <p>The working copies belong to JDT's PRIMARY owner on purpose. JDT's search and its type resolution see the primary owner's
 * working copies of every other file, so a reference found in one file sees an unsaved rename made in another; a private owner
 * would make each buffer visible to its own file only.</p>
 *
 * <p>Holders are counted. The first open of a file makes the working copy from the text given; a later open of the same file
 * shares it and does not replace the text, because the first holder's text may be newer than what the second one read from disk.
 * The working copy goes when the last holder closes, after which the saved file is the text again. A change replaces the text for
 * every holder: the later version wins.</p>
 *
 * <p>It is owned by the service that holds the projects, one per workspace, so a face that attaches and detaches does not take the
 * buffers with it. It reads nothing from disk by itself and never writes to disk.</p>
 */
public final class BufferStore {

    private static final Logger log = LoggerFactory.getLogger(BufferStore.class);

    /** One open file: its working copy and how many holders have it open. */
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
            ICompilationUnit copy = saved.getWorkingCopy(null);
            copy.getBuffer().setContents(text);
            copy.reconcile(ICompilationUnit.NO_AST, false, null, null); // the types and methods a reader walks follow the text only after this
            entry = new Entry(copy);
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
        return entry != null && !entry.copy.getSource().equals(saved.getSource());
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
