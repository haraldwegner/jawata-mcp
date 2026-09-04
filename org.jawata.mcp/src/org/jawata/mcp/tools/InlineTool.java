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
 * Parametric front door for the FIVE inline refactorings: {@code method},
 * {@code variable}, {@code class} (row 17), {@code subclass} (row 38) and
 * {@code middle_man} (row 36).
 *
 * <p>It began as a uniform collapse over two delegates that took the identical
 * {@code {filePath, line, column}} input. That is no longer true and saying so was
 * misleading: {@code middle_man} takes {@code delegateField} and {@code accessorName},
 * and those parameters exist precisely BECAUSE the inputs are not identical — a class
 * forwarding to several fields needs the caller to name which one. The kind still
 * selects the delegate; the input shape is per-kind and documented per-kind below.</p>
 *
 * <p>Replaces {@code inline_method} / {@code inline_variable}; apply/undo
 * contract unchanged.</p>
 */
public class InlineTool extends AbstractTool {

    private static final List<String> KINDS =
        List.of("method", "variable", "class", "subclass", "middle_man");

    private final InlineMethodTool method;
    private final InlineVariableTool variable;
    private final InlineClassTool clazz;
    private final RemoveSubclassTool subclass;
    private final RemoveMiddleManTool middleMan;

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
        // Row 36. The third fold on this door, and the one that folds a class's
        // METHODS away rather than the class: a middle man keeps its name and loses
        // the forwarding that was all it did.
        this.middleMan = new RemoveMiddleManTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "inline";
    }

    /**
     * Three of the five kinds DELETE A TYPE or change what a hierarchy contains.
     *
     * <p>{@code class} folds a class away, {@code subclass} removes one from a hierarchy
     * and reparents every reference, {@code middle_man} changes what a class exposes to
     * every caller it had. {@code method} and {@code variable} rewrite inside one body.</p>
     *
     * <p>THIS DOOR HAD NO DECLARATION AT ALL until a C6 audit looked, so the
     * architect-involvement gate had been silent on every one of them — including
     * {@code subclass}, whose entire job is to remove a class from a hierarchy. An absent
     * override is indistinguishable from a considered "nothing here is structural", which
     * is why it went unnoticed through the stage that added all three.</p>
     *
     * <p><b>Stage 6a (M3a): DERIVED.</b> Each delegate declares it, and this asks them. On a
     * door whose failure was an ABSENT declaration, that is the whole point: a delegate that
     * exists but says nothing is now the same as a kind that is not structural, and the only
     * way to be structural is to say so on the class that does the work.</p>
     */
    @Override
    public java.util.Set<String> structuralKinds() {
        java.util.Set<String> structural = new java.util.LinkedHashSet<>();
        for (KindDelegate delegate
                : java.util.List.of(method, variable, clazz, subclass, middleMan)) {
            if (delegate.isStructural()) {
                structural.add(delegate.kindName());
            }
        }
        return java.util.Set.copyOf(structural);
    }

    @Override
    public String getDescription() {
        return """
            Inline a method, a local variable, a whole class, a subclass into its
            parent, or a middle man's forwarding (behaviour-preserving, reversible).

            USAGE: inline(kind="<method|variable|class|subclass|middle_man>", filePath=..., line=..., column=...)

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
            - middle_man — stop a class forwarding: every method whose whole body is
                         one call on one of its own fields, passing its parameters
                         through unchanged, is deleted and its call sites become
                         `middleMan.<accessor>().method(args)`. The accessor is
                         generated if the class has none — that exposure IS the
                         refactoring, and the summary says it happened. Refuses a class
                         with no forwarder at all. A class forwarding to SEVERAL
                         fields is NOT refused — name the one to remove with
                         `delegateField` and repeat; the fork's own GiantController is
                         why the old blanket refusal was wrong. A method that
                         transforms the result is left alone: that is behaviour, not
                         forwarding. (find_quality_issue kind=middle_man finds them.)

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
        kind.put("description", "Inline a method, a local variable, a whole class, a"
            + " subclass into its parent, or a middle man's forwarding.");
        properties.put("kind", kind);
        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the symbol to inline."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));

        // The name form reaches every kind whose target HAS a name, and after Stage 6 that
        // is four of the five. The old text said "kind=method only", which was true when
        // this door had two kinds and became false the moment it took three type-targeted
        // ones — a published sentence telling clients a form does not apply where it does.
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "target to inline: pkg.Type#method for kind=method, pkg.Type for kind=class, "
                + "subclass and middle_man. kind=variable is POSITIONAL only — a local has "
                + "no name to address it by"));
        properties.put("accessorName", Map.of("type", "string",
            "description", "kind=middle_man: name for the accessor that exposes the delegate "
                + "(default: the field's own name). Every rewritten call site reads it."));
        properties.put("delegateField", Map.of("type", "string",
            "description", "kind=middle_man: which field to stop forwarding to, when the "
                + "class forwards to more than one. Required only then, and the refusal "
                + "lists the candidates."));
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
            case "middle_man" -> middleMan.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }
}
