package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A — parametric front door for pull-up / push-down, selected by
 * {@code direction}. Both delegates take the identical {@code {filePath, line,
 * column}} input (a member at the caret), so this is a clean uniform collapse.
 *
 * <p>Replaces {@code pull_up} / {@code push_down}; apply/undo contract
 * unchanged.</p>
 */
public class HierarchyTool extends AbstractTool implements KindedTool {

    /**
     * ONE table, keyed by the direction that reaches each delegate — and the delegates are held
     * as TOOLS, so dispatch is a lookup rather than a switch.
     *
     * <p><b>This is deliberately not the shape the two doors created earlier in this sprint
     * took.</b> Both of those keep a {@code Map<String, KindDelegate>} and, because the role
     * cannot execute, a hand-written switch beside it — two routing tables for one fact, with a
     * guard bolted on to catch them disagreeing. An architect watch at C4 named it, and
     * {@code ExtractTool} had already argued against it in its own javadoc. Converting this door
     * today with that shape would have been the third instance of a defect already reported, so
     * it holds the tools and PROJECTS the role view in {@link #delegates()} instead.</p>
     */
    private final Map<String, AbstractTool> byDirection;

    public HierarchyTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        Map<String, AbstractTool> directions = new LinkedHashMap<>();
        for (AbstractTool delegate : List.of(new PullUpTool(serviceSupplier, cache),
                new PushDownTool(serviceSupplier, cache),
                new org.jawata.mcp.tools.inheritance.PullUpConstructorBodyTool(
                    serviceSupplier, cache),
                new org.jawata.mcp.tools.inheritance.ReplaceTypeCodeWithSubclassesTool(
                    serviceSupplier, cache),
                new org.jawata.mcp.tools.inheritance.ReplaceSuperclassWithDelegateTool(
                    serviceSupplier, cache))) {
            directions.put(((KindDelegate) delegate).kindName(), delegate);
        }
        this.byDirection = java.util.Collections.unmodifiableMap(directions);
    }

    @Override
    public String getName() {
        return "hierarchy";
    }

    /**
     * NOT {@code kind}. This door has always selected on {@code direction} and keeps doing so:
     * the seam takes the discriminator FROM the door rather than imposing one, which is the
     * whole reason {@code FrontDoorDescription} asks for it instead of writing "kind=" itself.
     */
    @Override
    public String discriminator() {
        return "direction";
    }

    /**
     * THE ROUTING TABLE IS THE LIST OF DIRECTIONS. It replaces a {@code DIRECTIONS} constant
     * that sat beside a two-arm switch and a hand-written description, all three naming the
     * same two strings — the shape this seam exists to remove.
     */
    @Override
    public java.util.Map<String, KindDelegate> delegates() {
        java.util.Map<String, KindDelegate> published = new LinkedHashMap<>();
        byDirection.forEach((direction, tool) -> published.put(direction, (KindDelegate) tool));
        return java.util.Collections.unmodifiableMap(published);
    }

    /**
     * A preamble and a footer only; the per-direction bullets are a projection of
     * {@link #delegates()} and are assembled rather than written.
     */
    /**
     * ASSEMBLED, not written. A concrete class's own method beats an interface default, so a
     * door cannot simply inherit this from {@link KindedTool} — it has to forward, and every
     * other door forwards the same way.
     */
    @Override
    public String getDescription() {
        return FrontDoorDescription.ASSEMBLER.describe(this);
    }

    @Override
    public String preamble() {
        return "Move a member up to a supertype or down to subtypes"
            + " (behaviour-preserving, reversible).";
    }

    @Override
    public String footer() {
        return """
            IMPORTANT: ZERO-BASED coordinates. Applies by default; returns
            filesModified/diff/undoChangeId/summary. Pass auto_apply=false to stage only.

            Requires load_project to be called first.""";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("type", "string");
        // DERIVED from the routing table, so a direction cannot be dispatched and left out of
        // the enum a client reads.
        direction.put("enum", publishedKinds());
        direction.put("description", "Which way through the hierarchy. See the tool description.");
        properties.put("direction", direction);
        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the member at the caret."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));

        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "member to pull up or push down"));
        // THE BACKSTOP: every parameter any delegate declares reaches the published contract,
        // curated or not. A kind wired for EXECUTION and unwired for CONTRACT runs correctly
        // for anyone who already knows the argument names and is invisible in tools/list.
        schema.put("properties", withDelegateParameters(properties));
        // Sprint 24 (D1): position OR name form.
        schema.put("required", List.of("direction"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Sprint 24 (D1): accept the name form — symbol=pkg.Type#member.
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        String direction = getStringParam(arguments, "direction");
        if (direction == null || direction.isBlank()) {
            return ToolResponse.invalidParameter("direction",
                "direction is required; one of " + publishedKinds());
        }
        AbstractTool delegate = byDirection.get(direction);
        if (delegate == null) {
            return ToolResponse.invalidParameter("direction",
                "Unknown direction '" + direction + "'. Allowed: " + publishedKinds());
        }
        // THE LOOKUP IS THE DISPATCH. There is no second table to disagree with this one, so
        // there is no "unreachable" arm to guard the disagreement either.
        return delegate.executeWithService(service, arguments);
    }
    /**
     * STRUCTURAL. Pulling a member up or pushing it down moves it through the hierarchy, which is
     * what this gate means by structural. It was reached under the name
     * `move_in_hierarchy` until stage 1 renamed the tool, and the gate's own list did
     * not follow — which is why the declaration now lives here.
     */
    @Override
    public boolean isStructural() {
        return true;
    }

}
