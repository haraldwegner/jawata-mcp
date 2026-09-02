package org.jawata.mcp.refactoring;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One step of a {@link Recipe}: the operation to run and the arguments to run it with.
 *
 * <p>The operation is a name the {@link OperationRegistry} publishes — a tool's own
 * name ({@code move_method}) or a kind a front door publishes ({@code extract_method}).
 * Naming rather than holding the tool is what lets a recipe be declared where the tools
 * are not visible, and what makes "does this step exist" a registry lookup.</p>
 */
public record RecipeStep(String operation, JsonNode arguments) {
}
