package org.jawata.mcp.change;

import java.util.List;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.lang.LoadedWorkspace;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The multi-step half of the refactoring front door (Sprint 28k, Stage 9): {@code plan}, {@code apply_plan}, {@code inspect_plan}
 * and {@code undo_plan}, answered by the language whose refactorings the plans are made of. The door that takes the call names no
 * language; the language offers this when it binds ({@link PlanSlot}).
 */
public interface PlanLifecycle {

    /** The refactorings a plan can be made of, in the order the front door advertises them. */
    List<String> KINDS = List.of(
        "compose_method", "replace_type_code_with_class", "inline_singleton",
        // OCP-cure recipes (CureCatalog.recipesFor) — single-step, parity-gated refactor_to_pattern plans.
        "refactor_to_state", "refactor_to_command_dispatcher", "form_template_method");

    /**
     * Runs one plan action.
     *
     * @param workspace the language's workspace, narrowed to one project when the call named one
     * @param arguments the call's arguments, {@code action} among them
     * @return the answer
     */
    ToolResponse run(LoadedWorkspace workspace, JsonNode arguments);
}
