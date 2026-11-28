package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The data of one step of a sprint plan, as a pm enters it.
 *
 * @param title      what the step is called
 * @param tool       the gate tool of a checked step; null for a person's step
 * @param arguments  what the gate call must carry; null for a person's step
 * @param expect     what the gate is expected to answer, or null when not given
 * @param checkpoint whether the step is marked as a checkpoint
 * @param person     whether the step is reserved to the person and carries no check
 */
public record PlannedStep(String title, String tool, JsonNode arguments, JsonNode expect, boolean checkpoint, boolean person) {

    public PlannedStep {
        java.util.Objects.requireNonNull(title, "a step with no title cannot be told apart from another");
        if (title.isBlank()) {
            throw new IllegalArgumentException("a blank title names no step");
        }
        if (person && (tool != null || arguments != null || expect != null || checkpoint)) {
            throw new IllegalArgumentException("step '" + title + "' is the person's and carries no check, so it has no tool");
        }
        if (!person && (tool == null || tool.isBlank())) {
            throw new IllegalArgumentException("step '" + title + "' has a check and so needs a tool that is not blank");
        }
        if (!person && arguments == null) {
            throw new IllegalArgumentException("step '" + title + "' has a check and so needs arguments; an empty object means the tool name alone");
        }
    }
}
