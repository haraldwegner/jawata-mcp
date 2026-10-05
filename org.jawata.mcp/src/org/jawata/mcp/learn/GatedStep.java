package org.jawata.mcp.learn;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One step of a plan, with the gate that lets the plan move past it
 * (Sprint 28g S9a M2).
 *
 * <p>The name is deliberately not {@code PlanStep}:
 * {@code org.jawata.mcp.refactoring.PlanStep} already exists and already carries
 * a step's arguments, so two types of that name would both mean <em>a step of a
 * plan</em> and both carry <em>the tool's arguments</em> while meaning different
 * things — one is the apply-plan loop's primitive, this is a gate declaration.
 * Nothing in {@code org.jawata.mcp.learn} imports the refactoring package, so a
 * bare {@code Plan}-prefixed name here would not silently resolve to that one;
 * the distinct name is so a READER is not the thing relied upon.</p>
 *
 * @param index         this step's position in its plan, counted from zero
 * @param tool          the gate tool whose passing call advances past this step —
 *     a name, because that is what a call arrives carrying
 * @param gateArguments what the gate call must carry to count. NEVER null: an
 *     explicitly EMPTY object means <em>the tool name alone is the gate</em>,
 *     which is a real thing to declare, and null would mean <em>nobody said</em>
 *     — the two must not collapse into one state, because the second is a step
 *     that was never finished being written and the first is a step that is
 *     finished
 */
public record GatedStep(int index, String tool, JsonNode gateArguments) {

    public GatedStep {
        if (index < 0) {
            throw new IllegalArgumentException("a step index below zero is not a position: " + index);
        }
        Objects.requireNonNull(tool, "a step with no gate tool declares no gate");
        if (tool.isBlank()) {
            throw new IllegalArgumentException("a blank gate tool declares no gate");
        }
        Objects.requireNonNull(gateArguments,
            "gate arguments must be declared; pass an empty object to mean the tool name alone");
    }
}
