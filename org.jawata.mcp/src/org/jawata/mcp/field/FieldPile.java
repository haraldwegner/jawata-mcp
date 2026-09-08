package org.jawata.mcp.field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The local field pile (Sprint 28b, D1): append-only JSONL under
 * {@code <workspace>/field/pile.jsonl}, versioned header line, fold-at-read.
 * Append-only is the write discipline the architecture artifact mandates for
 * any concurrently-written file (the read-modify-write corruption class);
 * studio reads the same file directly and folds it itself.
 *
 * <p>A failed write is LOUD, never silent: logged and counted in
 * {@link #failedWrites()} — a pile that quietly drops events is a lying
 * utilization metric (the D-RECALL-DEAD lesson).</p>
 */
public final class FieldPile {

    private static final Logger log = LoggerFactory.getLogger(FieldPile.class);

    /** Bump on any change to the line schema; studio refuses lines from a
     *  newer format instead of misreading them (the D7 discipline applied to
     *  the file seam). */
    public static final int FORMAT_VERSION = 1;

    /** The pile's file name inside the field directory. Part of the FILE SEAM
     *  — studio and the hook open this name themselves — so it is a named
     *  constant rather than a literal repeated at each end. */
    public static final String FILE_NAME = "pile.jsonl";

    private final Path file;
    private final AtomicLong failedWrites = new AtomicLong();
    private final AtomicLong failedReads = new AtomicLong();

    /** @param dir the {@code <workspace>/field} directory (created lazily). */
    public FieldPile(Path dir) {
        this.file = dir.resolve(FILE_NAME);
    }

    /** Appends one event; writes the versioned header first on a fresh file.
     *  Never throws — recording must never fail the tool call it observes. */
    public synchronized void append(FieldEvent event) {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file,
                    "{\"pileFormat\":" + FORMAT_VERSION
                        + ",\"contract\":" + FieldContract.VERSION + "}\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(file, event.toJsonLine() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            failedWrites.incrementAndGet();
            log.error("FIELD EVENT DROPPED ({} total) — the local field signal is"
                + " incomplete until this is fixed", failedWrites.get(), e);
        }
    }

    /** Fold-at-read: every event line, oldest first; the header and any
     *  unparseable line are skipped (skips are counted as failed reads by the
     *  caller's delta against {@link #lineCount()} if it cares). */
    public synchronized List<FieldEvent> fold() {
        List<FieldEvent> events = new ArrayList<>();
        if (!Files.exists(file)) {
            return events;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                FieldEvent event = parse(line);
                if (event != null) {
                    events.add(event);
                }
            }
        } catch (IOException e) {
            // mcp#74: COUNTED, not only logged. Returning the events read so far — usually
            // none — makes "the recording is empty" and "I could not read the recording" the
            // same answer, and the second is precisely the state someone is looking at the
            // pile to discover. The write side has had this counter since Sprint 28b under a
            // javadoc saying why; the read side did not.
            failedReads.incrementAndGet();
            log.error("Field pile unreadable at {} ({} failed read(s)) — every count below is"
                + " a FLOOR, not a total", file, failedReads.get(), e);
        }
        return events;
    }

    /** Error-shape counts over the folded pile, insertion-ordered. */
    public Map<String, Long> countErrorShapes() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (FieldEvent event : fold()) {
            if (!event.ok()) {
                counts.merge(event.shapeKey(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /**
     * One error shape's recurrence AND its position in time.
     *
     * @param count       how many times it happened inside the window asked for
     * @param firstMillis when it was first seen
     * @param lastMillis  when it was LAST seen — the field that answers mcp#74
     */
    public record ShapeStat(long count, long firstMillis, long lastMillis) {

        /** Whole days between the last occurrence and {@code now}; 0 for today. */
        public long daysSinceLast(long now) {
            return Math.max(0, (now - lastMillis) / 86_400_000L);
        }
    }

    /** The span a fold actually covers: no event is older than {@code fromMillis}
     *  or newer than {@code toMillis}. {@code events} is 0 for an empty pile, and
     *  then both bounds are 0 — which the caller must not render as 1970. */
    public record Span(long fromMillis, long toMillis, int events) {}

    /**
     * Error shapes with their first and LAST occurrence, over events no older than
     * {@code sinceMillis} (0 for the whole pile).
     *
     * <p>mcp#74. The pile ranked shapes by a lifetime count and said nothing about WHEN,
     * so `experience/recall/KNOWLEDGE_UNAVAILABLE` at 323 could equally be a live fault or
     * one fixed months ago — and the issue could only be filed as a field report because
     * of it. <b>The timestamp was never missing.</b> Every event carries {@code epochMillis}
     * and every pile line writes it as {@code "t"}; the AGGREGATION discarded it. So this
     * needs no schema change, no migration, and it can answer about piles already on disk.</p>
     */
    public Map<String, ShapeStat> errorShapes(long sinceMillis) {
        Map<String, ShapeStat> shapes = new LinkedHashMap<>();
        for (FieldEvent event : fold()) {
            if (event.ok() || event.epochMillis() < sinceMillis) {
                continue;
            }
            shapes.merge(event.shapeKey(),
                new ShapeStat(1, event.epochMillis(), event.epochMillis()),
                (a, b) -> new ShapeStat(a.count() + b.count(),
                    Math.min(a.firstMillis(), b.firstMillis()),
                    Math.max(a.lastMillis(), b.lastMillis())));
        }
        return shapes;
    }

    /** What the pile actually covers, over events no older than {@code sinceMillis}.
     *  A count without the span it was taken over is a bare number, which is the
     *  defect mcp#74 is about one level up. */
    public Span span(long sinceMillis) {
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        int seen = 0;
        for (FieldEvent event : fold()) {
            if (event.epochMillis() < sinceMillis) {
                continue;
            }
            from = Math.min(from, event.epochMillis());
            to = Math.max(to, event.epochMillis());
            seen++;
        }
        return seen == 0 ? new Span(0, 0, 0) : new Span(from, to, seen);
    }

    /** Writes that survived no attempt — surfaced, never hidden. */
    public long failedWrites() {
        return failedWrites.get();
    }

    /**
     * Reads that FAILED — surfaced for the same reason writes are.
     *
     * <p>mcp#74, and it is the worst form of the defect that issue names. {@link #fold}
     * answers with an empty list when the pile cannot be read, which a caller cannot tell
     * from a clean install: "no failures recorded" and "I could not open the recording" are
     * the same answer, and the second is exactly when someone is looking. The write side
     * already had this counter and its javadoc already said why — <i>a pile that quietly
     * drops events is a lying utilization metric</i> — while the read side did not.</p>
     */
    public long failedReads() {
        return failedReads.get();
    }

    /** Parses one pile line back into an event; null for the header or any
     *  line that does not match the emitted shape. The parser accepts ONLY
     *  what {@link FieldEvent#toJsonLine()} emits — token-valued fields — so a
     *  hand-edited pile cannot smuggle free text into a folded event. */
    static FieldEvent parse(String line) {
        try {
            if (line == null || !line.startsWith("{\"t\":")) {
                return null;
            }
            long t = Long.parseLong(section(line, "\"t\":", ','));
            Token tool = Token.of(section(line, "\"tool\":\"", '"'));
            Token kind = Token.of(section(line, "\"kind\":\"", '"'));
            boolean ok = Boolean.parseBoolean(section(line, "\"ok\":", ','));
            Token code = Token.of(section(line, "\"code\":\"", '"'));
            int lat = Integer.parseInt(section(line, "\"lat\":", ','));
            Token client = Token.of(section(line, "\"client\":\"", '"'));
            Version version = Version.of(
                section(line, "\"ver\":\"", '"').replace('_', '.'));
            return new FieldEvent(t, tool, kind, ok, code, lat, client, version);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String section(String line, String key, char end) {
        int from = line.indexOf(key);
        if (from < 0) {
            throw new IllegalArgumentException("missing key");
        }
        from += key.length();
        int to = line.indexOf(end, from);
        if (to < 0) {
            to = line.indexOf('}', from);
        }
        return line.substring(from, to);
    }
}
