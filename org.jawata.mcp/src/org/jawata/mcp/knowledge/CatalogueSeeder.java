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
 * <p>Retire BEFORE writing the newcomer. On a store that could be read
 * concurrently, retire-then-write leaves a moment with no live copy and
 * write-then-retire leaves a moment with two — and two live copies is the defect
 * itself, because the address index resolves whichever row comes back first.</p>
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
     * separation is the point.</b> Both end with a row superseded, but they answer
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

        // Read the live rows ONCE, before the loop. Asking the store per item
        // would be a full scan per item, which is the kind of quiet quadratic
        // that only shows up on somebody else's machine.
        Map<String, List<String>> liveByRef = new HashMap<>();
        for (StoredEntry e : store.all()) {
            String ref = e.sourceRef();
            if (ref != null && ref.startsWith(prefix)
                    && !ExperienceEntry.SUPERSEDED.equals(e.status())) {
                liveByRef.computeIfAbsent(ref, k -> new ArrayList<>()).add(e.id());
            }
        }

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
            for (String incumbent : liveByRef.getOrDefault(item.sourceRef(), List.of())) {
                if (store.setStatus(incumbent, ExperienceEntry.SUPERSEDED)) {
                    retired++;
                }
            }
            store.putWithSource(item.entry(), item.sourceRef(), item.hash());
            seeded++;
        }

        retired += sweepOrphans(store, liveByRef, claimed, declaredCount, bounded, authorityRef);
        return new Outcome(seeded, unchanged, retired, migrated);
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
     * <p>Idempotent by construction — it only ever touches rows that are still
     * live, so a second run finds none and reports zero. That is what makes a
     * recurring non-zero count meaningful rather than noise.</p>
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

        int migrated = 0;
        List<String> migratedRefs = new ArrayList<>();
        for (StoredEntry e : store.all()) {
            String ref = e.sourceRef();
            if (ref == null || ExperienceEntry.SUPERSEDED.equals(e.status())) {
                continue;
            }
            for (String retired : safe) {
                if (ref.startsWith(retired)) {
                    if (store.setStatus(e.id(), ExperienceEntry.SUPERSEDED)) {
                        migrated++;
                        migratedRefs.add(ref);
                    }
                    break;
                }
            }
        }
        if (migrated > 0) {
            // NAMED, like the orphan sweep: this should fire once per install and
            // never again, so a reader needs to see WHICH rows moved, not just how
            // many — a recurring count is a defect, and the refs say where to look.
            log.info("Catalogue: migrated {} row(s) off retired prefix(es) {} onto '{}': {}",
                migrated, safe, prefix, migratedRefs);
        }
        return migrated;
    }

    /**
     * THE ROWS THE INPUT NO LONGER CLAIMS.
     *
     * <p>Supersession above is per-item and keyed on the ref that item claims, so
     * it can only ever retire a row the CURRENT input still names. An item the
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

        int orphaned = 0;
        List<String> orphanRefs = new ArrayList<>();
        for (Map.Entry<String, List<String>> live : liveByRef.entrySet()) {
            if (claimed.contains(live.getKey())) {
                continue;
            }
            for (String id : live.getValue()) {
                if (store.setStatus(id, ExperienceEntry.SUPERSEDED)) {
                    orphaned++;
                }
            }
            orphanRefs.add(live.getKey());
        }
        if (orphaned > 0) {
            // NAMED, not just counted: "3 retired" is the same line whether
            // upstream dropped three items or an input bump mangled three slugs,
            // and those need opposite responses.
            log.info("Catalogue: retired {} row(s) for {} item(s) no longer in the input at {}: {}",
                orphaned, orphanRefs.size(), authorityRef, orphanRefs);
        }
        return orphaned;
    }
}
