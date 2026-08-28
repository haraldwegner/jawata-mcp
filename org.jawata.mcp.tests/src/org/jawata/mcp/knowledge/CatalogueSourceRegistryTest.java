package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE REGISTRY IS THE ONE LIST — Sprint 28d Stage 2 (ARCHITECTURE-28d step 1).
 *
 * <p><b>What this replaces.</b> Four production sites hardcoded the single
 * catalogue's class or its {@code source_ref} prefix: the boot seeder, the
 * address renderer, the {@code stats} block and the reseed's kept-counts. A
 * second source would have had to be added to all four, found by memory. These
 * tests pin the properties that make one list enough.</p>
 */
class CatalogueSourceRegistryTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /**
     * A source is CHEAP TO CONSTRUCT — the interface says so, and the registry
     * relies on it: {@code stats} builds every source just to ask which
     * namespace owns a row. The bundled snapshot is ~200 patterns of JSON, so an
     * eager constructor would put that parse on a read path called constantly.
     *
     * <p>Asserted by CONSTRUCTING MANY and requiring it to stay fast. A timing
     * assertion is a weak instrument in general — this one is deliberately three
     * orders of magnitude off the eager cost, so it can only fail if the
     * laziness is actually gone.</p>
     */
    @Test
    void constructing_a_source_does_not_parse_its_snapshot() {
        long start = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            assertFalse(CatalogueSources.all().isEmpty());
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 500,
            () -> "500 registry builds took " + millis + " ms — a source is parsing its"
                + " snapshot in its constructor, which puts that cost on every stats call");
    }

    /** Ownership has ONE home, so two callers cannot disagree about a row. */
    @Test
    void the_registry_answers_who_owns_a_row() {
        CatalogueOrigin fork = CatalogueSources.all().get(0);
        String mine = fork.prefix() + "some-pattern/README.md";

        assertNotNull(CatalogueSources.owning(mine));
        assertEquals(fork.namespace(), CatalogueSources.owning(mine).namespace());
        assertTrue(CatalogueSources.isCatalogue(mine));

        assertNull(CatalogueSources.owning("memory:/home/h/stories/x.md"),
            "a story is not a catalogue row");
        assertNull(CatalogueSources.owning(null),
            "a direct record has no source at all — and null must be an answer,"
                + " not an exception, because every caller passes one");
        assertFalse(CatalogueSources.isCatalogue("memory:/home/h/stories/x.md"));
    }

    /** Every source declares the three things the registry needs of it. */
    @Test
    void every_registered_source_declares_its_identity_and_authority() {
        List<CatalogueOrigin> all = CatalogueSources.all();
        assertFalse(all.isEmpty(), "an empty registry means nothing seeds");
        for (CatalogueOrigin o : all) {
            assertFalse(o.namespace().isBlank(), "a namespace is how a degradation names it");
            assertFalse(o.prefix().isBlank(), "a prefix is the ownership key");
            assertFalse(o.manifestResource().isBlank(),
                "an origin that names no manifest can never be read, and would register"
                    + " a namespace that seeds nothing");
            // S6: the authority moved OFF the origin and into its manifest, because
            // both origins derive it from one — the fork from its pinned commit, ours
            // from its own field — so a value held on the record would be wrong for
            // the fork the moment the pin moved, and would report a stale pin without
            // saying so. It is still asserted, just read from where it now lives.
            assertFalse(CatalogueManifest.authorityOf(o).isBlank(),
                "authority distinguishes a pinned foreign source from our own — the"
                    + " difference that decides whether addresses can drift");
        }
    }

    /**
     * PER-NAMESPACE COUNTS, and every registered namespace present even at ZERO.
     *
     * <p>An absent key and a zero must not read alike: "which catalogue is empty?"
     * is the question a single global total cannot answer, and it is the question
     * a degradation line has to answer once there is more than one source.</p>
     */
    @SuppressWarnings("unchecked")
    @Test
    void stats_reports_each_namespace_separately_including_the_empty_ones() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "stats");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "stats failed: " + r.getError());

        Map<String, Object> catalogue =
            (Map<String, Object>) ((Map<String, Object>) r.getData()).get("catalogue");
        Map<String, Object> byNamespace = (Map<String, Object>) catalogue.get("byNamespace");
        assertNotNull(byNamespace, "the per-namespace block must exist");

        for (CatalogueOrigin o : CatalogueSources.all()) {
            assertTrue(byNamespace.containsKey(o.namespace()),
                () -> "namespace " + o.namespace() + " is registered and must be REPORTED"
                    + " even holding nothing: " + byNamespace);
            assertEquals(0, byNamespace.get(o.namespace()),
                "this store was never seeded, so every namespace is legitimately zero"
                    + " — and says zero rather than saying nothing");
        }
        assertEquals(0, catalogue.get("entries"));
    }

    /**
     * Seeding runs THROUGH the registry, and EACH source's rows land in its own
     * namespace — the property that only exists once there is more than one.
     *
     * <p>This assertion was first written summing every source's seed count and
     * comparing it against one namespace, which passed only while a single
     * source was registered. Registering the second made it fail with
     * {@code expected 189 but was 187} — the test asserting a one-source world
     * out loud. Per-source is what the registry actually promises.</p>
     */
    @SuppressWarnings("unchecked")
    @Test
    void each_sources_rows_land_in_its_own_namespace() {
        Map<String, Integer> seededBySource = new java.util.LinkedHashMap<>();
        int total = 0;
        for (CatalogueOrigin o : CatalogueSources.all()) {
            // S6: seeding is no longer a method ON the source — an origin is a
            // record, so there is nothing for it to implement wrongly. The one
            // lifecycle takes the origin instead.
            int n = CatalogueSeeder.seed(store, o).seeded();
            seededBySource.put(o.namespace(), n);
            total += n;
        }
        assertTrue(total > 0, "the bundled sources must actually seed");
        assertTrue(seededBySource.size() >= 2,
            "this test is about the multi-source property; with one source it proves"
                + " nothing the single-namespace case did not already prove");

        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "stats");
        Map<String, Object> catalogue = (Map<String, Object>)
            ((Map<String, Object>) tool.execute(a).getData()).get("catalogue");
        Map<String, Object> byNamespace = (Map<String, Object>) catalogue.get("byNamespace");

        for (Map.Entry<String, Integer> seeded : seededBySource.entrySet()) {
            assertEquals(seeded.getValue(), byNamespace.get(seeded.getKey()),
                () -> "namespace " + seeded.getKey() + ": what its source seeded and what"
                    + " stats reports for it are the same number, or one of them is"
                    + " lying — and a row counted under the wrong namespace is exactly"
                    + " the drift the registry exists to make impossible: " + catalogue);
        }
        assertEquals(total, catalogue.get("entries"),
            "and the total is the sum of the namespaces, not a separately maintained count");
    }
}
