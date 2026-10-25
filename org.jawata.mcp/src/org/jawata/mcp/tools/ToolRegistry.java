package org.jawata.mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jawata.mcp.ResidentDegradation;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.SyncReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Registry for all JAWATA tools.
 * Handles tool registration, listing, and dispatching calls.
 */
public class ToolRegistry implements org.jawata.mcp.engine.ToolCalls {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * Sprint 14b: MCP tool annotations (spec ≥ 2025-03-26). Detect tools are
     * marked {@code readOnlyHint} so restricted client modes (Cursor Ask
     * mode) can allow them without switching to an agent mode. Name-driven:
     * the four detect prefixes plus the read-only tools whose names match no
     * prefix.
     *
     * <p><b>This paragraph used to end "anything that mutates … stays unannotated — when in
     * doubt, leave a tool unannotated (a missing hint is unknown, a wrong hint lets a
     * restricted mode mutate the workspace)". mcp#36 overturned HALF of that, and the half
     * it kept is the important one.</b></p>
     *
     * <p>What was wrong: a tool the product KNOWS rewrites source said nothing, so a client
     * had to guess, and measured in the Cursor dogfood the guess was not even stable across
     * one session. Those tools now say so — see {@link #annotationsFor}.</p>
     *
     * <p>What was RIGHT, and what a first attempt at this fix broke before an architect
     * watch caught it: <b>when the product genuinely does not know, silence is the correct
     * answer.</b> That attempt derived {@code destructiveHint} as the negation of the
     * read-only name match, which has only two values for a question with three — read-only,
     * rewrites source, and neither — so every unclassified tool began asserting it was
     * destructive. {@code compile_workspace}, the tool this file's own {@link #steeringFor}
     * tells every agent to run, was published as destructive. The third state is restored:
     * a hint is emitted where the product can vouch and OMITTED where it cannot.</p>
     *
     * <p>What has NOT changed is the weakness: which tools are read-only is decided from
     * these NAME literals, and a policy expressed as names fails open when a tool is
     * renamed or added, because no reference-updating refactoring touches a string. The
     * destructive side does NOT share it — {@link #rewritesSource} asks the type.</p>
     */
    private static final Set<String> READ_ONLY_PREFIXES =
        Set.of("find_", "get_", "analyze_", "search_");
    // C9a-1: `list_projects`, `inspect_refactoring` and `suggest_imports` stood here after the tools
    // were folded away (project, refactoring(action=inspect), quick_fix(action=suggest_imports));
    // ShippedToolsTest asks the shipped set and found them, which is the check this list lacked.
    private static final Set<String> READ_ONLY_NAMES = Set.of(
        "go_to_definition", "health_check",
        "validate_syntax",
        // Sprint 16b/A (v1.1.1): parametric read-only front doors whose names
        // match no detect prefix.
        "analyze", "inspect");

    /**
     * The registered tools in publishing order. Replaced, never changed in place, so a call being served while a language arrives or
     * leaves reads one whole set: the old one or the new one, never half of either.
     */
    private volatile Map<String, Tool> tools = new LinkedHashMap<>();

    /** Sprint 21d: the strict-disk-sync guard, run once per call before any tool executes. */
    private Reconciler reconciler;

    /** Install the host's reconciler, run once per call before any tool executes (Sprint 21d; 28k E5). Null = none (tests). */
    public void setReconciler(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    /**
     * Sprint 28g D42 — the knowledge store's PRIOR-WORK advisor, asked on every tool response.
     *
     * <p>It is the advisor the application already builds; before this it was reached by ONE
     * tool of forty-two ({@code plan_refactoring}), so an agent learned what the store already
     * knew only when it happened to call that one. Null = not wired, which is every test that
     * constructs this registry directly.</p>
     */
    private org.jawata.mcp.knowledge.ExperienceAdvisor advisor;

    /** Install the prior-work advisor (Sprint 28g D42). Null = no prior-work block. */
    public void setExperienceAdvisor(org.jawata.mcp.knowledge.ExperienceAdvisor advisor) {
        this.advisor = advisor;
    }

    /** Sprint 21e (item A): fired after a SUCCESSFUL project mutation
     *  ({@code load_project}, {@code project(action=add|remove)}) so the experience
     *  store's refresh + anchor backfill see the new project set. Null = no hook. */
    private Runnable projectsMutatedHook;

    /** Sprint 26: the learner event tap — null until the application wires it. */
    private org.jawata.mcp.learn.EventTap eventTap;

    /** Sprint 26 (D1): the watch engine — null until the application wires it. */
    private org.jawata.mcp.learn.WatchEngine watchEngine;

    /** Sprint 26 (D4/D5): the server-side checks — null until wired. */
    private org.jawata.mcp.learn.ServerChecks serverChecks;

    /** Sprint 26a (D2): the weighted precedent push's retrieval seam (nullable). */
    private org.jawata.mcp.learn.PrecedentRetriever precedentRetriever;

    /** v3.3.1 (D2 enforcement): the surfaced negative precedents awaiting a written
     *  justification. Instantiated by DEFAULT — the signed D2 body says the steer is
     *  "enforced-by-default", so enforcement may not depend on optional wiring. */
    private org.jawata.mcp.learn.PrecedentLedger precedentLedger =
        new org.jawata.mcp.learn.PrecedentLedger();

    /** Sprint 27 (D6): measurement of what the steering surfaces actually do.
     *  NULL = not installed; the choke behaves identically either way, because a
     *  counter that changes what it counts measures nothing. */
    private org.jawata.mcp.knowledge.QualityLedger qualityLedger;

    /** Sprint 26a (D3b): the deterministic architect-involvement gate (nullable). */
    private org.jawata.mcp.learn.ArchitectGate architectGate;

    /** v3.2.1 (dogfood #1): supplier of the degraded-store notice — non-null
     *  return = the store is degraded and EVERY answer must say so. */
    private java.util.function.Supplier<String> storeNotice;

    /** v3.2.1: install the degraded-store notice supplier (application wiring). */
    public void setStoreNotice(java.util.function.Supplier<String> notice) {
        this.storeNotice = notice;
    }

    /**
     * mcp#42: calls that started and have not come back. Non-null by DEFAULT and not
     * optional wiring, because the whole defect is that a hang leaves no trace — a
     * mechanism that only records when someone remembered to install it would reproduce
     * that exactly one level up.
     */
    private final org.jawata.mcp.field.InFlightCalls inFlight =
        new org.jawata.mcp.field.InFlightCalls();

    /** mcp#42: the in-flight registry, read by {@code field(action=pile)}. */
    public org.jawata.mcp.field.InFlightCalls inFlight() {
        return inFlight;
    }

    /** Sprint 26: install the server-side checks (application wiring). */
    public void setServerChecks(org.jawata.mcp.learn.ServerChecks checks) {
        this.serverChecks = checks;
    }

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

    // THE OPERATION-PUBLISHING POLICY LIVES ON OperationSurface (Stage 6a, M8).
    //
    // It was two private constants and a private method here: which tools' kinds are
    // operations a cure step may name, and the reader that applied that filter. Both are
    // now public on OperationSurface, because they answer a question about the SURFACE
    // rather than about this map — and every test that wanted the answer had to build a
    // registry to reach it, or re-derive it, and several did the latter.

    /**
     * Register a tool with the registry.
     *
     * <p>Sprint 28d-rescue (P1): this is also where the tool's operations enter
     * {@link org.jawata.mcp.change.OperationRegistry}. Here rather than in each
     * tool's constructor because this method already sees every tool exactly once, so
     * the operation list cannot drift from the tool list.</p>
     */
    public synchronized void register(Tool tool) {
        String name = tool.getName();
        if (tools.containsKey(name)) {
            log.warn("Overwriting existing tool: {}", name);
        }
        Map<String, Tool> next = new LinkedHashMap<>(tools);
        next.put(name, tool);
        // One way in: the same swap and the same entry into OperationSurface that a language's arrival uses (Stage 6a, M8: which of a
        // tool's kinds are OPERATIONS is OperationSurface's decision, not this map's).
        publish(new java.util.ArrayList<>(next.values()));
        log.debug("Registered tool: {}", name);
    }

    /**
     * Makes the registered tools exactly {@code arranged}, in that order (Sprint 28k, E19): the engine calls this each time a
     * language arrives or leaves, with the neutral tools and every bound language's tools in the order the product publishes. A tool
     * not seen before enters the operation registry; a tool no longer listed is withdrawn from the registry (the operation registry
     * keeps what it was given, since a language does not leave during a run). Calls being served read the old set or the new, whole.
     */
    public synchronized void publish(List<Tool> arranged) {
        Map<String, Tool> next = new LinkedHashMap<>();
        for (Tool tool : arranged) {
            next.put(tool.getName(), tool);
        }
        for (Tool tool : next.values()) {
            if (tools.get(tool.getName()) != tool) {
                OperationSurface.publish(tool);
            }
        }
        tools = next;
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
     *
     * <h2>READ-ONLY TOOLS COME FIRST — jawata-mcp#22, and it is a safety property</h2>
     *
     * <p>The published order used to be registration order, which put {@code load_project}
     * (REPLACES the workspace) and {@code project} (add/remove) in slots 2 and 3, ahead of
     * every analysis tool this product exists to provide. A client that truncates a tool list
     * by position — and they do — saw two workspace-mutating tools and almost none of the
     * analysis. Observed live on 2026-08-15: an agent that could not find a class reached for
     * the workspace-replacing tool, because that was what it had been shown.</p>
     *
     * <p><b>The order is DERIVED, not hand-written.</b> It is the same read-only
     * classification already published in each tool's {@code annotations}, so there is no
     * second list to keep in step — this file has repeatedly paid for exactly that. Two tiers
     * only: read-only, then everything else. A third tier ranking a workspace replacement
     * against a reversible refactoring would claim a comparison this product has not
     * established, and {@link #rewritesSource} is deliberately narrower than "mutates".</p>
     *
     * <p>The sort is STABLE, so registration order still decides within each tier — the fix
     * moves the boundary and rearranges nothing else.</p>
     */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();

        List<Tool> published = new ArrayList<>(tools.values());
        published.sort(java.util.Comparator.comparingInt(
            t -> isReadOnly(((Tool) t).getName()) ? 0 : 1));

        for (Tool tool : published) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", tool.getName());
            def.put("description", tool.getDescription());
            def.put("inputSchema", declaringPrecedentOverride(tool.getInputSchema()));
            def.put("annotations", annotationsFor(tool));
            def.put("_meta", metaFor(tool));
            definitions.add(def);
        }

        return definitions;
    }

    /**
     * THE CHOKE DECLARES ITS OWN META-ARGUMENT — jawata-mcp#31.
     *
     * <p>{@code precedentOverride} is read here and STRIPPED before dispatch, so no tool ever
     * sees it and no tool's own schema mentioned it. A client reading {@code tools/list} could
     * therefore not discover it: the only place it appeared was inside the refusal text that
     * tells an agent to use it — the parameter documented by the failure it causes, learnable
     * only once you have already been blocked.</p>
     *
     * <p>It is declared HERE rather than in each tool because this is what owns it. It applies
     * to every call, it is consumed by this class, and it never reaches a delegate — so
     * putting it in forty-odd hand-written schemas would be forty copies of one fact, and the
     * forty-first tool would be the one that forgot.</p>
     *
     * <p>The maps are COPIED rather than mutated. A tool's schema is its own, several are
     * built with {@code Map.of(...)} and are immutable, and a published view has no business
     * writing into the object it is describing.</p>
     */
    private static Map<String, Object> declaringPrecedentOverride(Map<String, Object> schema) {
        Map<String, Object> published =
            schema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(schema);

        Object existing = published.get("properties");
        Map<String, Object> properties = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>(castProperties(map))
            : new LinkedHashMap<>();

        properties.put("precedentOverride", Map.of(
            "type", "string",
            "description", "Optional. One line saying why THIS case differs from a past call"
                + " of this tool on this target that was reverted or errored. Required only"
                + " when that precedent has already been surfaced to you and you are calling"
                + " anyway; the reason is logged with the call. Never reaches the tool."));

        published.put("properties", properties);
        return published;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castProperties(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * THE FULL MCP ANNOTATION SET, FOR EVERY TOOL — jawata-mcp#36.
     *
     * <p><b>The defect was an ABSENCE, not a wrong value.</b> Read-only tools carried
     * {@code readOnlyHint: true} and everything else carried no annotations at all, so a
     * client deciding whether to gate a call had nothing from us to reason about and fell
     * back to its own heuristic over the tool's NAME and arguments. Measured in the v3.10.0
     * Cursor dogfood: eight identical {@code experience(kind=record)} calls in one session,
     * three auto-blocked and five not — same tool, same argument shape, same session.</p>
     *
     * <p>jawata cannot make a client deterministic. It can stop being the source of the
     * ambiguity: a mutating tool now SAYS it mutates rather than declining to say anything,
     * so a client that gates on the declaration gates consistently.</p>
     *
     * <p><b>What each hint claims here, in this product's terms:</b></p>
     * <ul>
     *   <li>{@code destructiveHint} — true for a mutator, because a refactoring REWRITES
     *       existing files. It is reversible (every change carries an undo handle) and
     *       compile-verified, but reversible is not additive, and a client gating on this
     *       is asking the second question.</li>
     *   <li>{@code idempotentHint} — a read-only query asked twice answers the same;
     *       a refactoring applied twice does not, and usually refuses the second time.</li>
     *   <li>{@code openWorldHint} — false for every jawata tool. The domain is the loaded
     *       workspace: a closed, enumerable set, not the open internet.</li>
     * </ul>
     *
     * <p><b>A known weakness, carried rather than extended.</b> Read-only-ness is decided
     * from the tool's NAME ({@link #READ_ONLY_PREFIXES}, {@link #READ_ONLY_NAMES}) — a
     * policy expressed as a set of names, which fails open the day a tool is renamed or
     * added, because no reference-updating refactoring touches a string literal. This
     * change does not fix that and does not make it worse: it publishes the classification
     * that already existed. Moving the fact onto the tool itself is its own work.</p>
     */
    private static Map<String, Object> annotationsFor(Tool tool) {
        boolean readOnly = isReadOnly(tool.getName());
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("readOnlyHint", readOnly);
        annotations.put("idempotentHint", readOnly);
        annotations.put("openWorldHint", Boolean.FALSE);
        if (readOnly) {
            annotations.put("destructiveHint", Boolean.FALSE);
        } else if (rewritesSource(tool)) {
            annotations.put("destructiveHint", Boolean.TRUE);
        }
        // else: OMITTED. See the javadoc — this is the third state, and it is real.
        return annotations;
    }

    /**
     * What a client that must decide "did this call change code?" can read without keeping its own list.
     *
     * <p>{@code jawata/changesCode} is {@link Tool#changesCode()}, the flag the engine already uses to
     * classify a hired run's calls (jawata-duplicate: that classification READS the flag, this
     * PUBLISHES it; HireToolTest pins the reading). The annotations cannot answer the question:
     * {@code readOnlyHint} is false for {@code compile_workspace}, {@code run_tests},
     * {@code experience} and {@code hire}, which change no source, and {@code destructiveHint} is
     * absent for {@code format}, {@code quick_fix}, {@code refactoring} and {@code dependency}, which
     * do. The studio's hook kept three hand-written lists of these names and they disagreed
     * (REL44-37); it generates its list from this field. It sits in {@code _meta}, the extension point
     * the protocol reserves, so a client that validates {@code annotations} strictly sees no new
     * key.</p>
     */
    private static Map<String, Object> metaFor(Tool tool) {
        return Map.of("jawata/changesCode", tool.changesCode());
    }

    /**
     * Whether this tool is one the product can VOUCH rewrites existing source.
     *
     * <p>Asked of the TYPE, not of the name: a refactoring front door
     * ({@link KindedTool}) or a tool on one of the two refactoring bases. That set is
     * self-populating — a new refactoring tool joins it by extending what refactoring
     * tools extend, which no string literal can be forgotten out of.</p>
     *
     * <p>It is deliberately NARROWER than "mutates". {@code format}, {@code load_project}
     * and {@code apply_quick_fix} also change things and are not in it, so they fall to the
     * unknown state and say nothing. That is the right trade: the cost of omitting a true
     * hint is a client that keeps guessing about that tool, and the cost of asserting a
     * false one is a client that gates a tool it should not.</p>
     */
    private static boolean rewritesSource(Tool tool) {
        return tool instanceof KindedTool
            || tool instanceof org.jawata.mcp.tools.AbstractRefactoringTool
            || tool instanceof org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
    }

    /**
     * Sprint 22 (POST layer): tools that get no steering — session/build/verify
     * tools where a "next step" nudge would be noise or self-referential.
     */
    private static final Set<String> NO_STEER = Set.of(
        "health_check", "load_project", "project",
        "refresh_workspace", "validate_syntax", "compile_workspace", "get_diagnostics");

    /**
     * The directional next-step nudge for a tool, by category. Reuses
     * {@link #isReadOnly} to split navigate/understand (→ change with a JAWATA
     * refactor tool, not a hand-edit) from mutate (→ verify with the build).
     * Returns null for session/build/verify tools (see {@link #NO_STEER}).
     */
    static String steeringFor(String name) {
        if (NO_STEER.contains(name)) {
            return null;
        }
        if (isReadOnly(name)) {
            return "Grounded next step: change what you found with a JAWATA refactor tool "
                + "(rename_symbol / extract / move / refactoring(action=plan)) — not a hand-edit "
                + "(grep and hand-edits miss references).";
        }
        return "Grounded next step: verify with compile_workspace + get_diagnostics; "
            + "JAWATA changes are reversible (undo / undo_plan).";
    }

    /**
     * Every tool name the two name-keyed policy sets mention (C9a-1). A policy keyed by name fails
     * open when a tool is renamed or retired, because no reference-updating refactoring touches a
     * string; this is the question that lets a test ask whether each name is still a tool that
     * ships. Package-private on purpose: a public test-only member is what the hollow-wiring gate
     * refuses.
     */
    static Set<String> namesFixedByPolicy() {
        Set<String> names = new java.util.TreeSet<>(READ_ONLY_NAMES);
        names.addAll(NO_STEER);
        return names;
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
     * Every tool name this product has PUBLISHED and no longer does → the live call that
     * replaced it, so a call to an old name gets a "did you mean" pointer instead of a bare
     * not-found.
     *
     * <p><b>This table is exactly the retired half of {@code build/published-tool-names.txt}</b>,
     * which is derived from each release's own registered tools, and
     * {@code PublishedToolNamesTest} checks both directions: a published name that is neither live
     * nor a row here fails, and a row here for a name nobody published fails. It was written from
     * memory before that, and 35 of the 65 retired names answered a bare not-found while four
     * patches each corrected the one copy of the surface a failure had happened to expose.</p>
     *
     * <p><b>Each pointer names the KIND or ACTION as well as the front door</b>, because told only
     * that a name is now {@code inspect}, a caller has a dozen kinds to guess between and that guess
     * is the whole cost of the fold. The same test reads the live tools' schemas and fails a pointer
     * whose front door is not live or whose kind, action, direction or scope that door does not
     * accept, so a pointer cannot go stale when a door is renamed.</p>
     */
    private static final Map<String, String> RENAMED_TOOLS = Map.ofEntries(
        // inspect
        Map.entry("get_type_members", "inspect kind=type_members"),
        Map.entry("get_type_hierarchy", "inspect kind=type_hierarchy"),
        Map.entry("get_type_usage_summary", "inspect kind=type_usage"),
        Map.entry("get_classpath_info", "inspect kind=classpath"),
        Map.entry("get_complexity_metrics", "inspect kind=complexity"),
        Map.entry("get_dependency_graph", "inspect kind=dependency_graph"),
        Map.entry("get_di_registrations", "inspect kind=di_registrations"),
        Map.entry("get_document_symbols", "inspect kind=document_symbols"),
        Map.entry("get_project_structure", "inspect kind=project_structure"),
        // get_at_position
        Map.entry("get_symbol_info", "get_at_position kind=symbol"),
        Map.entry("get_type_at_position", "get_at_position kind=type"),
        Map.entry("get_method_at_position", "get_at_position kind=method"),
        Map.entry("get_field_at_position", "get_at_position kind=field"),
        Map.entry("get_hover_info", "get_at_position kind=hover"),
        Map.entry("get_javadoc", "get_at_position kind=javadoc"),
        Map.entry("get_signature_help", "get_at_position kind=signature"),
        Map.entry("get_enclosing_element", "get_at_position kind=enclosing"),
        Map.entry("get_super_method", "get_at_position kind=super"),
        // get_call_hierarchy and find_references
        Map.entry("get_call_hierarchy_incoming", "get_call_hierarchy direction=incoming"),
        Map.entry("get_call_hierarchy_outgoing", "get_call_hierarchy direction=outgoing"),
        Map.entry("find_method_references", "find_references kind=method_references"),
        Map.entry("find_implementations", "find_references kind=implementations"),
        // analyze
        Map.entry("analyze_change_impact", "analyze kind=change_impact"),
        Map.entry("analyze_control_flow", "analyze kind=control_flow"),
        Map.entry("analyze_data_flow", "analyze kind=data_flow"),
        Map.entry("analyze_file", "analyze kind=file"),
        Map.entry("analyze_javadocs", "analyze kind=javadocs"),
        Map.entry("analyze_method", "analyze kind=method"),
        Map.entry("analyze_naming", "analyze kind=naming"),
        Map.entry("analyze_nullness", "analyze kind=nullness"),
        Map.entry("analyze_type", "analyze kind=type"),
        // move, hierarchy, extract, inline, generate
        Map.entry("move_class", "move kind=class"),
        Map.entry("move_package", "move kind=package"),
        Map.entry("pull_up", "hierarchy direction=up"),
        Map.entry("push_down", "hierarchy direction=down"),
        Map.entry("extract_method", "extract kind=method"),
        Map.entry("extract_variable", "extract kind=variable"),
        Map.entry("extract_constant", "extract kind=constant"),
        Map.entry("extract_interface", "extract kind=interface"),
        Map.entry("inline_method", "inline kind=method"),
        Map.entry("inline_variable", "inline kind=variable"),
        Map.entry("generate_constructor", "generate kind=constructor"),
        Map.entry("generate_equals_hashcode", "generate kind=equals_hashcode"),
        Map.entry("generate_getters_setters", "generate kind=getters_setters"),
        Map.entry("generate_test_skeleton", "generate kind=test_skeleton"),
        Map.entry("generate_tostring", "generate kind=tostring"),
        Map.entry("override_methods", "generate kind=override_methods"),
        // Sprint 28d-rescue (stage 1): the four folds and the two renames. A fold's
        // pointer names the KIND as well as the front door — for these six the front
        // door alone would leave a caller to guess which of its kinds replaced their
        // tool, and that guess is the whole cost of the fold.
        Map.entry("move_method", "move kind=method"),
        Map.entry("convert_anonymous_to_lambda",
            "refactor_to_pattern kind=replace_pattern_with_idiom"),
        Map.entry("replace_duplicates", "extract kind=replace_inline_code"),
        Map.entry("optimize_imports_workspace", "organize_imports with scope=workspace"),
        Map.entry("encapsulate_field", "data kind=encapsulate_field"),
        Map.entry("move_in_hierarchy", "hierarchy"),
        // refactoring: the staged-change lifecycle is one door with an action
        Map.entry("apply_refactoring", "refactoring action=apply"),
        Map.entry("undo_refactoring", "refactoring action=undo"),
        Map.entry("inspect_refactoring", "refactoring action=inspect"),
        // project, quick_fix, dependency
        Map.entry("add_project", "project action=add"),
        Map.entry("remove_project", "project action=remove"),
        Map.entry("list_projects", "project action=list"),
        Map.entry("apply_quick_fix", "quick_fix action=apply"),
        Map.entry("get_quick_fixes", "quick_fix action=list"),
        Map.entry("suggest_imports", "quick_fix action=suggest_imports"),
        Map.entry("add_dependency", "dependency action=add"),
        Map.entry("update_dependency", "dependency action=update"),
        Map.entry("find_unused_dependencies", "dependency action=find_unused")
    );

    /**
     * Every retired name the map answers for — the population an invariant sweeps.
     *
     * <p>Exposed because a test that writes the retired names out by hand proves the
     * pointers say the right thing and cannot prove anything about a row nobody
     * remembered. Both are needed: the hand-written list is the claim, this is the
     * coverage.</p>
     *
     * <p><b>public</b> since Stage 6a's M9, for a reader in another package: the catalog
     * every client is sent at connect time lives in {@code org.jawata.mcp.protocol}, and
     * the check that it names no retired tool has to read this population rather than a
     * copy of it — a copy being the thing that whole stage removes.</p>
     */
    public static java.util.Set<String> retiredNames() {
        return java.util.Set.copyOf(RENAMED_TOOLS.keySet());
    }

    /** The fully resolved pointer for a retired name, or null when it is not retired. */
    static String pointerFor(String name) {
        return aliasHint(name);
    }

    /**
     * The current front door for a renamed/removed tool name, or {@code null}.
     *
     * <p>FOLLOWED TRANSITIVELY, because a rename can retire a name that is already
     * somebody's forwarding address. {@code pull_up} has pointed at
     * {@code move_in_hierarchy} since Sprint 19; stage 1 then renamed
     * {@code move_in_hierarchy} to {@code hierarchy}, and a single lookup left a caller
     * of {@code pull_up} pointed at a second tool that is also not found. One hop is not
     * a guarantee when the map itself has a history.</p>
     *
     * <p>Only the HEAD is followed — the front-door name at the start of a pointer, so
     * {@code "move kind=method"} resolves through {@code move} and keeps its kind. The
     * hop count is bounded rather than cycle-detected: a cycle here is a table defect,
     * and stopping is the same answer either way.</p>
     */
    private static String aliasHint(String name) {
        String direct = RENAMED_TOOLS.get(name);
        if (direct != null) {
            for (int hop = 0; hop < 8; hop++) {
                int cut = direct.indexOf(' ');
                String head = cut < 0 ? direct : direct.substring(0, cut);
                String next = RENAMED_TOOLS.get(head);
                if (next == null) {
                    break;
                }
                direct = cut < 0 ? next : next + direct.substring(cut);
            }
            return direct;
        }
        // No prefix guess for a name the table does not hold. Two used to stand here
        // (analyze_* and inspect_*), and they answered for names nobody ever published and
        // answered inspect_refactoring with the wrong door; the table now holds every name
        // that WAS published, so a name it lacks is one this product never had.
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
        // Sprint 28g S9a M4: a DECLARED in-process identity. This used to pass
        // the literal "local", which manufactured an identity out of an
        // absence — indistinguishable, in the ledger, from a session actually
        // called that. Measured before the change: 41 references, every one a
        // test, so no production path ever reached that bucket.
        return callTool(name, arguments, CallerIdentity.IN_PROCESS);
    }

    /**
     * Sprint 26, amended 28g S9a M4: the caller-scoped entry — {@code caller}
     * comes from the transport (the client's {@code Mcp-Session-Id}, or the one
     * identity that transport minted) and keys the ledger + the learner event
     * stream. A {@link CallerIdentity} rather than a string because an id can
     * be absent and an identity cannot, which is what retires the four sites
     * that invented one when it was.
     */
    public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller)
            throws ToolNotFoundException {
        // The private helpers below still take the id; which of them carry the
        // whole identity is settled one step later, where the collaborators
        // that would read its origin live.
        String sessionId = caller.id();
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
        java.util.List<String> syncDelta = java.util.List.of();
        if (reconciler != null) {
            try {
                SyncReport sync = reconciler.syncBeforeCall();
                syncDelta = sync.refreshedPaths();
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

        // v3.3.1 (D2 enforcement): LEVY the justification-cost. If a negative
        // precedent for this (tool, target) was already surfaced in this session,
        // using the tool anyway costs a written reason — refused BEFORE dispatch,
        // because the signed D2 body says the steer is "not an optional hint the
        // agent may ignore". v3.3.0 worded the cost but never charged it.
        ToolResponse unpaid = precedentCharge(caller, name, arguments);
        if (unpaid != null) {
            return stamped(name, arguments, unpaid);
        }
        // A paid override is a meta-argument of the CHOKE, not of the tool.
        arguments = withoutOverride(arguments);

        // Sprint 28g S9a M3: ACCEPTANCE, before the act. A seat outcome claimed
        // over a gate that never ran is REFUSED here rather than stored and then
        // commented on afterwards.
        //
        // THE POSITION IS LOAD-BEARING, TWICE. It is before `tool.execute` below,
        // which is the whole point — the old site ran 47 lines after it and could
        // only remark on something already done. And it is before the in-flight
        // ticket is taken: a refusal returned between that ticket and its `try`
        // would leak it, and the `finally` that releases it calls a leaked ticket
        // a FALSE HANG in its own comment. `precedentCharge` above returns a
        // refusal from this same position, which is what proves the position safe.
        if (serverChecks != null && eventTap != null) {
            ToolResponse refused = serverChecks.acceptance(caller, name, arguments,
                eventTap.ledger());
            if (refused != null) {
                return stamped(name, arguments, refused);
            }
        }

        // mcp#42: mark the call in flight. The recorder fires on the RESPONSE, so a call
        // that never returns produced no row and no shape — the worst failure mode was the
        // one the recording could not see. Released in the finally below, so no return
        // path, exception or JVM Error can leave a false hang behind.
        long ticket = inFlight.started(name,
            org.jawata.mcp.field.FieldEvent.discriminatorOf(arguments));

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
            // Sprint 23 (D6): remember files changed by MECHANICAL transforms —
            // the done-time coverage advisory exempts them (a rename needs no
            // new test; NEW BEHAVIOR does).
            if (response.isSuccess() && org.jawata.mcp.coverage.MechanicalChangeJournal.isMechanicalTool(name)
                    && response.getData() instanceof Map<?, ?> map
                    && map.get("filesModified") instanceof List<?> files) {
                files.forEach(f -> org.jawata.mcp.coverage.MechanicalChangeJournal
                    .recordMechanical(String.valueOf(f)));
            }
            // Sprint 22 (POST layer): central steering injection — every success
            // result names the next grounded step (see steeringFor).
            response.applySteering(steeringFor(name));
            // Sprint 26a (D2): the WEIGHTED PRECEDENT push — for the thing this
            // call is working on, what did tools do in similar cases before?
            // Runs BEFORE the tap so it reflects PAST experience, not this call's
            // own outcome; appended, never replacing the tool's own line.
            precedent(caller, name, arguments, response);
            // Sprint 28g (D42): the PRIOR-WORK block IS NOT EMITTED HERE, and the move is the
            // C14a repair. It rides `stamped` below — the funnel every return point already uses
            // — because this position is the NORMAL path only: the two refusals the choke itself
            // mints (an unpaid precedent charge, a failed acceptance check) and both catch blocks
            // return without passing this line. Those are precisely the answers the block matters
            // most on, by its own stated reason: a caller whose tool just declined is the one
            // most likely to go and do the thing by hand.
            // Sprint 26: the event tap — every outcome becomes a learner label
            // as a side effect of the call itself (D7: training is a side
            // effect of use). Tap failures are the tap's own concern (loud
            // there); they never fail the tool call.
            tap(caller, name, arguments, response, duration);
            // Sprint 26 (D1): the automatic architect — the delta (hand edits
            // seen by the pre-call disk sync + files this call modified) runs
            // through the watch engine; NEW findings ride the answer. Never
            // on the quality tool itself (recursion) and never fatally.
            boolean smellFound = watch(sessionId, name, syncDelta, response);
            // Sprint 26a (D3b): the architect-involvement gate — smell (above) |
            // signature/hierarchy | large edit → involve the architect.
            architectGate(name, arguments, response, smellFound);
            // Sprint 26 (D4/D5/D3): the server-side enforcement lane.
            if (serverChecks != null && eventTap != null) {
                try {
                    String block = serverChecks.onCompleted(caller, name, arguments,
                        eventTap.ledger());
                    if (block != null) {
                        response.appendSteering(block);
                    }
                } catch (Exception e) {
                    log.error("Server checks failed after {}", name, e);
                }
            }
            // v3.2.1 (dogfood #1): a degraded store announces itself on EVERY
            // answer — a degraded result presented as normal is the recorded
            // top-bug class; nobody should have to NOTICE a missing file size.
            //
            // mcp#12: it now announces itself THROUGH THE ONE REGISTRY rather than
            // beside it. This site used appendSteering, which no-ops on a refusal —
            // so the degraded store was silent on exactly the answers most likely to
            // be misread as a fact about the caller's code. Declaring it makes it a
            // resident state that health_check mirrors and that every response
            // carries, and the supplier answering null is what cures it.
            if (storeNotice != null) {
                try {
                    String degraded = storeNotice.get();
                    if (degraded != null) {
                        ResidentDegradation.declare("experience-store", degraded);
                    } else {
                        ResidentDegradation.cure("experience-store");
                    }
                } catch (Exception e) {
                    log.error("Store-notice supplier failed after {}", name, e);
                }
            }
            return stamped(name, arguments, response);
        } catch (Exception e) {
            log.error("Tool {} failed with exception", name, e);
            ToolResponse error = ToolResponse.internalError(e);
            tap(caller, name, arguments, error, System.currentTimeMillis() - startTime);
            return stamped(name, arguments, error);
        } catch (Error err) {
            // v2.7.1 (dogfood 2026-07-10): a JVM Error (StackOverflowError from a
            // pathological scan) escaped every catch(Exception), killed the
            // transport worker and the client saw a dropped socket. This is the
            // per-request boundary — answer structurally for ANY Throwable; the
            // request already failed, dying with it helps nobody.
            log.error("Tool {} failed with a JVM Error — returning a structured error instead of dropping the connection", name, err);
            ToolResponse error = ToolResponse.internalError(err);
            tap(caller, name, arguments, error, System.currentTimeMillis() - startTime);
            return stamped(name, arguments, error);
        } finally {
            // mcp#42: the call came back, however it came back. In a finally rather than
            // beside each return, because a ticket left behind by a path someone forgot is
            // a FALSE hang — and a mechanism whose whole subject is untrustworthy silence
            // must not become a source of untrustworthy noise.
            inFlight.finished(ticket);
        }
    }

    /**
     * THE DEGRADATION STAMP, applied to every answer this registry hands back (mcp#12).
     *
     * <p>One method rather than the rule written at each of the four return points: this
     * method dispatches, refuses on an unpaid precedent charge, catches an exception and
     * catches a JVM Error, and a caller has no way to tell which of those they got. The
     * resident's degraded state is equally true of all four, so the answer is equally
     * owed on all four.</p>
     *
     * <p>A no-op when nothing is degraded — {@code stamp()} answers null then, so an
     * ordinary response is byte-unchanged and the whole mechanism is invisible until it
     * has something to say.</p>
     *
     * <p><b>Sprint 28g C14a — THE D42 PRIOR-WORK BLOCK RIDES HERE TOO, and it did not
     * before.</b> The same argument this method's own first paragraph makes about the
     * degradation stamp is true of it word for word: the state is equally true of all
     * five return points, so the answer is equally owed on all five. D42 emitted it on
     * the NORMAL path, which left it silent on the two refusals the choke mints and on
     * both catch blocks — and {@link #priorWork} carries a paragraph arguing that a
     * refusal is exactly where the block matters MOST. <b>That half of the repair had
     * been made and the other half had not:</b> the emission was moved onto
     * {@code appendStoreAnswer}, which rides a refusal, while its one call site reached
     * no refusal this registry produces. A method able to speak on a refusal, never
     * called on one, reads identical to a method that cannot.</p>
     *
     * @param name the tool that was called, for the prior-work target derivation
     * @param arguments that call's arguments, from which the target is derived
     */
    private ToolResponse stamped(String name, JsonNode arguments, ToolResponse response) {
        if (response != null) {
            // BEFORE the degradation stamp, so a degraded resident's warning is the LAST
            // thing appended and stays the first thing read.
            priorWork(name, arguments, response);
            response.stampDegradation(ResidentDegradation.stamp());
        }
        return response;
    }

    /**
     * Sprint 26a (D2): the weighted precedent push. Builds the current target,
     * retrieves past tool outcomes for it through the swappable
     * {@link org.jawata.mcp.learn.PrecedentRetriever}, and appends a weighted
     * steer (never replacing the tool's own line). No retriever, no target, or
     * no clear signal → silent. Never fails the call.
     */
    /**
     * Sprint 28g D42 — THE PRIOR-WORK BLOCK, on every tool response.
     *
     * <p>What the store already knows about the thing this call is working on, carried by the
     * response rather than waiting to be asked. The channel is the point: a hook reaches one
     * client of six and the standing instruction for the rest is <i>"clients without hook
     * injection must PULL it"</i>, which is an opt-in, which this project has measured at
     * chosen-by-nobody. A response reaches every client with no cooperation at all.</p>
     *
     * <p><b>It builds nothing.</b> The advisor, the duplicate check, the cap and the deadline all
     * existed; {@code adviseBefore} simply had ONE caller out of forty-two tools. This method is
     * the connection and not a mechanism — which is why D-SIX permits it where a fifth
     * prior-work check would have been refused.</p>
     *
     * <p><b>The budget is short, deliberately, and it is {@link #STEERING_BUDGET_MILLIS}.</b> A
     * block nobody asked for must not hold the caller: the default fifteen seconds is right for a
     * verb an agent chose to call and would be a hostage here — the vouched fact behind this
     * clause is store reads walking past a four-second watchdog until the mandatory line was
     * never emitted at all. It was the store's FLOOR (200 ms) until REL44-42, which measured that
     * a healthy store of ten thousand rows needs more than that; see the constant.</p>
     *
     * <p><b>It does not touch the quality ledger, unlike the two lanes below it.</b>
     * {@code adviseBefore} already counts itself to the pre-advice surface through its own
     * recall, so a fired/silent pair here would double-count one surface. Said explicitly
     * because a reader meeting this beside {@link #precedent} would expect the measurement.</p>
     *
     * <p>Never fails the call: the tool's own answer is the thing the caller asked for, and an
     * advisory that could not be computed must not take it down.</p>
     */
    /**
     * What the prior-work lookup on every response may take (REL44-42, 2026-10-19). It WAS the
     * store's floor, 200 ms, and a measurement on a store of about ten thousand rows showed that
     * the four-lane lookup costs 234 ms at the median and 414 ms at the worst, so the floor threw
     * nearly every answer away after the caller had waited the full 200 ms for it. 400 ms covers
     * the 90th percentile (276 ms) and costs a caller about 30 ms on average, since the 200 ms
     * was being spent anyway; against a store that never returns it costs 200 ms more than
     * before, which is the price of the answer arriving at all.
     *
     * <p>It is NOT the shared floor {@code MIN_RETRIEVAL_BUDGET_MILLIS}, which stays what it was:
     * that constant is also the clamp behind "a caller may buy a faster answer, never a longer
     * wait", and the 15-second default the incident behind this clause was about is nowhere near
     * 400 ms.</p>
     */
    static final long STEERING_BUDGET_MILLIS = 400;

    private void priorWork(String name, JsonNode arguments, ToolResponse response) {
        if (advisor == null) {
            return;
        }
        try {
            // The SAME target derivation the precedent lane uses — one statement of what this
            // call is working on, so the two blocks cannot disagree about it.
            String target = org.jawata.mcp.learn.ToolExperienceRecorder.target(name, arguments);
            if (target == null || target.isBlank()) {
                return;
            }
            // THE OPERATION CUE IS DELIBERATELY NULL HERE, and that is a bound rather than an
            // omission. `adviseBefore`'s other caller passes a REFACTORING KIND, which is what
            // the store's `operation` facet actually holds (`seat:refactor` and the like). A TOOL
            // NAME is not an operation, so passing it would add a cue that rarely matches and can
            // match something unrelated — on every response. Symbol-only means a target that is
            // not FQN-shaped (a `filePath` call, which `target` reduces to a basename) yields an
            // EMPTY query and no block at all, which is the right answer rather than a broad one.
            org.jawata.mcp.knowledge.ExperienceAdvisor.PriorWork asked = advisor.priorWork(null,
                target, STEERING_BUDGET_MILLIS,
                org.jawata.mcp.knowledge.QualityLedger.SURFACE_CHOKE_PRIOR_WORK);
            // PAST THE DEADLINE THE RESPONSE SAYS SO. Silence here would be read as "the store
            // knows nothing about this", which is a claim nobody made — and the one thing the
            // store's own unavailable answer insists on is that it is NOT an absence. The reason
            // is the recall's own sentence, because the layer that failed is the one that can say
            // what happened.
            // BOTH EMISSIONS GO THROUGH `appendStoreAnswer`, WHICH RIDES A REFUSAL TOO, and the
            // difference from `appendSteering` is not a detail: that method no-ops on failure —
            // correctly, since steering is next-step advice and a refused call has no next step.
            // This block is a FACT about prior work, and on a refusal it matters MORE, because a
            // caller whose tool just declined is the one most likely to go and do the thing by
            // hand. Riding `appendSteering` it was silent on exactly those answers, which is
            // `mcp#12`'s defect — recorded 120 lines above this method and not taken until the
            // C14a audit pointed at it.
            if (!asked.looked()) {
                response.appendStoreAnswer("The store was NOT consulted about " + target
                    + " — this is not an absence: " + asked.reason());
                return;
            }
            java.util.List<String> advice = asked.advice();
            if (advice.isEmpty()) {
                return;
            }
            response.appendStoreAnswer("What the store already knows about " + target + ":\n"
                + String.join("\n", advice));
        } catch (Exception e) {
            log.warn("prior-work advice failed after {}: {}", name, e.toString());
        }
    }

    private void precedent(CallerIdentity caller, String name, JsonNode arguments,
            ToolResponse response) {
        if (precedentRetriever == null) {
            return;
        }
        try {
            String target = org.jawata.mcp.learn.ToolExperienceRecorder.target(name, arguments);
            if (target == null || target.isBlank()) {
                return;
            }
            java.util.List<org.jawata.mcp.learn.ToolExperience> hits =
                precedentRetriever.retrieve(target, 20);
            // Sprint 27 D3 — the two tiers. The retriever may now gather
            // meaning-near captures from OTHER targets; only IDENTITY hits
            // (situation contains THIS target) may warn and charge — that is
            // v3.3.1's behaviour, unchanged. Similar-but-not-identical cases
            // become ONE advisory line: they inform, they never arm the ledger.
            java.util.List<org.jawata.mcp.learn.ToolExperience> identity = new java.util.ArrayList<>();
            org.jawata.mcp.learn.ToolExperience similar = null;
            for (org.jawata.mcp.learn.ToolExperience h : hits) {
                if (org.jawata.mcp.learn.IdentityMatch.matches(h, target)) {
                    // D8b, half 1: only a tool whose work can be reverted has a precedent to
                    // steer by. A read cannot be undone and costs nothing to repeat, and a
                    // row naming one (or a tool no longer registered) steers at nothing.
                    if (isRevertible(h.tool())) {
                        identity.add(h);
                    }
                } else if (similar == null) {
                    similar = h;               // capped top-1, the plan constant
                }
            }
            org.jawata.mcp.learn.PrecedentSteer.Verdict verdict =
                org.jawata.mcp.learn.PrecedentSteer.evaluate(name, identity);
            // Sprint 27a: both tiers count their ABSTAIN as well as their speak.
            // A tier that stayed silent and a tier never consulted must not look
            // identical — that is the exact gap QualityLedger.silent exists to
            // close, and until now the choke fired only on speak.
            if (verdict.steer() != null) {
                response.appendSteering(verdict.steer());
                measure(q -> q.fired(
                    org.jawata.mcp.knowledge.QualityLedger.SURFACE_CHOKE_PRECEDENT));
            } else {
                measure(q -> q.silent(
                    org.jawata.mcp.knowledge.QualityLedger.SURFACE_CHOKE_PRECEDENT));
            }
            if (similar != null) {
                measure(q -> q.fired(
                    org.jawata.mcp.knowledge.QualityLedger.SURFACE_CHOKE_ADVISORY));
                response.appendSteering("Similar past case (a DIFFERENT target — "
                    + "advisory only, judge the transfer): `" + similar.tool() + "` "
                    + ("compiled".equals(similar.outcome()) ? "worked" : similar.outcome())
                    + " in: " + similar.situation());
            } else {
                measure(q -> q.silent(
                    org.jawata.mcp.knowledge.QualityLedger.SURFACE_CHOKE_ADVISORY));
            }
            // v3.3.1: a NEGATIVE precedent was just SURFACED — remember it, so a
            // later use of that tool on this target owes the written justification
            // the steer just named. Charging a cost for a warning never shown
            // would be enforcement by ambush.
            if (verdict.warnedTool() != null && precedentLedger != null) {
                precedentLedger.warn(caller, verdict.warnedTool(), target);
                measure(org.jawata.mcp.knowledge.QualityLedger::warned);
            }
        } catch (Exception e) {
            log.error("Precedent push failed after {}", name, e);
        }
    }

    /**
     * v3.3.1 (D2 enforcement): the justification-cost, levied. Returns the refusal
     * when a SURFACED negative precedent for this {@code (tool, target)} is still
     * unpaid, else {@code null}.
     *
     * <p>Fails OPEN on any internal error: a broken charge must never block real
     * work. It also never charges for a warning that was not actually shown —
     * enforcement by ambush would be worse than no enforcement.</p>
     */
    private ToolResponse precedentCharge(CallerIdentity caller, String name, JsonNode arguments) {
        if (precedentLedger == null) {
            return null;
        }
        try {
            String target = org.jawata.mcp.learn.ToolExperienceRecorder.target(name, arguments);
            if (target == null || target.isBlank()
                    || !precedentLedger.isOutstanding(caller, name, target)) {
                return null;
            }
            String reason = justification(arguments);
            if (reason != null && !isAbout(reason, name, target)) {
                // D8b, half 3. A payment is accepted as a REASON, so it has to be one. "x",
                // the name of the tool and the name of the target are each non-blank and
                // none of them says why this case differs from the one that went wrong. The
                // charge stays outstanding, so paying again with a real sentence is still owed
                // — and the refusal says what a payment is, because a refusal that does not is
                // the same unexplained wall the guard was already being accused of.
                return ToolResponse.error("PRECEDENT_UNJUSTIFIED",
                    "`" + name + "` was reverted or errored on " + target + " in a case like"
                        + " this, and `precedentOverride=\"" + reason + "\"` is not a reason:"
                        + " it is not about the precedent that was charged.",
                    "Say what went wrong before and what is different now, in a sentence that"
                        + " names the tool, the target, or what happened (reverted, errored,"
                        + " failed) — for example \"the earlier revert was a bad range; this is"
                        + " a clean block\". A token, or a sentence that mentions none of them,"
                        + " does not pay.");
            }
            if (reason != null) {
                // Paid. The agent MAY defect from precedent — it may not defect
                // SILENTLY. Clear the charge so the reason is owed only once.
                precedentLedger.clear(caller, name, target);
                measure(org.jawata.mcp.knowledge.QualityLedger::defected);
                log.info("Precedent defection on {} for target {} justified: {}",
                    name, target, reason);
                return null;
            }
            return ToolResponse.error("PRECEDENT_UNJUSTIFIED",
                "`" + name + "` was reverted or errored on " + target + " in a case like this,"
                    + " and that precedent was already surfaced to you. Using it anyway costs"
                    + " a written justification.",
                "Prefer what worked before; or re-call with precedentOverride=\"<one line:"
                    + " why this case is different>\". The reason is logged with the call.");
        } catch (Exception e) {
            log.error("Precedent charge failed before {} — proceeding UNCHARGED", name, e);
            return null;
        }
    }

    /** Whether the named tool is registered and its work can be reverted — see {@link Tool#isRevertible()}. */
    private boolean isRevertible(String toolName) {
        Tool t = toolName == null ? null : tools.get(toolName);
        return t != null && t.isRevertible();
    }

    /** Words that refer to what happened at the precedent: it went wrong and was undone. */
    private static final java.util.regex.Pattern ABOUT_THE_OUTCOME = java.util.regex.Pattern.compile(
        "(?i)\\b(revert\\w*|error\\w*|fail\\w*|undo\\w*|undid|broke\\w*|earlier|previous\\w*"
            + "|before|last time)\\b");

    /**
     * Whether a payment is a reason ABOUT THE PRECEDENT CHARGED (D8b, half 3).
     *
     * <p>The decidable reading of "about", and no more: at least three words, AND it names the
     * tool, the target (in full or by its simple name), or what happened there (reverted,
     * errored, failed, earlier, before). "x" and "retry" fail the first; "just try it again"
     * passes the first and says nothing about the precedent, so it fails the second. Whether
     * the sentence is a GOOD reason is the audit log's question and the human's — as with a
     * {@code jawata-fallback:}, the declaration is self-certified and its value is that it is
     * written down.</p>
     *
     * <p>The cost is stated: a true reason that names none of these ("a different range this
     * time") is refused once, and the refusal teaches what to add. That is the price of the
     * payment having to be about something.</p>
     */
    static boolean isAbout(String reason, String tool, String target) {
        String text = reason.trim();
        if (text.split("\\s+").length < 3) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (tool != null && !tool.isBlank() && lower.contains(tool.toLowerCase(java.util.Locale.ROOT))) {
            return true;
        }
        if (target != null && !target.isBlank()) {
            String full = target.toLowerCase(java.util.Locale.ROOT);
            int cut = Math.max(full.lastIndexOf('.'), full.lastIndexOf('#'));
            String simple = cut >= 0 ? full.substring(cut + 1) : full;
            if (lower.contains(full) || (!simple.isBlank() && lower.contains(simple))) {
                return true;
            }
        }
        return ABOUT_THE_OUTCOME.matcher(text).find();
    }

    /** The written justification carried by the call, or {@code null} when absent/blank. */
    private static String justification(JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("precedentOverride")) {
            return null;
        }
        String reason = arguments.get("precedentOverride").asText("").trim();
        return reason.isEmpty() ? null : reason;
    }

    /** Strips the choke's meta-argument so no tool ever sees it in its own schema. */
    private static JsonNode withoutOverride(JsonNode arguments) {
        if (arguments instanceof com.fasterxml.jackson.databind.node.ObjectNode obj
                && obj.has("precedentOverride")) {
            com.fasterxml.jackson.databind.node.ObjectNode copy = obj.deepCopy();
            copy.remove("precedentOverride");
            return copy;
        }
        return arguments;
    }

    /** Sprint 26a D2: the swappable retrieval seam (Sprint 27 → embeddings). */
    public void setPrecedentRetriever(org.jawata.mcp.learn.PrecedentRetriever retriever) {
        this.precedentRetriever = retriever;
    }

    /**
     * v3.3.1: install the precedent ledger. Package-private on purpose: nothing in production
     * calls it (the registry builds its own), so it is a test seam — a clock-driven ledger for
     * the expiry case — and a public one would be an exported member only tests reach, which
     * the unwired-members release gate refuses.
     */
    void setPrecedentLedger(org.jawata.mcp.learn.PrecedentLedger ledger) {
        this.precedentLedger = ledger;
    }

    /** Sprint 27 D6: install the quality ledger (application wiring / tests). */
    public void setQualityLedger(org.jawata.mcp.knowledge.QualityLedger ledger) {
        this.qualityLedger = ledger;
    }

    /** The ledger the choke warns into — the outcome-after join reads it. */
    public org.jawata.mcp.learn.PrecedentLedger precedentLedger() {
        return precedentLedger;
    }

    /**
     * Run a measurement if one is installed, and never let it reach the caller:
     * the choke's behaviour must be identical with and without counting.
     */
    private void measure(
            java.util.function.Consumer<org.jawata.mcp.knowledge.QualityLedger> m) {
        org.jawata.mcp.knowledge.QualityLedger q = qualityLedger;
        if (q == null) {
            return;
        }
        try {
            m.accept(q);
        } catch (RuntimeException e) {
            log.warn("quality measurement failed; the call itself is unaffected", e);
        }
    }

    /** Sprint 26 (D1): runs the watch engine over the call's delta. Returns
     *  whether a smell finding was produced — the D3b gate's smell trigger. */
    private boolean watch(String sessionId, String name, java.util.List<String> syncDelta,
            ToolResponse response) {
        if (watchEngine == null || "find_quality_issue".equals(name)) {
            return false;
        }
        try {
            java.util.List<String> delta = new java.util.ArrayList<>(syncDelta);
            if (response.getData() instanceof Map<?, ?> map
                    && map.get("filesModified") instanceof List<?> files) {
                files.forEach(f -> delta.add(String.valueOf(f)));
            }
            java.util.Optional<String> findings = watchEngine.watch(sessionId, delta);
            findings.ifPresent(response::appendSteering);
            return findings.isPresent();
        } catch (Exception e) {
            log.error("Watch engine failed after {} — findings for this delta were lost", name, e);
            return false;
        }
    }

    /**
     * Sprint 26a (D3b): the architect-involvement gate — the deterministic rule
     * (smell | signature/hierarchy | over the LoC threshold) that replaces the
     * retired edit-switch. Appends the review steer; a plain edit passes silent.
     * Never fails the call.
     */
    private void architectGate(String name, JsonNode arguments, ToolResponse response,
            boolean smellFound) {
        if (architectGate == null) {
            return;
        }
        try {
            String steer = architectGate.evaluate(name, arguments, response, smellFound);
            if (steer != null) {
                response.appendSteering(steer);
            }
        } catch (Exception e) {
            log.error("Architect gate failed after {}", name, e);
        }
    }

    /** Sprint 26a D3b: install the deterministic architect-involvement gate. */
    public void setArchitectGate(org.jawata.mcp.learn.ArchitectGate gate) {
        this.architectGate = gate;
    }

    /** Sprint 26 (D1): install the watch engine (application wiring). */
    public void setWatchEngine(org.jawata.mcp.learn.WatchEngine engine) {
        this.watchEngine = engine;
    }

    /** Sprint 26: forwards the outcome to the event tap; never fails the call.
     *  Sprint 28b: carries the call's wall-clock duration for the field
     *  recording's latency bucket. */
    private void tap(CallerIdentity caller, String name, JsonNode arguments, ToolResponse response,
            long durationMs) {
        if (eventTap == null) {
            return;
        }
        try {
            eventTap.onCall(caller, name, arguments, response, durationMs);
        } catch (Exception e) {
            log.error("Event tap failed after {} — the label stream missed this outcome", name, e);
        }
    }

    /** Sprint 26: install the learner event tap (application wiring). */
    public void setEventTap(org.jawata.mcp.learn.EventTap tap) {
        this.eventTap = tap;
    }
}
