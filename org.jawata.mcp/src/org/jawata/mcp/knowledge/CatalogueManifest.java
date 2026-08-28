package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * THE ONE READER — every origin's manifest is parsed here, and nowhere else.
 *
 * <h2>Why one reader (Sprint 28d Stage 6 / S6)</h2>
 *
 * <p>There were two, and they were two because there were two FORMATS. Our
 * specimens were authored in a bespoke JSON shape because their content felt
 * narrower than the fork's — a taste judgement, not a behavioural one — and a
 * bespoke shape needs its own reader, and a second reader became a second
 * lifecycle that nothing forced to agree. The fork's loader was taught to retire
 * an incumbent on an edit and to sweep rows the input no longer claims; the
 * other lane learned neither, and no test caught it because the assertions lived
 * in a class bound to the first implementation.</p>
 *
 * <p>The two {@code entryFor} bodies were, by the end, IDENTICAL — same type,
 * same fields, same {@code design:<slug>} operation key. Only the address
 * composition still differed, and S4 removed that. What remains is one reader
 * over one shape, so a fix is taught once by construction rather than twice by
 * discipline.</p>
 *
 * <h2>Two compatibility rules, stated rather than hidden</h2>
 *
 * <p><b>The rows key.</b> The fork's snapshot writes its array as
 * {@code patterns}; ours wrote {@code samples}. That was the last surviving
 * fragment of the second format. Both are accepted, with {@code entries}
 * preferred, so a manifest can be migrated without a flag day — but a NEW origin
 * uses {@code entries} and nothing else.</p>
 *
 * <p><b>The authority.</b> The fork states a {@code pinned_commit}, because it is
 * a FOREIGN authority that moves under us and whose addresses must be re-resolved
 * when it does. Ours states an {@code authority} outright, because these
 * specimens version with the product and there is nothing to pin. Both are read
 * here; neither is invented. An origin declaring neither is UNPINNED and says so,
 * rather than reporting a confident stale value.</p>
 */
