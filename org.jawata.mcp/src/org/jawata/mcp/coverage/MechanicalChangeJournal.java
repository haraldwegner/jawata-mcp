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
 * <p>The exempt tool CLASS (parity-gated in the spec): rename_symbol,
 * extract, inline, move, move_in_hierarchy, move_method,
 * change_method_signature, organize_imports, format, apply_cleanup,
 * refactoring (plan/apply), refactor_to_pattern, replace_duplicates,
 * encapsulate_field, generate. Hand edits and new code are BEHAVIORAL.</p>
 */
public final class MechanicalChangeJournal {

    public static final Set<String> EXEMPT_TOOLS = Set.of(
        "rename_symbol", "extract", "inline", "move", "move_in_hierarchy",
        "move_method", "change_method_signature", "organize_imports",
        "optimize_imports_workspace", "format", "apply_cleanup", "refactoring",
        "refactor_to_pattern", "replace_duplicates", "encapsulate_field",
        "generate", "convert_anonymous_to_lambda", "apply_null_annotations");

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
