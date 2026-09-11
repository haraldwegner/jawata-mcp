package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f D2 — the copy taken before anything destroys rows.
 *
 * <p>What these tests pin is the promise the architecture makes and no more.
 * H2's online backup is CONSISTENT — it opens, every row reads, nothing is
 * corrupt — but it is NOT a snapshot of the instant the statement ran. Measured
 * against the shipped driver with a concurrent writer, three runs restored 540,
 * 531 and 531 rows out of a possible 500 to 700. So the concurrent case asserts
 * a RANGE and readability, never an exact count; an equality there would be
 * flaky by construction and would be asserting something the mechanism never
 * offered.</p>
 */
class StoreBackupsTest {

    private static String put(H2ExperienceStore store, String summary) {
        return store.put(SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
            .symbol("com.example.Target").build());
    }

    @Test
    void a_copy_opens_as_a_working_store_with_the_same_rows(@TempDir Path dir,
            @TempDir Path restoreDir) throws Exception {
        Path copy;
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            for (int i = 0; i < 25; i++) {
                put(store, "row " + i);
            }
            StoreBackups backups = new StoreBackups(() -> store);
            copy = backups.before("wipe");
            assertNotNull(copy, "a file store must yield a copy");
            assertTrue(Files.isRegularFile(copy), "the copy is a file on disk: " + copy);
        }
        // OPENED AS A STORE, not merely present. A zip of the right size proves
        // nothing about whether a database can be made from it, and "the file
        // exists" is exactly the check that would pass over a corrupt copy.
        Path unpacked = unpackInto(copy, restoreDir);
        try (H2ExperienceStore restored = H2ExperienceStore.openAt(unpacked)) {
            assertEquals(25L, restored.count(), "every row is in the copy");
            assertEquals(25, restored.all().size(), "and every row READS back");
        }
    }

    @Test
    void every_destructive_verb_leaves_its_own_copy(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "something worth keeping");
            StoreBackups backups = new StoreBackups(() -> store);
            // The verbs the spec names, each taking its own copy. The NAME is
            // what a human reads when choosing which to go back to: "before the
            // wipe" and "before the prune" are different decisions.
            for (String verb : List.of("wipe", "prune", "import", "delete", "reseed")) {
                Path copy = backups.before(verb);
                assertNotNull(copy, "verb must leave a copy: " + verb);
                assertTrue(copy.getFileName().toString().endsWith("-" + verb + ".zip"),
                    "the copy is named for the verb it preceded: " + copy.getFileName());
            }
            assertEquals(5, backups.list().size(), "one copy per verb");
        }
    }

    /**
     * The rotation, and the ORDER it depends on.
     *
     * <p>Eleven copies, ten kept, and the one that goes is the oldest. The
     * ordering is by NAME, so this also pins that the name sorts the way time
     * runs — which it did not in the first version: an instant prints with 0, 3,
     * 6 or 9 fractional digits, so {@code ...07.15Z} sorted before
     * {@code ...07.1Z} and the rotation would have evicted the copy it had just
     * taken. Found while writing this test rather than by it, and the fixed-width
     * stamp is what this case now holds in place.</p>
     */
    @Test
    void ten_are_kept_and_the_eleventh_evicts_the_oldest(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "one row is enough to copy");
            StoreBackups backups = new StoreBackups(() -> store);
            List<String> taken = new ArrayList<>();
            for (int i = 0; i < 11; i++) {
                Path copy = backups.before("wipe");
                assertNotNull(copy);
                taken.add(copy.getFileName().toString());
            }
            List<String> kept = backups.list().stream()
                .map(p -> p.getFileName().toString()).toList();
            assertEquals(StoreBackups.DEFAULT_DEPTH, kept.size(),
                "the depth is the ceiling, not a suggestion");
            assertTrue(kept.contains(taken.get(10)), "the newest copy survives");
            assertTrue(!kept.contains(taken.get(0)),
                "the OLDEST is the one evicted — if this fails while the count is right,"
                    + " the name is not sorting the way time runs");
            // The names must be strictly increasing, which is the property the
            // eviction leans on. Asserted directly so a future change to the
            // stamp cannot quietly break the rotation while the count stays ten.
            for (int i = 1; i < taken.size(); i++) {
                assertTrue(taken.get(i).compareTo(taken.get(i - 1)) > 0,
                    "backup names must sort in the order they were taken: "
                        + taken.get(i - 1) + " then " + taken.get(i));
            }
        }
    }

    /**
     * The copy taken WHILE another connection writes.
     *
     * <p>This is the case a plain file copy cannot serve, and the reason the
     * architecture chose H2's online backup over one. The assertion is a range,
     * for the measured reason in this class's javadoc: the copy is consistent,
     * not frozen at the statement.</p>
     */
    @Test
    void a_copy_taken_under_a_concurrent_writer_still_opens(@TempDir Path dir,
            @TempDir Path restoreDir) throws Exception {
        Path copy;
        int before = 40;
        int concurrent = 60;
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            for (int i = 0; i < before; i++) {
                put(store, "settled row " + i);
            }
            Thread writer = new Thread(() -> {
                for (int i = 0; i < concurrent; i++) {
                    put(store, "late row " + i);
                }
            }, "backup-concurrency-writer");
            writer.start();
            copy = new StoreBackups(() -> store).before("wipe");
            writer.join();
            assertNotNull(copy);
            assertEquals(before + concurrent, store.count(), "the writer finished its work");
        }
        Path unpacked = unpackInto(copy, restoreDir);
        try (H2ExperienceStore restored = H2ExperienceStore.openAt(unpacked)) {
            long rows = restored.count();
            assertTrue(rows >= before && rows <= before + concurrent,
                "a consistent copy holds at least what was committed before it started ("
                    + before + ") and at most everything (" + (before + concurrent)
                    + "); it held " + rows);
            assertEquals(rows, restored.all().size(),
                "and every row it holds READS — a count from a corrupt store proves nothing");
        }
    }

    @Test
    void restore_puts_the_rows_back(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            for (int i = 0; i < 12; i++) {
                put(store, "row " + i);
            }
            StoreBackups backups = new StoreBackups(() -> store);
            Path copy = backups.before("wipe");
            assertNotNull(copy);

            store.wipe();
            assertEquals(0L, store.count(), "the control: the wipe really emptied it");

            backups.restore(copy.getFileName().toString());
            assertEquals(12L, store.count(), "the rows come back through the same store object");
            assertEquals(12, store.all().size(), "and they read");
        }
    }

    /**
     * A restore is destructive too, so it leaves a copy of what it REPLACED.
     *
     * <p>The discriminator is the row written after the copy was taken. It is in
     * neither the copy nor the store once the restore has run, so the safety copy
     * is the only place it can be. Asserting the copy merely EXISTS would pass
     * against a copy of the restored state, which would undo nothing.</p>
     */
    @Test
    void restoring_leaves_a_copy_of_what_it_replaced(@TempDir Path dir, @TempDir Path unpackDir)
            throws Exception {
        Path copy;
        StoreBackups.Restored done;
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "the row both states share");
            StoreBackups backups = new StoreBackups(() -> store);
            copy = backups.before("wipe");
            assertNotNull(copy);

            put(store, "the row that exists ONLY in the state being replaced");
            assertEquals(2L, store.count());

            done = backups.restore(copy.getFileName().toString());
            assertEquals(copy, done.from(), "it put back the copy it was asked for");
            assertEquals(1L, store.count(), "the control: the restore really replaced the state");
            assertNotNull(done.safetyCopy(),
                "a restore replaces every row, so it owes a copy of what it replaced");
        }
        try (H2ExperienceStore replaced = H2ExperienceStore.openAt(
                unpackInto(done.safetyCopy(), unpackDir))) {
            assertEquals(2L, replaced.count(),
                "the safety copy holds the state the restore REPLACED, not the one it restored");
        }
    }

    /**
     * The OLDEST copy is still restorable when the depth is full.
     *
     * <p>This is what resolving the chosen copy BEFORE taking the safety copy
     * buys. The other order runs the rotation first, which at the depth evicts
     * the oldest — the copy a caller reaching back furthest is likeliest to have
     * asked for — and the restore then fails on a name that was valid when they
     * read it off the list.</p>
     */
    @Test
    void the_oldest_copy_is_still_restorable_at_the_depth(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "the one row the oldest copy holds");
            StoreBackups backups = new StoreBackups(() -> store);
            Path oldest = backups.before("wipe");
            assertNotNull(oldest);
            for (int i = 1; i < StoreBackups.DEFAULT_DEPTH; i++) {
                put(store, "row " + i);
                assertNotNull(backups.before("wipe"));
            }
            assertEquals(StoreBackups.DEFAULT_DEPTH, backups.list().size(),
                "the control: the depth is FULL, which is the only state the order matters in");

            StoreBackups.Restored done = backups.restore(oldest.getFileName().toString());
            assertEquals(oldest, done.from());
            assertEquals(1L, store.count(), "the oldest copy's single row came back");
        }
    }

    @Test
    void an_unknown_name_is_refused_with_the_names_that_exist(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "a row");
            StoreBackups backups = new StoreBackups(() -> store);
            Path real = backups.before("wipe");
            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> backups.restore("no-such-copy.zip"));
            assertTrue(e.getMessage().contains(real.getFileName().toString()),
                "a refusal that does not say what IS available leaves the caller guessing: "
                    + e.getMessage());
        }
    }

    /**
     * An in-memory store answers ABSENCE rather than pretending.
     *
     * <p>There is nothing a restore could bring back, so a copy would be a file
     * that looks like safety and is not. The caller gets null and must say so —
     * which is what {@code ExperienceTool} does, because "no backup was taken"
     * and "a backup was taken and I did not mention it" must not read alike.</p>
     */
    @Test
    void an_in_memory_store_has_no_copy_and_says_so() {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            StoreBackups backups = new StoreBackups(() -> store);
            assertNull(backups.before("wipe"), "no file store, no copy");
            assertTrue(backups.list().isEmpty());
            assertThrows(IllegalStateException.class, () -> store.backupTo(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "never.zip")),
                "the store itself refuses rather than writing an empty archive");
        }
    }

    @Test
    void a_zip_that_is_not_a_store_backup_is_refused_and_changes_nothing(@TempDir Path dir)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "the row that must survive a bad restore");
            Path notABackup = dir.resolve("backups").resolve("decoy.zip");
            Files.createDirectories(notABackup.getParent());
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                    Files.newOutputStream(notABackup))) {
                out.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
                out.write("not a database".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            assertThrows(IllegalStateException.class, () -> store.restoreFrom(notABackup));
            // THE STORE IS STILL USABLE. A restore that refuses must leave the
            // resident with a working store rather than a closed one — the
            // reopen happens whatever the copy did.
            assertEquals(1L, store.count(), "the refused restore touched nothing");
            put(store, "and the store still takes writes");
            assertEquals(2L, store.count());
        }
    }

    /** Unpack a backup's database file into {@code into}, so it can be opened as a store. */
    private static Path unpackInto(Path zip, Path into) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            for (java.util.zip.ZipEntry e : java.util.Collections.list(zf.entries())) {
                if (e.getName().endsWith(".mv.db")) {
                    try (java.io.InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, into.resolve("experience.mv.db"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    return into;
                }
            }
        }
        throw new IllegalStateException("no database entry in " + zip);
    }
}
