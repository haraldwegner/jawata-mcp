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
     * The kinds this tool publishes, read off its OWN schema.
     *
     * <p>A parametric front door dispatches on a discriminator — {@code kind} on most,
     * {@code direction} on {@code hierarchy} — declared in its schema as an enum. This
     * returns that enum's values, and an empty list for the ordinary tool that has no
     * discriminator at all.</p>
     *
     * <p><b>The object answers for itself.</b> This reader lived as a private method on
     * {@code ToolRegistry} that walked another object's schema from outside, and four test
     * copies of it grew beside it — which is what happens to a fact its owner will not
     * publish. Asking the tool is the same question with one place to ask it.</p>
     *
     * <p><b>What this deliberately does NOT decide: whether those kinds are OPERATIONS.</b>
     * A {@code kind} enum is a common shape — {@code find_quality_issue} publishes
     * {@code god_class}, {@code analyze} publishes {@code method} — and harvesting every
     * one of them into the operation namespace would let a cure step name a smell and be
     * called runnable. That judgement is a POLICY about the published surface, not a fact
     * about the tool, and it lives on {@code OperationSurface.operationKindsOf} — moved
     * there by Stage 6a's M8, out of a private method on {@code ToolRegistry} that a test
     * had to build a whole registry to reach. This method answers "what do you dispatch
     * on", never "may a cure name it".</p>
     *
     * <p>{@code action} is not read here. A lifecycle verb — apply, undo, plan — is not a
     * transformation. {@code refactoring} is the one tool that publishes them, and it
     * OVERRIDES this method to return its own seven, because its schema also declares a
     * {@code kind} enum belonging to one of those actions and the reader below would
     * otherwise answer with another door's operations.</p>
     */
    default java.util.List<String> publishedKinds() {
        try {
            Object props = getInputSchema().get("properties");
            if (!(props instanceof Map<?, ?> properties)) {
                return java.util.List.of();
            }
            for (String discriminator : java.util.List.of("kind", "direction")) {
                Object schema = properties.get(discriminator);
                if (!(schema instanceof Map<?, ?> discriminatorSchema)) {
                    continue;
                }
                // ANY collection, not just a List: apply_cleanup publishes its enum as a
                // Set, which every List-typed reader before this one skipped in silence.
                Object values = discriminatorSchema.get("enum");
                if (!(values instanceof java.util.Collection<?> enumeration)) {
                    continue;
                }
                java.util.List<String> kinds = new java.util.ArrayList<>();
                for (Object value : enumeration) {
                    if (value instanceof String s) {
                        kinds.add(s);
                    }
                }
                return java.util.List.copyOf(kinds);
            }
            return java.util.List.of();
        } catch (RuntimeException e) {
            // A schema this malformed is a defect the schema tests catch loudly; it must
            // not take registration down with it.
            return java.util.List.of();
        }
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
