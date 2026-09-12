package org.jawata.mcp.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Render a stored row as the markdown file {@code ExperienceMaintenance.parse} reads back
 * — Sprint 28f Stage 6, the story folder's EXPORT half.
 *
 * <p><b>The direction this restores.</b> The store used to be rebuilt from files, so a
 * direct record wrote knowledge the next reseed deleted, and every agent was told to
 * write a file instead. Stages 1 and 2 gave the store backups and durable writes; this is
 * what lets the instruction be reversed — an agent records to the STORE, and the store
 * writes the file when a reader accepts the row. The folder becomes an export and a
 * human-readable mirror rather than the truth.</p>
 *
 * <h2>The format is the PARSER's, and nothing here may assume otherwise</h2>
 *
 * <p>Every key below is one {@code ExperienceMaintenance.parse} actually reads. That list
 * is deliberately NOT shared as a constant between the two: a shared constant would keep
 * them spelled alike while saying nothing about whether the parser still READS a key it
 * spells. What binds them is {@code StoryRoundTripTest}, which writes a row, parses the
 * file back, and compares — so a key the parser stops reading turns that test red rather
 * than quietly dropping a field on export.</p>
 *
 * <h2>Two shapes of the loader this writer must respect</h2>
 *
 * <ul>
 *   <li><b>A heading splits a file into section rows.</b> {@code splitSections} cuts the
 *       body at any markdown heading, and each slice becomes its own entry. A row whose
 *       details contain a heading therefore re-imports as several rows. That is the
 *       loader's long-standing design for memory files rather than anything new here, and
 *       it is stated so the next reader of a multiplied row knows where to look.</li>
 *   <li><b>The name becomes a symptom.</b> The loader indexes the prosified file name as
 *       a cue. So a re-imported row carries one symptom the original did not, which is
 *       why the round trip is asserted as BYTE-STABLE ON RE-EXPORT rather than as a
 *       fixed point after one pass — the added cue is the loader's, it is deterministic,
 *       and the second export equals the first.</li>
 * </ul>
 *
 * <p>PURE where it can be: {@link #render} takes a row and answers text, with no
 * filesystem and no clock, so every format decision is testable without a directory.</p>
 */
public final class StoryWriter {

    private static final Logger log = LoggerFactory.getLogger(StoryWriter.class);

    /**
     * Where accepted stories are written. ABSENT means the export is off, which is what
     * every installation is until somebody points it somewhere.
     *
     * <p>Read per call so a change does not need a restart — the same choice
     * {@code StoreBackups.DEPTH_PROPERTY} makes, and for the same reason.</p>
     */
    public static final String DIRECTORY_PROPERTY = "jawata.stories.dir";

    /** The stamp's format, which is the one the parser's own fixtures carry. */
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private StoryWriter() {
    }

    /** The configured folder, or empty when nothing is configured. */
    public static Optional<Path> directory() {
        String raw = System.getProperty(DIRECTORY_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(raw.trim()));
    }

    /**
     * Write {@code entry} into the configured folder, answering the file written.
     *
     * <p>Empty when no folder is configured — the common case, and NOT a failure: the
     * export is opt-in and a store with nowhere to write is simply a store that keeps
     * its knowledge in the database, which is where it now lives.</p>
     *
     * <p>An I/O failure is LOGGED AND SWALLOWED, deliberately. This runs on the
     * acceptance path, and a full disk or a read-only folder must not be able to
     * refuse a review that already happened — the row is the truth and the file is a
     * mirror, so losing the mirror costs a re-export and losing the acceptance costs
     * the review.</p>
     */
    public static Optional<Path> write(StoredEntry entry) {
        Optional<Path> dir = directory();
        if (dir.isEmpty()) {
            return Optional.empty();
        }
        Path file = dir.get().resolve(fileName(entry));
        try {
            Files.createDirectories(dir.get());
            Files.writeString(file, render(entry), StandardCharsets.UTF_8);
            return Optional.of(file);
        } catch (IOException e) {
            log.warn("could not write the story for {} to {}: {}",
                entry.id(), file, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The file a row is written to — STABLE for a given row, so a re-export overwrites
     * its own file rather than growing a second copy beside it.
     *
     * <p>The slug is for a human scanning the folder; the id suffix is what makes it
     * unique and stable. Two rows can genuinely share a claim, and a slug alone would
     * silently let the later one overwrite the earlier.</p>
     */
    static String fileName(StoredEntry entry) {
        String slug = slug(entry.summary());
        String id = entry.id() == null ? "row" : entry.id();
        return (slug.isEmpty() ? "story" : slug) + "-"
            + id.substring(0, Math.min(8, id.length())) + ".md";
    }

    /** Render the row as the markdown a re-import reads back. */
    public static String render(StoredEntry entry) {
        StringBuilder b = new StringBuilder();
        b.append("---\n");
        line(b, "name", slug(entry.summary()));
        line(b, "description", entry.summary());
        line(b, "type", entry.type());
        line(b, "symbol", entry.symbolFqn());
        line(b, "language", entry.language());
        StoredEntry.Facets f = entry.facets();
        line(b, "situation", f.situation());
        line(b, "cause", f.cause());
        line(b, "verdict", f.verdict());
        // Written ONLY when the row really carries one. A row nobody reviewed exports no
        // stamp, rather than today's date — the stamp is what the reseed gate trusts,
        // and synthesising it here is the forgery this whole column exists to avoid.
        if (f.reviewedAt() != null) {
            line(b, "reviewed", STAMP.format(f.reviewedAt()));
        }
        List<String> symptoms = entry.symptoms();
        if (symptoms != null && !symptoms.isEmpty()) {
            b.append("symptoms:\n");
            for (String s : symptoms) {
                String one = oneLine(s);
                if (!one.isEmpty()) {
                    b.append("  - ").append(one).append('\n');
                }
            }
        }
        b.append("---\n\n");
        Object details = entry.body() == null ? null : entry.body().get("details");
        if (details != null && !details.toString().isBlank()) {
            b.append(details.toString().strip()).append('\n');
        }
        return b.toString();
    }

    /** One {@code key: value} line, omitted entirely when the value is absent. */
    private static void line(StringBuilder b, String key, String value) {
        String one = oneLine(value);
        if (!one.isEmpty()) {
            b.append(key).append(": ").append(one).append('\n');
        }
    }

    /**
     * Flatten to a single line, because frontmatter is line-oriented: the parser splits
     * a key from its value at the first colon of ONE line, so an embedded newline would
     * put the rest of the value in the body — or, worse, have it read as another key.
     */
    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    /** A filesystem- and frontmatter-safe slug: lower case, words joined by hyphens. */
    static String slug(String summary) {
        if (summary == null) {
            return "";
        }
        String s = summary.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
        return s.length() > 60 ? s.substring(0, 60).replaceAll("-+$", "") : s;
    }
}
