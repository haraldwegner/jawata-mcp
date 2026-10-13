package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REL44-7 — a store its owner closed must not keep a database connection open behind it.
 *
 * <p><b>The case.</b> {@code DestructiveVerbsBackUpFirstTest#the_rebuild_copies_first} failed once in
 * CI with JUnit unable to delete its temp folder (DirectoryNotEmpty). The rebuild verb starts a
 * background embedding pass; the test closed the store while that pass was reading from it. The pass
 * had BORROWED a read connection, so {@code close()} — which drains only the connections sitting in
 * the pool — never saw it, and when the pass handed it back the store put it into the now-drained
 * pool and left it OPEN. An open connection keeps the database open, and an open file database
 * keeps rewriting its lock file, which is what a deleted temp folder looks like when it grows back.</p>
 *
 * <p>The property is stated at the store, not at the test that happened to expose it: once
 * {@code close()} has returned, no connection the store ever handed out stays open.</p>
 */
class ACloseTakesEveryConnectionWithItTest {

    @Test
    void aReadConnectionHandedBackAfterTheCloseIsClosedNotPooled(@TempDir Path dir) throws Exception {
        H2ExperienceStore store = H2ExperienceStore.openAt(dir);
        Connection borrowed = store.borrowRead();
        assertNotNull(borrowed, "the control: a file store gives out a read connection");
        assertTrue(!borrowed.isClosed(), "the control: it is open while the store is");

        store.close();
        store.releaseRead(borrowed, true);

        assertTrue(borrowed.isClosed(),
            "a connection handed back to a store its owner had already closed must be closed, not"
                + " pooled — pooled, it keeps the database open for as long as the process lives");
    }

    @Test
    void aClosedStoreRefusesToBorrowAndOpensNothing(@TempDir Path dir) {
        H2ExperienceStore store = H2ExperienceStore.openAt(dir);
        store.close();
        assertThrows(IllegalStateException.class, store::borrowRead);
    }
}
