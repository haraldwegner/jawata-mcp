package org.jawata.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.tools.Tool;

/**
 * WHERE EACH OF THE 44 TOOLS SITS IN THE PUBLISHED LIST (Sprint 28k, E12).
 *
 * <p>A client is shown the registry's list, which puts the read-only tools first and otherwise keeps registration order
 * (jawata-mcp#22), so the order in which a producer hands tools to the registry is part of what the product publishes, and "the 44
 * tools, same names, order and schemas" is a promise about it. While one method wrote the whole list its order was the order of its
 * statements. Split by language, the tools that name no language and the provider's tools arrive as two lists, and the 44 are
 * interleaved in the published order ({@code hire} sits between {@code profile} and {@code refresh_workspace}, {@code get_diagnostics}
 * between {@code get_at_position} and {@code validate_syntax}), so putting one list after the other would reorder the surface.</p>
 *
 * <p>This class holds the position of the 44 by name, in the order a client sees them, which is the order of
 * {@code build/28k-golden-tools.json}. A tool it does not name, one a provider adds after these 44, follows them in the order it
 * arrived. The names are held to the golden by {@code TheFortyFourPublishedToolsTest}, on the registry's output.</p>
 */
final class PublishedOrder {

    /** The 44, in the order a client sees them. */
    private static final List<String> NAMES = List.of(
        "health_check", "search_symbols", "go_to_definition", "find_references", "inspect", "analyze", "get_at_position",
        "get_diagnostics", "validate_syntax", "get_call_hierarchy", "find_field_writes", "find_tests", "find_pattern_usages",
        "find_quality_issue", "find_string_literals", "find_duplicate_code", "find_modernization",
        "load_project", "project", "field", "rename_symbol", "organize_imports", "delete_anonymous_implementation", "extract",
        "inline", "move", "hierarchy", "generate", "refactor_to_pattern", "change_method_signature", "data", "apply_cleanup",
        "compile_workspace", "run_tests", "debug", "profile", "hire", "refresh_workspace", "apply_null_annotations", "dependency",
        "format", "quick_fix", "refactoring", "experience");

    private PublishedOrder() {
    }

    /**
     * The given tools in published order: the named 44 as listed, then any other in the order it arrived. Two tools answering to one
     * name is a producer defect and is refused, not resolved by letting the later one replace the earlier.
     */
    static List<Tool> arrange(List<Tool> tools) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : tools) {
            if (byName.put(tool.getName(), tool) != null) {
                throw new IllegalStateException("two tools answer to " + tool.getName());
            }
        }
        List<Tool> arranged = new ArrayList<>(tools.size());
        for (String name : NAMES) {
            Tool tool = byName.remove(name);
            if (tool != null) {
                arranged.add(tool);
            }
        }
        arranged.addAll(byName.values());
        return arranged;
    }
}
