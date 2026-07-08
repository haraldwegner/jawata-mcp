package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.workspace.StrictDiskSync;
import org.goja.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Registry for all GOJA tools.
 * Handles tool registration, listing, and dispatching calls.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * Sprint 14b: MCP tool annotations (spec ≥ 2025-03-26). Detect tools are
     * marked {@code readOnlyHint} so restricted client modes (Cursor Ask
     * mode) can allow them without switching to an agent mode. Name-driven:
     * the four detect prefixes plus the read-only tools whose names match no
     * prefix. Anything that mutates source, project configuration, build
     * output, or workspace state stays unannotated — when in doubt, leave a
     * tool unannotated (a missing hint is "unknown", a wrong hint lets a
     * restricted mode mutate the workspace).
     */
    private static final Set<String> READ_ONLY_PREFIXES =
        Set.of("find_", "get_", "analyze_", "search_");
    private static final Set<String> READ_ONLY_NAMES = Set.of(
        "go_to_definition", "health_check", "list_projects",
        "validate_syntax", "inspect_refactoring", "suggest_imports",
        // Sprint 16b/A (v1.1.1): parametric read-only front doors whose names
        // match no detect prefix.
        "analyze", "inspect");

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** Sprint 21d: the strict-disk-sync guard, run once per call before any tool executes. */
    private StrictDiskSync diskSync;

    /** Install the per-call disk-sync guard (Sprint 21d). Null = no guard (tests). */
    public void setDiskSync(StrictDiskSync diskSync) {
        this.diskSync = diskSync;
    }

    /** Sprint 21e (item A): fired after a SUCCESSFUL project mutation
     *  ({@code load_project}, {@code project(action=add|remove)}) so the experience
     *  store's refresh + anchor backfill see the new project set. Null = no hook. */
    private Runnable projectsMutatedHook;

    /** Install the post-project-mutation hook (Sprint 21e). Null = no hook (tests). */
    public void setProjectsMutatedHook(Runnable hook) {
        this.projectsMutatedHook = hook;
    }

    private static boolean isProjectMutation(String name, JsonNode arguments) {
        if ("load_project".equals(name)) {
            return true;
        }
        if (!"project".equals(name)) {
            return false;
        }
        String action = arguments != null && arguments.has("action")
            ? arguments.get("action").asText("") : "";
        return "add".equals(action) || "remove".equals(action);
    }

    /**
     * Register a tool with the registry.
     */
    public void register(Tool tool) {
        String name = tool.getName();
        if (tools.containsKey(name)) {
            log.warn("Overwriting existing tool: {}", name);
        }
        tools.put(name, tool);
        log.debug("Registered tool: {}", name);
    }

    /**
     * Register multiple tools.
     */
    public void registerAll(Tool... toolsToRegister) {
        for (Tool tool : toolsToRegister) {
            register(tool);
        }
    }

    /**
     * Get a tool by name.
     */
    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Check if a tool exists.
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * Get all registered tool names.
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Get the number of registered tools.
     */
    public int getToolCount() {
        return tools.size();
    }

    /**
     * Get tool definitions for the tools/list response.
     * Returns a list of tool definitions in MCP format.
     */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();

        for (Tool tool : tools.values()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", tool.getName());
            def.put("description", tool.getDescription());
            def.put("inputSchema", tool.getInputSchema());
            if (isReadOnly(tool.getName())) {
                def.put("annotations", Map.of("readOnlyHint", Boolean.TRUE));
            }
            definitions.add(def);
        }

        return definitions;
    }

    /**
     * Sprint 22 (POST layer): tools that get no steering — session/build/verify
     * tools where a "next step" nudge would be noise or self-referential.
     */
    private static final Set<String> NO_STEER = Set.of(
        "health_check", "list_projects", "load_project", "project",
        "refresh_workspace", "validate_syntax", "compile_workspace", "get_diagnostics");

    /**
     * The directional next-step nudge for a tool, by category. Reuses
     * {@link #isReadOnly} to split navigate/understand (→ change with a GOJA
     * refactor tool, not a hand-edit) from mutate (→ verify with the build).
     * Returns null for session/build/verify tools (see {@link #NO_STEER}).
     */
    static String steeringFor(String name) {
        if (NO_STEER.contains(name)) {
            return null;
        }
        if (isReadOnly(name)) {
            return "Grounded next step: change what you found with a GOJA refactor tool "
                + "(rename_symbol / extract / move / refactoring(action=plan)) — not a hand-edit "
                + "(grep and hand-edits miss references).";
        }
        return "Grounded next step: verify with compile_workspace + get_diagnostics; "
            + "GOJA changes are reversible (undo / undo_plan).";
    }

    /** True when the named tool is a detect tool per the Sprint 14b sets. */
    static boolean isReadOnly(String toolName) {
        if (READ_ONLY_NAMES.contains(toolName)) {
            return true;
        }
        for (String prefix : READ_ONLY_PREFIXES) {
            if (toolName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sprint 22a P2 rider: renamed / consolidated tool names → their current
     * front door, so a call to an old name gets a "did you mean" hint instead of
     * a bare not-found (the tool surface collapsed many narrow tools into
     * parametric front doors across Sprints 11–19).
     */
    private static final Map<String, String> RENAMED_TOOLS = Map.ofEntries(
        Map.entry("get_type_members", "inspect"),
        Map.entry("get_type_hierarchy", "inspect"),
        Map.entry("get_type_usage_summary", "inspect"),
        Map.entry("get_symbol_info", "get_at_position"),
        Map.entry("get_call_hierarchy_incoming", "get_call_hierarchy"),
        Map.entry("get_call_hierarchy_outgoing", "get_call_hierarchy"),
        Map.entry("find_method_references", "find_references"),
        Map.entry("move_class", "move"),
        Map.entry("move_package", "move"),
        Map.entry("pull_up", "move_in_hierarchy"),
        Map.entry("push_down", "move_in_hierarchy"),
        Map.entry("extract_method", "extract"),
        Map.entry("extract_variable", "extract"),
        Map.entry("inline_method", "inline")
    );

    /** The current front door for a renamed/removed tool name, or {@code null}. */
    private static String aliasHint(String name) {
        String direct = RENAMED_TOOLS.get(name);
        if (direct != null) {
            return direct;
        }
        if (name.startsWith("analyze_")) {
            return "analyze";
        }
        if (name.startsWith("inspect_")) {
            return "inspect";
        }
        return null;
    }

    /**
     * Call a tool by name with the given arguments.
     *
     * @param name The tool name
     * @param arguments The tool arguments
     * @return The tool response
     * @throws ToolNotFoundException if the tool is not registered
     */
    public ToolResponse callTool(String name, JsonNode arguments) throws ToolNotFoundException {
        Tool tool = tools.get(name);
        if (tool == null) {
            String hint = aliasHint(name);
            throw new ToolNotFoundException("Tool not found: " + name
                + (hint != null ? ". Did you mean '" + hint + "'?" : ""));
        }

        log.info("Executing tool: {}", name);
        long startTime = System.currentTimeMillis();

        // Sprint 21d: strict disk sync — reconcile external edits (agent, git, another
        // editor) BEFORE the tool computes anything, so every answer reflects the
        // CURRENT tree. Failure = WARN and proceed (availability over freshness on a
        // guard crash ONLY — never a switch; correctness is not configurable).
        if (diskSync != null) {
            try {
                StrictDiskSync.SyncReport sync = diskSync.syncBeforeCall();
                if (sync.reconciled()) {
                    log.info("Strict disk sync before {}: {} new project(s), {} file(s) reconciled,"
                            + " {} project(s) built in {}ms",
                        name, sync.newProjects(), sync.refreshedFiles(), sync.builtProjects(),
                        sync.totalNanos() / 1_000_000);
                }
            } catch (Exception e) {
                log.warn("Strict disk sync FAILED before {} — answers may be STALE until the"
                    + " next successful reconcile", name, e);
            }
        }

        try {
            ToolResponse response = tool.execute(arguments);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Tool {} completed in {}ms, success={}", name, duration, response.isSuccess());
            // Sprint 21e (item A): a successful project mutation changes what symbol
            // anchors can resolve — refresh + backfill the store's anchors now (add:
            // new types become anchorable; remove: their auto-anchors get cleared).
            if (projectsMutatedHook != null && response.isSuccess()
                    && isProjectMutation(name, arguments)) {
                try {
                    projectsMutatedHook.run();
                } catch (Exception e) {
                    log.warn("Post-project-mutation experience hook failed after {}", name, e);
                }
            }
            // Sprint 22 (POST layer): central steering injection — every success
            // result names the next grounded step (see steeringFor).
            response.applySteering(steeringFor(name));
            return response;
        } catch (Exception e) {
            log.error("Tool {} failed with exception", name, e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Exception thrown when a tool is not found.
     */
    public static class ToolNotFoundException extends Exception {
        public ToolNotFoundException(String message) {
            super(message);
        }
    }
}
