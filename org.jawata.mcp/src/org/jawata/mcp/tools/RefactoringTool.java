package org.jawata.mcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.change.PlanLifecycle;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.lang.LoadedWorkspace;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 16b/A (v1.1.1) — the staged-refactoring lifecycle front door, collapsing
 * apply_refactoring / undo_refactoring / inspect_refactoring by {@code action}.
 * Not marked read-only (apply/undo mutate; inspect reads).
 */
public class RefactoringTool extends WorkspaceTool<LoadedWorkspace> implements FrontDoor {

    // Sprint 18: the single-change lifecycle (apply/undo/inspect on a changeId) plus
    // the multi-step plan lifecycle. Actions are advertised as they land (C4: plan;
    // C5: apply_plan; C6: inspect_plan/undo_plan).
    private static final List<String> ACTIONS =
        List.of("apply", "undo", "inspect", "plan", "apply_plan", "inspect_plan", "undo_plan");

    private final ApplyRefactoringTool apply;
    private final UndoRefactoringTool undo;
    private final InspectRefactoringTool inspect;
    private final Supplier<PlanLifecycle> plans;

    /**
     * The lifecycle door over a language's workspace (Sprint 28k, Stage 9): the staged-change actions run under the change context the
     * language supplies, and the plan actions go to the plan lifecycle the language offered, asked at each call because the door is
     * built before any language binds.
     *
     * @param workspace the language's loaded workspace
     * @param cache     the staged changes and undo handles
     * @param changes   what a change is applied under
     * @param plans     the language's plan actions, or null while no language has offered them
     */
    public RefactoringTool(Supplier<? extends LoadedWorkspace> workspace, RefactoringChangeCache cache,
                           Supplier<ChangeContext> changes, Supplier<PlanLifecycle> plans) {
        super(workspace);
        this.apply = new ApplyRefactoringTool(workspace, cache, changes);
        this.undo = new UndoRefactoringTool(workspace, cache, changes);
        this.inspect = new InspectRefactoringTool(workspace, cache);
        this.plans = plans;
    }

    @Override
    public String getName() {
        return "refactoring";
    }

    /**
     * Applies and undoes changes, so it has a history that can go wrong — the lifecycle door is
     * the one tool outside the two refactoring bases that does.
     */
    @Override
    public boolean isRevertible() {
        return true;
    }

    /**
     * The DISCRIMINATOR, which for this door is {@code action} and for every other is
     * {@code kind}.
     *
     * <p>Taking it from the door rather than assuming is what lets one assembler serve all
     * ten: the generated line reads {@code action=} here, {@code direction=} on
     * {@code hierarchy}, {@code kind=} elsewhere.</p>
     */
    @Override
    public String discriminator() {
        return "action";
    }

    /**
     * The seven ACTIONS — overridden because the inherited reader would name the wrong list.
     *
     * <p>{@link Tool#publishedKinds()} walks the schema for {@code kind} or {@code direction}
     * and stops at the first it finds. This tool's schema declares BOTH: {@code action}, its
     * real discriminator, and {@code kind}, which is a PARAMETER OF ONE ACTION — the six
     * plannable refactorings {@code plan} accepts. So the inherited reader would answer with
     * the plan kinds, which are published by {@code refactor_to_pattern} and belong to it.
     * That is the boot failure {@code ToolRegistry} records in its own comments, and here it
     * would have put six other doors' operations into this door's usage line.</p>
     *
     * <p>It does NOT put these verbs into the operation namespace, and nothing here could:
     * that is the registry's policy, and it reads {@code instanceof KindedTool} — which this
     * door deliberately is not.</p>
     */
    @Override
    public List<String> publishedKinds() {
        return ACTIONS;
    }

    /**
     * ASSEMBLED, not written (Stage 6a, M5) — the DESCRIPTION seam only.
     *
     * <p>One declared change to the published text: the {@code USAGE:} line said
     * {@code action="<action>"}, a placeholder, and now names all seven verbs.</p>
     *
     * <p>The per-kind block stays this door's own prose, and that is not a deferral. Seven
     * actions dispatch to four delegates — {@code plan}, {@code apply_plan},
     * {@code inspect_plan} and {@code undo_plan} all reach {@code PlanRefactoringTool} — so
     * there is no one delegate per action to ask for a bullet. That arity is the same fact
     * that keeps this door out of {@link KindedTool}.</p>
     */
    @Override
    public String getDescription() {
        return FrontDoorDescription.ASSEMBLER.describe(this);
    }

    @Override
    public String preamble() {
        return "Apply, preview or undo staged changes, and run multi-step refactoring plans.";
    }

    @Override
    public String usageTail() {
        return ", ...";
    }

    @Override
    public String kindBlock() {
        return """
            - apply / inspect: perform / preview a staged change (changeId).
            - undo: revert an applied change (undoChangeId).
            - plan: ordered step list, no changes; returns planId. kind + filePath, line/column;
              newTypeName for replace_type_code_with_class, sections[] for compose_method. Kinds:
              compose_method, replace_type_code_with_class, inline_singleton, refactor_to_state,
              refactor_to_command_dispatcher, form_template_method.
            - apply_plan: run planId, checked after each step, rolled back on failure.
            - inspect_plan / undo_plan: review / revert planId.""";
    }

    @Override
    public String footer() {
        return "";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", ACTIONS);
        action.put("description", "What to do.");
        properties.put("action", action);
        properties.put("changeId", Map.of("type", "string", "description", "apply/inspect: staged change id."));
        properties.put("undoChangeId", Map.of("type", "string", "description", "undo: undo handle."));
        properties.put("kind", Map.of("type", "string", "enum", PlanLifecycle.KINDS,
            "description", "plan: what to plan."));
        properties.put("target", Map.of("type", "string", "description", "plan: label (optional)."));
        properties.put("filePath", Map.of("type", "string", "description", "plan: source file."));
        properties.put("line", Map.of("type", "integer", "description", "plan: zero-based."));
        properties.put("column", Map.of("type", "integer", "description", "plan: zero-based."));
        properties.put("newTypeName", Map.of("type", "string", "description", "plan replace_type_code_with_class: type name."));
        properties.put("sections", Map.of("type", "array", "items", Map.of("type", "object"),
            "description", "plan compose_method: {startLine,startColumn,endLine,endColumn,methodName}, zero-based."));
        properties.put("planId", Map.of("type", "string", "description", "Plan id from plan."));
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithWorkspace(LoadedWorkspace workspace, JsonNode arguments) {
        String action = getStringParam(arguments, "action");
        if (action == null || action.isBlank()) {
            return ToolResponse.invalidParameter("action", "action is required; one of " + ACTIONS);
        }
        return switch (action) {
            case "apply"   -> apply.executeWithWorkspace(workspace, arguments);
            case "undo"    -> undo.executeWithWorkspace(workspace, arguments);
            case "inspect" -> inspect.executeWithWorkspace(workspace, arguments);
            case "plan", "apply_plan", "inspect_plan", "undo_plan" -> planned(workspace, arguments);
            default -> ToolResponse.invalidParameter("action",
                "Unknown action '" + action + "'. Allowed: " + ACTIONS);
        };
    }

    /** A plan action, answered by the language that offered the plan lifecycle, or refused while none has. */
    private ToolResponse planned(LoadedWorkspace workspace, JsonNode arguments) {
        PlanLifecycle lifecycle = plans.get();
        if (lifecycle == null) {
            return ToolResponse.invalidParameter("action",
                "No loaded language offers refactoring plans, so '" + getStringParam(arguments, "action") + "' is not available.");
        }
        return lifecycle.run(workspace, arguments);
    }
}
