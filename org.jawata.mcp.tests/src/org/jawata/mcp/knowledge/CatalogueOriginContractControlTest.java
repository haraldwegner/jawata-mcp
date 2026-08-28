package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

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

        // Half two: the shape that CAN, so the contract's check is proven live.
        String nested = origin("java/design").prefix();
        assertEquals("catalogue:java/design/", nested);
        assertTrue(nested.startsWith(siblingA),
            "'" + siblingA + "' swallows '" + nested + "' — a namespace carrying a"
                + " separator is the one collision the trailing slash does not prevent."
                + " First-match ownership would leave the nested origin permanently"
                + " unreachable while appearing registered, its rows swept as orphans by"
                + " the origin that claims none of them");
    }

    /**
     * CONTRACT: {@code a_retired_prefix_never_overlaps_a_live_one}. The migration
     * supersedes every row under a retired prefix WITHOUT the completeness guard —
     * deliberately, since a retired spelling has no current input — so an overlap
     * would retire a live origin's whole catalogue on the next boot.
     */
    @Test
    void a_retired_prefix_that_overlaps_a_live_one_is_detectable() {
        String live = CatalogueSources.all().get(0).prefix();
        CatalogueOrigin reckless =
            new CatalogueOrigin("bogus", "/nowhere/none.json", "", List.of(live));
        String retired = reckless.retiredPrefixes().get(0);
        assertTrue(live.startsWith(retired) || retired.startsWith(live),
            "retiring '" + retired + "' while '" + live + "' is LIVE must register as an"
                + " overlap. If it did not, the next boot would supersede that origin's"
                + " entire catalogue with no guard to stop it");
    }

    /**
     * CONTRACT: {@code every_origin_states_an_authority} rejects {@code UNPINNED}.
     *
     * <p>Read through {@link CatalogueManifest#of} rather than
     * {@code authorityOf}, on purpose: {@code authorityOf} memoises by manifest
     * resource, and poisoning that cache from a test would leak a fake authority
     * into every later reader in the same JVM.</p>
     */
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
