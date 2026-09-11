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

    private final Supplier<H2ExperienceStore> store;

    public StoreBackups(Supplier<H2ExperienceStore> store) {
        this.store = store;
    }

    /** How many copies are kept right now — published because the verbs report it. */
    public static int depth() {
        String raw = System.getProperty(DEPTH_PROPERTY);
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
            h2.backupTo(target);
        } catch (RuntimeException e) {
            log.warn("Could not take the pre-{} backup at {}: {}", verb, target, e.getMessage());
            return null;
        }
        evictBeyond(depth());
        return target;
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
        List<Path> all = list();
        for (int i = keep; i < all.size(); i++) {
            try {
                Files.deleteIfExists(all.get(i));
            } catch (IOException e) {
                log.warn("Could not evict old backup {}: {}", all.get(i), e.getMessage());
            }
        }
    }

    /**
     * What a restore did: the copy it put back, and the copy of the state it
     * REPLACED, so a restore chosen by mistake is itself undoable.
     *
     * <p>{@code safetyCopy} is null on the same terms as {@link #before(String)} —
     * the copy could not be written — and a caller must report that absence
     * rather than let it read like a copy nobody mentioned.</p>
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
            h2.backupTo(target);
            return target;
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
        return STAMP.format(Instant.now()) + "-" + verb + ".zip";
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
