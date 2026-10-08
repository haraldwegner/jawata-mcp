package org.jawata.mcp.runtime.profile;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 24 (D11) — turns a JFR recording into ranked, symbol-named hotspots.
 * Same honesty rule as the rest of the profiling floor: totals are reported
 * alongside a capped, paginated list, so a page never reads as the whole
 * recording.
 *
 * <p>Four dimensions, three of them per-method rankings built from a stack-
 * sampling JFR event (top frame = the method actually running/allocating/
 * locking); the fourth ({@code gc}) has no Java stack to rank BY method — a
 * GC pause is a fact about the collector, not a call site — so it reports a
 * summary instead of inventing an attribution JFR does not provide.</p>
 *
 * <p>The symbol format ({@code ClassName#methodName}) matches the convention
 * used across the rest of jawata (breakpoint hits, coverage) — the same key
 * a caller can hand straight to {@code get_call_hierarchy} or {@code
 * find_references}, no search in between.</p>
 */
public final class JfrParser {

    /** Count-based dimensions → the JFR event type each ranks by. `wall` is duration-based (see below). */
    public static final Map<String, String> DIMENSION_EVENTS = Map.of(
        "cpu", "jdk.ExecutionSample",
        "alloc", "jdk.ObjectAllocationSample",
        "lock", "jdk.JavaMonitorEnter");

    /**
     * The duration-bearing JFR events that make up WALL-CLOCK time OFF the CPU — time the
     * program was elapsing but not running: blocked on a monitor, parked, waiting, or in a
     * blocking I/O call. Each carries a real {@code getDuration()}. On-CPU wall time is added
     * from execution samples (see {@link #wallHotspots}). Together they are total elapsed
     * time attributed per method — which is what "wall time" means, and what D11 lists as a
     * ranking dimension beside cpu/alloc/lock/gc.
     */
    public static final List<String> WALL_BLOCKING_EVENTS = List.of(
        "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark",
        "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite");

    private JfrParser() {
    }

    /**
     * Rank methods for one dimension (cpu | alloc | lock), capped at {@code limit}
     * starting at {@code offset} — with the TRUE total sample and method counts
     * reported alongside, same discipline as the class histogram.
     */
    public static Map<String, Object> hotspots(Path jfrFile, String dimension,
                                               int offset, int limit) throws IOException {
        String eventType = DIMENSION_EVENTS.get(dimension);
        if (eventType == null) {
            throw new IllegalArgumentException("Unknown dimension '" + dimension
                + "'. One of " + DIMENSION_EVENTS.keySet());
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        long totalEvents = 0;
        long eventsWithoutStack = 0;

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                if (!eventType.equals(event.getEventType().getName())) {
                    continue;
                }
                totalEvents++;
                String symbol = topFrameSymbol(event);
                if (symbol == null) {
                    eventsWithoutStack++;
                    continue;
                }
                counts.merge(symbol, 1L, Long::sum);
            }
        }

        List<Map.Entry<String, Long>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = offset; i < ranked.size() && rows.size() < limit; i++) {
            Map.Entry<String, Long> entry = ranked.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("symbol", entry.getKey());
            row.put("samples", entry.getValue());
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);
        result.put("rows", rows);
        result.put("returnedRows", rows.size());
        result.put("totalMethods", ranked.size());
        result.put("totalSamples", totalEvents);
        result.put("truncated", offset + rows.size() < ranked.size());
        if (eventsWithoutStack > 0) {
            result.put("samplesWithoutStack", eventsWithoutStack);
        }
        return result;
    }

    /**
     * Rank methods by total WALL-CLOCK time — elapsed time attributed per method whether the
     * method was on the CPU or blocked. On-CPU time comes from execution samples, each worth
     * one measured sampling interval; off-CPU time comes from the real durations of blocking
     * and I/O events ({@link #WALL_BLOCKING_EVENTS}). This is a genuine wall-time profile, not
     * lock-time renamed: a method that spends its wall clock parked, in a socket read, or
     * simply running all show up, ranked by the milliseconds each accounts for.
     */
    public static Map<String, Object> wallHotspots(Path jfrFile, int offset, int limit)
            throws IOException {
        Map<String, Long> wallNanos = new LinkedHashMap<>();
        // C15 — A TIME WITH NO COUNT BESIDE IT CANNOT BE WEIGHED. Every other dimension's row
        // carries `samples` — `assumeRankable` in the suite reads exactly that field to refuse
        // ranking a thin recording — and the wall dimension carried times alone, so a reader
        // could not tell a row built from ONE 4-second block from one built from four hundred
        // short ones. Those are different facts about a program and they ranked identically.
        //
        // IT IS NOT A CALL COUNT AND IS NOT NAMED ONE. `callCounts`' own javadoc records that
        // v2.13.0 relabelled a hotspot's top-of-stack sample count as an invocation count and a
        // Sprint-24 audit caught it; true per-method counts need a live method trace, which is
        // `call_counts`, and this is the number of EVENTS the milliseconds were summed from.
        Map<String, Long> wallCounts = new LinkedHashMap<>();
        List<Long> executionSampleTimesNanos = new ArrayList<>();
        List<String> executionSampleSymbols = new ArrayList<>();
        long blockingEvents = 0;
        long eventsWithoutStack = 0;
        long idleNanos = 0;
        long idleEvents = 0;

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String type = event.getEventType().getName();
                String symbol = topFrameSymbol(event);

                if ("jdk.ExecutionSample".equals(type)) {
                    // On-CPU: defer — attributed one MEASURED sampling interval each, once we
                    // know the interval from the samples themselves (below).
                    if (symbol == null) {
                        eventsWithoutStack++;
                        continue;
                    }
                    executionSampleTimesNanos.add(event.getStartTime().toEpochMilli() * 1_000_000L);
                    executionSampleSymbols.add(symbol);
                } else if (WALL_BLOCKING_EVENTS.contains(type)) {
                    if (!hasStack(event)) {
                        eventsWithoutStack++;
                        continue;
                    }
                    blockingEvents++;
                    long nanos = event.getDuration().toNanos();
                    // M13 HALF 1 — a thread WAITING FOR WORK is not a method that is slow.
                    // Attributed to the blocked PROGRAM method, or counted as idle when the
                    // stack holds none. Idle is reported BESIDE the ranking rather than inside
                    // it, which is what this method's own catalogued job asks for: parked time
                    // "shows up ALONGSIDE one that mostly computes" — visible, not dominant.
                    String blocked = blockedSymbol(event);
                    if (blocked == null) {
                        idleNanos += nanos;
                        idleEvents++;
                    } else {
                        wallNanos.merge(blocked, nanos, Long::sum);
                        wallCounts.merge(blocked, 1L, Long::sum);
                    }
                }
            }
        }

        // Each execution sample represents one sampling interval of on-CPU wall time. Measure
        // the interval from the samples rather than assuming JFR's default (which the recording
        // may have overridden): the median gap between consecutive sample timestamps.
        long sampleIntervalNanos = medianGapNanos(executionSampleTimesNanos);
        for (String symbol : executionSampleSymbols) {
            wallNanos.merge(symbol, sampleIntervalNanos, Long::sum);
            wallCounts.merge(symbol, 1L, Long::sum);
        }

        List<Map.Entry<String, Long>> ranked = new ArrayList<>(wallNanos.entrySet());
        ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        long rankedNanos = wallNanos.values().stream().mapToLong(Long::longValue).sum();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = offset; i < ranked.size() && rows.size() < limit; i++) {
            Map.Entry<String, Long> entry = ranked.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("symbol", entry.getKey());
            row.put("wallMillis", entry.getValue() / 1_000_000);
            // M13 HALF 2 — the measure asks for the method "reported AT ITS SHARE", and the
            // row carried none. The total was already computed here; the share simply was not
            // in the row, so even a corrected ranking could not satisfy the clause. Of the
            // RANKED total, because idle is no longer in it — a share of a number that
            // included idle would be a share of mostly nothing. One decimal: this is read by
            // an agent weighing rows against each other, and further digits spend context to
            // say what one says.
            row.put("sharePercent", rankedNanos == 0 ? 0.0
                : Math.round(entry.getValue() * 1000.0 / rankedNanos) / 10.0);
            // The count the milliseconds were summed from, so a 1-event row and a 400-event row
            // are distinguishable instead of ranking identically. Named `samples` to match every
            // other dimension's row, which is also the field the suite's rankability guard reads.
            row.put("samples", wallCounts.getOrDefault(entry.getKey(), 0L));
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", "wall");
        result.put("rows", rows);
        result.put("returnedRows", rows.size());
        result.put("totalMethods", ranked.size());
        result.put("totalWallMillis", rankedNanos / 1_000_000);
        // IDLE IS REPORTED, NEVER DROPPED. Excluding it from the ranking without saying what was
        // excluded would replace one dishonest answer with a quieter one: a reader could not tell
        // "nothing was waiting" from "the waiting is not shown here". `sharePercent` is of
        // `totalWallMillis`, which is the ranked total, and these two name what sits outside it.
        result.put("idleWallMillis", idleNanos / 1_000_000);
        result.put("idleEvents", idleEvents);
        result.put("onCpuSamples", executionSampleSymbols.size());
        result.put("samplingIntervalMillis", sampleIntervalNanos / 1_000_000);
        result.put("blockingEvents", blockingEvents);
        result.put("truncated", offset + rows.size() < ranked.size());
        if (eventsWithoutStack > 0) {
            result.put("eventsWithoutStack", eventsWithoutStack);
        }
        return result;
    }

    /**
     * A CALL TREE FROM A NAMED ENTRY POINT — Sprint 28g S15, C15's hot-path clause.
     *
     * <p><b>Why a flat ranking cannot answer this, and the store had it written down before the
     * stage opened:</b> <i>"a ranking that credits each sample only to its top stack frame never
     * names the method that holds the time"</i> — because a self-time ranking credits the method
     * executing at that instant, so a caller whose time goes entirely into what it calls scores
     * ZERO and never appears at all. {@link #wallHotspots} names where the CPU was; it cannot
     * name what the work was FOR. That is not a defect in it — self time is the right answer to
     * its own question — it is a different question, and this is it.</p>
     *
     * <p>Each node carries the time of its whole SUBTREE (inclusive), so a caller is credited
     * with everything it caused, and the sample count beside it — which is what C15 means by
     * call counts sitting beside times rather than behind a second call with its own required
     * argument.</p>
     *
     * <p><b>{@code hotPath} is the deliverable, not the tree.</b> Following the heaviest child
     * down from the entry point is the one thing a reader actually wants and the one thing a leaf
     * ranking structurally cannot give, so it is computed here rather than left as an exercise
     * for whoever reads the nesting.</p>
     *
     * <p>Inclusive time double-counts BY DESIGN — a caller's milliseconds include its callees'.
     * That is what makes a caller visible, and it is why these numbers are reported as a tree
     * rather than as shares: siblings sum to their parent, and no level sums to 100%.</p>
     *
     * @param entryPoint {@code Type#method}, or a bare {@code Type} to enter at any of its
     *                   methods — the frame the tree is rooted at, and samples whose stack never
     *                   passes through it are not this question's business
     */
    public static Map<String, Object> callTree(Path jfrFile, String entryPoint, int maxDepth)
            throws IOException {
        List<List<String>> stacks = new ArrayList<>();
        List<Long> timesNanos = new ArrayList<>();

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                if (!"jdk.ExecutionSample".equals(event.getEventType().getName())
                        || !hasStack(event)) {
                    continue;
                }
                List<String> frames = new ArrayList<>();
                for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                    RecordedMethod method = frame.getMethod();
                    if (method != null && method.getType() != null) {
                        frames.add(method.getType().getName() + "#" + method.getName());
                    }
                }
                stacks.add(frames);
                timesNanos.add(event.getStartTime().toEpochMilli() * 1_000_000L);
            }
        }

        long intervalNanos = medianGapNanos(timesNanos);
        Node root = new Node(entryPoint);
        long matched = 0;

        for (List<String> frames : stacks) {
            // JFR hands frames INNERMOST first, so the entry point is the OUTERMOST match and
            // the callees are at lower indices. Searching from the outside in is deliberate:
            // on a recursive stack the outermost occurrence is the one the caller named.
            int entry = -1;
            for (int i = frames.size() - 1; i >= 0; i--) {
                if (matchesEntry(frames.get(i), entryPoint)) {
                    entry = i;
                    break;
                }
            }
            if (entry < 0) {
                continue;
            }
            matched++;
            Node at = root;
            at.add(intervalNanos);
            for (int i = entry - 1; i >= 0; i--) {
                at = at.child(frames.get(i));
                at.add(intervalNanos);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entryPoint", entryPoint);
        result.put("samplesThroughEntryPoint", matched);
        result.put("samplingIntervalMillis", intervalNanos / 1_000_000);
        if (matched == 0) {
            // AN ABSENCE IS AN ANSWER, and it is not the same answer as an empty tree. A caller
            // who named a method the recording never executed is told that, rather than handed
            // a tree with no children and left to read it as "this method is cheap".
            result.put("tree", Map.of());
            result.put("hotPath", List.of());
            result.put("note", "no sample's stack passed through " + entryPoint
                + " — this is not a measurement that it is fast, it is an absence of samples."
                + " Either it did not run during the recording, or the name does not match a"
                + " frame (a bare Type enters at any of its methods).");
            return result;
        }
        result.put("tree", root.render(maxDepth));
        result.put("hotPath", root.hotPath(maxDepth));
        return result;
    }

    /** Whether a frame is the named entry — a bare {@code Type} enters at any of its methods. */
    private static boolean matchesEntry(String frameSymbol, String entryPoint) {
        if (frameSymbol.equals(entryPoint)) {
            return true;
        }
        return entryPoint.indexOf('#') < 0 && frameSymbol.startsWith(entryPoint + "#");
    }

    /** One method in the tree, holding its subtree's time and the samples that passed through. */
    private static final class Node {
        private final String symbol;
        private long nanos;
        private long samples;
        private final Map<String, Node> children = new LinkedHashMap<>();

        Node(String symbol) {
            this.symbol = symbol;
        }

        void add(long interval) {
            nanos += interval;
            samples++;
        }

        Node child(String childSymbol) {
            return children.computeIfAbsent(childSymbol, Node::new);
        }

        /** The heaviest child first, which is what makes the first-child chain the hot path. */
        private List<Node> byWeight() {
            List<Node> sorted = new ArrayList<>(children.values());
            sorted.sort((a, b) -> Long.compare(b.nanos, a.nanos));
            return sorted;
        }

        Map<String, Object> render(int maxDepth) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", symbol);
            out.put("inclusiveMillis", nanos / 1_000_000);
            out.put("samples", samples);
            if (maxDepth > 0 && !children.isEmpty()) {
                List<Map<String, Object>> kids = new ArrayList<>();
                for (Node c : byWeight()) {
                    kids.add(c.render(maxDepth - 1));
                }
                out.put("children", kids);
            } else if (!children.isEmpty()) {
                // SAY WHAT WAS CUT. A tree truncated in silence reads as a leaf, which is the
                // same lie the top-frame ranking told one level up.
                out.put("childrenNotShown", children.size());
            }
            return out;
        }

        List<String> hotPath(int maxDepth) {
            List<String> path = new ArrayList<>();
            Node at = this;
            for (int depth = 0; depth <= maxDepth && at != null; depth++) {
                path.add(at.symbol + " (" + (at.nanos / 1_000_000) + "ms, " + at.samples + ")");
                List<Node> kids = at.byWeight();
                at = kids.isEmpty() ? null : kids.get(0);
            }
            return path;
        }
    }

    /** The median gap between consecutive (unsorted) timestamps — robust to one hiccup. */
    private static long medianGapNanos(List<Long> timestampsNanos) {
        if (timestampsNanos.size() < 2) {
            return 10_000_000L;   // no basis to measure — JFR's own default profiling period, 10ms
        }
        List<Long> sorted = new ArrayList<>(timestampsNanos);
        sorted.sort(Long::compareTo);
        List<Long> gaps = new ArrayList<>(sorted.size() - 1);
        for (int i = 1; i < sorted.size(); i++) {
            long gap = sorted.get(i) - sorted.get(i - 1);
            if (gap > 0) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return 10_000_000L;
        }
        gaps.sort(Long::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    /**
     * The TARGET'S OWN domain events — application-defined {@code jdk.jfr.Event} subclasses it
     * emitted during the recording, as opposed to the JVM's built-in {@code jdk.*} events.
     *
     * <p>A trading sim that commits an {@code OrderPlaced} or {@code RiskBreach} JFR event puts
     * its own domain right into the same recording jawata profiles — D12 asks for exactly this
     * ("plus the target's own domain events where it emits them"), and it was not built. Groups
     * them by event type with a count each, plus a bounded sample of the most recent ones with
     * their fields, so the domain is legible without dumping the whole stream.</p>
     */
    public static Map<String, Object> domainEvents(Path jfrFile, int perTypeSample)
            throws IOException {
        Map<String, Long> countByType = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> recentByType = new LinkedHashMap<>();

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String type = event.getEventType().getName();
                // Built-in JVM/JDK/JFR events are not the application's domain — skip them.
                if (type.startsWith("jdk.") || type.startsWith("jfr.") || type.startsWith("java.")) {
                    continue;
                }
                countByType.merge(type, 1L, Long::sum);
                List<Map<String, Object>> recent =
                    recentByType.computeIfAbsent(type, k -> new ArrayList<>());
                recent.add(fieldsOf(event));
                if (recent.size() > perTypeSample) {
                    recent.remove(0);   // keep only the most recent `perTypeSample`
                }
            }
        }

        List<Map<String, Object>> types = new ArrayList<>();
        countByType.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .forEach(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventType", entry.getKey());
                row.put("count", entry.getValue());
                row.put("recent", recentByType.get(entry.getKey()));
                types.add(row);
            });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domainEventTypes", types);
        result.put("totalTypes", types.size());
        result.put("totalEvents", countByType.values().stream().mapToLong(Long::longValue).sum());
        return result;
    }

    /** An application event's own fields — its domain payload — as plain values. */
    private static Map<String, Object> fieldsOf(RecordedEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        event.getFields().forEach(field -> {
            String name = field.getName();
            // The event's declared payload — skip JFR's own bookkeeping fields.
            if (name.equals("stackTrace") || name.equals("eventThread") || name.startsWith("jdk.")) {
                return;
            }
            try {
                Object value = event.getValue(name);
                fields.put(name, value == null ? null : value.toString());
            } catch (Exception e) {
                // A field we cannot read is skipped, not faked.
                fields.put(name, "(unreadable)");
            }
        });
        return fields;
    }

    /** Total GC pause count and duration — a fact about the collector, not a call site. */
    public static Map<String, Object> gcPauses(Path jfrFile) throws IOException {
        long count = 0;
        long totalNanos = 0;
        long maxNanos = 0;
        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                if (!"jdk.GCPhasePause".equals(event.getEventType().getName())) {
                    continue;
                }
                count++;
                long nanos = event.getDuration().toNanos();
                totalNanos += nanos;
                maxNanos = Math.max(maxNanos, nanos);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pauseCount", count);
        result.put("totalPauseMillis", totalNanos / 1_000_000);
        result.put("maxPauseMillis", maxNanos / 1_000_000);
        return result;
    }

    /**
     * The package prefixes that belong to the JVM ITSELF rather than to any program running on
     * it.
     *
     * <p><b>Structural on purpose, and NOT a list of pool method names.</b> The tempting
     * discriminator for "this thread is idle" is to recognise the places threads wait for work
     * — {@code ForkJoinPool.awaitWork}, {@code ThreadPoolExecutor.getTask},
     * {@code LinkedBlockingQueue.take}. This project's own record is that a policy expressed as
     * a set of NAMES fails open the moment one of them is renamed, retired or added, because no
     * reference-updating refactoring touches a string literal. A package prefix is a fact about
     * where code LIVES, and the JDK's top-level namespaces do not get renamed.</p>
     */
    private static final List<String> RUNTIME_PREFIXES =
        List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    /**
     * The method that was BLOCKED — the innermost frame belonging to a PROGRAM rather than to
     * the JVM — or {@code null} when the whole stack is runtime.
     *
     * <p><b>A null answer is the discriminator M13 needs, and it falls out of the definition
     * rather than being imposed.</b> A thread waiting FOR work has no work on its stack: an
     * idle pool worker parks under {@code ForkJoinPool.awaitWork} beneath
     * {@code ForkJoinWorkerThread.run}, every frame of it runtime, so there is no program
     * method to attribute the wait to. A thread blocked IN work has the method that was blocked
     * sitting right there under the park, and walking outward from the park finds it first —
     * which also means the row NAMES that method instead of naming {@code Unsafe#park}, where
     * every blocked thread in the process collapses together.</p>
     *
     * <p>Measured before the repair, on a 12-second recording of an essentially idle resident:
     * {@code Unsafe#park} was attributed <b>210002 ms against 12000 ms of recording</b>, 17.5x
     * the recording's own length — which no single method can occupy, and which is reachable
     * only by summing many threads' parks into one frame. Both ranked rows were park/wait and
     * no real method appeared at all.</p>
     */
    private static String blockedSymbol(RecordedEvent event) {
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) {
            return null;
        }
        List<String> symbols = new ArrayList<>();
        for (RecordedFrame frame : stack.getFrames()) {
            RecordedMethod method = frame.getMethod();
            if (method == null || method.getType() == null) {
                continue;
            }
            symbols.add(method.getType().getName() + "#" + method.getName());
        }
        return innermostProgramFrame(symbols);
    }

    /**
     * THE M13 DECISION, as a pure function over frame symbols — innermost first.
     *
     * <p>Separated from the JFR walking above <b>because the decision is the part worth
     * testing and the walking is not.</b> A {@code RecordedEvent} cannot reasonably be
     * constructed in a test, so a rule buried inside that loop is a rule no test can reach;
     * this takes the one thing the rule actually reads — the ordered frame symbols — and the
     * plumbing that produces them stays a three-line loop with nothing to get wrong.</p>
     *
     * @param framesInnermostFirst {@code Type#method} per frame, innermost (the park itself)
     *                             first, which is the order JFR hands them over in
     * @return the first symbol whose type is not the JVM's own, or {@code null} when every
     *         frame is runtime — a thread with no program work on its stack
     */
    static String innermostProgramFrame(List<String> framesInnermostFirst) {
        for (String symbol : framesInnermostFirst) {
            int hash = symbol.indexOf('#');
            String type = hash < 0 ? symbol : symbol.substring(0, hash);
            boolean runtime = false;
            for (String prefix : RUNTIME_PREFIXES) {
                if (type.startsWith(prefix)) {
                    runtime = true;
                    break;
                }
            }
            if (!runtime) {
                return symbol;
            }
        }
        return null;
    }

    /** Whether this event carries a stack at all — distinct from carrying an all-runtime one. */
    private static boolean hasStack(RecordedEvent event) {
        if (!event.hasField("stackTrace")) {
            return false;
        }
        RecordedStackTrace stack = event.getStackTrace();
        return stack != null && !stack.getFrames().isEmpty();
    }

    private static String topFrameSymbol(RecordedEvent event) {
        if (!event.hasField("stackTrace")) {
            return null;
        }
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null || stack.getFrames().isEmpty()) {
            return null;
        }
        RecordedFrame frame = stack.getFrames().get(0);
        RecordedMethod method = frame.getMethod();
        if (method == null || method.getType() == null) {
            return null;
        }
        return method.getType().getName() + "#" + method.getName();
    }
}
