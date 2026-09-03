package org.jawata.mcp.coverage;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprint 23 (D6) — the exemption memory of the advisory gate: files whose
 * changes came from MECHANICAL, behavior-preserving transforms (the
 * refactoring tool surface) this session. The done-time coverage advisory
 * stays silent for them — a rename does not need a new test; NEW BEHAVIOR
 * does.
 *
 * <p>WHICH tools are exempt is no longer written here. It was — a {@code Set.of(...)}
 * of eighteen tool names — and Sprint 28d-rescue's stage 1 folded four of those tools
 * away and renamed two more without this list noticing. {@code hierarchy} and
 * {@code data} were simply absent from it afterwards, so a pull-up and a field
 * encapsulation started being asked for tests they cannot need, and nothing failed:
 * a list of names in this package has no way to learn that a name stopped existing.</p>
 *
 * <p>The question is now asked of {@link org.jawata.mcp.refactoring.OperationRegistry},
 * which is populated by the tools themselves at registration. Every refactoring
 * declares itself mechanical by extending the refactoring base, so the set cannot drift
 * from the tool surface and there is nothing to remember when one changes.</p>
 */
public final class MechanicalChangeJournal {

    /**
     * Is a change made by this tool behaviour-preserving?
     *
     * <p>False for a tool nothing registered — the safe direction, because the advisory
     * then ASKS for a test rather than exempting an operation it knows nothing about.</p>
     */
    public static boolean isMechanicalTool(String toolName) {
        return org.jawata.mcp.refactoring.OperationRegistry.theRegistry()
            .isMechanical(toolName);
    }

    /**
     * Recorded paths, normalized separators. Refactoring responses format
     * paths PROJECT-RELATIVE, callers query ABSOLUTE — matching is
     * suffix-based with a path-segment boundary.
     */
    private static final Set<String> TOUCHED = ConcurrentHashMap.newKeySet();

    private MechanicalChangeJournal() {}

    public static void recordMechanical(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        TOUCHED.add(normalize(filePath));
    }

    /** Was this file's latest change mechanical (recorded this session)? */
    public static boolean isMechanicallyTouched(String filePath) {
        if (filePath == null) return false;
        String query = normalize(filePath);
        for (String recorded : TOUCHED) {
            if (query.equals(recorded)
                    || suffixOnBoundary(query, recorded)
                    || suffixOnBoundary(recorded, query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean suffixOnBoundary(String longer, String suffix) {
        return longer.length() > suffix.length()
            && longer.endsWith(suffix)
            && longer.charAt(longer.length() - suffix.length() - 1) == '/';
    }

    private static String normalize(String path) {
        return Path.of(path).normalize().toString().replace('\\', '/');
    }

    /** Test seam: forget everything. */
    /** Sprint 26 (event tap): whether any mechanical touches are pending. */
    public static boolean hasEntries() {
        return !TOUCHED.isEmpty();
    }

    public static void clear() {
        TOUCHED.clear();
    }
}