public final class CatalogueManifest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The provenance value every catalogue row carries — the store's vocabulary. */
    public static final String PROVENANCE = "catalog";

    /**
     * The TYPE every catalogue row carries, and the snapshot's own value is ignored.
     *
     * <p>A published pattern is a REFERENCE: it says what to reach for and what it
     * costs, and nobody on this machine lived it. It therefore has no outcome to
     * report. Labelling the catalogue {@code lesson} made 187 library descriptions
     * owe a verdict they cannot earn, and the value invented so they could pay it —
     * {@code unproven} — was the rule announcing it was wrong about them.</p>
     */
    public static final String CATALOGUE_TYPE = "reference";

    /**
     * Resolved authorities, by manifest resource.
     *
     * <p><b>Why a cache is correct here rather than a hazard.</b> The manifest is a
     * classpath resource: it cannot change while the process runs, so a cached
     * read can never be stale. What it buys is the reason the registry could not
     * hold an authority as a field — {@link CatalogueSources#all()} is called on
     * hot paths (the {@code stats} block, the address renderer) and is cheap by
     * contract, and the fork's snapshot is about a megabyte. Parsing it per call
     * would put that behind every one; parsing it once does not.</p>
     */
    private static final java.util.Map<String, String> AUTHORITIES =
        new java.util.concurrent.ConcurrentHashMap<>();

    private final CatalogueOrigin origin;
    private final JsonNode root;

    private CatalogueManifest(CatalogueOrigin origin, JsonNode root) {
        this.origin = origin;
        this.root = root;
    }

    /** Read an origin's manifest off the classpath. */
    public static CatalogueManifest read(CatalogueOrigin origin) {
        try (InputStream in =
                 CatalogueManifest.class.getResourceAsStream(origin.manifestResource())) {
            if (in == null) {
                throw new IllegalStateException(
                    "the manifest for '" + origin.namespace() + "' is not on the classpath at "
                        + origin.manifestResource()
                        + " — a build that ships without it would seed nothing and say nothing");
            }
            return new CatalogueManifest(origin, JSON.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + origin.manifestResource(), e);
        }
    }

    /** Read from a caller-supplied node — the seam the tests use. */
    public static CatalogueManifest of(CatalogueOrigin origin, JsonNode root) {
        return new CatalogueManifest(origin, root);
    }

    /** The rows array, under whichever key this manifest uses. */
    private JsonNode rows() {
        for (String key : List.of("entries", "patterns", "samples")) {
            JsonNode node = root.path(key);
            if (node.isArray()) {
                return node;
            }
        }
        return JSON.createArrayNode();
    }

    /**
     * How many rows the manifest SAYS it holds — the completeness guard's input.
     *
     * <p>Negative when it declares nothing, which disables the orphan sweep. That
     * is deliberate: a sweep run against an input that cannot vouch for its own
     * completeness would retire every row the input happens not to carry.</p>
     */
    public int declaredCount() {
        return root.path("count").asInt(-1);
    }

    /**
     * The version identity for an origin, read once and remembered.
     *
     * <p>This is the accessor the read-only paths use. It exists so that asking
     * "which version is this origin at?" does not cost a manifest parse every
     * time the registry is walked.</p>
     */
    public static String authorityOf(CatalogueOrigin origin) {
        return AUTHORITIES.computeIfAbsent(origin.manifestResource(),
            key -> read(origin).authority());
    }

    /** The version identity these rows were derived at. Never invented. */
    public String authority() {
        String stated = root.path("authority").asText("");
        if (!stated.isBlank()) {
            return stated;
        }
        String pin = root.path("pinned_commit").asText("");
        return pin.isBlank() ? "UNPINNED" : "pinned upstream commit " + pin;
    }

    /**
     * The rows this origin currently claims.
     *
     * @param limit stop after this many, or 0 for all — the sample-before-bulk
     *     gate. A bounded read must never drive the orphan sweep, because almost
     *     everything is unclaimed for the trivial reason the sample never reached it
     */
    public List<CatalogueSeeder.SeedItem> items(int limit) {
        List<CatalogueSeeder.SeedItem> items = new ArrayList<>();
        int considered = 0;
        for (JsonNode row : rows()) {
            if (limit > 0 && considered >= limit) {
                break;
            }
            considered++;
            String slug = row.path("slug").asText("");
            if (slug.isBlank()) {
                continue;
            }
            items.add(new CatalogueSeeder.SeedItem(
                origin.prefix() + slug + "/README.md", hashOf(row), entryFor(row, slug)));
        }
        return items;
    }

    /** How many rows the manifest actually carries, whatever it declares. */
    public int size() {
        return rows().size();
    }

    /**
     * The content hash that decides seeded-versus-unchanged.
     *
     * <p>Taken over the row's own JSON, so ANY field changing — the situation, the
     * principle, the prose in {@code details} — makes the row stale. Hashing only
     * the summary would let a rewritten body ship forever behind an unchanged
     * first line.</p>
     */
    static String hashOf(JsonNode row) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                md.digest(row.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /**
     * One row's entry. The two origins' versions of this were identical by the end
     * of S4, which is why there is now one.
     */
    private static ExperienceEntry entryFor(JsonNode row, String slug) {
        SymbolFact.Builder fact =
            SymbolFact.of(CATALOGUE_TYPE, row.path("principle").asText(""), Confidence.MEDIUM);
        String details = row.path("details").asText(null);
        if (details != null && !details.isBlank()) {
            fact.details(details);
        }

        List<String> symptoms = new ArrayList<>();
        for (JsonNode s : row.path("symptoms")) {
            symptoms.add(s.asText());
        }

        String situation = row.path("situation").asText(null);
        ExperienceEntry.Builder b = ExperienceEntry.of(fact.build())
            // candidate, never accepted: these are somebody else's patterns and our
            // own demonstrations, not this user's earned experience. Promotion is
            // theirs to give.
            .status(ExperienceEntry.CANDIDATE)
            .situation(situation)
            // The design FORCE the row answers — the Minto complication. Factory and
            // Builder share one situation ("constructing an object"); the cause is
            // which construction problem each solves, and it is what a recall's
            // differential discriminates on.
            .cause(row.path("cause").asText(null))
            // form = "carries a situation", by its definition at the record verb.
            // Rows seeded without this stamp were classified as defective by the
            // quality lane despite perfect situations — 187 of them, measured.
            .form(EntryForm.formOf(situation))
            .provenanceKind(PROVENANCE)
            .operation("design:" + slug);
        if (!symptoms.isEmpty()) {
            b.symptoms(symptoms);
        }
        return b.build();
    }
}
