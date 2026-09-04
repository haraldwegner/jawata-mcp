package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Advisor;
import org.jawata.mcp.domain.NoOpAdvisor;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.PlanStore;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A (v1.1.1) — the staged-refactoring lifecycle front door, collapsing
 * apply_refactoring / undo_refactoring / inspect_refactoring by {@code action}.
 * Not marked read-only (apply/undo mutate; inspect reads).
 */
public class RefactoringTool extends AbstractTool implements FrontDoor {

    // Sprint 18: the single-change lifecycle (apply/undo/inspect on a changeId) plus
    // the multi-step plan lifecycle. Actions are advertised as they land (C4: plan;
    // C5: apply_plan; C6: inspect_plan/undo_plan).
    private static final List<String> ACTIONS =
        List.of("apply", "undo", "inspect", "plan", "apply_plan", "inspect_plan", "undo_plan");

    private final ApplyRefactoringTool apply;
    private final UndoRefactoringTool undo;
    private final InspectRefactoringTool inspect;
    private final PlanRefactoringTool planTool;

    /** Back-compat: no store wired — the advisor is the Sprint-18 {@link NoOpAdvisor}. */
    public RefactoringTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        this(serviceSupplier, cache, new NoOpAdvisor());
    }

    /**
     * Sprint 21 Stage 3 — inject the {@link Advisor} (the store-backed
     * {@code ExperienceAdvisor} in production) so the plan lifecycle consults + records
     * against the knowledge store.
     */
    public RefactoringTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache,
                           Advisor advisor) {
        super(serviceSupplier);
        this.apply = new ApplyRefactoringTool(serviceSupplier, cache);
        this.undo = new UndoRefactoringTool(serviceSupplier, cache);
        this.inspect = new InspectRefactoringTool(serviceSupplier, cache);
        // Process-lived plan store so a planId from `plan` survives to apply_plan/inspect_plan/undo_plan.
        this.planTool = new PlanRefactoringTool(serviceSupplier, cache, new PlanStore(), advisor);
    }

    @Override
    public String getName() {
        return "refactoring";
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
        return "Manage a refactoring: the single-change lifecycle and the multi-step plan"
            + " lifecycle.";
    }

    @Override
    public String usageTail() {
        return ", ...";
    }

    @Override
    public String kindBlock() {
        return """
            Single change (staged/auto_apply=false flows):
            - apply   — perform a staged change. Needs: changeId.
            - undo    — revert an applied change. Needs: undoChangeId.
            - inspect — preview a staged change without applying. Needs: changeId.

            Multi-step plan (behaviour-preserving, parity-gated orchestration):
            - plan       — decompose a kind into an ordered, inspectable step list (no changes made).
                           Needs: kind + filePath; kind params: line/column, plus newTypeName for
                           replace_type_code_with_class and sections[] for compose_method.
                           Returns a planId. The six plannable kinds:
                             compose_method — a long method becomes a short sequence of
                               intention-revealing calls.
                             replace_type_code_with_class — a group of static-final type-code
                               constants becomes a type-safe enum.
                             inline_singleton — a singleton whose uniqueness no longer matters
                               loses its static holder; call sites construct it directly.
                             refactor_to_state — a method switching on an int state field becomes
                               delegation to per-state classes.
                             refactor_to_command_dispatcher — a switch over command codes becomes
                               a dispatch table of command objects.
                             form_template_method — sibling methods differing only in steps become
                               one template with the steps overridden.
            - apply_plan — run a planId step by step, parity-gated (compile 0/0 + a purity check)
                           after each; rolls the whole plan back atomically on failure. Needs: planId.
                           Returns the composed undoChangeId + any purity findings.
            - inspect_plan — review a planId: its steps, which are applied vs pending. Needs: planId.
            - undo_plan  — revert a fully-applied plan via its composed undo. Needs: planId.""";
    }

    @Override
    public String footer() {
        return """
            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", ACTIONS);
        action.put("description", "Which lifecycle action to run.");
        properties.put("action", action);
        properties.put("changeId", Map.of("type", "string", "description", "apply/inspect: the staged change id."));
        properties.put("undoChangeId", Map.of("type", "string", "description", "undo: the undo handle id."));
        properties.put("kind", Map.of("type", "string", "enum", PlanRefactoringTool.PLAN_KINDS,
            "description", "plan: which multi-step refactoring to plan."));
        properties.put("target", Map.of("type", "string", "description", "plan: optional symbol/label for advice + display."));
        properties.put("filePath", Map.of("type", "string", "description", "plan: source file of the refactoring target."));
        properties.put("line", Map.of("type", "integer", "description", "plan: zero-based caret line."));
        properties.put("column", Map.of("type", "integer", "description", "plan: zero-based caret column."));
        properties.put("newTypeName", Map.of("type", "string", "description", "plan (replace_type_code_with_class): name for the generated type."));
        properties.put("sections", Map.of("type", "array", "items", Map.of("type", "object"),
            "description", "plan (compose_method): statement ranges to extract, each {startLine,startColumn,endLine,endColumn,methodName} (0-based)."));
        properties.put("planId", Map.of("type", "string", "description", "apply_plan/inspect_plan/undo_plan: the plan id from `plan`."));
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String action = getStringParam(arguments, "action");
        if (action == null || action.isBlank()) {
            return ToolResponse.invalidParameter("action", "action is required; one of " + ACTIONS);
        }
        return switch (action) {
            case "apply"   -> apply.executeWithService(service, arguments);
            case "undo"    -> undo.executeWithService(service, arguments);
            case "inspect" -> inspect.executeWithService(service, arguments);
            case "plan", "apply_plan", "inspect_plan", "undo_plan"
                           -> planTool.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("action",
                "Unknown action '" + action + "'. Allowed: " + ACTIONS);
        };
    }
}
