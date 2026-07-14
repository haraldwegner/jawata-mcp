package org.jawata.mcp.runtime.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 24 (D10) — turns {@code jcmd}'s human-readable diagnostic text into
 * structured data. The floor's whole point: "each is answered as structured data,
 * not a raw dump file" — a caller must never be handed a blob and told to read it.
 */
public final class ProfileParsers {

    // "main" #1 [1267604] prio=5 os_prio=0 cpu=29.21ms elapsed=1.24s tid=0x... nid=1267604 <status> [addr]
    private static final Pattern THREAD_HEADER = Pattern.compile(
        "^\"(?<name>[^\"]+)\"(?<daemon> daemon)? #(?<num>\\d+) .*?"
            + "nid=(?<nid>\\d+) (?<status>[^\\[]+?)\\s*(\\[0x[0-9a-f]+])?$");
    private static final Pattern STATE_LINE =
        Pattern.compile("^\\s*java\\.lang\\.Thread\\.State: (?<state>\\S+).*$");
    // HotSpot spells the count as a WORD for the singular case — "Found one
    // Java-level deadlock:" — and presumably a digit for plural ("Found N
    // Java-level deadlocks:"). Verified empirically: a 3-thread cycle (one
    // cycle, three participants) still says "one" — it counts CYCLES, not
    // threads. The footer line ("Found 1 deadlock.") uses a digit either way,
    // but that line is not what this pattern matches.
    private static final Pattern DEADLOCK_HEADER =
        Pattern.compile("^Found (?:one|\\d+) [Jj]ava-level deadlock");
    private static final Pattern HISTOGRAM_ROW = Pattern.compile(
        "^\\s*(?<rank>\\d+):\\s+(?<instances>\\d+)\\s+(?<bytes>\\d+)\\s+(?<klass>\\S+).*$");

    private ProfileParsers() {
    }

