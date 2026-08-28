package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d Stage 6 / S8 — WHAT EVERY REGISTERED ORIGIN OWES.
 *
 * <h2>The failure this exists to make impossible</h2>
 *
 * <p>There were two catalogue sources and one lifecycle test, bound to one of
 * them. The other's {@code seed} was nine lines that performed no supersession
 * at all, and nothing noticed for two sprints — because <b>a contract asserted
 * about one implementation says nothing about the second</b>. The fix at S6 was
 * structural: an origin is a record, so there is no method left to implement
 * wrongly. This test closes the remaining half — that every origin the registry
 * offers is actually WELL-FORMED, and that the properties are asserted OVER THE
 * REGISTRY rather than over a name someone remembered to add.</p>
 *
 * <p>So every assertion below iterates {@link CatalogueSources#all()}. A third
 * origin registered next year is covered the day it is registered, by
 * construction, without anybody remembering this file exists. That is the
 * difference between a contract test and a test that happens to check two
 * things.</p>
 */
class CatalogueOriginContractTest {

    private static List<CatalogueOrigin> origins() {
        List<CatalogueOrigin> all = CatalogueSources.all();
        assertFalse(all.isEmpty(),
            "an EMPTY registry means nothing seeds, and every assertion below would pass"
                + " vacuously — a green run over zero origins proves nothing at all");
        return all;
    }

    @Test
    void every_origin_is_addressable() {
        for (CatalogueOrigin o : origins()) {
            assertNotNull(o.namespace(), "a null namespace cannot name anything");
            assertFalse(o.namespace().isBlank(),
                "the namespace is how a degradation line names WHICH catalogue is empty;"
                    + " blank makes 'catalogue  holds zero rows' the message a user gets");
            assertEquals("catalogue:" + o.namespace() + "/", o.prefix(),
                "the prefix is DERIVED from the namespace and must stay derived: every"
                    + " ownership question in the lane keys on this exact string, so a"
                    + " prefix that drifted from its namespace would make rows owned by"
                    + " nobody — invisible rather than mis-filed");
        }
    }

    @Test
    void every_origin_names_a_manifest_that_actually_loads() {
        for (CatalogueOrigin o : origins()) {
            assertFalse(o.manifestResource().isBlank(),
                o.namespace() + " names no manifest, so it can never be read — it would"
                    + " register a namespace that seeds nothing and says nothing");

            // Not "the field is non-blank" but "the resource is THERE and parses".
            // A manifest path that is merely well-formed is the shape of a
            // registration that looks complete and ships an empty namespace.
            CatalogueManifest manifest = CatalogueManifest.read(o);
            assertTrue(manifest.size() > 0,
                o.namespace() + "'s manifest loaded but carries NO rows. An origin that"
                    + " seeds nothing is indistinguishable from one that is broken, and"
                    + " the registry would report the namespace present at zero");
            assertEquals(manifest.size(), manifest.declaredCount(),
                o.namespace() + "'s manifest must DECLARE the number of rows it carries."
                    + " The orphan sweep refuses to run without that agreement, so a"
                    + " mismatch here silently disables retirement for this origin —"
                    + " the exact defect this stage was opened to fix");
        }
    }

    @Test
    void every_origin_states_an_authority() {
        for (CatalogueOrigin o : origins()) {
            String authority = CatalogueManifest.authorityOf(o);
            assertFalse(authority.isBlank(),
                o.namespace() + " states no authority. It distinguishes a pinned FOREIGN"
                    + " source, whose addresses must be re-resolved when the pin moves,"
                    + " from one that versions with this product and cannot drift");
            assertFalse("UNPINNED".equals(authority),
                o.namespace() + " resolved to UNPINNED — the manifest declares neither an"
                    + " authority nor a pinned commit, so nothing can say which version"
                    + " its rows came from");
        }
    }

    @Test
    void no_two_origins_can_claim_the_same_row() {
        Set<String> prefixes = new LinkedHashSet<>();
        for (CatalogueOrigin o : origins()) {
            assertTrue(prefixes.add(o.prefix()),
                "two origins share the prefix " + o.prefix() + ". Ownership is resolved by"
                    + " FIRST match, so the second would be permanently unreachable while"
                    + " appearing registered — and its rows would be swept as orphans by"
                    + " the first origin's seed, which claims none of them");
        }

        // The subtler collision: not equal, but one a prefix OF another. First-match
        // ownership makes the longer one unreachable just as completely.
        List<String> all = new ArrayList<>(prefixes);
        for (String a : all) {
            for (String b : all) {
                if (!a.equals(b)) {
                    assertFalse(b.startsWith(a),
                        "prefix '" + a + "' swallows '" + b + "': every row of the second"
                            + " matches the first, so owning() answers with the wrong"
                            + " origin and the seeder retires rows it does not own");
                }
            }
        }
    }

    @Test
    void a_retired_prefix_never_overlaps_a_live_one(@TempDir Path dir) throws Exception {
        List<String> live = origins().stream().map(CatalogueOrigin::prefix).toList();
        for (CatalogueOrigin o : origins()) {
            for (String retired : o.retiredPrefixes()) {
                for (String alive : live) {
                    assertFalse(alive.startsWith(retired) || retired.startsWith(alive),
                        o.namespace() + " retires '" + retired + "', which overlaps the LIVE"
                            + " prefix '" + alive + "'. The migration supersedes every row"
                            + " under a retired prefix without the completeness guard —"
                            + " deliberately, since a retired spelling has no current input"
                            + " — so an overlap would retire a live origin's whole catalogue"
                            + " on the next boot");
                }
            }
        }
    }

    /**
     * The lifecycle is not opt-in for any origin, present or future.
     *
     * <p>Seeding twice from the same manifest must write nothing the second time.
     * That is the property the old {@code seed}-per-source design could not
     * guarantee — one implementation had it and the other did not — and it is
     * asserted here over EVERY registered origin rather than over the one whose
     * test happened to exist.</p>
     */
    @Test
    void every_origin_gets_the_same_lifecycle(@TempDir Path dir) throws Exception {
        for (CatalogueOrigin o : origins()) {
            try (H2ExperienceStore store = H2ExperienceStore.open(dir.resolve(o.namespace()))) {
                CatalogueSeeder.Outcome first = CatalogueSeeder.seed(store, o);
                assertTrue(first.seeded() > 0,
                    "PROOF OF LIFE: " + o.namespace() + " must actually seed before anything"
                        + " about its idempotence can mean anything");

                CatalogueSeeder.Outcome second = CatalogueSeeder.seed(store, o);
                assertEquals(0, second.seeded(),
                    o.namespace() + " RE-SEEDED on a second run. Every boot would duplicate"
                        + " its rows, and duplicates spend the budget an answer has");
                assertEquals(first.seeded(), second.unchanged(),
                    o.namespace() + " must recognise every row it already wrote as current,"
                        + " rather than seeding nothing because it read nothing");
                assertEquals(0, second.retired() + second.migrated(),
                    o.namespace() + " retired or migrated something on an unchanged second"
                        + " run — a lifecycle that keeps finding work on a store it just"
                        + " wrote is reporting activity that did not happen");
            }
        }
    }
}
