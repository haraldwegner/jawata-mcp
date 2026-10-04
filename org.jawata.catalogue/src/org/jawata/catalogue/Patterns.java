package org.jawata.catalogue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The pattern lane of the shared catalogue — the design patterns a second engine can declare
 * against, keyed the same way everything else here is.
 *
 * <h2>jawata-duplicate: these rows are SEEDED, not authored, and that is the point</h2>
 *
 * <p>The 187 patterns already exist, derived once from the pinned fork by
 * {@code CatalogExtractor} and seeded into the knowledge store with their full shape — the
 * situation, the cause, the principle, the consequences, the entry-point class. <b>Hand-typing
 * 187 names into Java would be a second copy of that snapshot</b>, and this repository has a long
 * record of what a second copy costs: five code generators with one byte-identical lookup, six
 * copies of a type search already drifted into two behaviours, thirty-three private copies of a
 * four-line parse helper.</p>
 *
 * <p>So the rows are read from {@code patterns.tsv}, which is GENERATED from
 * {@code build/acceptance/patterns-<commit>.json} and carries that commit in its own header. The
 * snapshot stays the single source; this file is a projection of its key space.</p>
 *
 * <h2>Why the lane exists at all, when the store already holds the patterns</h2>
 *
 * <p>Because the store holds the CONTENT and the catalogue holds the IDENTITY. A pattern's store
 * entry is addressed by {@code catalogue:java-design-patterns/<slug>/README.md}; an engine
 * declaring what it can do about that pattern needs a key it can name without quoting a file path
 * in another repository. {@code java-design-patterns:visitor} is that key, and it is the same
 * shape as {@code fowler:23} and the architecture's own {@code cpp:rule-of-five}.</p>
 *
 * <h2>The name is the SLUG, verbatim, and nothing is derived from it</h2>
 *
 * <p>{@code abstract-document}, not "Abstract Document". The snapshot carries no title field — the
 * extractor never kept the README's heading — so a title-cased name would be one I generated
 * rather than one anybody published, and the whole soundness argument for the other two lanes is
 * that their names are external and verbatim. The slug IS how the fork names the pattern: it is
 * the directory, and it is what the store's own {@code source_ref} already points at.</p>
 *
 * <p>RECORDED FOR C8 as a decision rather than settled here: giving these rows a published
 * display title means teaching the extractor to keep the README's first heading and regenerating
 * the pinned 354 KB snapshot. That is a change to a committed artifact with a licence obligation
 * attached, so it is raised at the checkpoint rather than taken mid-stage.</p>
 */
public final class Patterns {

    /** The origin every row of the pinned fork's catalogue carries. */
    public static final String FORK = "java-design-patterns";

    /** The generated resource, beside this class so the classpath lookup needs no package path. */
    private static final String RESOURCE = "patterns.tsv";

    private static final List<CatalogueRow> ROWS = rows();

    private Patterns() {}

    /**
     * Every pattern this lane holds, in the snapshot's own order.
     *
     * <p>Cross-lane questions belong to {@link Catalogue}.</p>
     */
    public static List<CatalogueRow> all() {
        return ROWS;
    }

    private static List<CatalogueRow> rows() {
        List<CatalogueRow> out = new ArrayList<>();
        try (InputStream in = Patterns.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                // A MISSING RESOURCE IS NOT AN EMPTY LANE. Returning an empty list would make
                // every count taken against this lane pass by agreeing with nothing, which is the
                // manufactured-absence failure this product has shipped before. Say it instead.
                throw new IllegalStateException(
                        "the pattern lane's resource " + RESOURCE + " is not on the classpath — "
                                + "if the bundle stopped copying it, every pattern row is silently "
                                + "gone and no count would notice");
            }
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String slug = line.trim();
                // The header states the resource's provenance — the pinned commit, the count and
                // the licence — so a reader of the file never has to go looking for the snapshot.
                if (slug.isEmpty() || slug.startsWith("#")) {
                    continue;
                }
                out.add(new CatalogueRow(FORK + CatalogueRow.SEPARATOR + slug, slug));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the pattern lane could not be read", e);
        }
        return List.copyOf(out);
    }

    /**
     * What the resource says about itself — the {@code # origin=… pinned=… count=…} line.
     *
     * <p>Exposed so a test can hold the lane against the snapshot's OWN count rather than against
     * a number written into the test. A hand-written 187 in an assertion is a second copy of the
     * one fact this whole class exists to avoid copying.</p>
     *
     * @return the header's declared count, or {@code -1} when the resource declares none
     */
    public static int declaredCount() {
        try (InputStream in = Patterns.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return -1;
            }
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                int at = line.indexOf("count=");
                if (line.startsWith("#") && at >= 0) {
                    String tail = line.substring(at + "count=".length()).trim();
                    int end = tail.indexOf(' ');
                    try {
                        return Integer.parseInt(end < 0 ? tail : tail.substring(0, end));
                    } catch (NumberFormatException ignored) {
                        return -1;
                    }
                }
            }
            return -1;
        } catch (IOException e) {
            throw new UncheckedIOException("the pattern lane's header could not be read", e);
        }
    }
}
