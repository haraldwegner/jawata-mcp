package org.jawata.mcp.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.jawata.mcp.engine.EngineEnvironment;
import org.jawata.mcp.engine.SettingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 28f D2 — the copy that is taken BEFORE anything destroys rows.
 *
 * <p>The rule this exists for, in Harald's words on 2026-09-08: <i>"write a
 * backup before we touch the db. version history 10"</i>. The only copy on the
 * development machine that day was dated three weeks earlier, which is what a
 * store with no backup discipline looks like from the outside — nothing is
 * wrong until the day something is, and then the last copy is whenever somebody
 * last remembered.</p>
 *
 * <p><b>Called INSIDE the verb, server-side, never by a caller.</b> That is the
 * whole design decision. A backup a caller performs is a backup a caller can
 * skip, and every client of this store is an agent under time pressure with its
 * own reasons. {@link #before(String)} runs first in the verb, so no route
 * reaches the destruction without passing it.</p>
 *
 * <p><b>What this class owns and what the store owns.</b> The store owns the
 * mechanism — taking a consistent copy of itself while open, and writing one
 * back — because those are facts about H2 and about its own connection.
 * This class owns the POLICY: where copies go, what they are called, how many
 * are kept, which one a name refers to. Putting the policy here keeps
 * {@link H2ExperienceStore} from growing a retention scheme, and putting the
 * mechanism there keeps this class from reaching into a connection it does not
 * hold.</p>
 *
 * <p>The delegate is resolved per call through a supplier rather than held,
 * for the reason every other reader of the concrete store does it: a
 * {@link RecoveringExperienceStore} can replace its delegate after a recovery,
 * and a held reference would go on backing up a store nobody is writing to.</p>
 */
public final class StoreBackups {

    private static final Logger log = LoggerFactory.getLogger(StoreBackups.class);

    /**
     * How many copies are kept, oldest evicted first.
     *
     * <p>Ten, and hardcoded first on Harald's own instruction — <i>"should be a
     * parameter in settings but could be hardcoded first"</i>. The system
     * property exists so the number is not welded in, and the settings surface
     * comes with the rest of the studio work; reading it here costs nothing and
     * means the later change is a wiring change rather than a code change.</p>
     *
     * <p>The cost is real and was priced before it was chosen: ten copies of a
     * 37 MB store is about 370 MB, and the copies compress — the measured one
     * was a fifth of the database's size.</p>
     */
    public static final int DEFAULT_DEPTH = 10;

    /** The setting, read per call so a change does not need a restart. */
    public static final String DEPTH_PROPERTY = "jawata.backups.depth";

    /**
     * Sprint 28g S13-9 — how many SCHEDULED copies are kept: two weeks of daily copies.
     *
     * <p>Its own ceiling, separate from {@link #DEFAULT_DEPTH}, for a reason that cuts both ways.
     * One shared pool would let a bad day of wipes push every daily copy out, and a run of daily
     * copies push out the one taken before a wipe, which is the copy a person reaches for first.</p>
     */
    public static final int DEFAULT_SCHEDULED_DEPTH = 14;

    /** The verb a scheduled copy carries in its name, so it is told apart from a pre-verb copy. */
    public static final String SCHEDULED_VERB = "scheduled";

    /** A scheduled copy is due once the newest one is this old: the requirement is "every day". */
    public static final java.time.Duration INTERVAL = java.time.Duration.ofHours(24);

    /**
     * REL44-33 — how many times the copy before a destructive operation is taken before the
     * operation is refused. The daily copy has no such count: an unclean one is deleted and the next
     * hourly check tries again, as long as it takes.
     */
    public static final int CLEAN_ATTEMPTS = 3;

    /** How a copy is made. The store's own backup; a test can hand in another to make a bad one. */
    @FunctionalInterface
    interface Copier {
        void copy(H2ExperienceStore store, Path target);
    }

    /**
     * A copy that reads but is not clean, still unclean after {@link #CLEAN_ATTEMPTS} attempts. The
     * operation it was a safety copy for has NOT run. The user decides whether to go on without a
     * clean copy, by repeating the request with {@code proceed_without_clean_backup=true}.
     */
    public static final class UncleanCopyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public UncleanCopyException(String verb, int attempts, String problem) {
            super("The safety copy before " + verb + " could not be made clean in " + attempts
                + " attempts (" + problem + "). Nothing was changed. To go on without a clean"
                + " copy, repeat the request with proceed_without_clean_backup=true.");
        }
    }

    private final Supplier<H2ExperienceStore> store;
    private final Copier copier;
    private final ThreadLocal<Boolean> goOnWithoutCleanCopy = ThreadLocal.withInitial(() -> false);

    public StoreBackups(Supplier<H2ExperienceStore> store) {
        this(store, H2ExperienceStore::backupTo);
    }

    StoreBackups(Supplier<H2ExperienceStore> store, Copier copier) {
        this.store = store;
        this.copier = copier;
    }

    /**
     * Run {@code action} with the clean-copy check switched off for this thread: the user has been
     * told the safety copy could not be made clean and has chosen to go on. One copy is still taken.
     */
    public <T> T withoutCleanCheck(Supplier<T> action) {
        goOnWithoutCleanCopy.set(true);
        try {
            return action.get();
        } finally {
            goOnWithoutCleanCopy.remove();
        }
    }

    /** How many copies are kept right now — published because the verbs report it. */
    public static int depth() {
        String raw = EngineEnvironment.settings().get(DEPTH_PROPERTY).orElse(null);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DEPTH;
        }
        try {
            // A depth of zero would mean "keep nothing", which is the one value
            // that silently turns this whole mechanism off. Refused by flooring
            // at one rather than honoured: somebody setting 0 has misunderstood
            // the setting, and a store with no copies is what D2 exists to end.
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            log.warn("{} is not a number ({}) — keeping {}", DEPTH_PROPERTY, raw, DEFAULT_DEPTH);
            return DEFAULT_DEPTH;
        }
    }

    /**
     * Take the copy that precedes {@code verb}, evict past the depth, and answer
     * where it went.
     *
     * <p><b>A null answer is a real one and the caller must report it rather
     * than hide it:</b> an in-memory store has no file to copy, so there is
     * nothing a restore could bring back. Saying "backed up" there would make
     * the one case with no safety net look exactly like the nine with one.</p>
     *
     * <p><b>A failure to copy does NOT fail the verb, and that is a decision
     * rather than an oversight.</b> The precedent is the delete verb, whose
     * archive failing cancels the delete — right there, because that archive is
     * the only undo a per-id delete has. Here the reasoning inverts: a store
     * that cannot write a copy (a full disk, a read-only mount) is a store the
     * user still has to be able to prune and wipe to recover. Refusing every
     * destructive verb until the disk has room would lock them out of the one
     * action that makes room. The failure is logged and returned to the caller
     * as an absent path, so it is visible in the response rather than silent.</p>
     */
    public Path before(String verb) {
        H2ExperienceStore h2 = store.get();
        if (h2 == null || h2.storeDir() == null) {
            return null;
        }
        Path target = backupDir(h2).resolve(name(verb));
        try {
            takeChecked(h2, target, verb);
        } catch (UncleanCopyException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Could not take the pre-{} backup at {}: {}", verb, target, e.getMessage());
            return null;
        }
        evict(depth(), false);
        return target;
    }

    /**
     * One copy that has been checked (REL44-33): up to {@link #CLEAN_ATTEMPTS} attempts, each
     * deleted when its count disagrees with its rows, then {@link UncleanCopyException}. A failure to
     * WRITE a copy still propagates as it always did, so the caller keeps its own rule for that
     * (a full disk must not lock the user out of the operation that makes room). With the check
     * switched off by {@link #withoutCleanCheck} the first copy is kept as it comes.
     */
    private Path takeChecked(H2ExperienceStore h2, Path target, String verb) {
        if (goOnWithoutCleanCopy.get()) {
            copier.copy(h2, target);
            return target;
        }
        String problem = null;
        for (int attempt = 1; attempt <= CLEAN_ATTEMPTS; attempt++) {
            copier.copy(h2, target);
            problem = uncleanReason(target);
            if (problem == null) {
                return target;
            }
            log.warn("The copy before {} is not clean (attempt {} of {}): {} — deleting it", verb,
                attempt, CLEAN_ATTEMPTS, problem);
            deleteQuietly(target);
        }
        throw new UncleanCopyException(verb, CLEAN_ATTEMPTS, problem);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove the backup {}: {}", file, e.getMessage());
        }
    }

    /**
     * Why a copy is not clean, or null when it is. The copy is opened on its own in a scratch folder
     * and its {@code COUNT(*)} is compared with the rows that read. A copy taken while a commit was
     * in progress used to come back one row high (REL44-33); the rows are all there, but a count that
     * disagrees with them is what makes a copy suspect, and so is a copy that is not a readable zip
     * or that H2 cannot open or read. If the check fails on ITS side (no scratch space, the copy file
     * missing) it answers null and logs the copy as UNVERIFIED: that is the check's own failure, and
     * it must not lock the user out of a copy that exists.
     */
    static String uncleanReason(Path zip) {
        Path scratch = null;
        try {
            // Getting the copy into a scratch folder is the CHECK's own business: if that fails the
            // copy is unverified, not damaged, and it is kept (see above).
            try {
                scratch = Files.createTempDirectory("jawata-copy-check");
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
                    for (java.util.zip.ZipEntry entry : java.util.Collections.list(zf.entries())) {
                        if (entry.getName().endsWith(".mv.db")) {
                            try (java.io.InputStream in = zf.getInputStream(entry)) {
                                Files.copy(in, scratch.resolve("experience.mv.db"));
                            }
                        }
                    }
                }
            } catch (java.util.zip.ZipException | java.io.EOFException e) {
                // The zip itself is malformed or cut short: that is the copy's fault, not the check's.
                return "the copy is not a readable zip: " + e.getMessage();
            } catch (IOException | RuntimeException e) {
                log.warn("UNVERIFIED: could not unpack the copy {} to check it: {} — keeping it as"
                    + " taken", zip, e.getMessage());
                return null;
            }
            // From here on a failure IS about the copy: H2 cannot open it or cannot read its rows.
            // That is exactly a damaged copy, so it is not kept.
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:file:" + scratch.resolve("experience").toAbsolutePath()
                + ";IFEXISTS=TRUE");
            try (java.sql.Connection c = ds.getConnection();
                    java.sql.Statement s = c.createStatement()) {
                long counted;
                try (java.sql.ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM experience_entry")) {
                    rs.next();
                    counted = rs.getLong(1);
                }
                long rows = 0;
                try (java.sql.ResultSet rs = s.executeQuery("SELECT id, summary FROM experience_entry")) {
                    while (rs.next()) {
                        rows++;
                    }
                }
                return counted == rows ? null
                    : "COUNT(*) says " + counted + " but " + rows + " rows read";
            } catch (java.sql.SQLException e) {
                return "the copy cannot be opened or read: " + e.getMessage().split("\n")[0];
            }
        } finally {
            if (scratch != null) {
                try (Stream<Path> walk = Files.walk(scratch)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(StoreBackups::deleteQuietly);
                } catch (IOException e) {
                    log.warn("Could not remove the scratch folder {}: {}", scratch, e.getMessage());
                }
            }
        }
    }

    /**
     * Sprint 28g S13-9 — the daily copy, taken because a day has passed and not because a
     * destructive verb was about to run.
     *
     * <p>Due when no scheduled copy exists or the newest is at least {@link #INTERVAL} old. The
     * time is a parameter so a test can cross a day boundary without waiting for one, and so the
     * caller states which clock it means.</p>
     *
     * <p>Answers the copy, or null when none was taken: nothing was due, the store has no file, the
     * copy could not be written, or the copy was written and could not be read back. The last two
     * are logged, because a backup that quietly does not happen is the failure this exists to end.
     * A copy that cannot be read back is DELETED and not counted: it would otherwise sit in the
     * rotation as the newest copy while being useless, and push a good one out.</p>
     */
    public Path takeIfDue(Instant now) {
        H2ExperienceStore h2 = store.get();
        if (h2 == null || h2.storeDir() == null) {
            return null;
        }
        Instant newest = newestScheduled();
        if (newest != null && java.time.Duration.between(newest, now).compareTo(INTERVAL) < 0) {
            return null;
        }
        Path target = backupDir(h2).resolve(name(SCHEDULED_VERB, now));
        try {
            copier.copy(h2, target);
        } catch (RuntimeException e) {
            log.warn("Could not take the scheduled backup at {}: {}", target, e.getMessage());
            return null;
        }
        if (!readsBack(target)) {
            log.warn("The scheduled backup at {} could not be read back — removing it, so it does"
                + " not stand in the rotation as a copy that is not one", target);
            deleteQuietly(target);
            return null;
        }
        // REL44-33: a copy that reads but whose count disagrees with its rows is not kept either.
        // Deleting it leaves no copy for today, so the next hourly check finds one due and tries again.
        String problem = uncleanReason(target);
        if (problem != null) {
            log.warn("The scheduled backup at {} is not clean ({}) — removing it; the next hourly"
                + " check tries again", target, problem);
            deleteQuietly(target);
            return null;
        }
        evict(scheduledDepth(), true);
        return target;
    }

    /**
     * Whether a copy can be read back end to end and holds a database file.
     *
     * <p>Reading every entry through to its end is what makes the zip check its own CRC; a copy that
     * merely opens as a zip can still be truncated mid-database. "The file exists" and "the zip
     * opens" are both checks a damaged copy passes. It stops short of restoring the copy into a
     * second store, which the tests do on a sample and a scheduled job should not do on a 90 MB file
     * every day.</p>
     */
    static boolean readsBack(Path zip) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            boolean database = false;
            byte[] buffer = new byte[1 << 16];
            for (java.util.zip.ZipEntry entry : java.util.Collections.list(zf.entries())) {
                long bytes = 0;
                try (java.io.InputStream in = zf.getInputStream(entry)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        bytes += read;
                    }
                }
                if (entry.getName().endsWith(".mv.db") && bytes > 0) {
                    database = true;
                }
            }
            return database;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * What the copies look like right now, for the store's stats: how many, which is newest, and how
     * old the newest SCHEDULED one is. A schedule nobody can see is a schedule nobody notices has
     * stopped, which is how this store came to be a month behind.
     */
    public java.util.Map<String, Object> status(Instant now) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        List<Path> all = list();
        out.put("count", all.size());
        out.put("newest", all.isEmpty() ? null : all.get(0).getFileName().toString());
        Path newestScheduled = all.stream().filter(StoreBackups::isScheduled).findFirst().orElse(null);
        Instant at = newestScheduled == null ? null : instantOf(newestScheduled);
        out.put("newestScheduled", newestScheduled == null ? null
            : newestScheduled.getFileName().toString());
        out.put("newestScheduledAgeHours", at == null ? null
            : java.time.Duration.between(at, now).toHours());
        out.put("dueNow", at == null || java.time.Duration.between(at, now).compareTo(INTERVAL) >= 0);
        out.put("scheduledDepth", scheduledDepth());
        return out;
    }

    /** How many scheduled copies are kept; the same floor of one as {@link #depth()}. */
    public static int scheduledDepth() {
        String raw = EngineEnvironment.settings().get(SettingKeys.BACKUPS_SCHEDULED_DEPTH.name()).orElse(null);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SCHEDULED_DEPTH;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            log.warn("jawata.backups.scheduled.depth is not a number ({}) — keeping {}", raw,
                DEFAULT_SCHEDULED_DEPTH);
            return DEFAULT_SCHEDULED_DEPTH;
        }
    }

    private static boolean isScheduled(Path copy) {
        return copy.getFileName().toString().endsWith("-" + SCHEDULED_VERB + ".zip");
    }

    /** The instant a copy was taken, read from its name; null when the name does not carry one. */
    private static Instant instantOf(Path copy) {
        String name = copy.getFileName().toString();
        int dash = name.lastIndexOf('-');
        try {
            return Instant.from(STAMP.parse(dash < 0 ? name : name.substring(0, dash)));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private Instant newestScheduled() {
        for (Path copy : list()) {
            if (isScheduled(copy)) {
                Instant at = instantOf(copy);
                if (at != null) {
                    return at;
                }
            }
        }
        return null;
    }

    /** Keep the newest {@code keep} of ONE kind — scheduled or not — and delete the rest. */
    private void evict(int keep, boolean scheduled) {
        deleteBeyond(list().stream().filter(p -> isScheduled(p) == scheduled).toList(), keep);
    }

    /** Newest first. Empty when there is no file store or no copy has been taken. */
    public List<Path> list() {
        H2ExperienceStore h2 = store.get();
        if (h2 == null || h2.storeDir() == null) {
            return List.of();
        }
        Path dir = backupDir(h2);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> out = new ArrayList<>(files
                .filter(p -> p.getFileName().toString().endsWith(".zip"))
                .toList());
            // By NAME, not by file time. The name carries the instant the copy
            // was taken, in a form that sorts; a modified time is a property of
            // the filesystem and survives neither a copy nor a restore of the
            // backup directory itself.
            out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
            return out;
        } catch (IOException e) {
            log.warn("Could not list backups in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /** Keep the newest {@code keep}; delete the rest. */
    public void evictBeyond(int keep) {
        deleteBeyond(list(), keep);
    }

    /** Delete everything past the first {@code keep} of a newest-first list. */
    private static void deleteBeyond(List<Path> newestFirst, int keep) {
        for (int i = keep; i < newestFirst.size(); i++) {
            try {
                Files.deleteIfExists(newestFirst.get(i));
            } catch (IOException e) {
                log.warn("Could not evict old backup {}: {}", newestFirst.get(i), e.getMessage());
            }
        }
    }

    /**
     * What a restore did: the copy it put back, and the copy of the state it
     * REPLACED, so a restore chosen by mistake is itself undoable.
     *
     * <p>{@code safetyCopy} is null for exactly ONE reason — the copy could not
     * be written — and a caller must report that absence rather than let it read
     * like a copy nobody mentioned. That is NARROWER than
     * {@link #before(String)}, which is also null when there is no file store at
     * all: a restore cannot reach here in that state, because {@code restore}
     * throws on it first. The shared response note names both reasons, which
     * stays true here without being tight.</p>
     */
    public record Restored(Path from, Path safetyCopy) {}

    /**
     * Put a named copy back, through the store's own close-and-reopen path.
     *
     * <p>The name is resolved against the backup directory rather than taken as
     * a path, so a caller cannot be talked into restoring an arbitrary file over
     * the store. A name that does not match a copy this class knows about is
     * refused with the list of the ones it does.</p>
     *
     * <p><b>A restore is a destructive verb and takes its own copy first,</b>
     * which is a WIDENING of the spec's enumeration (<i>wipe_and_import, wipe,
     * prune, import</i>) under that same sentence's universal — "every
     * destructive verb". Restoring replaces every row with the ones the copy
     * holds, so anything written since that copy is gone; that is the hazard the
     * clause exists for, and it is worst in the one verb whose whole purpose is
     * recovery.</p>
     *
     * <p><b>The order is load-bearing:</b> the chosen copy is resolved BEFORE the
     * safety copy is taken. Taken first, the safety copy would run the rotation
     * and — at the depth — evict the oldest copy, which is the one a caller
     * reaching back furthest is most likely to have asked for. The restore would
     * then fail on a name that was valid when they read it.</p>
     */
    public Restored restore(String name) {
        H2ExperienceStore h2 = store.get();
        if (h2 == null || h2.storeDir() == null) {
            throw new IllegalStateException(
                "this resident has no file store, so there is nothing to restore into.");
        }
        Path chosen = null;
        for (Path p : list()) {
            if (p.getFileName().toString().equals(name)) {
                chosen = p;
                break;
            }
        }
        if (chosen == null) {
            List<String> known = list().stream().map(p -> p.getFileName().toString()).toList();
            throw new IllegalStateException("no backup named '" + name + "'. Known: " + known);
        }
        Path safety = safetyCopy(h2);
        h2.restoreFrom(chosen);
        return new Restored(chosen, safety);
    }

    /**
     * The copy of the state a restore is about to replace.
     *
     * <p><b>It deliberately does not evict.</b> Eviction is what makes the order
     * above matter, and running it here would re-open the same hole one step
     * later — the copy being restored from is still needed by the very next
     * line. Going one over the depth is self-correcting: the next
     * {@link #before(String)} rotates back down.</p>
     */
    private Path safetyCopy(H2ExperienceStore h2) {
        Path target = backupDir(h2).resolve(name("restore"));
        try {
            return takeChecked(h2, target, "restore");
        } catch (UncleanCopyException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Could not take the pre-restore backup at {}: {}", target, e.getMessage());
            return null;
        }
    }

    static Path backupDir(H2ExperienceStore h2) {
        return h2.storeDir().resolve("backups");
    }

    /**
     * The copy's name: the instant, then the verb that was about to run.
     *
     * <p>Both halves earn their place. The instant sorts, so the newest copy is
     * the last name and eviction needs no file metadata. The verb is what a
     * human reads when choosing which copy to go back to — "before the wipe" and
     * "before the prune" are different decisions, and a list of bare timestamps
     * makes the user reconstruct which was which from memory.</p>
     */
    static String name(String verb) {
        return name(verb, Instant.now());
    }

    /** The same name for a stated instant — a scheduled copy is named for the time it was due. */
    static String name(String verb, Instant at) {
        return STAMP.format(at) + "-" + verb + ".zip";
    }

    /**
     * FIXED WIDTH, and that is the whole reason this formatter exists rather than
     * {@code Instant.toString()}.
     *
     * <p>Java prints an instant with 0, 3, 6 or 9 fractional digits, dropping
     * trailing zero groups — so one copy reads {@code ...07.1Z} and the next
     * {@code ...07.15Z}. Compared as text, {@code 15Z} sorts BEFORE {@code 1Z},
     * because '5' is below 'Z'. The newer copy would sort as the older one, and
     * since eviction keeps the head of that ordering, the rotation would delete
     * the copy it had just taken and keep one it meant to drop.</p>
     *
     * <p>Nine digits always, so the text order is the time order. Found while
     * writing the eviction test, not by it.</p>
     */
    private static final java.time.format.DateTimeFormatter STAMP =
        new java.time.format.DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH-mm-ss")
            .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 9, 9, true)
            .toFormatter()
            .withZone(java.time.ZoneOffset.UTC);
}
