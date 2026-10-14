package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28g S13-9 — a backup that is taken because a day has passed, not because somebody
 * remembered a destructive verb.
 *
 * <p>The requirement, in Harald's words on 2026-10-14: <i>"it should make backups every day"</i>.
 * On that day the newest copy of the user-level store was a month old, because copies were only
 * ever taken BEFORE a destructive verb, while the store was written to every day and then
 * corrupted. The ledger row asks for "a test that a day boundary produces a copy", which is the
 * first case here; the rest pin what keeps that copy worth having: its own retention, so a month
 * of wipes cannot push the daily copies out and a run of daily copies cannot push out the copy
 * taken before a wipe, and a read-back, so a copy that cannot be read is removed and not counted.</p>
 */
class ScheduledBackupsTest {

    private static final Instant T0 = Instant.parse("2026-10-14T08:00:00Z");

    private static String put(H2ExperienceStore store, String summary) {
        return store.put(SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
            .symbol("com.example.Target").build());
    }

    private static boolean scheduled(Path copy) {
        return copy.getFileName().toString().endsWith("-scheduled.zip");
    }

    @Test
    void a_day_boundary_produces_a_copy_and_a_shorter_gap_does_not(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "one row is enough to copy");
            StoreBackups backups = new StoreBackups(() -> store);

            Path first = backups.takeIfDue(T0);
            assertNotNull(first, "a store with no scheduled copy is due one at once");
            assertTrue(scheduled(first), "and the copy says what it is: " + first.getFileName());

            assertNull(backups.takeIfDue(T0.plus(Duration.ofHours(23))),
                "23 hours after a copy, none is due — copying on every check would fill the disk");
            Path second = backups.takeIfDue(T0.plus(Duration.ofHours(25)));
            assertNotNull(second, "past the day boundary a second copy is taken");

            assertEquals(2, backups.list().stream().filter(ScheduledBackupsTest::scheduled).count());
        }
    }

    @Test
    void scheduled_copies_keep_their_own_depth_and_leave_the_verb_copies_alone(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "one row is enough to copy");
            StoreBackups backups = new StoreBackups(() -> store);

            Path beforeAWipe = backups.before("wipe");
            assertNotNull(beforeAWipe);
            int days = StoreBackups.DEFAULT_SCHEDULED_DEPTH + 2;
            for (int i = 0; i < days; i++) {
                assertNotNull(backups.takeIfDue(T0.plus(Duration.ofDays(i))), "day " + i);
            }

            List<Path> kept = backups.list();
            assertEquals(StoreBackups.DEFAULT_SCHEDULED_DEPTH,
                kept.stream().filter(ScheduledBackupsTest::scheduled).count(),
                "the scheduled depth is its own ceiling");
            assertTrue(kept.contains(beforeAWipe),
                "sixteen daily copies must not evict the copy taken before the wipe");
        }
    }

    @Test
    void verb_copies_do_not_evict_the_scheduled_ones(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "one row is enough to copy");
            StoreBackups backups = new StoreBackups(() -> store);

            Path daily = backups.takeIfDue(T0);
            assertNotNull(daily);
            for (int i = 0; i < StoreBackups.DEFAULT_DEPTH + 1; i++) {
                assertNotNull(backups.before("wipe"));
            }
            assertTrue(backups.list().contains(daily),
                "eleven wipes rotate the verb copies; the daily copy is not part of that rotation");
        }
    }

    @Test
    void a_copy_is_judged_by_reading_it_back_and_a_damaged_one_is_refused(@TempDir Path dir)
            throws IOException {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "one row is enough to copy");
            Path good = new StoreBackups(() -> store).takeIfDue(T0);
            assertNotNull(good);
            assertTrue(StoreBackups.readsBack(good), "a real copy reads back end to end");

            byte[] bytes = Files.readAllBytes(good);
            Path truncated = dir.resolve("truncated.zip");
            Files.write(truncated, Arrays.copyOf(bytes, bytes.length / 2));
            assertFalse(StoreBackups.readsBack(truncated), "half a zip is not a backup");

            Path empty = dir.resolve("empty.zip");
            Files.write(empty, new byte[0]);
            assertFalse(StoreBackups.readsBack(empty), "an empty file is not a backup");

            Path noDatabase = dir.resolve("no-database.zip");
            try (OutputStream out = Files.newOutputStream(noDatabase);
                 ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("readme.txt"));
                zip.write("not a store".getBytes());
                zip.closeEntry();
            }
            assertFalse(StoreBackups.readsBack(noDatabase),
                "a zip that holds no database file restores into an empty store, so it is refused");
        }
    }

    @Test
    void the_status_names_the_newest_copy_and_how_old_it_is(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            put(store, "one row is enough to copy");
            StoreBackups backups = new StoreBackups(() -> store);

            Map<String, Object> none = backups.status(T0);
            assertEquals(0, none.get("count"));
            assertNull(none.get("newestScheduled"));
            assertEquals(true, none.get("dueNow"), "no copy at all means one is due");

            Path copy = backups.takeIfDue(T0);
            Map<String, Object> aged = backups.status(T0.plus(Duration.ofHours(30)));
            assertEquals(1, aged.get("count"));
            assertEquals(copy.getFileName().toString(), aged.get("newestScheduled"));
            assertEquals(30L, ((Number) aged.get("newestScheduledAgeHours")).longValue());
            assertEquals(true, aged.get("dueNow"), "30 hours is past the day, so another is due");
        }
    }

    @Test
    void an_in_memory_store_has_nothing_to_copy_and_says_so() {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            assertNull(new StoreBackups(() -> store).takeIfDue(T0),
                "no file means no copy, answered as absence and not as a copy that was taken");
        }
    }
}
