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
 * <p><b>What composes what — corrected 2026-08-28, and the correction is the
 * interesting part.</b> This said the product side set "only the two fields a
 * snapshot cannot know ({@code provenance_kind}, {@code status}). Nothing
 * composes twice." That was already false when Stage 6 folded the readers:
 * {@code CatalogueManifest.entryFor} sets FIVE — those two plus {@code type}
 * (from the {@code CATALOGUE_TYPE} constant, ignoring whatever the snapshot
 * says), {@code form} (derived from the situation) and {@code operation}
 * ({@code "design:" + slug}). And {@code items()} composes each address as
 * {@code prefix() + slug + "/README.md"} while this extractor writes its own
 * {@code source_ref} into the snapshot — so {@code source_ref} and {@code type}
 * are each produced on BOTH sides, which is exactly what "nothing composes
 * twice" denied.</p>
 *
 * <p>That double composition is not a defect today, because both sides agree on
 * the same rule. It is recorded rather than tidied away because it is the shape
 * that caused this stage: the two catalogue readers also agreed, until one of
 * them was taught a fix and the other was not.</p>
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
    private final String namespace;
    private final String pinnedCommit;
    private final String licenceNote;
    private final Map<String, String> reviewedSituations;
    private final Map<String, String> curatedCauses;

    /**
     * Sprint 28d Stage 6 / S5 — the origin-agnostic form.
     *
     * @param root         the tree to read: {@code <root>/<slug>/README.md} beside
     *                     {@code <root>/<slug>/src/main/java/...}
     * @param namespace    the origin's namespace, which becomes the {@code source_ref}
     *                     prefix. It was hardcoded to the fork's, which gave any other
     *                     tree the fork's identity — and every ownership question in
     *                     the seeder is keyed on exactly this prefix
     * @param authorityRef the version identity these records were derived at —
     *     PROVENANCE ONLY. It never enters {@code source_ref}: the ref must be
     *     stable across snapshots, or every pattern reads as new at the next one
     *     and the whole update path becomes unreachable
     * @param reviewedSituations slug -&gt; the reviewed one-line condition; a slug
     *     absent here gets its mechanical draft and is reported as UNREVIEWED
     */
    public CatalogExtractor(Path root, String namespace, String authorityRef,
                            Map<String, String> reviewedSituations) {
        this(root, namespace, authorityRef, null, reviewedSituations, Map.of());
    }

    /**
     * As above, plus an attribution line carried onto every entry.
     *
     * @param licenceNote the redistribution terms this origin's prose ships under,
     *     or {@code null} for an origin that has none to state. It is NOT invented
     *     per origin: the fork's rows already carry theirs and keep it, because
     *     attribution is a condition of redistributing that prose. An own-authored
     *     origin supplies nothing here rather than a default
     */
    public CatalogExtractor(Path root, String namespace, String authorityRef,
                            String licenceNote, Map<String, String> reviewedSituations) {
        this(root, namespace, authorityRef, licenceNote, reviewedSituations, Map.of());
    }

    /**
     * As above, plus the curated causes.
     *
     * @param curatedCauses slug -&gt; the design FORCE this pattern answers. Absent for a
     *     slug means the row carries no cause, which is a normal state and NOT a default:
     *     an invented cause would be ranked on by the recall differential, so a wrong one
     *     is worse than none. See {@link #readCurated} for why this input exists at all
     */
    public CatalogExtractor(Path root, String namespace, String authorityRef,
                            String licenceNote, Map<String, String> reviewedSituations,
                            Map<String, String> curatedCauses) {
        this.forkRoot = root;
        this.namespace = namespace;
        this.pinnedCommit = authorityRef;
        this.licenceNote = licenceNote;
        this.reviewedSituations = Map.copyOf(reviewedSituations);
        this.curatedCauses = Map.copyOf(curatedCauses);
    }

    /**
     * One extracted pattern, before it becomes a store row.
     *
     * <p>Sprint 28d S10.1 adds {@code category} and {@code tags}: THE PATTERN'S OWN
     * CLASSIFICATION, which the source declares and this extractor used to discard.
     * Harald, 2026-08-30: <i>"we have different patterns for different situations:
     * create an object -&gt; creational pattern like builder or factory. So you can
     * distinguish"</i>.</p>
     *
     * <p>185 of the 188 upstream READMEs declare {@code category:} across 16 values
     * (Behavioral 40, Structural 34, Architectural 27, Concurrency 22, Creational 14,
     * …), and none of it reached the store. Two existing fields look like they might
     * have served and do not: {@code type} is the constant {@code reference} on every
     * row, and {@code referenceType} — despite the name — holds the Java entry-point
     * class. Nor could it be recovered downstream: 170 of 187 rows never name a family
     * anywhere in their prose.</p>
     *
     * <p><b>{@code category} is nullable on purpose.</b> Three READMEs declare none.
     * Defaulting them would file three patterns under a family nobody assigned them,
     * and a wrong classification is worse than an absent one — an absent one can be
     * answered with "we do not know".</p>
     */
    public record Record(String slug, String situation, boolean situationReviewed,
                         String cause, String principle, String details, String sourceRef,
                         String entryPointClass, String category, List<String> tags) {
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
                curatedCauses.get(slug),
                principleOf(intent),
                detailsOf(slug, when, tradeoffs),
                "catalogue:" + namespace + "/" + slug + "/README.md",
                referenceType(readme, slug),
                categoryOf(text),
                tagsOf(text)));
            built++;
        }
        return new Report(files.size(), built, unreviewed, missing);
    }

    /**
     * The YAML frontmatter block — everything between the opening and closing
     * {@code ---}. Returns empty for a README with none, which is a normal state and
     * not an error: the caller then sees no category and records that honestly.
     *
     * <p>Bounded to the frontmatter ON PURPOSE. A README body can contain a line
     * beginning {@code category:} in prose or inside a fenced block, and a whole-file
     * scan would read it as the declaration. The frontmatter is where the author
     * declares; everywhere else is where they write.</p>
     */
    private static String frontmatter(String text) {
        if (!text.startsWith("---\n")) {
            return "";
        }
        int end = text.indexOf("\n---", 4);
        return end < 0 ? "" : text.substring(4, end + 1);
    }

    /**
     * The pattern's declared family, under EITHER spelling the fork uses.
     *
     * <p><b>Measured at pin {@code 22a34127d}, 2026-08-30, over all 187 READMEs:</b>
     * 185 write {@code category:}, 2 write {@code categories:} (component →
     * Structural, serialized-entity → Data access), and <b>none writes neither</b>.
     * Both plural values are plain scalars, not lists.</p>
     *
     * <p><b>Why the synonym rather than "those two have no category".</b> The key
     * match is exact — {@code startsWith(key + ":")} — so reading only the singular
     * reports {@code null} for two patterns whose family is sitting in the file. That
     * is not an absent category, it is a MISSED one, and the two are opposite kinds of
     * answer: {@code null} means "upstream did not say", and saying it about an author
     * who did say is a wrong answer wearing a correct one's shape. The plan recorded
     * "185 of 188 declare a category, 3 have none" — wrong on both halves; there are
     * 187 READMEs and every one of them names a family.</p>
     *
     * <p>The singular wins where a README somehow carried both, because it is the
     * fork's own documented key and the 185-file majority.</p>
     */
    private static String categoryOf(String text) {
        String singular = frontmatterScalar(text, "category");
        return singular != null ? singular : frontmatterScalar(text, "categories");
    }

    /**
     * The pattern's declared tags, under EITHER spelling — the same split as
     * {@link #categoryOf}, found the same way and worth stating separately because
     * the majority runs the other direction.
     *
     * <p><b>Measured at pin {@code 22a34127d} over all 187 READMEs:</b> 182 write
     * {@code tag:} and 5 write {@code tags:} (client-session, context-object,
     * model-view-intent, notification, page-controller). <b>None writes neither.</b>
     * So the singular is the majority key here while the PLURAL is the majority key
     * for the family — which is why neither can be assumed from the other.</p>
     *
     * <p>Reading one spelling returned an empty list for the other five, and an empty
     * list is how this record says "the author declared no tags". Seven of 187 rows
     * carried that wrong answer before this: five from the spelling, two more from a
     * blank line the block reader broke on.</p>
     */
    private static List<String> tagsOf(String text) {
        List<String> singular = frontmatterList(text, "tag");
        return singular.isEmpty() ? frontmatterList(text, "tags") : singular;
    }

    /** A scalar frontmatter value ({@code category: Creational}), or null if absent. */
    private static String frontmatterScalar(String text, String key) {
        for (String line : frontmatter(text).split("\n", -1)) {
            if (line.startsWith(key + ":")) {
                String v = line.substring(key.length() + 1).strip();
                if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')
                        && v.charAt(0) == v.charAt(v.length() - 1)) {
                    v = v.substring(1, v.length() - 1);
                }
                return v.isBlank() ? null : v;
            }
        }
        return null;
    }

    /**
     * A frontmatter LIST value, in both shapes the fork writes:
     * {@code tag: [a, b]} inline, and the block form
     *
     * <pre>
     * tag:
     *   - Gang of Four
     *   - Instantiation
     * </pre>
     *
     * <p>Both, because reading only one and silently returning empty for the other
     * would look exactly like a pattern that declared no tags — a wrong answer wearing
     * the shape of a correct one. The fork uses the block form; the inline form is
     * accepted so a future README written the other way is not silently thinned.</p>
     */
    private static List<String> frontmatterList(String text, String key) {
        List<String> out = new ArrayList<>();
        String[] lines = frontmatter(text).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith(key + ":")) {
                continue;
            }
            String inline = lines[i].substring(key.length() + 1).strip();
            if (inline.startsWith("[") && inline.endsWith("]")) {
                for (String part : inline.substring(1, inline.length() - 1).split(",")) {
                    String v = part.strip();
                    if (!v.isBlank()) {
                        out.add(v);
                    }
                }
                return out;
            }
            // BLANK LINES BETWEEN THE KEY AND ITS FIRST ITEM ARE SKIPPED, and this
            // is a measured defect rather than defensive coding. Two of the fork's
            // 187 READMEs — thread-pool-executor and thread-specific-storage — write
            //
            //     tag:
            //                     <- this line
            //     - Performance
            //
            // and the original loop broke on the first non-item line, which was that
            // blank. Both returned an EMPTY list while declaring five tags each: the
            // exact failure this method's own javadoc names two paragraphs up, "a
            // wrong answer wearing the shape of a correct one". It said the right
            // thing and did the wrong one.
            //
            // Blanks INSIDE the list are skipped for the same reason. The list still
            // ends at the first non-blank line that is not an item, so a following
            // key or the closing `---` terminates it and no other key's items can be
            // absorbed.
            for (int j = i + 1; j < lines.length; j++) {
                String item = lines[j].strip();
                if (item.isEmpty()) {
                    continue;
                }
                if (!item.startsWith("- ")) {
                    break;
                }
                String v = item.substring(2).strip();
                if (!v.isBlank()) {
                    out.add(v);
                }
            }
            return out;
        }
        return out;
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
        String type = referenceTypeName(slug);
        if (!type.isBlank()) {
            // Omitted rather than guessed when the tree holds none. A composed
            // package for a slug with no sources is an address that does not open,
            // and one of those is worse than saying nothing.
            sb.append("\n\nReference implementation: ").append(type);
        }
        sb.append("\nSource: ").append(namespace).append(", ").append(slug)
          .append("/README.md at ").append(pinnedCommit);
        if (licenceNote != null && !licenceNote.isBlank()) {
            // Only where the origin states one. Attribution is a condition of
            // redistributing somebody else's prose, so the fork's rows keep theirs;
            // an own-authored origin has none to state and gets no invented default.
            sb.append("\nLicence: ").append(licenceNote);
        }
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

    /**
     * EVERY source root under the slug, not just the flat one.
     *
     * <p><b>Measured 2026-08-30 at pin {@code 22a34127d}:</b> seven of the 187 patterns
     * are MULTI-MODULE — {@code <slug>/<module>/src/main/java/...} — and have no
     * {@code <slug>/src/main/java} at all. Looking only at the flat layout found nothing
     * for all seven and returned the empty string, which then travelled onto the row as
     * a value. Six of them have 7 to 15 source files sitting one directory deeper.</p>
     *
     * <p>Ordered so the answer is deterministic across roots: flat first if it exists,
     * then the nested roots in path order.</p>
     */
    private List<Path> sourceRoots(String slug) {
        Path slugDir = forkRoot.resolve(slug);
        List<Path> roots = new ArrayList<>();
        Path flat = slugDir.resolve("src/main/java");
        if (Files.isDirectory(flat)) {
            roots.add(flat);
        }
        if (!Files.isDirectory(slugDir)) {
            return roots;
        }
        try (Stream<Path> walk = Files.walk(slugDir)) {
            walk.filter(Files::isDirectory)
                .filter(p -> p.endsWith(Path.of("src", "main", "java")))
                .filter(p -> !p.equals(flat))
                .sorted(Comparator.comparing(Path::toString))
                .forEach(roots::add);
        } catch (IOException e) {
            // An unreadable tree yields the roots found so far. Returning none here
            // would be indistinguishable from a slug that genuinely has no sources.
            return roots;
        }
        return roots;
    }

    /**
     * The entry point, or {@code ""} when the slug genuinely has no Java source.
     *
     * <p>{@code naked-objects} is the one such pattern at this pin — a README and an
     * {@code etc} directory, no sources anywhere. The caller omits the field entirely
     * rather than writing {@code ""}, because an empty address on a row reads as a value
     * and this project has shipped that confusion three times in one stage.</p>
     */
    private String referenceTypeName(String slug) {
        List<Path> roots = sourceRoots(slug);
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        int bestDepth = Integer.MAX_VALUE;
        for (Path srcRoot : roots) {
            try (Stream<Path> walk = Files.walk(srcRoot)) {
                for (Path p : walk.filter(x -> x.getFileName().toString().endsWith(".java"))
                                  .sorted(Comparator.comparing(x -> srcRoot.relativize(x).toString()))
                                  .toList()) {
                    // App first where one exists — it is the fork's entry point and the
                    // most useful thing to open — then shortest-path first, so the type
                    // nearest a source root wins over one buried in a sub-package.
                    int rank = "App.java".equals(p.getFileName().toString()) ? 0 : 1;
                    int depth = srcRoot.relativize(p).getNameCount();
                    if (rank < bestRank || (rank == bestRank && depth < bestDepth)) {
                        String rel = srcRoot.relativize(p).toString();
                        best = rel.substring(0, rel.length() - ".java".length())
                            .replace(java.io.File.separatorChar, '.');
                        bestRank = rank;
                        bestDepth = depth;
                    }
                }
            } catch (IOException e) {
                // Skip an unreadable root; another may still answer.
                continue;
            }
        }
        return best == null ? "" : best;
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
        root.put("namespace", namespace);
        // pinned_commit and count are THE HEADER THE LOADER DEPENDS ON, whatever the
        // origin. count is the completeness guard the orphan sweep refuses to run
        // without; pinned_commit is the authority a row is re-resolved against. A
        // generalised extractor that emits every body correctly and drops these two
        // would silently disable the sweep and unpin the authority — which is why
        // they are asserted present, not assumed.
        root.put("pinned_commit", pinnedCommit);
        root.put("count", records.size());
        if (licenceNote != null && !licenceNote.isBlank()) {
            // Kept for an origin that states one — attribution is a condition of
            // redistributing somebody else's prose. Not invented for one that does not.
            root.put("licence_verdict", licenceNote);
        }
        ArrayNode arr = root.putArray("patterns");
        for (Record r : records) {
            ObjectNode n = json.createObjectNode();
            n.put("slug", r.slug());
            // `reference`, and NO verdict — matching PatternCatalogueLoader's
            // CATALOGUE_TYPE, which is the authority and cannot be referenced from
            // this bundle. These were `lesson` / `unproven`, and the loader has
            // always overridden both: it forces `reference` and ignores the verdict.
            // So the snapshot asserted two things about every row that were never
            // true of the row actually written. A published pattern is somebody
            // else's reference, not this machine's experience — it never turned out
            // any way at all, and `unproven` was a value invented so 187 rows could
            // pay a debt their type does not owe.
            //
            // NOTE: the COMMITTED snapshot still carries the old pair. Correcting it
            // means re-running this extraction against the fork checkout, which
            // re-hashes every record and rewrites all 187 rows.
            n.put("type", "reference");
            n.put("situation", r.situation());
            // The design FORCE — read by CatalogueManifest.entryFor and ranked on by the
            // recall differential when two patterns share a situation. It existed only in
            // the committed snapshot until now, so re-running this extraction DELETED all
            // 187 of them, invisibly, until a recall stopped discriminating. Absent stays
            // absent: an invented cause would be ranked on, and a wrong one is worse than
            // none.
            if (r.cause() != null && !r.cause().isBlank()) {
                n.put("cause", r.cause());
            }
            n.put("principle", r.principle());
            n.put("details", r.details());
            n.put("source_ref", r.sourceRef());
            // S10.1c: this holds `com.iluwatar.builder.App` — the Java entry point,
            // never a classification. Under its old name, `reference_type`, it was read
            // as the pattern's family by the one person who looked, which is the whole
            // reason S10.1 exists. Renamed at the same regeneration as the two fields
            // below so the 187 rows are re-hashed ONCE: hashOf digests the entire row,
            // so every field added or renamed here costs a full supersede-and-rewrite
            // of all of them, and two separate landings would cost that twice.
            // ABSENT, not "". naked-objects is the one pattern at this pin with no Java
            // source anywhere — a README and an `etc` directory — so it has no entry
            // point, and an empty string on the row reads as a value. Same rule as
            // `category` and `cause` above; this is the third place in one stage where
            // writing absence as presence would have shipped a wrong answer.
            if (r.entryPointClass() != null && !r.entryPointClass().isBlank()) {
                n.put("entry_point_class", r.entryPointClass());
            }
            // S10.1a — the pattern's OWN classification, which the source declares and
            // this extractor discarded until now. Harald, 2026-08-30: "we have different
            // patterns for different situations: create an object -> creational pattern
            // like builder or factory. So you can distinguish".
            //
            // ABSENT IS WRITTEN AS ABSENT. Three of the 188 upstream READMEs declare no
            // category; they get no key rather than a default. A row filed under a family
            // nobody assigned it is worse than one carrying none, because "we do not
            // know" is an answer a reader can act on and a wrong family is not.
            if (r.category() != null && !r.category().isBlank()) {
                n.put("category", r.category());
            }
            if (!r.tags().isEmpty()) {
                ArrayNode tags = n.putArray("tags");
                r.tags().forEach(tags::add);
            }
            arr.add(n);
        }
        return root;
    }

    /** slug -&gt; reviewed situation, from the committed curation file. */
    public static Map<String, String> readReviewed(Path file, ObjectMapper json) throws IOException {
        return readCurated(file, json, "situations");
    }

    /**
     * slug -&gt; a hand-curated value, under the named top-level object.
     *
     * <p><b>Why there is a second curated input.</b> The 187 committed rows each carry a
     * {@code cause} — the design FORCE the pattern answers, which is what a recall
     * differential discriminates on when two patterns share a situation (Factory and
     * Builder both answer "constructing an object"). Those causes were authored straight
     * into {@code patterns.json} and existed in no other file, so this extractor could
     * not reproduce its own committed artifact: every regeneration silently dropped all
     * 187 of them, and the loss would have been invisible until a recall stopped
     * discriminating.</p>
     *
     * <p>They now live in {@code causes.json} beside {@code situations.json} and are read
     * the same way, because two curated inputs handled two different ways is how the next
     * person picks the wrong one.</p>
     */
    public static Map<String, String> readCurated(Path file, ObjectMapper json, String key)
            throws IOException {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        JsonNode values = root.path(key);
        values.fieldNames().forEachRemaining(k -> out.put(k, values.get(k).asText()));
        return out;
    }
}
