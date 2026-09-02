package org.jawata.mcp.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE ONE CATALOGUE LIFECYCLE — every source's rows are seeded, retired and swept
 * here, and nowhere else.
 *
 * <h2>Why this exists (Sprint 28d Stage 6, ARCHITECTURE-28d v2)</h2>
 *
 * <p>Until now {@code CatalogueSource} was an interface carrying a {@code seed}
 * method, so each source wrote its own lifecycle. That is an invitation, and it
 * had already been accepted wrongly: one source's {@code seed} was nine lines
 * that skipped supersession entirely, and nothing in the type system noticed. It
 * shipped both the duplicate-on-edit defect and the orphan defect that the other
 * source had already fixed — while the spec's D10 said, from the start,
 * <em>"one registry seeds them all"</em>.</p>
 *
 * <p>So the lifecycle moved OUT of the sources. A source contributes only the
 * rows it currently claims; everything that can diverge lives in one place. This
 * class is deliberately not a template method with hooks — hooks are the same
 * invitation one level down. It takes DATA and runs one algorithm over it.</p>
 *
 * <h2>The order of operations, and why it is that order</h2>
 *
 * <p>Remove the incumbent BEFORE writing the newcomer. On a store that could be
 * read concurrently, remove-then-write leaves a moment with no live copy and
 * write-then-remove leaves a moment with two — and two live copies is the defect
 * itself, because the address index resolves whichever row comes back first.</p>
 *
 * <h2>REMOVE, not supersede (2026-09-02, Harald's ruling)</h2>
 *
 * <p>Until this version every step here marked the displaced row
 * {@code superseded} and left it in the store. Nothing collected those rows —
 * the per-item path only iterates refs the current input names, and the orphan
 * sweep reads live rows only — so each pass over an edited catalogue left one
 * behind per changed pattern, permanently. Measured on the author's own store:
 * 187 live rows and 187 superseded ones. His ruling: <em>"I do not want to have
 * a new version of the catalogue and leave the old in there. 10 updates 1870
 * redundant entries!"</em></p>
 *
 * <p>The reason removal is right HERE and would be wrong for a hand-written
 * experience: a catalogue row is DERIVED. Its manifest reproduces it exactly, so
 * the copy a replacement displaces is a duplicate answering the same address,
 * not a piece of history anything can rebuild from.</p>
 */
public final class CatalogueSeeder {

    private static final Logger log = LoggerFactory.getLogger(CatalogueSeeder.class);

    private CatalogueSeeder() {
    }

    /**
     * One row a source claims: where it lives, what it currently hashes to, and
     * the entry to write if it changed.
     *
     * <p>The source composes the {@code sourceRef}; the seeder never does. An
     * address built here would be a claim about a file nobody looked at.</p>
     */
    public record SeedItem(String sourceRef, String hash, ExperienceEntry entry) {
    }

    /**
     * What one lifecycle run did. Deliberately NOT the caller's own result type:
     * how many items a source considered is the source's business (it may have
     * skipped malformed ones), while what the lifecycle wrote is this class's.
     *
     * <p><b>{@code migrated} is counted APART from {@code retired}, and the
     * separation is the point.</b> Both end with a row removed, but they answer
     * opposite questions. A retirement says the current input dropped an item or
     * replaced it — ordinary traffic, a number that moves whenever the source
     * changes. A migration says rows were found under a spelling this source no
     * longer uses, which should happen ONCE per install and then never again.
     * Folded into one number, a migration recurring on every boot would read as
     * normal churn, and that is exactly the signal worth seeing. Same reasoning as
     * the sweep's own log line, which names its orphans rather than counting them:
     * two causes needing opposite responses must not share a number.</p>
     */
    public record Outcome(int seeded, int unchanged, int retired, int migrated) {
    }

    /**
     * THE FRONT DOOR — seed one origin from its own manifest.
     *
     * <p>This is what replaced {@code CatalogueSource#seed}. The difference is not
     * cosmetic: an origin is now a record, so there is no method on it for a source
     * to implement wrongly, and every step that can diverge between origins happens
     * here, once. The old interface invited each source to write its own lifecycle
     * and one of them accepted — skipping supersession entirely, and shipping both
     * defects the other lane had already fixed.</p>
     *
     * @param limit stop after this many rows, or 0 for all. A bounded read is a
     *     deliberate SAMPLE and is passed through as such, so the orphan sweep
     *     withholds itself — otherwise almost every row is unclaimed for the
     *     trivial reason the sample never reached it
     */
    public static Outcome seed(ExperienceStore store, CatalogueOrigin origin, int limit) {
        return seed(store, origin, CatalogueManifest.read(origin), limit);
    }

    /**
     * Seed from a manifest already in hand.
     *
     * <p>The seam for a caller that HAS the manifest rather than a resource path —
     * the lifecycle contract tests, which construct snapshots in memory precisely
     * so that "an upstream edit" and "a truncated read" are things a test can
     * cause. Reading a real resource cannot express either.</p>
     */
    public static Outcome seed(ExperienceStore store, CatalogueOrigin origin,
                               CatalogueManifest manifest, int limit) {
        return seed(store, origin.prefix(), manifest.items(limit), manifest.declaredCount(),
            limit > 0, manifest.authority(), origin.retiredPrefixes());
    }

    /** Seed one origin's whole manifest. */
    public static Outcome seed(ExperienceStore store, CatalogueOrigin origin) {
        return seed(store, origin, 0);
    }

    /**
     * Seed one source's claimed rows.
     *
     * @param store         the target
     * @param prefix        this source's ownership key — every row it owns starts
     *                      with it, and no other source's rows do
     * @param items         the rows this source claims RIGHT NOW, already limited
     *                      if the caller is sampling
     * @param declaredCount what the source's input says it should contain — the
     *                      completeness guard, see below. Negative when the input
     *                      declares nothing, which disables the sweep
     * @param bounded       true when {@code items} is a deliberate SAMPLE rather
     *                      than the whole input
     * @param authorityRef  the version identity reported in the run's result
     * @param retiredPrefixes spellings this source USED to own and no longer does.
     *     Every live row under one of them is superseded before anything else
     *     happens. Empty for a source that has never been renamed — which is every
     *     source until it is, and then forever after, because the migration must
     *     keep running for installs that never saw the intervening versions
     */
    public static Outcome seed(
            ExperienceStore store, String prefix, List<SeedItem> items,
            int declaredCount, boolean bounded, String authorityRef, List<String> retiredPrefixes) {

        // FIRST, before anything reads the current prefix: clear any spelling this
        // source has abandoned. It must precede the rest because every structure
        // below is keyed on the CURRENT prefix and therefore cannot see the old
        // one at all.
        int migrated = migrateRetiredPrefixes(store, prefix, retiredPrefixes);

        // Read the rows ONCE, before the loop. Asking the store per item would be
        // a full scan per item, which is the kind of quiet quadratic that only
        // shows up on somebody else's machine.
        //
        // Superseded rows are collected SEPARATELY rather than skipped: under this
        // prefix they are the leftovers of the version that used to supersede in
        // place, and they are what this pass exists to clear.
        Map<String, List<String>> liveByRef = new HashMap<>();
        List<String> leftovers = new ArrayList<>();
        for (StoredEntry e : store.all()) {
            String ref = e.sourceRef();
            if (ref == null || !ref.startsWith(prefix)) {
                continue;
            }
            if (ExperienceEntry.SUPERSEDED.equals(e.status())) {
                leftovers.add(e.id());
            } else {
                liveByRef.computeIfAbsent(ref, k -> new ArrayList<>()).add(e.id());
            }
        }
        purgeLeftovers(store, prefix, leftovers);

        int seeded = 0;
        int unchanged = 0;
        int retired = 0;
        Set<String> claimed = new HashSet<>();

        for (SeedItem item : items) {
            claimed.add(item.sourceRef());
            if (store.sourceUnchanged(item.sourceRef(), item.hash())) {
                unchanged++;
                continue;
            }
            // REMOVE the incumbent, do not supersede it. A catalogue row is
            // DERIVED — the manifest can produce it again — so the copy it
            // replaces is not history, it is a duplicate that answers the same
            // address. Superseding kept one such copy PER EDIT: ten passes over
            // 187 rows left 1,870 rows behind, and nothing ever collected them.
            retired += store.deleteByIds(liveByRef.getOrDefault(item.sourceRef(), List.of()));
            store.putWithSource(item.entry(), item.sourceRef(), item.hash());
            seeded++;
        }

        retired += sweepOrphans(store, liveByRef, claimed, declaredCount, bounded, authorityRef);
        return new Outcome(seeded, unchanged, retired, migrated);
    }

    /**
     * THE LEFTOVERS OF AN EARLIER LIFECYCLE, CLEARED ONCE.
     *
     * <p>Until this version every replacement here marked the incumbent
     * {@code superseded} and left it in the store. Nothing ever collected those
     * rows: the per-item path only iterates refs the current input names, and the
     * orphan sweep reads LIVE rows only, so a superseded row was unreachable by
     * both. They accumulated one per edited pattern per pass.</p>
     *
     * <p>So this pass removes every superseded row under the prefix. It is keyed
     * on ids, not on the ref, because a leftover and the row that replaced it
     * share a ref — {@code deleteBySource} would take the live one with it.</p>
     *
     * <p>Idempotent by construction: the lifecycle no longer creates superseded
     * rows in this lane, so a second run finds none. A recurring non-zero count
     * means something else is still superseding catalogue rows, which is worth
     * seeing — hence the log line.</p>
     */
    private static int purgeLeftovers(ExperienceStore store, String prefix, List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        int purged = store.deleteByIds(ids);
        log.info("Catalogue: removed {} superseded leftover row(s) under '{}' — a catalogue row"
                + " is derived, so the copy a replacement supersedes is a duplicate rather than"
                + " history.", purged, prefix);
        return purged;
    }

    /**
     * THE ONE CHANGE A PREFIX-KEYED LIFECYCLE CANNOT MAKE TO ITSELF.
     *
     * <p>Every ownership question here is keyed on a prefix — {@code owning()},
     * {@code isCatalogue()}, {@link #sweepOrphans}'s {@code liveByRef}, the reseed
     * lane rule. So the instant a source's spelling changes, its existing rows fall
     * out of ALL of them at once. They are not superseded and not swept; they are
     * invisible, still live, and still answering with an address nothing backs. No
     * amount of care inside the per-item loop reaches them, because the loop only
     * ever iterates refs the CURRENT input names.</p>
     *
     * <p><b>Deliberately NOT gated by the completeness guard, and this is the
     * subtle half.</b> {@link #sweepOrphans} withholds itself unless the input is
     * complete, because a truncated read would otherwise retire everything it
     * happens not to carry. That reasoning does not transfer: a retired prefix has
     * no current input AT ALL, so every row under it is stale whatever today's
     * input contains. Sharing the gate would make the migration skip precisely when
     * a partial read most needs it, and skip silently.</p>
     *
     * <p>Idempotent by construction — the rows it touches are REMOVED, so a second
     * run finds none and reports zero. That is what makes a recurring non-zero
     * count meaningful rather than noise.</p>

     * <p>It takes rows under a retired spelling WHATEVER their status, superseded
     * ones included. A superseded row there is doubly dead: it was already a
     * displaced duplicate, and its address is a spelling nothing uses.</p>
     *
     * <p><b>Guarded against self-retirement.</b> A retired prefix that is a prefix
     * OF the current one — or equal to it — would sweep the source's own live rows.
     * That is refused rather than trusted to never be configured.</p>
     */
    private static int migrateRetiredPrefixes(ExperienceStore store, String prefix,
                                              List<String> retiredPrefixes) {
        if (retiredPrefixes == null || retiredPrefixes.isEmpty()) {
            return 0;
        }
        List<String> safe = new ArrayList<>();
        for (String retired : retiredPrefixes) {
            if (retired == null || retired.isBlank()) {
                continue;
            }
            if (prefix.startsWith(retired) || retired.startsWith(prefix)) {
                log.warn("Catalogue: REFUSING to migrate rows at '{}' — it overlaps the source's"
                        + " current prefix '{}', so the sweep would retire the source's own live"
                        + " rows. A retired spelling must be disjoint from the current one.",
                    retired, prefix);
                continue;
            }
            safe.add(retired);
        }
        if (safe.isEmpty()) {
            return 0;
        }

        List<String> doomed = new ArrayList<>();
        List<String> migratedRefs = new ArrayList<>();
        for (StoredEntry e : store.all()) {
            String ref = e.sourceRef();
            if (ref == null) {
                continue;
            }
            for (String retired : safe) {
                if (ref.startsWith(retired)) {
                    // Whatever its status. A row under an abandoned spelling has no
                    // input behind it at all, so a superseded one there is doubly
                    // dead — it was already a duplicate, and its address is gone too.
                    doomed.add(e.id());
                    migratedRefs.add(ref);
                    break;
                }
            }
        }
        int migrated = doomed.isEmpty() ? 0 : store.deleteByIds(doomed);
        if (migrated > 0) {
            // NAMED, like the orphan sweep: this should fire once per install and
            // never again, so a reader needs to see WHICH rows moved, not just how
            // many — a recurring count is a defect, and the refs say where to look.
            log.info("Catalogue: removed {} row(s) under retired prefix(es) {}, now owned by"
                    + " '{}': {}", migrated, safe, prefix, migratedRefs);
        }
        return migrated;
    }

    /**
     * THE ROWS THE INPUT NO LONGER CLAIMS.
     *
     * <p>The replacement above is per-item and keyed on the ref that item claims,
     * so it can only ever remove a row the CURRENT input still names. An item the
     * source drops is never iterated, so its row stayed live indefinitely —
     * carrying a key that still resolves and an address that no longer exists.
     * Nothing failed: the cure audit asks whether a live row carries the key, and
     * one does, so it reported clean while the address was dead.</p>
     *
     * <p><b>TWO GUARDS, because the sweep is far more destructive than the bug if
     * either is missing.</b></p>
     *
     * <ul>
     *   <li><b>bounded</b> — a deliberate sample leaves almost everything
     *       unclaimed for the trivial reason that the sample never reached it.</li>
     *   <li><b>completeness</b> — the input declares its own count, and the sweep
     *       runs only when the claimed rows equal it. Emptiness was the guard here
     *       first, and it only covers zero: an input that parses and yields 40 of
     *       187 items passes an is-it-empty check and retires the other 147 on
     *       every user's next boot. Note {@code declaredCount > 0}, not
     *       {@code >= 0} — an input declaring zero and carrying zero is internally
     *       CONSISTENT, and an earlier version called that complete and swept the
     *       whole catalogue on it. Consistency is not completeness; an empty read
     *       agrees with itself.</li>
     * </ul>
     */
    private static int sweepOrphans(ExperienceStore store, Map<String, List<String>> liveByRef,
                                    Set<String> claimed, int declaredCount, boolean bounded,
                                    String authorityRef) {
        boolean complete = declaredCount > 0 && declaredCount == claimed.size();
        if (bounded) {
            return 0;
        }
        if (!complete) {
            if (!claimed.isEmpty()) {
                log.warn("Catalogue: NOT sweeping orphans at {} — the input declares {} item(s)"
                        + " and yielded {} claimable ref(s). A partial input would retire every"
                        + " item it happens not to carry.", authorityRef, declaredCount,
                    claimed.size());
            }
            return 0;
        }

        List<String> doomed = new ArrayList<>();
        List<String> orphanRefs = new ArrayList<>();
        for (Map.Entry<String, List<String>> live : liveByRef.entrySet()) {
            if (claimed.contains(live.getKey())) {
                continue;
            }
            doomed.addAll(live.getValue());
            orphanRefs.add(live.getKey());
        }
        int orphaned = doomed.isEmpty() ? 0 : store.deleteByIds(doomed);
        if (orphaned > 0) {
            // NAMED, not just counted: "3 retired" is the same line whether
            // upstream dropped three items or an input bump mangled three slugs,
            // and those need opposite responses.
            log.info("Catalogue: removed {} row(s) for {} item(s) no longer in the input at {}: {}",
                orphaned, orphanRefs.size(), authorityRef, orphanRefs);
        }
        return orphaned;
    }
}
