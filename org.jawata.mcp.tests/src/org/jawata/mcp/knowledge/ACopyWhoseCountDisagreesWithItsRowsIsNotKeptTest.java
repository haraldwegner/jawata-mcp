package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REL44-33 — a backup copy whose {@code COUNT(*)} disagrees with its rows is not kept.
 *
 * <p>The race that makes such a copy cannot be reproduced on demand (14 of 40 copies unpaused, 0 of 40
 * with the writers paused, measured once and recorded in the plan). So these cases do not race: they
 * use ONE SAVED BAD COPY from that race, {@code test-resources/backups/count-one-high.zip} (COUNT(*)
 * says 51, 50 rows read), handed to the policy through the seam where a copy is made. That makes the
 * check, the three attempts, the deletion, the hourly retry and the explicit override the same every
 * time. The pause itself rests on the measurement, not on a case here.</p>
 */
class ACopyWhoseCountDisagreesWithItsRowsIsNotKeptTest {

    private static final String BAD = "/test-resources/backups/count-one-high.zip";

    /** The saved bad copy, written to {@code target} as if the store had just made it. */
    static void copyTheBadOne(Path target) {
        try (InputStream in = ACopyWhoseCountDisagreesWithItsRowsIsNotKeptTest.class
                .getResourceAsStream(BAD)) {
            assertNotNull(in, "PROOF OF LIFE: the saved bad copy is on the classpath: " + BAD);
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(H2ExperienceStore store, String summary) {
        store.put(SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
            .symbol("com.example.Target").build());
    }

    @Test
    void theSavedBadCopyIsReportedUncleanAndARealCopyIsNot(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad.zip");
        copyTheBadOne(bad);
        String problem = StoreBackups.uncleanReason(bad);
        assertNotNull(problem, "the count says 51 and 50 rows read, so the copy is not clean");
        assertTrue(problem.contains("51") && problem.contains("50"), problem);

        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir.resolve("live"))) {
            for (int i = 0; i < 20; i++) {
                put(store, "row " + i);
            }
            Path good = dir.resolve("good.zip");
            store.backupTo(good);
            assertNull(StoreBackups.uncleanReason(good),
                "CONTROL: a copy taken by the store, paused, agrees with its rows");
        }
    }

    @Test
    void threeUncleanAttemptsDeleteEachCopyAndRefuseTheOperation(@TempDir Path dir) {
        AtomicInteger copies = new AtomicInteger();
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "something worth keeping");
            StoreBackups backups = new StoreBackups(() -> store, (h2, target) -> {
                copies.incrementAndGet();
                copyTheBadOne(target);
            });

            StoreBackups.UncleanCopyException refused = assertThrows(
                StoreBackups.UncleanCopyException.class, () -> backups.before("wipe"));

            assertEquals(StoreBackups.CLEAN_ATTEMPTS, copies.get(), "exactly three attempts");
            assertTrue(backups.list().isEmpty(), "every unclean copy was deleted: " + backups.list());
            assertTrue(refused.getMessage().contains("proceed_without_clean_backup=true"),
                "the message says how to go on: " + refused.getMessage());
            assertTrue(refused.getMessage().contains("Nothing was changed"), refused.getMessage());
        }
    }

    @Test
    void aCleanCopyOnALaterAttemptIsKept(@TempDir Path dir) {
        AtomicInteger copies = new AtomicInteger();
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "something worth keeping");
            StoreBackups backups = new StoreBackups(() -> store, (h2, target) -> {
                if (copies.incrementAndGet() < 3) {
                    copyTheBadOne(target);
                } else {
                    h2.backupTo(target);
                }
            });

            Path kept = backups.before("wipe");

            assertNotNull(kept);
            assertEquals(3, copies.get(), "two bad attempts, then a clean one");
            assertEquals(1, backups.list().size(), "only the clean copy stands in the rotation");
        }
    }

    @Test
    void goingOnWithoutACleanCopyTakesOneCopyAndKeepsIt(@TempDir Path dir) {
        AtomicInteger copies = new AtomicInteger();
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "something worth keeping");
            StoreBackups backups = new StoreBackups(() -> store, (h2, target) -> {
                copies.incrementAndGet();
                copyTheBadOne(target);
            });

            Path kept = backups.withoutCleanCheck(() -> backups.before("wipe"));

            assertNotNull(kept, "the user chose to go on, so the operation gets its copy");
            assertEquals(1, copies.get(), "one copy, not three, and not checked");
            assertTrue(Files.isRegularFile(kept));
            assertThrows(StoreBackups.UncleanCopyException.class, () -> backups.before("wipe"),
                "CONTROL: the choice was for that call only, the next one is checked again");
        }
    }

    @Test
    void anUncleanDailyCopyIsRemovedAndEveryHourlyCheckTriesAgain(@TempDir Path dir) {
        AtomicInteger copies = new AtomicInteger();
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "something worth keeping");
            StoreBackups backups = new StoreBackups(() -> store, (h2, target) -> {
                copies.incrementAndGet();
                copyTheBadOne(target);
            });
            Instant start = Instant.parse("2026-10-18T06:00:00Z");

            for (int hour = 0; hour < 5; hour++) {
                assertNull(backups.takeIfDue(start.plusSeconds(3600L * hour)),
                    "an unclean daily copy is not kept");
                assertTrue(backups.list().isEmpty(), "and none stands in the rotation");
            }
            assertEquals(5, copies.get(),
                "the hourly check tried again each time, with no count that stops it");
        }
    }

    /**
     * A copy that is not a readable zip, or that H2 cannot open or read, is damaged, which is exactly
     * what the check is for. Only a failure on the CHECK's side (here: the copy file is not there to
     * be read) is unverified and keeps the copy.
     */
    @Test
    void aCopyThatCannotBeOpenedIsUncleanAndAMissingFileIsUnverified(@TempDir Path dir)
            throws Exception {
        Path garbage = dir.resolve("garbage.zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(garbage))) {
            out.putNextEntry(new java.util.zip.ZipEntry("experience.mv.db"));
            out.write(new byte[8192]);
            out.closeEntry();
        }
        String problem = StoreBackups.uncleanReason(garbage);
        assertNotNull(problem, "a database file H2 cannot open is not a clean copy");
        assertTrue(problem.contains("cannot be opened or read"), problem);

        Path notAZip = dir.resolve("not-a-zip.zip");
        Files.writeString(notAZip, "this is not a zip file");
        String notZip = StoreBackups.uncleanReason(notAZip);
        assertNotNull(notZip, "a file that is not a zip is a damaged copy, not an unverified one");
        assertTrue(notZip.contains("not a readable zip"), notZip);

        assertNull(StoreBackups.uncleanReason(dir.resolve("never-written.zip")),
            "CONTROL: a copy the check cannot even find is the check's failure, unverified and kept");
    }

    @Test
    void theChoiceToGoOnEndsWhenTheCallEndsEvenIfTheCallThrows(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "something worth keeping");
            StoreBackups backups = new StoreBackups(() -> store, (h2, target) -> copyTheBadOne(target));

            assertThrows(IllegalArgumentException.class, () -> backups.withoutCleanCheck(() -> {
                throw new IllegalArgumentException("the operation failed after the copy");
            }));

            assertThrows(StoreBackups.UncleanCopyException.class, () -> backups.before("wipe"),
                "the next call on this thread is checked again");
        }
    }
}
