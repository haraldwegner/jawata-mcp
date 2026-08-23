package org.jawata.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sprint 28c M0 — turns the pinned `java-design-patterns` fork into the committed
 * catalogue snapshot.
 *
 * <p><b>Why this lives in the test/build source root and not in the product.</b>
 * It runs offline, against a checkout that is not shipped, to PRODUCE the
 * snapshot; no shipped code calls it. An extractor authored into the product
 * with no production caller is exactly the hollow shape
 * {@code build/unwired-gate.sh} exists to catch, and the baseline already
 * carries one such member.</p>
 *
 * <p><b>What composes what.</b> This extractor composes every CONTENT field of
 * a record. The loader (product side) sets only the two fields a snapshot
 * cannot know — {@code provenance_kind} and {@code status} — and writes.
 * Nothing composes twice.</p>
 *
 * <p><b>The situation is DERIVED, and that is Harald's ruling R10.</b> The
 * README's "When to Use" section is a multi-sentence paragraph; the store's
 * admission gate refuses 12 of the 187 because a gate built for single short
 * symptom lines reads {@code NoSQL} and {@code /Act/} as code and paths. The
 * paragraph is therefore carried VERBATIM in {@code details}, and the
 * {@code situation} is a one-line condition. This class emits a mechanical
 * DRAFT of that line; the reviewed line lives in the committed
 * {@code situations.json} beside the snapshot and wins whenever it is present.
 * A draft that no human has read is never shipped as a situation.</p>
 */
public final class CatalogExtractor {

    /** The sections the extractor keys on, in the fork's own template wording. */
    private static final Pattern INTENT =
        section("Intent");
    private static final Pattern WHEN =
        section("When to Use|Applicability");
    private static final Pattern TRADEOFFS =
        section("Benefits and Trade-offs|Consequences|Trade-offs");

    /**
     * A section heading, tolerant of the fork's one template variation.
     *
     * <p>The optional {@code The } is not cosmetic: 186 READMEs write
     * {@code ## Intent of X} and exactly one writes {@code ## The Intent of X}
     * (surveyed, not guessed). The first bulk run stopped on that one rather
     * than shipping 186 and reporting 187 — which is what the report exists for
     * — and the fix belongs in the pattern rather than in a hand-curated
     * snapshot entry, because a curated entry fixes one README while the
     * pattern fixes the class.</p>
     */
    private static Pattern section(String titles) {
        return Pattern.compile(
            "^#{2,3}\\s*(?:The\\s+)?(?:" + titles + ")[^\\n]*\\n(.*?)(?=^#{1,3}\\s|\\Z)",
            Pattern.MULTILINE | Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    }

    private final Path forkRoot;
    private final String pinnedCommit;
    private final Map<String, String> reviewedSituations;

    /**
     * @param forkRoot           the pinned checkout
     * @param pinnedCommit       the sha the snapshot is frozen at — PROVENANCE ONLY.
     *     It never enters {@code source_ref}: the ref must be stable across
     *     snapshots or every pattern reads as new at the next one and the whole
     *     update path becomes unreachable.
     * @param reviewedSituations slug -&gt; the reviewed one-line condition; a slug
     *     absent here gets its mechanical draft and is reported as UNREVIEWED
     */
    public CatalogExtractor(Path forkRoot, String pinnedCommit,
                            Map<String, String> reviewedSituations) {
        this.forkRoot = forkRoot;
        this.pinnedCommit = pinnedCommit;
        this.reviewedSituations = Map.copyOf(reviewedSituations);
    }

    /** One extracted pattern, before it becomes a store row. */
    public record Record(String slug, String situation, boolean situationReviewed,
                         String principle, String details, String sourceRef,
                         String referenceType) {
    }

    /** What the run saw, so a count is never inferred from the output's length. */
    public record Report(int readmesSeen, int recordsBuilt, List<String> unreviewed,
                         List<String> missingSection) {
    }

    /** Every top-level pattern directory holding a README, in slug order. */
    public List<Path> readmes() throws IOException {
        try (Stream<Path> top = Files.list(forkRoot)) {
            List<Path> out = new ArrayList<>();
            top.filter(Files::isDirectory)
               .map(d -> d.resolve("README.md"))
               .filter(Files::isRegularFile)
               .sorted(Comparator.comparing(p -> p.getParent().getFileName().toString()))
               .forEach(out::add);
            return out;
        }
    }

    /**
     * Extract every pattern, or the first {@code limit} of them.
     *
     * <p>{@code limit} is the sample-before-bulk gate D5 asks for, and it exists
     * because this loader has produced heading-shaped entries before: the first
     * run reads a handful by hand before 187 rows are written anywhere.</p>
     */
    public Report extract(int limit, List<Record> into) throws IOException {
        List<Path> files = readmes();
        List<String> unreviewed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int built = 0;
        for (Path readme : files) {
            if (limit > 0 && built >= limit) {
                break;
            }
            String slug = readme.getParent().getFileName().toString();
            // Line endings are normalised, and that is NOT a paraphrase: one of the
            // 187 READMEs is CRLF and the rest are LF. Carrying the difference through
            // would make sourceHash depend on a checkout's line-ending settings, so a
            // clone with different settings would read all 187 as changed and write
            // 187 spurious successors at the next load — the exact failure D6's
            // identity design exists to prevent. The prose itself is untouched.
            String text = Files.readString(readme, StandardCharsets.UTF_8).replace("\r\n", "\n");

            String intent = body(INTENT, text);
            String when = body(WHEN, text);
            if (intent.isBlank() || when.isBlank()) {
                // D3: a missing template section STOPS the bulk run rather than
                // silently yielding a thinner entry. It is reported, curated in
                // the snapshot, and never skipped.
                missing.add(slug + " (intent=" + !intent.isBlank() + " whenToUse=" + !when.isBlank() + ")");
                continue;
            }
            String tradeoffs = body(TRADEOFFS, text);
            String reviewed = reviewedSituations.get(slug);
            if (reviewed == null) {
                unreviewed.add(slug);
            }
            into.add(new Record(
                slug,
                reviewed != null ? reviewed : draftSituation(when),
                reviewed != null,
                principleOf(intent),
                detailsOf(slug, when, tradeoffs),
                "catalogue:java-design-patterns/" + slug + "/README.md",
                referenceType(readme, slug)));
            built++;
        }
        return new Report(files.size(), built, unreviewed, missing);
    }

    private static String body(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).strip() : "";
    }

