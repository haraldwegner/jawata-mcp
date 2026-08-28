package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d Stage 6 / S8 — THE CONTROL FOR {@link CatalogueOriginContractTest}.
 *
 * <h2>Why the contract test alone does not discharge its own clause</h2>
 *
 * <p>{@code CatalogueOriginContractTest} asserts its properties over
 * {@link CatalogueSources#all()} — the two origins that really exist. Both are
 * well-formed, so it is green, and its green means <b>"these two pass"</b>. It
 * does NOT mean "a malformed third would be caught", and those are different
 * claims: the second is the one C6 actually asks for — <i>a third origin cannot
 * ship without the lifecycle</i>.</p>
 *
 * <p>A suite of assertions that has never been shown to fail is the exact shape
 * this whole stage exists to refuse. Stage 3 recorded "every samples address
 * opens" as met while the module was absent from the model entirely, so the
 * question was VACUOUS rather than failing. A registry-wide contract with no
 * broken input is the same trap one level up.</p>
 *
 * <h2>What each test here does</h2>
 *
 * <p>For every property the contract asserts, this builds an origin that
 * VIOLATES it and shows the production path reports the violation — so the
 * contract's assertion would fire. The pairing is the proof: the contract says
 * "all real origins are well-formed", and this says "and here is what
 * well-formed excludes".</p>
 *
 * <p>Nothing here touches production code. {@link CatalogueManifest#of} is the
 * seam the manifest already exposes for tests, and every broken origin is
 * constructed locally — the real registry is never mutated, so these cannot
 * make the live catalogue misbehave.</p>
 */
class CatalogueOriginContractControlTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A manifest node carrying {@code rows} rows and DECLARING {@code declared}. */
    private static ObjectNode manifestNode(int rows, int declared) {
        ObjectNode root = JSON.createObjectNode();
        root.put("count", declared);
        var arr = root.putArray("patterns");
        for (int i = 0; i < rows; i++) {
            ObjectNode row = arr.addObject();
            row.put("slug", "row-" + i);
            row.put("principle", "a principle");
            row.put("situation", "when something happens");
        }
        return root;
    }

    private static CatalogueOrigin origin(String namespace) {
        return new CatalogueOrigin(namespace, "/nowhere/none.json", "", List.of());
    }

    /**
     * CONTRACT: {@code every_origin_names_a_manifest_that_actually_loads} asserts
     * {@code manifest.size() > 0}. Here is the input that makes it fire.
     */
    @Test
    void an_origin_whose_manifest_carries_no_rows_is_detectable() {
        CatalogueManifest empty = CatalogueManifest.of(origin("bogus"), manifestNode(0, 0));
        assertEquals(0, empty.size(),
            "an origin that seeds nothing must READ as zero rows. If a manifest with no"
                + " rows reported a non-zero size, the contract's size() > 0 assertion"
                + " could never fail and its green would mean nothing");
    }

    /**
     * CONTRACT: the same test asserts {@code size() == declaredCount()}. A mismatch
     * silently disables the orphan sweep for that origin — the exact defect Stage 6
     * was opened to fix — so the agreement must be observable, not assumed.
     */
    @Test
    void a_manifest_that_miscounts_itself_is_detectable() {
        CatalogueManifest lying = CatalogueManifest.of(origin("bogus"), manifestNode(2, 5));
        assertEquals(2, lying.size());
        assertEquals(5, lying.declaredCount());
        assertFalse(lying.size() == lying.declaredCount(),
            "a manifest declaring 5 rows while carrying 2 must be visible as a mismatch;"
                + " if these agreed, retirement would run against an input that cannot"
                + " vouch for its own completeness and would retire whatever it omits");
    }

    /**
     * CONTRACT: a manifest that declares NOTHING must read as -1, not 0.
     *
     * <p>The distinction is load-bearing and easy to lose: -1 disables the orphan
     * sweep deliberately, while 0 would mean "this origin declares it has no rows"
     * and would authorise retiring every row it owns.</p>
     */
    @Test
    void a_manifest_declaring_no_count_disables_the_sweep_rather_than_authorising_it() {
        ObjectNode undeclared = JSON.createObjectNode();
        undeclared.putArray("patterns").addObject().put("slug", "row-0");
        CatalogueManifest m = CatalogueManifest.of(origin("bogus"), undeclared);
        assertEquals(-1, m.declaredCount(),
            "a manifest with no count must report -1 (sweep disabled), NEVER 0 — zero"
                + " reads as a positive claim to hold nothing, which would authorise"
                + " retiring every row this origin owns");
    }

    /**
     * CONTRACT: {@code every_origin_names_a_manifest_that_actually_loads} depends on
     * {@link CatalogueManifest#read} refusing a resource that is not there. A read
     * that returned an empty manifest instead would ship a registered namespace
     * seeding nothing, and say nothing.
     */
    @Test
    void a_manifest_that_is_not_on_the_classpath_fails_loudly() {
        IllegalStateException boom = assertThrows(IllegalStateException.class,
            () -> CatalogueManifest.read(origin("bogus")),
            "a missing manifest must THROW. Returning an empty manifest would make a"
                + " broken build indistinguishable from an origin that legitimately"
                + " holds nothing yet");
        assertTrue(boom.getMessage().contains("bogus")
                && boom.getMessage().contains("/nowhere/none.json"),
            "the refusal must name the origin AND the path it looked at, or the reader"
                + " gets a failure with nothing to act on: " + boom.getMessage());
    }

    /**
     * CONTRACT: {@code no_two_origins_can_claim_the_same_row} asserts no prefix is a
     * prefix of another. This pins down WHICH inputs can actually trigger it, and the
     * first half was a genuine surprise.
     *
     * <p><b>A sibling namespace cannot swallow another, and the trailing slash is
     * why.</b> Written first as {@code java} against {@code java-design-patterns} —
     * the obvious-looking hazard — this control FAILED on its own premise, because
     * {@code catalogue:java-design-patterns/} does not start with
     * {@code catalogue:java/}: the character after {@code java} is {@code -}, not
     * {@code /}. The slash {@link CatalogueOrigin#prefix()} appends is load-bearing
     * rather than cosmetic, and it makes the entire family of sibling collisions
     * impossible by construction.</p>
     *
     * <p><b>What survives the slash is a namespace carrying a separator.</b>
     * {@code java} against {@code java/design} really does swallow, so the contract's
     * check guards a reachable shape rather than being dead code.</p>
     */
    @Test
    void one_namespace_swallowing_another_is_detectable() {
        // Half one: the shape that CANNOT happen. Asserted so that if prefix() ever
        // loses its trailing slash, this goes red and names the reason — rather than
        // the registry quietly acquiring a live collision class it never had.
        String siblingA = origin("java").prefix();
        String siblingB = origin("java-design-patterns").prefix();
        assertEquals("catalogue:java/", siblingA);
        assertFalse(siblingB.startsWith(siblingA),
            "sibling namespaces must NOT collide: '" + siblingA + "' vs '" + siblingB
                + "'. If this ever becomes true, prefix() has lost its trailing slash and"
                + " every namespace that is a text-prefix of another has silently become"
                + " a first-match collision");

        // Half two: the shape that CAN collide is now UNCONSTRUCTIBLE, so what is
        // asserted is the refusal — which exercises production code and goes red the
        // moment the invariant is weakened or removed.
        IllegalArgumentException boom = assertThrows(IllegalArgumentException.class,
            () -> origin("java/design"),
            "a namespace carrying the prefix separator is the one collision the trailing"
                + " slash does not prevent, so CatalogueOrigin must refuse to construct it."
                + " If this stops throwing, 'catalogue:java/' silently swallows"
                + " 'catalogue:java/design/', first-match ownership leaves the nested origin"
                + " permanently unreachable while appearing registered, and its rows are"
                + " swept as orphans by the origin that claims none of them");
        assertTrue(boom.getMessage().contains("java/design"),
            "the refusal must name the offending namespace, or the reader gets a failure"
                + " with nothing to act on: " + boom.getMessage());
    }

    /**
     * CONTRACT: {@code a_retired_prefix_never_overlaps_a_live_one}. The migration
     * supersedes every row under a retired prefix WITHOUT the completeness guard —
     * deliberately, since a retired spelling has no current input — so an overlap
     * would retire a live origin's whole catalogue on the next boot.
     */
    /**
     * The overlap predicate must DISCRIMINATE, which the first version of this test
     * did not check.
     *
     * <p><b>It was a tautology, found by a fresh-context audit 2026-08-28.</b> It set
     * {@code retired := live} and then asserted
     * {@code live.startsWith(retired) || retired.startsWith(live)} — which reduces to
     * {@code live.startsWith(live)}, true for every string that has ever existed. It
     * exercised no production code and could not fail, inside the very file whose job
     * is proving that controls can fail.</p>
     *
     * <p>So both directions are asserted now. The overlapping case must register, and
     * a genuinely-retired spelling must NOT — and it is the second half that carries
     * the weight, because without it the predicate could be true of everything and the
     * first half would still pass.</p>
     *
     * <p><b>The better fix, not taken here.</b> This mirrors the two-line expression
     * written inline in {@code CatalogueOriginContractTest}, so the rule has two
     * homes. The design answer is to give {@link CatalogueOrigin} the invariant in its
     * own compact constructor — refusing a namespace or a retired prefix that collides
     * — which would make the collision unconstructible rather than merely detectable,
     * and would let this control exercise production code by asserting the refusal.
     * That is an open architect proposal awaiting a ruling, so the mirror stands and
     * is named rather than hidden.</p>
     */
    @Test
    void an_origin_retiring_its_own_live_prefix_is_refused() {
        // The hazard: an origin declaring its OWN current spelling retired. The
        // migration supersedes every row under a retired prefix WITHOUT the
        // completeness guard -- deliberately, since a retired spelling has no current
        // input -- so such an origin would retire its entire catalogue on the next boot.
        IllegalArgumentException boom = assertThrows(IllegalArgumentException.class,
            () -> new CatalogueOrigin(
                "bogus", "/nowhere/none.json", "", List.of("catalogue:bogus/")),
            "an origin retiring its own live prefix must be REFUSED at construction. If"
                + " this stops throwing, the origin is constructible and supersedes its"
                + " whole catalogue on the next seed, with no completeness guard in the"
                + " way to stop it");
        assertTrue(boom.getMessage().contains("catalogue:bogus/"),
            "the refusal must name the offending prefix: " + boom.getMessage());

        // And the near miss, so the refusal is not simply "reject every retired prefix":
        // a genuinely retired spelling under the SAME scheme must still construct. This
        // is the discrimination -- both differ from the live prefix only in the
        // namespace, which is the part the rule is about.
        CatalogueOrigin ok = new CatalogueOrigin(
            "bogus", "/nowhere/none.json", "", List.of("catalogue:bogus-as-it-used-to-be/"));
        assertEquals(List.of("catalogue:bogus-as-it-used-to-be/"), ok.retiredPrefixes(),
            "a retired spelling that does NOT overlap the live one must be accepted, or"
                + " the invariant has banned the ordinary rename it exists to support");
    }

    /**
     * CONTRACT: {@code every_origin_states_an_authority} rejects {@code UNPINNED}.
     *
     * <p>Read through {@link CatalogueManifest#of} rather than
     * {@code authorityOf}, on purpose: {@code authorityOf} memoises by manifest
     * resource, and poisoning that cache from a test would leak a fake authority
     * into every later reader in the same JVM.</p>
     */
    /**
     * CONTRACT: {@code every_origin_gets_the_same_lifecycle} asserts that a second seed
     * from the same manifest writes NOTHING. <b>This is the control that assertion was
     * missing</b> — added 2026-08-28 after a fresh-context audit observed that the
     * lifecycle property, the one the C6 clause is actually about, had no control at all
     * while the commit message claimed "seven controls, one per contract property".
     *
     * <p>"The second run wrote nothing" is the same observation whether the seeder
     * correctly recognised its own rows or is simply unable to write. Only a CHANGED
     * input separates them, so that is what this asserts: same input twice → no write;
     * edited input → a write. Without the third step, an inert seeder passes the
     * contract.</p>
     */
    @Test
    void the_idempotence_the_lifecycle_asserts_is_not_vacuous(@TempDir Path dir)
            throws Exception {
        CatalogueOrigin o = CatalogueSources.all().get(0);
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            CatalogueSeeder.Outcome first = CatalogueSeeder.seed(
                store, o, CatalogueManifest.of(o, manifestNode(1, 1)), 0);
            assertEquals(1, first.seeded(),
                "PROOF OF LIFE: the seeder must write the row before its refusal to"
                    + " rewrite it can mean anything");

            CatalogueSeeder.Outcome same = CatalogueSeeder.seed(
                store, o, CatalogueManifest.of(o, manifestNode(1, 1)), 0);
            assertEquals(0, same.seeded(),
                "an unchanged input must write nothing on the second pass");

            // THE STEP THAT MAKES THE ZERO ABOVE MEAN SOMETHING. hashOf digests the whole
            // row, so an edited principle is a different row at the same address.
            ObjectNode edited = manifestNode(1, 1);
            ((ObjectNode) edited.path("patterns").get(0))
                .put("principle", "an edited principle, so the hash must move");
            CatalogueSeeder.Outcome changed = CatalogueSeeder.seed(
                store, o, CatalogueManifest.of(o, edited), 0);
            assertTrue(changed.seeded() > 0,
                "an EDITED row must be written. If it is not, then 'the second run wrote"
                    + " nothing' above proves only that this seeder never writes, and the"
                    + " whole idempotence contract is satisfied by an inert implementation"
                    + " — which is the shape S2 found in the second source's nine-line"
                    + " seed. seeded=" + changed.seeded());
        }
    }

    @Test
    void an_origin_declaring_no_version_identity_reads_as_unpinned() {
        CatalogueManifest anonymous =
            CatalogueManifest.of(origin("bogus"), manifestNode(1, 1));
        assertEquals("UNPINNED", anonymous.authority(),
            "a manifest declaring neither an authority nor a pinned commit must resolve"
                + " to UNPINNED, so the contract can refuse it. Inventing an authority"
                + " here would let rows ship with no way to say which version they came"
                + " from");
    }
}
