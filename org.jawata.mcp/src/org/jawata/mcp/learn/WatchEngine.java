package org.jawata.mcp.learn;

import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The automatic architect's watch engine (Sprint 26, D1): on every call whose
 * delta touched source files, run CHEAP single-file detectors on exactly those
 * files, diff against the retained per-file baseline, and surface at most
 * {@link #NOISE_BUDGET} NEW findings as a steering block — attached to the
 * answer the agent already reads, in every client, with no command and no
 * hook. Never a project sweep: the delta is the scope.
 */
public class WatchEngine {

    /** The detector seam — the real binding calls {@code find_quality_issue};
     * tests inject a fake. Kept functional so the engine stays pure. */
    @FunctionalInterface
    public interface DetectorFn {
        ToolResponse detect(String kind, String filePath);
    }

    private static final Logger log = LoggerFactory.getLogger(WatchEngine.class);

    /** The filePath-accepting kinds cheap enough to run per changed file. */
    static final List<String> WATCH_KINDS = List.of("bugs", "unused", "naming");
    /** At most this many findings ride one answer — the noise budget. */
    public static final int NOISE_BUDGET = 2;
    /** A finding emitted once stays silent for this long (decay floor). */
    static final long COOLDOWN_MS = 30 * 60_000L;
    /** A mass change is not a watchable delta — cap the per-call file scope. */
    static final int MAX_FILES_PER_CALL = 10;

    private final DetectorFn detector;
    private final LearnerEventStore events;
    private final Map<String, Long> recentlyEmitted =
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > 500;
            }
        };

    public WatchEngine(DetectorFn detector, LearnerEventStore events) {
        this.detector = detector;
        this.events = events;
    }

    /**
     * Inspect the delta; return the steering block when NEW findings surfaced.
     * Every emission is journaled as a {@code watch_finding} learner event.
     */
    public synchronized Optional<String> watch(String sessionId, List<String> deltaPaths) {
        if (deltaPaths == null || deltaPaths.isEmpty()) {
            return Optional.empty();
        }
        List<String> emitted = new ArrayList<>();
        long now = System.currentTimeMillis();
        int filesInspected = 0;
        for (String path : deltaPaths) {
            if (!path.endsWith(".java") || filesInspected >= MAX_FILES_PER_CALL) {
                continue;
            }
            filesInspected++;
            Set<String> current = new LinkedHashSet<>();
            List<String[]> details = new ArrayList<>();
            for (String kind : WATCH_KINDS) {
                collectFindings(kind, path, current, details);
            }
            Set<String> baseline = loadBaseline(path);
            saveBaseline(path, current);
            for (String[] d : details) {
                if (emitted.size() >= NOISE_BUDGET) {
                    break;
                }
                String key = d[0];
                if (baseline.contains(key)) {
                    continue;
                }
                Long last = recentlyEmitted.get(key);
                if (last != null && now - last < COOLDOWN_MS) {
                    continue;
                }
                recentlyEmitted.put(key, now);
                emitted.add(d[1]);
                if (events != null) {
                    events.append(new LearnerEvent(sessionId,
                        LearnerEvent.KIND_WATCH_FINDING, "watch",
                        "{\"key\":\"" + key.replace("\"", "'") + "\"}"));
                }
            }
        }
        if (emitted.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder block = new StringBuilder(
            "ARCHITECT WATCH (new since your change — design fix or bandage?):");
        for (String line : emitted) {
            block.append("\n- ").append(line);
        }
        return Optional.of(block.toString());
    }

    private void collectFindings(String kind, String path, Set<String> keys,
            List<String[]> details) {
        try {
            ToolResponse r = detector.detect(kind, path);
            if (r == null || !r.isSuccess() || !(r.getData() instanceof Map<?, ?> map)) {
                return;
            }
            // Two response shapes ride the quality door: the common
            // {findings:[{message,symbol,line}]} and the unused kind's legacy
            // {unusedItems:[{name,kind,line,signature}]}.
            if (map.get("findings") instanceof List<?> findings) {
                for (Object f : findings) {
                    if (!(f instanceof Map<?, ?> finding)) {
                        continue;
                    }
                    Object symbol = finding.get("symbol");
                    Object line = finding.get("line");
                    Object message = finding.get("message");
                    add(keys, details, kind, path, line,
                        symbol != null ? String.valueOf(symbol) : String.valueOf(line),
                        String.valueOf(message));
                }
            }
            if (map.get("unusedItems") instanceof List<?> items) {
                for (Object f : items) {
                    if (!(f instanceof Map<?, ?> item)) {
                        continue;
                    }
                    Object name = item.get("name");
                    Object line = item.get("line");
                    add(keys, details, kind, path, line, String.valueOf(name),
                        "unused " + item.get("kind") + " " + name);
                }
            }
            // Sprint 28: THREE more shapes ride the quality door, and this engine saw none of
            // them — `violations` (naming, large_classes, nullness), `issues` (bugs) and
            // `cycles` (circular_deps). A watch on any of those kinds recorded silently
            // nothing, which reads exactly like "no findings". Same defect the v3.6.4
            // summary=true fix had, in a second consumer: the comment above said "two response
            // shapes ride the quality door" and there were five.
            for (String key : new String[] {"violations", "issues", "cycles"}) {
                if (!(map.get(key) instanceof List<?> rows)) {
                    continue;
                }
                for (Object f : rows) {
                    if (!(f instanceof Map<?, ?> row)) {
                        continue;
                    }
                    Object line = row.get("line");
                    // Identity, best-first: these shapes disagree on their name field, and an
                    // identity that falls back to the line still keys a stable watch entry.
                    Object identity = firstNonNull(row.get("symbol"), row.get("name"),
                        row.get("type"), row.get("packageName"), line);
                    Object message = firstNonNull(row.get("message"), row.get("description"),
                        row.get("reason"), identity);
                    add(keys, details, kind, path, line, String.valueOf(identity),
                        String.valueOf(message));
                }
            }
        } catch (Exception e) {
            log.warn("watch detector {} failed on {} — skipped", kind, path, e);
        }
    }

    /**
     * Returns the first non-null candidate.
     *
     * <p>Sprint 28: the quality shapes disagree on which field names a finding
     * ({@code symbol} / {@code name} / {@code type} / {@code packageName}), so the watch
     * identity is picked best-first rather than assumed.</p>
     *
     * @param candidates the values to probe, in preference order
     * @return the first non-null value, or {@code null} when every candidate is null
     */
    private static Object firstNonNull(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static void add(Set<String> keys, List<String[]> details, String kind,
            String path, Object line, String identity, String message) {
        String key = kind + "|" + path + "|" + identity;
        keys.add(key);
        details.add(new String[] {key,
            kind + " at " + path + (line != null ? ":" + line : "") + " — " + message});
    }

    private Set<String> loadBaseline(String path) {
        if (events == null) {
            return Set.of();
        }
        return events.loadState("watch:" + path)
            .map(s -> (Set<String>) new LinkedHashSet<>(List.of(s.split("\n"))))
            .orElse(Set.of());
    }

    private void saveBaseline(String path, Set<String> keys) {
        if (events != null) {
            events.saveState("watch:" + path, String.join("\n", keys));
        }
    }
}
