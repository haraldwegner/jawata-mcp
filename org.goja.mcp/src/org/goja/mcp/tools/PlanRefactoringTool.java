package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Advisor;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.Plan;
import org.goja.mcp.refactoring.PlanStep;
import org.goja.mcp.refactoring.PlanStore;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 18 — the plan-lifecycle delegate behind {@code refactoring(action=plan|
 * apply_plan|inspect_plan|undo_plan)}. This stage (C4) implements {@code plan}: it
 * decomposes a kind into an ordered, inspectable {@link PlanStep} list, consults the
 * {@link Advisor}, and stores a {@link Plan} for later execution. Execution
 * ({@code apply_plan}, C5) and inspect/undo (C6) are layered on in later stages.
 *
 * <p>Not registered — a package-private delegate the {@code refactoring} front door
 * routes plan actions to (it shares the front door's {@code changeCache} and a
 * process-lived {@link PlanStore}, so a planId survives across calls).</p>
 *
 * <p>{@code plan} is pure descriptor construction — it does not touch the workspace;
 * each step is validated when the loop runs it (C5).</p>
 */
class PlanRefactoringTool extends AbstractTool {

    static final List<String> PLAN_KINDS =
        List.of("compose_method", "replace_type_code_with_class", "inline_singleton");

    private final RefactoringChangeCache changeCache;
    private final PlanStore planStore;
    private final Advisor advisor;
    private final ObjectMapper mapper = new ObjectMapper();

    PlanRefactoringTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache changeCache,
                        PlanStore planStore, Advisor advisor) {
        super(serviceSupplier);
        this.changeCache = changeCache;
        this.planStore = planStore;
        this.advisor = advisor;
    }

    @Override
    public String getName() {
        return "refactoring.plan";
    }

    @Override
    public String getDescription() {
        return "Plan-lifecycle delegate of refactoring(action=plan…). Not registered; the front door owns the schema.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object");
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String action = getStringParam(arguments, "action", "");
        return switch (action) {
            case "plan" -> doPlan(arguments);
            default -> ToolResponse.invalidParameter("action",
                "plan lifecycle: '" + action + "' is not available in this build.");
        };
    }

    private ToolResponse doPlan(JsonNode args) {
        String kind = getStringParam(args, "kind");
        if (kind == null || !PLAN_KINDS.contains(kind)) {
            return ToolResponse.invalidParameter("kind", "plan kind must be one of " + PLAN_KINDS + ".");
        }
        String filePath = getStringParam(args, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required.");
        }
        String target = getStringParam(args, "target", filePath);

        List<PlanStep> steps;
        try {
            steps = buildSteps(kind, args, filePath);
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("plan", e.getMessage());
        }
        if (steps.isEmpty()) {
            return ToolResponse.invalidParameter("plan", "No steps could be built for kind '" + kind + "'.");
        }

        List<String> advice = advisor.adviseBefore(kind, target);
        Plan plan = planStore.create(kind, target, steps, advice);

        List<Map<String, Object>> stepView = new ArrayList<>();
        for (PlanStep s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", s.index());
            m.put("tool", s.tool());
            m.put("args", s.args());
            m.put("expectedStateAfter", s.expectedStateAfter());
            m.put("rollbackTo", s.rollbackTo());
            stepView.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "refactoring");
        data.put("action", "plan");
        data.put("planId", plan.planId());
        data.put("kind", kind);
        data.put("target", target);
        data.put("stepCount", steps.size());
        data.put("steps", stepView);
        data.put("advice", advice);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(steps.size())
            .returnedCount(steps.size())
            .suggestedNextTools(List.of(
                "refactoring(action=inspect_plan, planId) to review the plan",
                "refactoring(action=apply_plan, planId) to run it through the parity gate"))
            .build());
    }

    private List<PlanStep> buildSteps(String kind, JsonNode args, String filePath) {
        return switch (kind) {
            case "inline_singleton" ->
                List.of(patternStep(0, "inline_singleton", args, filePath, "singleton inlined"));
            case "replace_type_code_with_class" -> {
                if (getStringParam(args, "newTypeName") == null) {
                    throw new IllegalArgumentException("replace_type_code_with_class needs newTypeName.");
                }
                yield List.of(patternStep(0, "replace_type_code_with_class", args, filePath,
                    "type code replaced with a class"));
            }
            case "compose_method" -> composeSteps(args, filePath);
            default -> List.of();
        };
    }

    /** A single {@code refactor_to_pattern} step carrying the caret + kind-specific params. */
    private PlanStep patternStep(int index, String patternKind, JsonNode args, String filePath, String expected) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", patternKind);
        a.put("filePath", filePath);
        copyInt(args, a, "line");
        copyInt(args, a, "column");
        copyStr(args, a, "newTypeName");
        copyStr(args, a, "prefix");
        return new PlanStep(index, "refactor_to_pattern", a, expected, -1);
    }

    /** One {@code extract(kind=method)} step per section, ordered bottom-up so extractions don't shift each other. */
    private List<PlanStep> composeSteps(JsonNode args, String filePath) {
        JsonNode sections = args.get("sections");
        if (sections == null || !sections.isArray() || sections.isEmpty()) {
            throw new IllegalArgumentException(
                "compose_method needs a non-empty 'sections' array of {startLine,startColumn,endLine,endColumn,methodName}.");
        }
        List<JsonNode> ordered = new ArrayList<>();
        sections.forEach(ordered::add);
        ordered.sort((x, y) -> Integer.compare(y.path("startLine").asInt(-1), x.path("startLine").asInt(-1)));

        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            JsonNode s = ordered.get(i);
            String name = s.path("methodName").asText(null);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("each compose_method section needs a methodName.");
            }
            ObjectNode a = mapper.createObjectNode();
            a.put("kind", "method");
            a.put("filePath", filePath);
            a.put("startLine", s.path("startLine").asInt());
            a.put("startColumn", s.path("startColumn").asInt());
            a.put("endLine", s.path("endLine").asInt());
            a.put("endColumn", s.path("endColumn").asInt());
            a.put("methodName", name);
            steps.add(new PlanStep(i, "extract", a, "extracted " + name, -1));
        }
        return steps;
    }

    private static void copyInt(JsonNode from, ObjectNode to, String field) {
        if (from.has(field) && from.get(field).isNumber()) {
            to.put(field, from.get(field).asInt());
        }
    }

    private static void copyStr(JsonNode from, ObjectNode to, String field) {
        if (from.has(field) && !from.get(field).isNull()) {
            String v = from.get(field).asText();
            if (v != null && !v.isBlank()) {
                to.put(field, v);
            }
        }
    }
}
