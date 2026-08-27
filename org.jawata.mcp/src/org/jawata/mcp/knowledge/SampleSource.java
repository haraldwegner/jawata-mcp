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
 * THE OWN-AUTHORED CURE SPECIMENS — the second catalogue source (Sprint 28d).
 *
 * <p>The pattern fork covers the patterns it covers. Some cures this engine
 * names have no module there — {@code compose_method} and
 * {@code replace_pattern_with_idiom} were measured moduleless — and a cure with
 * no openable address is a name-drop. These rows point into
 * {@code org.jawata.samples}: a module in THIS repository, compiled by the
 * build so an address cannot rot, absent from the shipped product, and public
 * so a reader can open it.</p>
 *
 * <p><b>Why this source has NO PIN, and that is the whole difference.</b> The
 * fork is a FOREIGN authority: it moves under us, so its rows carry a pinned
 * commit and its addresses must be re-resolved when the pin moves. These
 * specimens version with the product — the detector that names the cure and the
 * code the cure points at ship from one commit — so there is nothing to pin and
 * nothing that can drift. Same registry, different authority, and the
 * difference is not cosmetic: it decides whether re-resolution is owed.</p>
 *
 * <p>Rows are {@code reference} and {@code candidate}, exactly like the fork's:
 * a specimen is somebody's demonstration, never the user's earned experience.</p>
 */
public final class SampleSource implements CatalogueSource {

    /** Where the index ships inside the bundle. */
    static final String BUNDLED_SAMPLES = "/samples/samples.json";

    /** The {@code source_ref} prefix that marks a row as ours. */
    public static final String SOURCE_PREFIX = "sample:jawata-samples/";

    /** The provenance value every specimen row carries — the catalogue vocabulary. */
    public static final String PROVENANCE = "catalog";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final java.util.function.Supplier<JsonNode> indexSource;
    private JsonNode index;

    /** Load from the bundled resource. Lazy, per {@link CatalogueSource}. */
    public SampleSource() {
        this.indexSource = SampleSource::bundled;
    }

    /** Load from a caller-supplied index — the seam the tests use. */
    public SampleSource(JsonNode index) {
        this.index = index;
        this.indexSource = () -> index;
    }

    private static JsonNode bundled() {
        try (InputStream in = SampleSource.class.getResourceAsStream(BUNDLED_SAMPLES)) {
            if (in == null) {
                throw new IllegalStateException(
                    "the sample index is not on the classpath at " + BUNDLED_SAMPLES
                    + " — a build that ships without it would seed nothing and say nothing");
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + BUNDLED_SAMPLES, e);
        }
    }

    private JsonNode index() {
        if (index == null) {
            index = indexSource.get();
        }
        return index;
    }

    @Override
    public String namespace() {
        return "jawata-samples";
    }

    @Override
    public String prefix() {
        return SOURCE_PREFIX;
    }

    /**
     * An OWN authority — no commit, because there is nothing foreign to pin to.
     * Stated rather than left blank: "ours, current by construction" is a fact a
     * reader needs, and an empty authority would read as an unanswered question.
     */
    @Override
    public String authority() {
        return index().path("authority")
            .asText("org.jawata.samples, versioned with this product");
    }

    @Override
    public int seed(ExperienceStore store) {
        int seeded = 0;
        for (JsonNode sample : index().path("samples")) {
            String slug = sample.path("slug").asText("");
            if (slug.isBlank()) {
                continue;
            }
            String sourceRef = SOURCE_PREFIX + slug;
            String hash = hashOf(sample);
            if (store.sourceUnchanged(sourceRef, hash)) {
                continue;
            }
            store.putWithSource(entryFor(sample, slug), sourceRef, hash);
            seeded++;
        }
        return seeded;
    }

    /** Over the sample's own JSON, so any field changing makes the row stale. */
    static String hashOf(JsonNode sample) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                md.digest(sample.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static ExperienceEntry entryFor(JsonNode sample, String slug) {
        String summary = sample.path("principle").asText("");
        SymbolFact.Builder fact = SymbolFact.of("reference", summary, Confidence.MEDIUM);
        String details = sample.path("details").asText(null);
        if (details != null && !details.isBlank()) {
            fact.details(details);
        }

        List<String> symptoms = new ArrayList<>();
        for (JsonNode s : sample.path("symptoms")) {
            symptoms.add(s.asText());
        }

        String situation = sample.path("situation").asText(null);
        ExperienceEntry.Builder b = ExperienceEntry.of(fact.build())
            .status(ExperienceEntry.CANDIDATE)
            .situation(situation)
            .cause(sample.path("cause").asText(null))
            .form(EntryForm.formOf(situation))
            .provenanceKind(PROVENANCE)
            // NOT the `capability` facet yet, deliberately. Each sample's JSON
            // carries the plan kind its cure names, and binding it to the facet
            // is Stage 9's tier work — the builder has no setter for it today,
            // and adding one here would ship a member with no reader. The data
            // sits in the index waiting; the wire arrives with its consumer.
            .operation("design:" + slug);
        if (!symptoms.isEmpty()) {
            b.symptoms(symptoms);
        }
        return b.build();
    }
}