    /**
     * {@code Thread.print} output → one row per thread (name, id, daemon, state, and a
     * bounded stack), plus the deadlock section if present. Threads and the deadlock
     * verdict come from the SAME dump, so they can never disagree with each other.
     */
    public static Map<String, Object> parseThreadDump(String raw, int maxFrames) {
        List<Map<String, Object>> threads = new ArrayList<>();
        Map<String, Object> current = null;
        List<String> stack = null;
        boolean inDeadlockSection = false;
        List<String> deadlockText = new ArrayList<>();

        for (String line : raw.split("\n", -1)) {
            if (line.startsWith("Found ") && line.contains("deadlock")) {
                inDeadlockSection = true;
            }
            if (inDeadlockSection) {
                deadlockText.add(line);
                continue;
            }
            Matcher header = THREAD_HEADER.matcher(line);
            if (header.matches()) {
                current = new LinkedHashMap<>();
                current.put("name", header.group("name"));
                current.put("daemon", header.group("daemon") != null);
                current.put("id", Long.parseLong(header.group("nid"), 16));
                current.put("statusLine", header.group("status").strip());
                stack = new ArrayList<>();
                current.put("stack", stack);
                threads.add(current);
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher state = STATE_LINE.matcher(line);
            if (state.matches()) {
                current.put("state", state.group("state"));
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.startsWith("at ") && stack.size() < maxFrames) {
                stack.add(trimmed.substring(3));
            } else if (trimmed.isEmpty()) {
                current = null;
                stack = null;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threads", threads);
        result.put("threadCount", threads.size());
        result.put("deadlock", parseDeadlockSection(deadlockText));
        return result;
    }

    /** The deadlock section alone, from a full {@code Thread.print} — or a fresh probe. */
    public static Map<String, Object> parseDeadlockSection(List<String> lines) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (lines.isEmpty() || !DEADLOCK_HEADER.matcher(lines.get(0)).find()) {
            result.put("deadlocked", false);
            return result;
        }

        List<Map<String, Object>> cycle = new ArrayList<>();
        List<String> blockedNames = new ArrayList<>();
        Pattern threadName = Pattern.compile("^\"(?<name>[^\"]+)\":$");
        // "which is held by ..." is its OWN line, one below "waiting to lock
        // monitor ..." — the two never share a line, so they are matched
        // independently and paired with whichever thread header came last.
        Pattern heldByLine = Pattern.compile("which is held by \"(?<holder>[^\"]+)\"");

        String currentThread = null;
        java.util.Set<String> sectionOneThreads = new java.util.LinkedHashSet<>();
        boolean inParticipantSection = true;
        for (String line : lines) {
            // The SECOND section ("Java stack information for the threads
            // listed above:") repeats the same thread names as headers but
            // never contains "which is held by" — cutting over here just
            // avoids re-adding duplicate blockedNames entries from it.
            if (line.startsWith("Java stack information")) {
                inParticipantSection = false;
                continue;
            }
            if (!inParticipantSection) {
                continue;
            }
            Matcher tn = threadName.matcher(line.strip());
            if (tn.matches()) {
                currentThread = tn.group("name");
                if (sectionOneThreads.add(currentThread)) {
                    blockedNames.add(currentThread);
                }
                continue;
            }
            Matcher hb = heldByLine.matcher(line);
            if (hb.find() && currentThread != null) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("waiting", currentThread);
                edge.put("heldBy", hb.group("holder"));
                cycle.add(edge);
            }
        }

        result.put("deadlocked", true);
        result.put("blockedThreads", blockedNames.stream().distinct().toList());
        result.put("cycle", cycle);
        // NAME the blocker in the summary — a deadlock report that makes you re-derive
        // who is holding what is not a diagnosis, it is the same raw text with a label.
        // Built by WALKING the wait-for edges (not declaration order — with more than
        // two threads those can differ, and a summary that just lists names in the
        // order jcmd printed them could describe a chain that is not the actual cycle).
        result.put("summary", summarizeCycle(cycle));
        return result;
    }

    private static String summarizeCycle(List<Map<String, Object>> cycle) {
        if (cycle.isEmpty()) {
            return "A deadlock was reported but its participants could not be parsed.";
        }
        Map<String, String> waitsFor = new LinkedHashMap<>();
        for (Map<String, Object> edge : cycle) {
            waitsFor.put((String) edge.get("waiting"), (String) edge.get("heldBy"));
        }
        String start = (String) cycle.get(0).get("waiting");
        List<String> chain = new ArrayList<>();
        String current = start;
        for (int i = 0; i < waitsFor.size() && current != null; i++) {
            chain.add(current);
            current = waitsFor.get(current);
            if (current != null && current.equals(start)) {
                chain.add(current);
                current = null;
            }
        }
        return "Deadlock: " + String.join(" -> ", chain)
            + " (each waits on a lock the next thread in the chain holds).";
    }

    /** {@code GC.class_histogram} output → ranked rows, capped at {@code limit}. */
    public static Map<String, Object> parseHistogram(String raw, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int totalClasses = 0;
        long totalInstances = 0;
        long totalBytes = 0;
        for (String line : raw.split("\n")) {
            Matcher m = HISTOGRAM_ROW.matcher(line);
            if (!m.matches()) {
                continue;
            }
            totalClasses++;
            long instances = Long.parseLong(m.group("instances"));
            long bytes = Long.parseLong(m.group("bytes"));
            totalInstances += instances;
            totalBytes += bytes;
            if (rows.size() < limit) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank", Integer.parseInt(m.group("rank")));
                row.put("instances", instances);
                row.put("bytes", bytes);
                row.put("class", m.group("klass"));
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("returnedRows", rows.size());
        result.put("totalClasses", totalClasses);
        result.put("totalInstances", totalInstances);
        result.put("totalBytes", totalBytes);
        result.put("truncated", totalClasses > rows.size());
        return result;
    }

    /**
     * {@code GC.heap_info} output → the generation/space lines as structured facts,
     * plus the raw text (short enough not to hide anything, and the format varies by
     * collector — G1 vs Parallel vs Serial each print different section names).
     */
    public static Map<String, Object> parseHeapInfo(String raw) {
        List<Map<String, Object>> sections = new ArrayList<>();
        // "PSYoungGen total 30720K, used 5461K [0x..., 0x..., 0x...)"  or
        // " garbage-first heap   total 57344K, used 1174K [0x..., 0x...)"
        Pattern sectionLine = Pattern.compile(
            "^\\s*(?<label>[A-Za-z][A-Za-z0-9 \\-]*?)\\s+total (?<total>\\d+)K, used (?<used>\\d+)K");
        Pattern metaLine = Pattern.compile(
            "^\\s*(?<label>Metaspace|class space)\\s+used (?<used>\\d+)K, "
                + "committed (?<committed>\\d+)K(?:, reserved (?<reserved>\\d+)K)?");
        for (String line : raw.split("\n")) {
            Matcher m = sectionLine.matcher(line);
            if (m.find()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("region", m.group("label").strip());
                s.put("totalKb", Long.parseLong(m.group("total")));
                s.put("usedKb", Long.parseLong(m.group("used")));
                sections.add(s);
                continue;
            }
            Matcher mm = metaLine.matcher(line);
            if (mm.find()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("region", mm.group("label"));
                s.put("usedKb", Long.parseLong(mm.group("used")));
                s.put("committedKb", Long.parseLong(mm.group("committed")));
                if (mm.group("reserved") != null) {
                    s.put("reservedKb", Long.parseLong(mm.group("reserved")));
                }
                sections.add(s);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("regions", sections);
        result.put("raw", raw.strip());
        return result;
    }

    /**
     * {@code VM.native_memory summary} output. When NMT was not enabled at launch,
     * jcmd answers with exactly that sentence — reported as a named capability-absent
     * fact, never merged into a fake zero-usage summary.
     */
    public static Map<String, Object> parseNativeMemory(String raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw.contains("Native memory tracking is not enabled")) {
            result.put("enabled", false);
            result.put("why", "This JVM was not started with -XX:NativeMemoryTracking=summary "
                + "(or =detail). NMT cannot be turned on after launch — relaunch it under the "
                + "dev/sim preset (debug(action=launch)), which enables it by default.");
            return result;
        }
        result.put("enabled", true);

        List<Map<String, Object>> categories = new ArrayList<>();
        // "-                Java Heap (reserved=65536KB, committed=65536KB)"
        Pattern categoryLine = Pattern.compile(
            "^-\\s*(?<label>[A-Za-z][A-Za-z0-9 /]*?)\\s+\\(reserved=(?<reserved>\\d+)KB, "
                + "committed=(?<committed>\\d+)KB\\)");
        for (String line : raw.split("\n")) {
            Matcher m = categoryLine.matcher(line);
            if (m.find()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("category", m.group("label").strip());
                c.put("reservedKb", Long.parseLong(m.group("reserved")));
                c.put("committedKb", Long.parseLong(m.group("committed")));
                categories.add(c);
            }
        }
        result.put("categories", categories);
        Pattern totalLine = Pattern.compile(
            "Total: reserved=(?<reserved>\\d+)KB, committed=(?<committed>\\d+)KB");
        Matcher tm = totalLine.matcher(raw);
        if (tm.find()) {
            result.put("totalReservedKb", Long.parseLong(tm.group("reserved")));
            result.put("totalCommittedKb", Long.parseLong(tm.group("committed")));
        }
        result.put("raw", raw.strip());
        return result;
    }
}
