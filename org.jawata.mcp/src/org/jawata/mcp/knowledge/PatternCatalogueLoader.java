package org.jawata.mcp.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28c D5 — seed the frozen pattern catalogue into a store, once, and
 * leave it seeded.
 *
 * <p>This runs at every start. That is the design, not an oversight: the
 * catalogue must arrive on a machine that installs jawata today and on one
 * that upgrades to a newer pinned snapshot next month, and a one-shot install
 * step cannot do the second. What makes it cheap is
 * {@link ExperienceStore#sourceUnchanged}: a pattern whose {@code source_ref}
 * and content hash are both already present is skipped without a write, so the
 * second start does no work and the {@code stats} row count does not move.</p>
 *
 * <p><b>Deliberately NOT the {@code .jawata-recovered} marker idiom.</b> Orphan
 * recovery drops a marker and never looks at the source again, which is right
 * for a one-time rescue and wrong here: "swept, never look again" would make an
 * updated catalogue unreachable forever. Identity is therefore content-based —
 * the reference plus the hash — so a changed pattern is naturally visible as a
 * changed hash on the next start.</p>
 *
 * <p><b>The {@code source_ref} carries no commit.</b> It is
 * {@code catalogue:java-design-patterns/<slug>/README.md}, and the pinned
 * commit lives in the snapshot header instead. Putting the commit in the key
 * would make every entry from an older snapshot unmatchable, so every update
 * would look like 187 brand-new patterns rather than 187 unchanged ones.</p>
 *
 * <p>Writes are confined to the catalogue's own namespace. Nothing here reads,
 * updates or deletes a row the user recorded.</p>
 */
public final class PatternCatalogueLoader implements CatalogueSource {

    private static final Logger log = LoggerFactory.getLogger(PatternCatalogueLoader.class);

    /** Where the frozen snapshot ships inside the bundle. */
    static final String BUNDLED_CATALOGUE = "/catalogue/patterns.json";

    /** The {@code source_ref} prefix that marks a row as ours. */
    public static final String SOURCE_PREFIX = "catalogue:java-design-patterns/";

    /**
     * The provenance value every catalogue row carries.
     *
     * <p>Spelled {@code catalog}, matching the vocabulary already in
     * {@link ExperienceEntry} — not {@code catalogue}, however the surrounding
     * prose spells it. A second spelling would be a value nothing else groups
     * on, which is how a report quietly starts reading zero.</p>
     */
    public static final String PROVENANCE = "catalog";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What a seeding run did, in the terms the start-up line reports.
     *
     * <p>{@code retired} counts incumbents superseded by a newer version of the
     * same pattern, and it is REPORTED rather than left as a silent side effect:
     * retiring a row stops it answering, so a start that retired forty patterns
     * and one that retired none must not read alike.</p>
     */
    public record Result(int inSnapshot, int seeded, int unchanged, int retired,
                         String pinnedCommit) {

        /** True when this run wrote nothing — the ordinary case after the first start. */
        public boolean quiet() {
            return seeded == 0;
        }
    }

    /**
     * LAZY, and the laziness is a contract rather than an optimisation:
     * {@link CatalogueSource} requires sources to be cheap to construct, because
     * the registry builds them to answer read-only questions — "which namespace
     * owns this row?" — on paths as hot as {@code stats}. Parsing the whole
     * snapshot in the constructor would put a megabyte of JSON behind every one
     * of those. Held as a supplier so the caller-supplied form stays eager and
     * the bundled form pays only when something actually seeds.
     */
    private final java.util.function.Supplier<JsonNode> snapshotSource;
    private JsonNode snapshot;

    /** Load from the bundled resource. */
    public PatternCatalogueLoader() {
        this.snapshotSource = PatternCatalogueLoader::bundled;
    }

    /** Load from a caller-supplied snapshot — the seam the tests use. */
    public PatternCatalogueLoader(JsonNode snapshot) {
        this.snapshot = snapshot;
        this.snapshotSource = () -> snapshot;
    }

    private JsonNode snapshot() {
        if (snapshot == null) {
            snapshot = snapshotSource.get();
        }
        return snapshot;
    }

    @Override
    public String namespace() {
        return "java-design-patterns";
    }

    @Override
    public String prefix() {
        return SOURCE_PREFIX;
    }

    /**
     * The pinned commit the snapshot was derived at — a FOREIGN authority, which
     * is why it can move under us and why its addresses must be re-resolved when
     * it does. Read from the snapshot rather than stated, so it cannot drift from
     * the rows it describes.
     */
    @Override
    public String authority() {
        return "pinned upstream commit " + snapshot().path("pinned_commit").asText("UNPINNED");
    }

    /** Registry entry point: seed everything, report what was WRITTEN. */
    @Override
    public int seed(ExperienceStore store) {
        return load(store).seeded();
    }

    private static JsonNode bundled() {
        try (InputStream in = PatternCatalogueLoader.class
                .getResourceAsStream(BUNDLED_CATALOGUE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "the pattern catalogue is not on the classpath at " + BUNDLED_CATALOGUE
                    + " — a build that ships without it would seed nothing and say nothing");
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + BUNDLED_CATALOGUE, e);
        }
    }

    /**
     * Seed every pattern the store does not already hold unchanged.
     *
     * @param store the target; only rows in the catalogue namespace are written
     * @param limit stop after this many patterns, or 0 for all of them — the
     *     bounded sample mode, which exists because this loader has produced
     *     heading-shaped entries before and the extractor's own sample does not
     *     cover the loader's writing
     */
    public Result load(ExperienceStore store, int limit) {
        JsonNode patterns = snapshot().path("patterns");
        String commit = snapshot().path("pinned_commit").asText("UNPINNED");
        int declared = snapshot().path("count").asInt(-1);
        int considered = 0;

        // THIS SOURCE'S ONLY JOB: say which rows it currently claims. Everything
        // that can diverge between sources — retire-then-write, the orphan sweep,
        // its two guards — belongs to CatalogueSeeder and is written once.
        // Sprint 28d Stage 6 / S1; ARCHITECTURE-28d v2.
        java.util.List<CatalogueSeeder.SeedItem> items = new ArrayList<>();
        for (JsonNode p : patterns) {
            if (limit > 0 && considered >= limit) {
                break;
            }
            considered++;
            String slug = p.path("slug").asText("");
            if (slug.isBlank()) {
                continue;
            }
            items.add(new CatalogueSeeder.SeedItem(
                SOURCE_PREFIX + slug + "/README.md", hashOf(p), entryFor(p, slug)));
        }

        CatalogueSeeder.Outcome outcome =
            CatalogueSeeder.seed(store, SOURCE_PREFIX, items, declared, limit > 0, commit, java.util.List.of());
        Result result = new Result(considered, outcome.seeded(), outcome.unchanged(),
            outcome.retired(), commit);

        // A start that loaded nothing logs nothing — recoverOrphans' own rule.
        // A line on every boot trains the reader to skip it, and then the one
        // start that DID seed something scrolls past unread.
        if (!result.quiet()) {
            log.info("Pattern catalogue: seeded {} of {} patterns at {} ({} already current,"
                    + " {} older version(s) retired)",
                result.seeded(), considered, commit, result.unchanged(), result.retired());
        }
        return result;
    }

    /** Seed the whole catalogue. */
    public Result load(ExperienceStore store) {
        return load(store, 0);
    }

    /**
     * The content hash that decides seeded-versus-unchanged.
     *
     * <p>Taken over the pattern's own JSON, so ANY field changing upstream —
     * the situation, the principle, the prose in {@code details} — makes the
     * row stale. Hashing only the summary would let a rewritten body ship
     * forever behind an unchanged first line.</p>
     */
    static String hashOf(JsonNode pattern) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                md.digest(pattern.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /**
     * The catalogue TYPE is fixed here rather than read from the snapshot, and
     * the snapshot's own {@code verdict} is deliberately ignored.
     *
     * <p>A published design pattern is a REFERENCE: it says what to reach for
     * and what it costs, and nobody on this machine lived it. It therefore has
     * no outcome to report, and the store's form rules only demand one from an
     * experience. Labelling the catalogue {@code lesson} made 187 library
     * descriptions owe a verdict they cannot earn, and the value invented so
     * they could pay it — {@code unproven} — was the rule announcing it was
     * wrong about them.</p>
     *
     * <p>{@code unproven} survives for its honest case: a real experience whose
     * outcome is still open. That is not this.</p>
     */
    static final String CATALOGUE_TYPE = "reference";

    private static ExperienceEntry entryFor(JsonNode p, String slug) {
        String type = CATALOGUE_TYPE;
        String summary = p.path("principle").asText("");
        SymbolFact.Builder fact = SymbolFact.of(type, summary, Confidence.MEDIUM);
        // The README prose, its MIT attribution and the licence verdict all ride
        // in details — the half that makes the row usable AND redistributable.
        String details = p.path("details").asText(null);
        if (details != null && !details.isBlank()) {
            fact.details(details);
        }

        List<String> symptoms = new ArrayList<>();
        for (JsonNode s : p.path("symptoms")) {
            symptoms.add(s.asText());
        }

        String situation = p.path("situation").asText(null);
        ExperienceEntry.Builder b = ExperienceEntry.of(fact.build())
            // candidate, never accepted: these are somebody else's patterns,
            // not this user's earned experience, and promotion is theirs.
            .status(ExperienceEntry.CANDIDATE)
            .situation(situation)
            // v15: the design FORCE the pattern answers — the Minto complication.
            // Factory and Builder share one situation ("constructing an object");
            // the cause is which construction problem each solves, and it is what
            // a recall's differential discriminates on.
            .cause(p.path("cause").asText(null))
            // form = "carries a situation", by its definition at the record
            // verb. Rows seeded without this stamp were classified as
            // defective by the quality lane despite perfect situations —
            // 187 of them, measured 2026-08-27.
            .form(EntryForm.formOf(situation))
            .provenanceKind(PROVENANCE)
            .operation("design:" + slug);
        if (!symptoms.isEmpty()) {
            b.symptoms(symptoms);
        }
        return b.build();
    }
}
