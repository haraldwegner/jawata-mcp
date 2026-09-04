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
 * Sprint 16b/A — parametric front door for inline-method / inline-variable.
 * Both delegates take the identical {@code {filePath, line, column}} input, so
 * this is a clean uniform collapse selected by {@code kind}.
 *
 * <p>Replaces {@code inline_method} / {@code inline_variable}; apply/undo
 * contract unchanged.</p>
 */
public class InlineTool extends AbstractTool {

    private static final List<String> KINDS =
        List.of("method", "variable", "class", "subclass");

    private final InlineMethodTool method;
    private final InlineVariableTool variable;
    private final InlineClassTool clazz;
    private final RemoveSubclassTool subclass;

    public InlineTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.method = new InlineMethodTool(serviceSupplier, cache);
        this.variable = new InlineVariableTool(serviceSupplier, cache);
        // Sprint 28d-rescue row 17. Fowler files Inline Class beside Inline Function:
        // both fold something back into its only user when it stopped earning its own
        // name.
        this.clazz = new InlineClassTool(serviceSupplier, cache);
        // Row 38. Remove Subclass is the same fold one level down: a class that is
        // not earning its name, except that its name is a place in a hierarchy.
        this.subclass = new RemoveSubclassTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "inline";
    }

    @Override
    public String getDescription() {
        return """
            Inline a method, a local variable, or a whole class at a caret
            (behaviour-preserving, reversible).

            USAGE: inline(kind="<method|variable|class>", filePath=..., line=..., column=...)

            - method   — inline all call sites of the method at the position.
            - variable — replace uses of the local variable at the position with its initializer.
            - class    — fold a class into the SINGLE class that uses it, then delete it.
                         Refuses when more than one class references it, when it has
                         subtypes, when the user holds none or several fields of its
                         type, when that field is assigned outside its own initializer,
                         when it has a constructor with a body, or when a member name
                         would collide. Each refusal names which. (find_quality_issue
                         kind=lazy_class locates candidates.)
            - subclass — fold a subclass that carries NO DISTINCTION into its parent:
                         its members move up, every reference to it becomes a reference
                         to the parent, and it is deleted. Refuses when the subclass
                         actually distinguishes something — it overrides a parent
                         method, an instanceof or a cast names its type, or its
                         constructor fixes an argument instead of forwarding — because
                         replacing a distinction with a field is a design decision.
                         Also refuses a subclass with subtypes (that is Collapse
                         Hierarchy), an abstract parent, a parent outside this
                         workspace, and a colliding member name.

            IMPORTANT: ZERO-BASED coordinates. Applies by default; returns
            filesModified/diff/undoChangeId/summary. Pass auto_apply=false to stage only.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", KINDS);
        kind.put("description", "Inline a method, a local variable, a whole class, or a"
            + " subclass into its parent.");
        properties.put("kind", kind);
        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the symbol to inline."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));

        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "method to inline (kind=method only — a local variable has no name form)"));
        schema.put("properties", properties);
        // Sprint 24 (D1): position OR name form.
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Sprint 24 (D1): kind=method accepts symbol=pkg.Type#method (a LOCAL
        // variable has no name to address — kind=variable stays positional).
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        return switch (kind) {
            case "method"   -> method.executeWithService(service, arguments);
            case "variable" -> variable.executeWithService(service, arguments);
            case "class"    -> clazz.executeWithService(service, arguments);
            case "subclass" -> subclass.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }
}
