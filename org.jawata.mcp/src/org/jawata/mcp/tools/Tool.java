package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.models.ToolResponse;

import java.util.Map;

/**
 * Interface for all JAWATA tools.
 * Each tool provides semantic code analysis or modification capabilities.
 */
public interface Tool {

    /**
     * Get the unique name of this tool.
     * This is used in tools/call requests.
     */
    String getName();

    /**
     * Get the human-readable description of this tool.
     * This should include USAGE, OUTPUT, and WORKFLOW information
     * to help AI agents understand how to use the tool effectively.
     */
    String getDescription();

    /**
     * Get the JSON Schema for the tool's input parameters.
     * Returns a map representing the schema in JSON Schema format.
     */
    Map<String, Object> getInputSchema();

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments The arguments from the tools/call request
     * @return The tool response with data or error
     */
    ToolResponse execute(JsonNode arguments);

    /**
     * Is this tool's work BEHAVIOUR-PRESERVING — a refactoring rather than new code?
     *
     * <p>Read by the done-time coverage advisory: a rename needs no new test, new
     * behaviour does. It used to be a {@code Set.of(...)} of tool NAMES kept in
     * {@code MechanicalChangeJournal}, and stage 1's fold walked straight past it — four
     * of the six retired names were in that set and the two renamed tools left it
     * silently, so a pull-up and a field encapsulation started being asked for tests
     * they cannot need. A tool knows what it does; a list in another package only knows
     * what it was told once.</p>
     *
     * <p>Default false, because most tools answer questions. The refactoring bases
     * override it once for every refactoring, so no operation has to remember.</p>
     */
    default boolean isMechanical() {
        return false;
    }

    /**
     * Does this tool change a SIGNATURE or a HIERARCHY, whatever kind is asked for?
     *
     * <p>Read by the architect-involvement gate, which fires a review steer on a
     * structural edit. Same history as {@link #isMechanical()}: it was a set of names in
     * {@code learn/}, two of the four names it held were retired by stage 1, and the
     * gate went quiet for pull-up, push-down and the method move without anything
     * failing.</p>
     */
    default boolean isStructural() {
        return false;
    }

    /**
     * The KINDS of a parametric front door that are structural, when the tool as a
     * whole is not.
     *
     * <p>{@code extract} is the standing example — extracting a superclass or an
     * interface changes the hierarchy, extracting a local variable does not — and
     * {@code move} joined it when {@code move_method} folded in. The gate used to carry
     * {@code extract}'s two kinds itself, in a set beside the tool names, which is the
     * same defect one level down.</p>
     */
    default java.util.Set<String> structuralKinds() {
        return java.util.Set.of();
    }
}