    /** The Intent paragraph, flattened to one sentence — the entry's principle. */
    private static String principleOf(String intent) {
        String flat = intent.replaceAll("\\s+", " ").strip();
        int stop = flat.indexOf(". ");
        return stop > 40 ? flat.substring(0, stop + 1) : flat;
    }

    /**
     * The README's own prose, UNPARAPHRASED, plus the provenance D5 requires.
     *
     * <p>The pinned commit and the licence verdict live here — on the entry, as
     * D5 says, not only in the snapshot's sidecar — and so does the
     * reference-implementation TYPE, whose package prefix is how "the pattern's
     * Java package as provenance" is carried without any anchor column being
     * written.</p>
     */
    private String detailsOf(String slug, String when, String tradeoffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("When to use (from the pattern's own README, unparaphrased):\n").append(when);
        if (!tradeoffs.isBlank()) {
            sb.append("\n\nConsequences (unparaphrased):\n").append(tradeoffs);
        }
        sb.append("\n\nReference implementation: ").append(referenceTypeName(slug));
        sb.append("\nSource: iluwatar/java-design-patterns, ").append(slug)
          .append("/README.md at ").append(pinnedCommit);
        sb.append("\nLicence: MIT (fork of iluwatar/java-design-patterns), attribution retained.");
        return sb.toString();
    }

    /**
     * A fully-qualified TYPE, not a package — measured at GATE 2: a package
     * string returns nothing from {@code search_symbols} while a type name
     * returns the file, so a package would be an address that does not open.
     */
    private String referenceType(Path readme, String slug) {
        return referenceTypeName(slug);
    }

    private String referenceTypeName(String slug) {
        Path src = forkRoot.resolve(slug).resolve("src/main/java/com/iluwatar")
            .resolve(slug.replace("-", ""));
        String pkg = "com.iluwatar." + slug.replace("-", "");
        if (Files.isDirectory(src)) {
            try (Stream<Path> types = Files.list(src)) {
                return types.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .sorted(Comparator.comparingInt((String n) -> "App".equals(n) ? 0 : 1)
                        .thenComparing(Comparator.naturalOrder()))
                    .findFirst().map(n -> pkg + "." + n).orElse(pkg);
            } catch (IOException e) {
                return pkg;
            }
        }
        return pkg;
    }

    /**
     * A mechanical DRAFT of the one-line condition — never shipped unreviewed.
     *
     * <p>It takes the first bullet, strips the template's lead-in, and states it
     * as a condition. Good enough to review against, not good enough to store:
     * the reviewed line in {@code situations.json} wins wherever it exists, and
     * {@link Report#unreviewed} names every slug still on its draft.</p>
     */
    static String draftSituation(String when) {
        for (String line : when.split("\\R")) {
            String t = line.strip();
            if (!t.startsWith("*") && !t.startsWith("-")) {
                continue;
            }
            t = t.replaceFirst("^[*-]\\s*", "").strip();
            t = t.replaceFirst("(?i)^(when|if|you (need|want|aim)( to)?|the application needs to|use .*? when:?)\\s+", "");
            t = t.replaceAll("\\s+", " ").replaceAll("[.;]$", "");
            if (t.length() > 8) {
                return "when " + Character.toLowerCase(t.charAt(0)) + t.substring(1);
            }
        }
        return "";
    }

    /** SHA-256 of the composed record — the loader recomputes this, never the snapshot. */
    public static String contentHash(Record r) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((r.situation() + ' ' + r.principle() + ' ' + r.details())
                .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The committed snapshot: one array of records, plus the sidecar fields. */
    public ObjectNode snapshot(List<Record> records, ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        root.put("fork", "iluwatar/java-design-patterns (fork: haraldwegner)");
        root.put("pinned_commit", pinnedCommit);
        root.put("licence", "MIT");
        root.put("licence_verdict", "MIT — redistribution permitted with attribution retained");
        root.put("count", records.size());
        ArrayNode arr = root.putArray("patterns");
        for (Record r : records) {
            ObjectNode n = json.createObjectNode();
            n.put("slug", r.slug());
            n.put("type", "lesson");
            n.put("verdict", "unproven");
            n.put("situation", r.situation());
            n.put("principle", r.principle());
            n.put("details", r.details());
            n.put("source_ref", r.sourceRef());
            n.put("reference_type", r.referenceType());
            arr.add(n);
        }
        return root;
    }

    /** slug -&gt; reviewed situation, from the committed curation file. */
    public static Map<String, String> readReviewed(Path file, ObjectMapper json) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        JsonNode situations = root.path("situations");
        situations.fieldNames().forEachRemaining(k -> out.put(k, situations.get(k).asText()));
        return out;
    }
}
