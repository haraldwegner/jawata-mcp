package org.jawata.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.learn.PlanMarks;
import org.jawata.mcp.learn.PlannedStep;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.step.PlanId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Which steps are still waiting for their gate (Sprint 28h, S8, D18). It reads the plan ledger that the hire path opens plans in and
 * that only a passing gate moves, and changes nothing: the agent asks it at the end of a brain's turn whether a step is pending, so the
 * answer comes from the one class that holds step state and not from a second copy kept by the asker.
 */
public final class PlanStepsTool implements Tool {

    private final PlanLedger plans;

    public PlanStepsTool(PlanLedger plans) {
        this.plans = plans;
    }

    @Override
    public String getName() {
        return "plan_steps";
    }

    @Override
    public String getDescription() {
        return """
            The steps still waiting for their gate, read from the plan ledger; and the way a pm enters a sprint plan into it.

            USAGE: plan_steps() or plan_steps(action=list) or plan_steps(all=true) tells the pending steps and changes nothing.
            plan_steps(action=enter, name, steps) records a sprint plan: each step has a title and a check (the gate tool, its arguments, what it
            must find) or is the person's own (person=true, no check); checkpoint=true marks a checkpoint. An open plan already holding the name is
            refused with PLAN_EXISTS; a step in a shape the ledger cannot hold is refused as an invalid parameter and nothing is recorded. Enter
            answers {planId, name, steps, reserved, checkpoints}.

            What follows describes the answer of the list action.

            A caller is told of the plans it opened and of those nobody was recorded for; all=true tells of every session's plans, for a
            caller that sends no session id and so owns none.

            Answers {pending: [{planId, passedStep, owner, inFlight, step: {index, tool, arguments}}]}, oldest plan first. owner is the
            session that opened the plan, empty when none was recorded. inFlight is true
            when a hire of that plan is running right now, so the step is waiting because someone is working on it. A plan whose every step has
            passed is not listed. A plan advances only when its next step's gate call answered and reported no errors, so an empty
            list means no step is waiting, and a listed plan names the gate tool that would move it.

            planCount is how many plans the ledger holds, so an empty pending list can be told apart from no plan at all.

            A caller that entered a sprint plan is also answered planStanding (where its newest plan stands) and goOn (true when a step is open and neither a checkpoint just reached nor a step of the person holds it); a caller with no such plan, a call with no caller and all=true get neither key.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> check = Map.of("type", "object",
            "description", "The gate that closes the step: its tool, arguments and expected answer. Absent only for a person step.",
            "properties", Map.of(
                "tool", Map.of("type", "string", "description", "The gate tool whose passing call closes the step."),
                "arguments", Map.of("type", "object", "description", "What the gate call must carry; default {}."),
                "expect", Map.of("type", "object", "description", "What the gate must find, as a hire's step states it; optional.")),
            "required", List.of("tool"));
        Map<String, Object> step = Map.of("type", "object",
            "properties", Map.of(
                "title", Map.of("type", "string", "description", "What the step is."),
                "check", check,
                "checkpoint", Map.of("type", "boolean", "description", "Marks a checkpoint, reached when this step has passed."),
                "person", Map.of("type", "boolean", "description", "A step the person does; it has no check and holds nothing.")),
            "required", List.of("title"));
        return Map.of("type", "object",
            "properties", Map.of(
                "action", Map.of("type", "string", "enum", List.of("list", "enter"),
                    "description", "list (default) tells the pending steps; enter records a sprint plan's steps."),
                "all", Map.of("type", "boolean", "description", "Tell of the plans of every session, not only the caller's."),
                "name", Map.of("type", "string", "description", "enter: the plan's name; an open plan already holding it is refused."),
                "steps", Map.of("type", "array", "description", "enter: the plan's steps in order, at least one with a check.", "items", step)),
            "required", List.of());
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        String action = arguments == null ? "list" : arguments.path("action").asText("list");
        if (!"list".equals(action) && !"enter".equals(action)) {
            return ToolResponse.invalidParameter("action", "action is list (the default) or enter, not '" + action + "'");
        }
        if ("enter".equals(action)) {
            return enter(arguments);
        }
        List<Map<String, Object>> pending = new ArrayList<>();
        // A caller answers for the plans it opened and for those nobody was recorded for: a hire another caller is running does not hold this
        // caller's turn (S10). A call made outside the registry has no caller to answer for and sees every plan.
        // A caller that asks for all plans (28h z20: the stop hook sends no session id, so as itself it would see only the plans nobody owns)
        // is told of every session's.
        boolean everyones = arguments != null && arguments.path("all").asBoolean(false);
        List<PlanLedger.Pending> mine = everyones ? plans.pending() : org.jawata.mcp.models.CallerIdentity.serving()
            .map(caller -> plans.pendingFor(caller.id()))
            .orElseGet(plans::pending);
        for (PlanLedger.Pending open : mine) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("index", open.next().index());
            step.put("tool", open.next().tool());
            step.put("arguments", open.next().gateArguments());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("planId", open.id().toString());
            row.put("passedStep", open.passedStep());
            // Who opened the plan (28h z23): a step nobody runs is then traceable to the session that opened it. Empty when nobody was recorded.
            row.put("owner", plans.ownerOf(open.id()));
            row.put("step", step);
            // A step of a plan whose hire is running is waiting because the hire is working on it (Stage 24, M11), not because it was left behind.
            row.put("inFlight", org.jawata.mcp.execution.HireRegistry.shared().isRunningPlan(open.id().toString()));
            // A plan a pm entered shows what it marks beside its chain (28h item 9 (i)); a plan opened without marks is listed as before.
            PlanMarks marks = plans.marks(open.id()).orElse(null);
            if (marks != null) {
                step.put("title", marks.titles().get(open.next().index()));
                step.put("checkpoint", marks.checkpoints().contains(open.next().index()));
                row.put("name", marks.name());
                List<Map<String, Object>> waitingForPerson = new ArrayList<>();
                for (PlanMarks.Reserved reserved : marks.reserved()) {
                    Map<String, Object> person = new LinkedHashMap<>();
                    person.put("title", reserved.title());
                    person.put("after", reserved.after());
                    waitingForPerson.add(person);
                }
                row.put("waitingForPerson", waitingForPerson);
            }
            pending.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pending", pending);
        data.put("planCount", plans.planCount());
        // Where the caller's own newest sprint plan stands and whether it may go on, so a lead's turn loop reads the one standing (28h Stage 28 (i), M1, M2).
        // A call with no caller, or one that asked for every session's plans, has no plan of its own to answer for.
        if (!everyones) {
            org.jawata.mcp.models.CallerIdentity.serving().flatMap(caller -> plans.standingOf(caller.id())).ifPresent(standing -> {
                data.put("planStanding", standing.asMap());
                data.put("goOn", standing.canGoOn());
            });
        }
        return ToolResponse.success(data);
    }

    /** Records a pm's sprint plan in the ledger: the checked steps become the chain its gates walk, the person's steps are kept beside it. */
    private ToolResponse enter(JsonNode arguments) {
        String name = arguments.path("name").asText("");
        if (name.isBlank()) {
            return ToolResponse.invalidParameter("name", "enter needs the plan's name, a string that is not blank");
        }
        JsonNode steps = arguments.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            return ToolResponse.invalidParameter("steps", "enter needs the plan's steps as an array with at least one step");
        }
        List<PlannedStep> planned = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String at = "steps[" + i + "]";
            String title = step.path("title").asText("");
            if (title.isBlank()) {
                return ToolResponse.invalidParameter(at, "a step needs a title that is not blank");
            }
            boolean person = step.path("person").asBoolean(false);
            boolean checkpoint = step.path("checkpoint").asBoolean(false);
            JsonNode check = step.path("check");
            if (person && !check.isMissingNode() && !check.isNull()) {
                return ToolResponse.invalidParameter(at, "step '" + title + "' is the person's and must carry no check");
            }
            if (person && checkpoint) {
                return ToolResponse.invalidParameter(at, "step '" + title + "' is the person's and cannot be a checkpoint");
            }
            if (!person && (!check.isObject() || check.path("tool").asText("").isBlank())) {
                return ToolResponse.invalidParameter(at, "step '" + title + "' needs a check with a tool");
            }
            planned.add(person ? new PlannedStep(title, null, null, null, false, true)
                : new PlannedStep(title, check.path("tool").asText(),
                    check.path("arguments").isObject() ? check.path("arguments") : JsonNodeFactory.instance.objectNode(),
                    check.path("expect").isObject() ? check.path("expect") : null, checkpoint, false));
        }
        PlanId id;
        try {
            id = plans.openSprint(name, planned);
        } catch (PlanLedger.PlanExists exists) {
            return ToolResponse.error("PLAN_EXISTS", "an open plan already holds the name '" + name.strip() + "': " + exists.planId().value(),
                "close that plan, or choose another name for this one");
        } catch (IllegalArgumentException refused) {
            return ToolResponse.invalidParameter("steps", refused.getMessage());
        }
        org.jawata.mcp.models.CallerIdentity.serving().ifPresent(caller -> plans.ownedBy(id, caller.id()));
        PlanMarks marks = plans.marks(id).orElseThrow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", id.value());
        data.put("name", marks.name());
        data.put("steps", marks.titles().size());
        data.put("reserved", marks.reserved().size());
        data.put("checkpoints", marks.checkpoints());
        return ToolResponse.success(data);
    }
}
